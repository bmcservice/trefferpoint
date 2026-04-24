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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
        // SGK GK720X / Viidure liefert immer 960×540 laut SDP.
        // Passend zur Kamera-Ausgabe → kein Surface-Wechsel nötig nach onTracksChanged.
        private const val INITIAL_WIDTH = 960
        private const val INITIAL_HEIGHT = 540
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

    // Viidure/SGK-spezifisch: TCP-Socket auf Port 6035 ("mail_tcp_socket")
    // muss offen bleiben während RTSP läuft, sonst blockt die Kamera den Stream.
    @Volatile private var mailSocket: Socket? = null

    // SDP-Proxy: patcht "a=recvonly"-Bug in der Kamera-Firmware, bevor ExoPlayer den SDP sieht.
    @Volatile private var sdpProxy: RtspSdpProxy? = null

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
                sdpProxy = proxy
                openMailSocket(host, 6035)
            }
            mainHandler.post { startInternal(rtspUrl, useTcp = true) }
        }
    }

    /**
     * Öffnet einen dauerhaften TCP-Socket auf Port 6035 ("mail_tcp_socket"-Port).
     * Ohne diesen Kanal interpretiert die Viidure-Firmware keinen Client als aktiv
     * und liefert keine RTP-Frames. Wir lesen die eingehenden JSON-Nachrichten
     * (Heartbeats wie battery/sd-Status) und loggen sie — Daten nur zum Offenhalten.
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
     * Viidure/SGK-HTTP-Setup-Sequenz — aus dem Crash-Log der Original-Viidure-App abgeleitet.
     * Ohne diesen Handshake liefert die Kamera zwar OPTIONS-Antwort auf RTSP, aber keine
     * Video-Pakete (silent drop).
     */
    private fun wakeupCameraHttp(host: String) {
        AppLog.i(TAG, "HTTP Wake-Up auf $host…")
        val endpoints = listOf(
            "http://$host:80/app/getdeviceattr",
            "http://$host:80/app/capability",
            "http://$host:80/app/getproductinfo",
            "http://$host:80/app/getmediainfo",
            // Mail-Heartbeat zeigt rec:value=1 (Aufnahme aktiv, keine SD-Karte).
            // Aufnahme stoppen — Kamera-Firmware liefert ggf. keinen RTSP-Stream im Aufnahme-Modus.
            "http://$host:80/app/setrec?rec=0"
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
        // Vollständigen SDP per rohem RTSP DESCRIBE loggen — zeigt exakte Codec-Parameter
        describeRtsp(host, "rtsp://$host/live/tcp/ch1")
    }

    /**
     * Roher RTSP DESCRIBE ohne ExoPlayer — loggt das vollständige SDP für Diagnose.
     * ExoPlayer macht DESCRIBE intern, aber der SDP bleibt unsichtbar.
     * Hier können wir Format-Parameter, Payload-Typen und Custom-Attributes sehen.
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
                // Abbruch wenn vollständige Antwort (Header + Content-Length Bytes)
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

        // Audio deaktivieren — SGK/Viidure-Kameras verstehen multi-track SETUP nicht:
        // sie senden keine Video-Daten wenn ExoPlayer auch SETUP für Audio schickt.
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
        player = exo

        // Scanner-Test hat gezeigt: Lavf57.83.100 (was Viidure selbst schickt) wird vom
        // OPTIONS-Handler abgewiesen. Aber ExoPlayer-default, VLC und Lavf58 bekommen 200 OK.
        // Nehmen wir Lavf58 — neutral und getestet funktionierend.
        val rtspSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcp)
            .setTimeoutMs(8000)
            .setUserAgent("Lavf58.76.100")
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

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) {
                    AppLog.w(TAG, "onTracksChanged: keine Tracks — SDP leer oder unbekanntes Format")
                    return
                }
                for (g in tracks.groups) {
                    for (i in 0 until g.length) {
                        val fmt = g.getTrackFormat(i)
                        AppLog.i(TAG, "Track[$i]: mime=${fmt.sampleMimeType} codec=${fmt.codecs} ${fmt.width}x${fmt.height} selected=${g.isTrackSelected(i)}")
                        // ImageReader muss exakt die Decoder-Ausgabegröße haben —
                        // sonst kann MediaCodec keine Output-Buffer dequeuen (stille Frame-Drops).
                        // onVideoSizeChanged feuert erst nach dem ersten Frame → Henne-Ei-Problem.
                        // Hier kennen wir die Größe aus dem SDP, noch vor PLAY → sofort korrigieren.
                        if (fmt.sampleMimeType?.startsWith("video/") == true
                            && g.isTrackSelected(i)
                            && fmt.width > 0 && fmt.height > 0) {
                            val w = fmt.width; val h = fmt.height
                            // Nicht direkt aus dem Listener-Callback aufrufen —
                            // setVideoSurface() aus onTracksChanged heraus kann ExoPlayer
                            // zu internem Reconnect zwingen. Post auf Main-Looper.
                            mainHandler.post {
                                AppLog.i(TAG, "→ ImageReader auf ${w}x${h} anpassen")
                                recreateImageReader(w, h)
                            }
                        }
                    }
                }
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

        // Watchdog: Wenn nach 30s keine Frames ankommen und wir TCP verwendet haben,
        // automatischer Fallback auf UDP. Viele OEM-Kameras machen TCP-Interleaving kaputt.
        // 30s statt 20s: SGK-Segment-Streaming hat ~620ms Reconnect-Pause alle 4.4s →
        // ExoPlayer braucht mehrere Segmente bis der Puffer gefüllt ist.
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            if (frameCount > 0) return@postDelayed
            if (!useTcp || triedUdpFallback) {
                AppLog.w(TAG, "Keine Frames nach 20s — beide Transport-Arten probiert, aufgegeben")
                onStatus("RTSP: keine Video-Pakete empfangen (Kamera neu starten?)")
                return@postDelayed
            }
            AppLog.i(TAG, "Keine Frames nach 20s mit TCP — Fallback auf UDP")
            onStatus("RTSP: TCP liefert keine Frames — versuche UDP")
            triedUdpFallback = true
            startInternal(currentUrl, useTcp = false)
        }, 30000)
    }

    private fun recreateImageReader(w: Int, h: Int) {
        try {
            // Surface-Wechsel während BUFFERING resettet den MediaCodec-Decoder.
            // Wenn die Größe bereits stimmt, Surface nicht neu erstellen.
            val current = imageReader
            if (current != null && current.width == w && current.height == h) {
                AppLog.i(TAG, "ImageReader bereits ${w}x${h} — kein Neustart")
                return
            }
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
        sdpProxy?.stop()
        sdpProxy = null
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null
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
