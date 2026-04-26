package de.bmcservice.trefferpoint

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * RTSP-ImageReader-Pipeline für WLAN-Okularkameras.
 *
 *   RTSP -> ExoPlayer (HW MediaCodec) -> ImageReader Surface
 *        -> YUV_420_888 -> NV21 -> JPEG -> onJpegFrame -> WebView
 */
class RtspImageReaderPipeline(
    private val context: Context,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtspImageReader"
        private const val IMAGE_WIDTH = 960
        private const val IMAGE_HEIGHT = 540
        private const val JPEG_QUALITY = 90
        private const val MAX_IMAGES = 2
        private const val MAX_DECODE_RESTARTS = 30
    }

    private var player: ExoPlayer? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile var frameCount: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastFrameJpeg: ByteArray? = null
        private set

    @Volatile private var currentUrl: String = ""
    @Volatile private var triedUdpFallback = false
    @Volatile private var decodeErrorRestarts = 0

    @Volatile private var mailSocket: Socket? = null
    @Volatile private var sdpProxy: RtspSdpProxy? = null

    fun start(url: String) {
        currentUrl = url
        triedUdpFallback = false
        thread(start = true, name = "rtsp-imagereader-wakeup") {
            val hostMatch = Regex("rtsp://([^:/]+)").find(url)
            val host = hostMatch?.groupValues?.get(1)
            var rtspUrl = url
            if (host != null && host.startsWith("192.168.")) {
                wakeupCameraHttp(host)
                sdpProxy?.stop()
                val proxy = RtspSdpProxy(host)
                rtspUrl = proxy.start()
                val capturedProxyUrl = rtspUrl
                proxy.onSegmentBoundary = {
                    mainHandler.post { recycleExoPlayer(capturedProxyUrl) }
                    true
                }
                sdpProxy = proxy
                openMailSocket(host, 6035)
            }
            mainHandler.post { startInternal(rtspUrl, useTcp = true) }
        }
    }

    fun stop() {
        triedUdpFallback = false
        currentUrl = ""
        stopInternal()
        sdpProxy?.stop()
        sdpProxy = null
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null
    }

    private fun startInternal(url: String, useTcp: Boolean) {
        if (running) stopInternal()
        running = true
        lastError = null
        frameCount = 0
        lastFrameJpeg = null

        AppLog.i(TAG, "start: $url (RTP über ${if (useTcp) "TCP" else "UDP"})")
        onStatus("RTSP: Verbinde (${if (useTcp) "TCP" else "UDP"}) zu $url")

        imageThread = HandlerThread("RtspImageReader").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(
            IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES
        ).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val jpeg = imageToJpeg(image)
                        lastFrameJpeg = jpeg
                        frameCount++
                        if (frameCount == 1L) {
                            decodeErrorRestarts = 0
                            AppLog.i(TAG, "Erster RTSP-Frame: ${jpeg.size}B JPEG @ ${image.width}x${image.height}")
                        }
                        onJpegFrame(jpeg)
                    } finally {
                        image.close()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "ImageReader-Verarbeitung fehlgeschlagen", e)
                }
            }, imageHandler)
        }

        val trackSelector = DefaultTrackSelector(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 10000, 100, 200)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val hardwareOnlyFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                val hardwareOnly = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneledDecoder ->
                    val decoders = mediaCodecSelector.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunneledDecoder
                    )
                    if (mimeType == MimeTypes.VIDEO_H264) {
                        val filtered = decoders.filterNot { info ->
                            info.name.startsWith("c2.android") || info.name.startsWith("OMX.google")
                        }
                        AppLog.i(
                            TAG,
                            "H264-HW-Decoder: ${filtered.firstOrNull()?.name ?: "(none)"} " +
                                "(HW=${filtered.size}, alle=${decoders.size})"
                        )
                        filtered
                    } else {
                        decoders
                    }
                }
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    hardwareOnly,
                    false,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }
        }

        val exo = ExoPlayer.Builder(context, hardwareOnlyFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
        player = exo

        val rtspSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcp)
            .setTimeoutMs(8000)
            .setUserAgent("Lavf57.83.100")
            .createMediaSource(MediaItem.fromUri(url))

        exo.setMediaSource(rtspSource)
        exo.setVideoSurface(imageReader!!.surface)
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                AppLog.i(TAG, "ExoPlayer state=$name")
                if (state == Player.STATE_READY) onStatus("RTSP-Stream aktiv")
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e(TAG, "ExoPlayer Fehler: ${error.errorCodeName} — ${error.message}", error)
                lastError = "${error.errorCodeName}: ${error.message}"
                onStatus("RTSP-Fehler: ${error.errorCodeName}")

                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && sdpProxy != null) {
                    AppLog.i(TAG, "DECODING_FAILED mit Proxy — Segment-Boundary übernimmt Recycle")
                    return
                }

                if (running && currentUrl.isNotEmpty() && decodeErrorRestarts <= MAX_DECODE_RESTARTS) {
                    val proxyUrl = sdpProxy?.let { "rtsp://127.0.0.1:15554/live/tcp/ch1" } ?: currentUrl
                    mainHandler.postDelayed({
                        if (currentUrl.isEmpty()) return@postDelayed
                        recycleExoPlayer(proxyUrl)
                    }, 200)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) {
                    AppLog.w(TAG, "onTracksChanged: keine Tracks")
                    return
                }
                for (group in tracks.groups) {
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        AppLog.i(
                            TAG,
                            "Track[$i]: mime=${fmt.sampleMimeType} codec=${fmt.codecs} " +
                                "${fmt.width}x${fmt.height} selected=${group.isTrackSelected(i)}"
                        )
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                AppLog.i(TAG, "Video-Size: ${videoSize.width}x${videoSize.height}")
            }
        })
        exo.prepare()

        mainHandler.postDelayed({
            if (!running) return@postDelayed
            if (frameCount > 0) return@postDelayed
            if (!useTcp || triedUdpFallback) {
                AppLog.w(TAG, "Keine Frames nach 30s — beide Transport-Arten probiert")
                onStatus("RTSP: keine Video-Pakete empfangen (Kamera neu starten?)")
                return@postDelayed
            }
            AppLog.i(TAG, "Keine Frames nach 30s mit TCP — Fallback auf UDP")
            onStatus("RTSP: TCP liefert keine Frames — versuche UDP")
            triedUdpFallback = true
            startInternal(currentUrl, useTcp = false)
        }, 30000)
    }

    private fun recycleExoPlayer(proxyUrl: String) {
        if (!running || currentUrl.isEmpty()) return
        decodeErrorRestarts++
        AppLog.i(TAG, "Segment-Boundary: ExoPlayer-Recycle #$decodeErrorRestarts -> $proxyUrl")
        onStatus("RTSP: Segment-Wechsel, Decoder-Reset #$decodeErrorRestarts")
        stopInternal()
        mainHandler.postDelayed({
            if (currentUrl.isEmpty()) return@postDelayed
            startInternal(proxyUrl, useTcp = true)
        }, 150)
    }

    private fun stopInternal() {
        if (!running) return
        running = false
        AppLog.i(TAG, "stop")
        try { player?.clearVideoSurface() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        imageHandler = null
    }

    private fun openMailSocket(host: String, port: Int) {
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2000)
            socket.soTimeout = 15000
            mailSocket = socket
            AppLog.i(TAG, "Mail-Socket offen auf $host:$port")

            val probes = listOf(
                "{\"msgid\":\"ikeyframe\"}\n",
                "{\"msgid\":\"forceikeyframe\"}\n",
                "{\"msgid\":\"req_iframe\"}\n",
                "{\"msgid\":\"setencode\",\"info\":{\"iframe\":1}}\n",
                "{\"msgid\":\"setgop\",\"info\":{\"value\":1}}\n",
                "{\"msgid\":\"getencode\"}\n",
                "{\"msgid\":\"capability\"}\n",
                "{\"msgid\":\"listcmd\"}\n"
            )
            for (probe in probes) {
                try {
                    socket.getOutputStream().write(probe.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    AppLog.i(TAG, "Mail-TX: ${probe.trim()}")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Mail-TX fehlgeschlagen: ${e.message}")
                    break
                }
            }

            thread(start = true, name = "mail-socket-rx") {
                try {
                    val buf = ByteArray(4096)
                    while (socket.isConnected && !socket.isClosed) {
                        val n = try { socket.getInputStream().read(buf) } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        val msg = String(buf, 0, n, Charsets.UTF_8).trim().take(180)
                        AppLog.i(TAG, "Mail-RX: $msg")
                    }
                } catch (_: Exception) {
                }
                AppLog.i(TAG, "Mail-Socket Reader beendet")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Mail-Socket öffnen fehlgeschlagen: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun wakeupCameraHttp(host: String) {
        AppLog.i(TAG, "HTTP Wake-Up auf $host…")
        val endpoints = listOf(
            "http://$host:80/app/getdeviceattr",
            "http://$host:80/app/capability",
            "http://$host:80/app/getproductinfo",
            "http://$host:80/app/getmediainfo",
            "http://$host:80/app/setrec?rec=0",
            "http://$host:80/app/forceikeyframe",
            "http://$host:80/app/ikeyframe",
            "http://$host:80/app/reqikeyframe",
            "http://$host:80/app/requestkeyframe",
            "http://$host:80/app/keyframe",
            "http://$host:80/app/forceiframe",
            "http://$host:80/app/reqiframe",
            "http://$host:80/app/setiframe?value=1",
            "http://$host:80/app/setencode?iframe=1",
            "http://$host:80/app/setencode?gop=1",
            "http://$host:80/app/setgop?value=1",
            "http://$host:80/app/setconfig?iframe=1",
            "http://$host:80/app/getencode",
            "http://$host:80/app/getgop",
            "http://$host:80/app/streamctrl?action=iframe",
            "http://$host:80/app/videoconfig?iframe=1"
        )
        for (endpoint in endpoints) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Lavf58.76.100")
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }.take(200)
                } else {
                    ""
                }
                conn.disconnect()
                AppLog.i(TAG, "  $endpoint -> $code ${if (body.isNotBlank()) "| $body" else ""}")
            } catch (e: Exception) {
                AppLog.i(TAG, "  $endpoint -> ${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}")
            }
        }
        AppLog.i(TAG, "HTTP Wake-Up abgeschlossen")
        describeRtsp(host, "rtsp://$host/live/tcp/ch1")
    }

    private fun describeRtsp(host: String, rtspUrl: String) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, 554), 3000)
            socket.soTimeout = 3000
            val req = "DESCRIBE $rtspUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint/2.3.74\r\nAccept: application/sdp\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val n = try { socket.getInputStream().read(buf) } catch (_: Exception) { break }
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                val headerEnd = sb.indexOf("\r\n\r\n")
                if (headerEnd >= 0) {
                    val cl = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(sb.substring(0, headerEnd))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (sb.length >= headerEnd + 4 + cl) break
                }
            }
            socket.close()
            AppLog.i(TAG, "DESCRIBE SDP (${sb.length}B):\n${sb.take(2000)}")
        } catch (e: Exception) {
            AppLog.w(TAG, "DESCRIBE fehlgeschlagen: ${e.message}")
        }
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream((width * height) / 2)
        yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val planes = image.planes
        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer

        copyPlane(
            buffer = yBuffer,
            rowStride = planes[0].rowStride,
            pixelStride = planes[0].pixelStride,
            width = width,
            height = height,
            output = nv21,
            outputOffset = 0,
            outputPixelStride = 1
        )
        copyPlane(
            buffer = vBuffer,
            rowStride = planes[2].rowStride,
            pixelStride = planes[2].pixelStride,
            width = width / 2,
            height = height / 2,
            output = nv21,
            outputOffset = ySize,
            outputPixelStride = 2
        )
        copyPlane(
            buffer = uBuffer,
            rowStride = planes[1].rowStride,
            pixelStride = planes[1].pixelStride,
            width = width / 2,
            height = height / 2,
            output = nv21,
            outputOffset = ySize + 1,
            outputPixelStride = 2
        )
        return nv21
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val rowData = ByteArray(rowStride)
        var outputPos = outputOffset
        for (row in 0 until height) {
            val length = if (pixelStride == 1 && outputPixelStride == 1) {
                width
            } else {
                (width - 1) * pixelStride + 1
            }
            buffer.position(row * rowStride)
            if (pixelStride == 1 && outputPixelStride == 1) {
                buffer.get(output, outputPos, width)
                outputPos += width
            } else {
                buffer.get(rowData, 0, length)
                var inputPos = 0
                for (col in 0 until width) {
                    output[outputPos] = rowData[inputPos]
                    outputPos += outputPixelStride
                    inputPos += pixelStride
                }
            }
        }
    }
}
