package de.bmcservice.trefferpoint

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.MediaItem
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream

/**
 * RTSP-Stream Pipeline für WLAN-Okularkameras (Viidure etc.).
 *
 *   RTSP → ExoPlayer (Decoder: MediaCodec) → TextureView (GPU-Rendering)
 *        → textureView.getBitmap(960, 540) alle ~33ms → JPEG
 *        → onJpegFrame Callback → TrefferPoint JS-Bridge
 *
 * TextureView statt ImageReader: Samsung c2.android.avc.decoder schreibt keine Pixel
 * in ImageReader-Puffer wenn exo.setVideoSurface() verwendet wird (YUV_420_888 bleibt
 * all-zero → zuerst grün, nach ChromaFix schwarz). TextureView + getBitmap() nutzt
 * den GPU-Rendering-Pfad der korrekte Frames liefert.
 *
 * Frame-Polling-Strategie:
 *   - captureRunnable läuft auf mainHandler (alle 33ms) → getBitmap() auf Main-Thread
 *   - JPEG-Kompression auf captureThread (HandlerThread) → Main-Thread bleibt frei
 *   - capturePending verhindert Stau bei langsamer JPEG-Kompression
 */
class RtspPipeline(
    private val context: Context,
    private val textureView: TextureView,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtspPipeline"
        // SGK GK720X / Viidure liefert immer 960×540 laut SDP.
        private const val INITIAL_WIDTH = 960
        private const val INITIAL_HEIGHT = 540
        private const val JPEG_QUALITY = 85
        private const val POLL_INTERVAL_MS = 33L  // ~30fps
    }

    private var player: ExoPlayer? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile var frameCount: Long = 0; private set
    @Volatile var lastError: String? = null; private set

    @Volatile private var currentUrl: String = ""
    @Volatile private var triedUdpFallback = false
    @Volatile private var decodeErrorRestarts = 0
    private val MAX_DECODE_RESTARTS = 30

    // capturePending: verhindert Stau wenn JPEG-Kompression länger als Poll-Intervall dauert
    @Volatile private var capturePending = false

    // Viidure/SGK-spezifisch: TCP-Socket auf Port 6035 ("mail_tcp_socket")
    @Volatile private var mailSocket: Socket? = null

    // SDP-Proxy: patcht "a=recvonly"-Bug in der Kamera-Firmware
    @Volatile private var sdpProxy: RtspSdpProxy? = null

    // Frame-Polling: läuft auf mainHandler, JPEG-Kompression auf captureThread
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            if (!capturePending) {
                val bmp = try {
                    textureView.getBitmap(INITIAL_WIDTH, INITIAL_HEIGHT)
                } catch (e: Exception) {
                    AppLog.w(TAG, "getBitmap Exception: ${e.message}")
                    null
                }
                if (bmp != null) {
                    capturePending = true
                    val handler = captureHandler
                    val posted = handler?.post {
                        var outBmp: Bitmap? = null
                        try {
                            // UV=0-Fix: c2.android.avc.decoder schreibt UV=0 in SurfaceTexture
                            // → GPU macht YCbCr→ARGB mit Cb=Cr=0 → grünes Bild (G≈Y, R≈0, B≈0).
                            // Fix: detektiere grünen Tint und extrahiere Y aus G-Kanal → Graustufe.
                            outBmp = fixGreenTintIfNeeded(bmp)
                            val out = ByteArrayOutputStream((INITIAL_WIDTH * INITIAL_HEIGHT) / 4)
                            outBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            val jpeg = out.toByteArray()
                            frameCount++
                            if (frameCount == 1L) {
                                AppLog.i(TAG, "Erster RTSP-Frame (TextureView): ${jpeg.size}B JPEG @ ${INITIAL_WIDTH}x${INITIAL_HEIGHT} uvFix=${outBmp !== bmp}")
                                decodeErrorRestarts = 0
                            }
                            onJpegFrame(jpeg)
                        } finally {
                            if (outBmp != null && outBmp !== bmp) outBmp.recycle()
                            bmp.recycle()
                            capturePending = false
                        }
                    }
                    // captureHandler bereits gestoppt (stop() während getBitmap lief)
                    if (posted != true) {
                        bmp.recycle()
                        capturePending = false
                    }
                }
            }

            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Detektiert UV=0-Bug des c2.android.avc.decoder: Pixel haben G >> R und G >> B (grüner Tint).
     * Falls erkannt: Y-Kanal aus G extrahieren und Graustufen-Bitmap zurückgeben.
     * Bei normalen Farbbildern (korrekte UV): gibt die Original-Bitmap zurück.
     * WICHTIG: Aufrufer muss BEIDE Bitmaps (original + return) recyceln wenn return !== original.
     */
    private fun fixGreenTintIfNeeded(bmp: Bitmap): Bitmap {
        // Stichprobe: 4 Pixel in Bildmitte analysieren
        val cx = bmp.width / 2; val cy = bmp.height / 2
        var greenExcess = 0
        for (dx in 0..1) for (dy in 0..1) {
            val p = bmp.getPixel(cx + dx, cy + dy)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (g > r + 40 && g > b + 40) greenExcess++
        }
        if (greenExcess < 3) return bmp  // Normales Bild → unverändert zurückgeben

        // UV=0 bestätigt: G-Kanal ≈ Y (Luma). Konvertiere zu Graustufe.
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val g = (pixels[i] shr 8) and 0xFF
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    fun start(url: String) {
        currentUrl = url
        triedUdpFallback = false
        thread(start = true, name = "rtsp-wakeup") {
            val hostMatch = Regex("rtsp://([^:/]+)").find(url)
            val host = hostMatch?.groupValues?.get(1)
            var rtspUrl = url
            if (host != null && host.startsWith("192.168.")) {
                wakeupCameraHttp(host)
                // SDP-Proxy starten: kamera-seitiger Bug a=recvonly → wird zu sendrecv gepatcht
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

    /**
     * Öffnet einen dauerhaften TCP-Socket auf Port 6035 ("mail_tcp_socket"-Port).
     */
    private fun openMailSocket(host: String, port: Int) {
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = 15000
            mailSocket = s
            AppLog.i(TAG, "Mail-Socket offen auf $host:$port")

            try {
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
                for (p in probes) {
                    try {
                        s.getOutputStream().write(p.toByteArray(Charsets.UTF_8))
                        s.getOutputStream().flush()
                        AppLog.i(TAG, "Mail-TX: ${p.trim()}")
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Mail-TX fehlgeschlagen: ${e.message}")
                        break
                    }
                }
            } catch (_: Exception) {}

            thread(start = true, name = "mail-socket-rx") {
                try {
                    val buf = ByteArray(4096)
                    while (s.isConnected && !s.isClosed) {
                        val n = try { s.getInputStream().read(buf) } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        val msg = String(buf, 0, n, Charsets.UTF_8).trim().take(180)
                        AppLog.i(TAG, "Mail-RX: $msg")
                    }
                } catch (_: Exception) {}
                AppLog.i(TAG, "Mail-Socket Reader beendet")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Mail-Socket öffnen fehlgeschlagen: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /**
     * Viidure/SGK-HTTP-Setup-Sequenz.
     */
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
        for (ep in endpoints) {
            try {
                val conn = URL(ep).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Lavf58.76.100")
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }.take(200)
                } else ""
                conn.disconnect()
                AppLog.i(TAG, "  $ep → $code ${if (body.isNotBlank()) "| $body" else ""}")
            } catch (e: Exception) {
                AppLog.i(TAG, "  $ep → ${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}")
            }
        }
        AppLog.i(TAG, "HTTP Wake-Up abgeschlossen")
        describeRtsp(host, "rtsp://$host/live/tcp/ch1")
    }

    /**
     * Roher RTSP DESCRIBE — loggt vollständiges SDP für Diagnose.
     */
    private fun describeRtsp(host: String, rtspUrl: String) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, 554), 3000)
            sock.soTimeout = 3000
            val req = "DESCRIBE $rtspUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint/2.3.18\r\nAccept: application/sdp\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val n = try { sock.getInputStream().read(buf) } catch (_: Exception) { break }
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                val hEnd = sb.indexOf("\r\n\r\n")
                if (hEnd >= 0) {
                    val cl = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(sb.substring(0, hEnd))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (sb.length >= hEnd + 4 + cl) break
                }
            }
            sock.close()
            AppLog.i(TAG, "DESCRIBE SDP (${sb.length}B):\n${sb.take(2000)}")
        } catch (e: Exception) {
            AppLog.w(TAG, "DESCRIBE fehlgeschlagen: ${e.message}")
        }
    }

    private fun startInternal(url: String, useTcp: Boolean) {
        if (running) stopInternal()
        running = true
        lastError = null
        frameCount = 0
        capturePending = false

        AppLog.i(TAG, "start: $url (RTP über ${if (useTcp) "TCP" else "UDP"})")
        onStatus("RTSP: Verbinde (${if (useTcp) "TCP" else "UDP"}) zu $url")

        // Capture-Thread für JPEG-Kompression starten
        captureThread = HandlerThread("RtspCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val trackSelector = DefaultTrackSelector(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,   // minBufferMs
                10000,  // maxBufferMs
                100,    // bufferForPlaybackMs
                200     // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Software-Decoder c2.android.avc.decoder bevorzugen.
        // OMX.qcom.video.decoder.avc (HW) rendert nicht korrekt in TextureView-SurfaceTexture
        // auf diesem Gerät → getBitmap() liefert schwarze Pixel.
        // c2.android.avc.decoder liefert grünes Bild (UV=0) aber mit korrektem Y-Kanal
        // → Treffererkennung via Frame-Differenz funktioniert auf Y-Kanal.
        // UV-Korrektur: captureFrame() detektiert grünen Tint und konvertiert zu Graustufe.
        val softwareFirstFactory = object : DefaultRenderersFactory(context) {
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
                val softFirst = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneledDecoder ->
                    val decoders = mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneledDecoder)
                    if (mimeType == MimeTypes.VIDEO_H264) {
                        val sorted = decoders.sortedBy { info ->
                            if (info.name.startsWith("c2.android") || info.name.startsWith("OMX.google")) 0 else 1
                        }
                        AppLog.i(TAG, "H264-Decoder: ${sorted.firstOrNull()?.name ?: "(none)"} (von ${decoders.size})")
                        sorted
                    } else decoders
                }
                super.buildVideoRenderers(context, extensionRendererMode, softFirst,
                    enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            }
        }

        val exo = ExoPlayer.Builder(context, softwareFirstFactory)
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
        // TextureView statt ImageReader: GPU-Rendering-Pfad → korrekte Pixel-Ausgabe
        exo.setVideoTextureView(textureView)
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

                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                    && sdpProxy != null) {
                    AppLog.i(TAG, "DECODING_FAILED mit Proxy → Segment-Boundary übernimmt Recycle")
                    return
                }

                if (running && currentUrl.isNotEmpty()
                    && decodeErrorRestarts <= MAX_DECODE_RESTARTS) {
                    val proxyUrl = sdpProxy?.let { "rtsp://127.0.0.1:15554/live/tcp/ch1" }
                        ?: currentUrl
                    AppLog.i(TAG, "Unerwarteter Fehler → Fallback-Recycle in 200ms")
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
                for (g in tracks.groups) {
                    for (i in 0 until g.length) {
                        val fmt = g.getTrackFormat(i)
                        AppLog.i(TAG, "Track[$i]: mime=${fmt.sampleMimeType} ${fmt.width}x${fmt.height} selected=${g.isTrackSelected(i)}")
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                AppLog.i(TAG, "Video-Size: ${videoSize.width}x${videoSize.height}")
            }
        })

        exo.prepare()

        // Frame-Polling starten (200ms Verzögerung — Decoder braucht erste Frames)
        mainHandler.postDelayed(captureRunnable, 200L)

        // Watchdog: kein UDP-Fallback nötig bei TextureView (war für ImageReader-Diagnose)
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            if (frameCount > 0) return@postDelayed
            if (!useTcp || triedUdpFallback) {
                AppLog.w(TAG, "Keine Frames nach 30s — beide Transport-Arten probiert, aufgegeben")
                onStatus("RTSP: keine Video-Pakete empfangen (Kamera neu starten?)")
                return@postDelayed
            }
            AppLog.i(TAG, "Keine Frames nach 30s mit TCP — Fallback auf UDP")
            onStatus("RTSP: TCP liefert keine Frames — versuche UDP")
            triedUdpFallback = true
            startInternal(currentUrl, useTcp = false)
        }, 30000)
    }

    /**
     * ExoPlayer recyceln ohne Proxy-Neustart (Segment-Boundary alle ~4-5s).
     */
    private fun recycleExoPlayer(proxyUrl: String) {
        if (!running || currentUrl.isEmpty()) return
        decodeErrorRestarts++
        AppLog.i(TAG, "Segment-Boundary: ExoPlayer-Recycle #$decodeErrorRestarts → proxyUrl=$proxyUrl")
        onStatus("RTSP: Segment-Wechsel, Decoder-Reset #$decodeErrorRestarts")
        stopInternal()
        mainHandler.postDelayed({
            if (currentUrl.isEmpty()) return@postDelayed
            startInternal(proxyUrl, useTcp = true)
        }, 150)
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

    private fun stopInternal() {
        if (!running) return
        running = false
        AppLog.i(TAG, "stop")
        // Polling stoppen (sowohl auf Main- als auch auf captureThread)
        mainHandler.removeCallbacks(captureRunnable)
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        capturePending = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
