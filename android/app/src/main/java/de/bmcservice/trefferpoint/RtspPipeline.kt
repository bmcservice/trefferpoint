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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * RTSP-Stream Pipeline für WLAN-Okularkameras (Viidure etc.).
 *
 *   RTSP → ExoPlayer (Decoder: MediaCodec) → Surface von ImageReader
 *        → YUV_420_888 Image → NV21 → JPEG (YuvImage.compressToJpeg)
 *        → onJpegFrame Callback → TrefferPoint JS-Bridge
 *
 * Design identisch zur UVC-Pipeline: am Ende fällt ein JPEG raus,
 * das die Bridge per `window.tpReceiveFrame(base64)` in die WebView drückt.
 */
class RtspPipeline(
    private val context: Context,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtspPipeline"
        private const val INITIAL_WIDTH = 1920
        private const val INITIAL_HEIGHT = 1080
        private const val JPEG_QUALITY = 85
    }

    private var player: ExoPlayer? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile var frameCount: Long = 0; private set
    @Volatile var lastError: String? = null; private set

    @Volatile private var currentUrl: String = ""
    @Volatile private var triedUdpFallback = false

    fun start(url: String) {
        currentUrl = url
        triedUdpFallback = false
        startInternal(url, useTcp = true)
    }

    private fun startInternal(url: String, useTcp: Boolean) {
        if (running) stopInternal()
        running = true
        lastError = null
        frameCount = 0

        AppLog.i(TAG, "start: $url (RTP über ${if (useTcp) "TCP" else "UDP"})")
        onStatus("RTSP: Verbinde (${if (useTcp) "TCP" else "UDP"}) zu $url")

        imageThread = HandlerThread("RtspImageReader").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        imageReader = ImageReader.newInstance(
            INITIAL_WIDTH, INITIAL_HEIGHT, ImageFormat.YUV_420_888, 2
        ).apply {
            setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val jpeg = imageToJpeg(img)
                        frameCount++
                        if (frameCount == 1L) {
                            AppLog.i(TAG, "Erster RTSP-Frame: ${jpeg.size}B JPEG @ ${img.width}x${img.height}")
                        }
                        onJpegFrame(jpeg)
                    } finally {
                        img.close()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Image-Verarbeitung fehlgeschlagen", e)
                }
            }, imageHandler)
        }

        val exo = ExoPlayer.Builder(context).build()
        player = exo

        // User-Agent imitiert FFmpeg libavformat — genau das, was die Viidure-App selbst
        // schickt (via PCAPdroid reverse-engineered). Viele OEM-Kameras liefern nur bei
        // bekanntem User-Agent einen Stream aus ("security through obscurity").
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
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                AppLog.i(TAG, "Video-Size: ${videoSize.width}x${videoSize.height}")
                // Bei Größenänderung neuen ImageReader anlegen damit Frames nicht verzerrt sind
                if (videoSize.width > 0 && videoSize.height > 0) {
                    recreateImageReader(videoSize.width, videoSize.height)
                }
            }
        })

        exo.prepare()

        // Watchdog: Wenn nach 6s immer noch keine Frames angekommen sind, und wir sind auf TCP,
        // automatischer Fallback auf UDP. Viele OEM-Kameras machen TCP-Interleaving kaputt.
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            if (frameCount > 0) return@postDelayed
            if (!useTcp || triedUdpFallback) {
                AppLog.w(TAG, "Keine Frames nach 6s — beide Transport-Arten probiert")
                onStatus("RTSP: keine Video-Pakete empfangen (Kamera neu starten?)")
                return@postDelayed
            }
            AppLog.i(TAG, "Keine Frames nach 6s mit TCP — Fallback auf UDP")
            onStatus("RTSP: TCP-Pakete kommen nicht — versuche UDP")
            triedUdpFallback = true
            startInternal(currentUrl, useTcp = false)
        }, 6000)
    }

    private fun recreateImageReader(w: Int, h: Int) {
        try {
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            newReader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val jpeg = imageToJpeg(img)
                        frameCount++
                        onJpegFrame(jpeg)
                    } finally { img.close() }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Image-Verarbeitung fehlgeschlagen", e)
                }
            }, imageHandler)
            player?.setVideoSurface(newReader.surface)
            imageReader = newReader
            oldReader?.close()
            AppLog.i(TAG, "ImageReader neu erstellt @ ${w}x${h}")
        } catch (e: Exception) {
            AppLog.e(TAG, "recreateImageReader fehlgeschlagen", e)
        }
    }

    fun stop() {
        triedUdpFallback = false
        currentUrl = ""
        stopInternal()
    }

    private fun stopInternal() {
        if (!running) return
        running = false
        AppLog.i(TAG, "stop")
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        imageHandler = null
    }

    // YUV_420_888 → NV21 → JPEG
    private fun imageToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream((w * h) / 2)
        yuv.compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 4
        val nv21 = ByteArray(ySize + 2 * uvSize)

        val planes = image.planes
        val yBuf: ByteBuffer = planes[0].buffer
        val uBuf: ByteBuffer = planes[1].buffer
        val vBuf: ByteBuffer = planes[2].buffer

        // Y-Plane: ggf. mit rowStride kopieren
        val yRowStride = planes[0].rowStride
        if (yRowStride == w) {
            yBuf.get(nv21, 0, ySize)
        } else {
            var pos = 0
            val row = ByteArray(w)
            for (r in 0 until h) {
                yBuf.position(r * yRowStride)
                yBuf.get(row, 0, w)
                System.arraycopy(row, 0, nv21, pos, w)
                pos += w
            }
        }

        // V/U interleaved für NV21
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val halfW = w / 2
        val halfH = h / 2

        if (uvPixelStride == 2 && uvRowStride == w) {
            // Bereits fast-NV21-Format — V kommt direkt nach Y
            val vuBytes = ByteArray(uvSize * 2)
            // V-Plane auslesen (pixelStride=2 bedeutet U/V sind interleaved)
            vBuf.position(0)
            val vLen = minOf(vBuf.remaining(), vuBytes.size)
            vBuf.get(vuBytes, 0, vLen)
            System.arraycopy(vuBytes, 0, nv21, ySize, vLen)
        } else {
            // Generisch: Pixel für Pixel kopieren
            var pos = ySize
            for (r in 0 until halfH) {
                for (c in 0 until halfW) {
                    val vIdx = r * uvRowStride + c * uvPixelStride
                    val uIdx = r * uvRowStride + c * uvPixelStride
                    nv21[pos++] = vBuf.get(vIdx)
                    nv21[pos++] = uBuf.get(uIdx)
                }
            }
        }
        return nv21
    }
}
