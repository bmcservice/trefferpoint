package de.bmcservice.trefferpoint

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
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
 *   RTSP → ExoPlayer (Decoder: MediaCodec) → SurfaceView (Hardware-Compositor)
 *        → PixelCopy.request() alle ~33ms → JPEG
 *        → onJpegFrame Callback → TrefferPoint JS-Bridge
 *
 * SurfaceView + PixelCopy statt TextureView + getBitmap():
 *   TextureView.getBitmap() liest via lockHardwareCanvas() aus dem Hardware-Layer.
 *   Auf Samsung-Geräten ist dieser NICHT mit dem OpenGL-Renderer synchronisiert →
 *   getBitmap() liefert immer Y=0 (schwarze/grüne Pixel), obwohl TextureView
 *   das Video korrekt anzeigt. Dieser Bug trat in v2.3.47–v2.3.51 auf.
 *
 *   SurfaceView + PixelCopy (ab v2.3.52):
 *   - ExoPlayer rendert in SurfaceView.holder.getSurface() (nativer Video-Surface)
 *   - holder.setFixedSize(960, 540) setzt den Surface-Buffer unabhängig von View-Größe
 *   - PixelCopy.request(surfaceView, null, bitmap, ...) liest direkt aus dem
 *     SurfaceFlinger-Compositor-Buffer → korrekte Pixel, unabhängig vom Hardware-Layer
 *   - API 26 (= minSdk) → keine @RequiresApi-Annotation nötig
 *
 * Frame-Capture-Strategie:
 *   - captureRunnable auf mainHandler (alle ~33ms)
 *   - PixelCopy.request() async → Callback auf captureThread (HandlerThread)
 *   - capturePending verhindert gleichzeitige PixelCopy-Requests
 */
class RtspPipeline(
    private val context: Context,
    private val surfaceView: SurfaceView,
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

    // setFixedSize(960×540) wird in MainActivity.surfaceCreated() gesetzt — einmalig
    // beim ersten Surface-Aufbau, bevor RtspPipeline überhaupt instanziiert wird.
    // match_parent-Layout garantiert einen ausreichend großen Surface-Buffer für PixelCopy.

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

    // capturePending: verhindert gleichzeitige PixelCopy-Requests
    @Volatile private var capturePending = false

    // Viidure/SGK-spezifisch: TCP-Socket auf Port 6035 ("mail_tcp_socket")
    @Volatile private var mailSocket: Socket? = null

    // SDP-Proxy: patcht "a=recvonly"-Bug in der Kamera-Firmware
    @Volatile private var sdpProxy: RtspSdpProxy? = null

    /**
     * UV=0-Detektion: Tritt auf wenn Software-Decoder Cb=Cr=0 in die Surface schreibt.
     * SurfaceFlinger konvertiert dann YUV→RGB mit Cb=Cr=0 → grüner Tint (G≈Y+135, R≈0, B≈0).
     * Fix: G-Kanal als Luma extrahieren + Min-Max-Normalisierung → Graustufen-Bild.
     * Mit Hardware-Decoder (bevorzugt) tritt das nicht auf — diese Methode ist dann No-Op.
     */
    private fun fixGreenTintIfNeeded(bmp: Bitmap): Bitmap {
        val cx = bmp.width / 2; val cy = bmp.height / 2
        var greenExcess = 0
        for (dx in 0..1) for (dy in 0..1) {
            val p = bmp.getPixel(cx + dx, cy + dy)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (g > r + 40 && g > b + 40) greenExcess++
        }
        if (greenExcess < 3) return bmp

        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var gMin = 255; var gMax = 0
        for (p in pixels) { val g = (p shr 8) and 0xFF; if (g < gMin) gMin = g; if (g > gMax) gMax = g }
        val gSpan = gMax - gMin
        if (frameCount <= 1L || frameCount % 30L == 0L) {
            // R,G,B-Sample aus 4 Center-Pixeln für vollständige Farb-Diagnose
            val sample = pixels.take(100)
            val avgR = sample.sumOf { (it shr 16) and 0xFF } / sample.size
            val avgG = sample.sumOf { (it shr 8) and 0xFF } / sample.size
            val avgB = sample.sumOf { it and 0xFF } / sample.size
            AppLog.i(TAG, "uvFix: gMin=$gMin gMax=$gMax span=$gSpan | sampleRGB=($avgR,$avgG,$avgB)")
        }
        if (gSpan < 20) {
            // Bild fast uniform (gSpan<20): G-Kanal direkt als Helligkeit ausgeben.
            // Normalisierung würde range≈1 → alle Pixel auf 0 treiben (komplett schwarz).
            // G ≈ Y + 135 bei UV=0 — absoluter Helligkeitswert ist besser als künstliches Schwarz.
            for (i in pixels.indices) {
                val g = (pixels[i] shr 8) and 0xFF
                pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        } else {
            // Normales Bild mit Kontrast: auf 0–255 normalisieren für maximalen Dynamikumfang
            val range = gSpan
            for (i in pixels.indices) {
                val y = ((pixels[i] shr 8) and 0xFF - gMin) * 255 / range
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Einen Frame per PixelCopy aus dem SurfaceView-Buffer lesen.
     * MUSS auf dem Main-Thread aufgerufen werden (PixelCopy.request braucht Main-Thread
     * für die SurfaceView-Koordination).
     *
     * Callback wird auf captureThread ausgeführt (JPEG-Kompression off Main-Thread).
     */
    private fun captureFrame() {
        if (!running || capturePending) return
        val handler = captureHandler ?: return
        capturePending = true
        val bmp = Bitmap.createBitmap(INITIAL_WIDTH, INITIAL_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                surfaceView,
                null,  // null = gesamter Surface-Buffer (INITIAL_WIDTH × INITIAL_HEIGHT)
                bmp,
                { result ->
                    // Callback auf captureThread
                    var outBmp: Bitmap? = null
                    try {
                        if (result == PixelCopy.SUCCESS) {
                            outBmp = fixGreenTintIfNeeded(bmp)
                            val out = ByteArrayOutputStream((INITIAL_WIDTH * INITIAL_HEIGHT) / 4)
                            outBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            val jpeg = out.toByteArray()
                            frameCount++
                            if (frameCount == 1L || frameCount % 30L == 0L) {
                                AppLog.i(TAG, "RTSP-Frame #$frameCount: ${jpeg.size}B JPEG (PixelCopy, uvFix=${outBmp !== bmp})")
                                if (frameCount == 1L) decodeErrorRestarts = 0
                            }
                            onJpegFrame(jpeg)
                        } else {
                            // ERROR_SOURCE_NO_DATA ist normal während Buffering → kein Log-Spam
                            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) {
                                AppLog.w(TAG, "PixelCopy result=$result (0=OK,1=UNKNOWN,2=TIMEOUT,3=NO_DATA,4=SRC_INVALID,5=DST_INVALID)")
                            }
                        }
                    } finally {
                        if (outBmp != null && outBmp !== bmp) outBmp.recycle()
                        bmp.recycle()
                        capturePending = false
                    }
                },
                handler
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "PixelCopy Exception: ${e.message}")
            bmp.recycle()
            capturePending = false
        }
    }

    // Frame-Polling auf Main-Thread: captureFrame() → PixelCopy → JPEG auf captureThread
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            captureFrame()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
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
                // Segment-Boundaries transparent vom Proxy handeln lassen (return false).
                // Proxy managed seqState + tsState + SPS/PPS-Dedup — ExoPlayer-Recycle nicht nötig.
                // recycleExoPlayer() bei onSegmentBoundary→true verursachte vicious cycle:
                // Kamera verwirrt durch abrupte Reconnects → Segmente nur ~0.7s → Loop.
                proxy.onSegmentBoundary = null  // transparent reconnect (Kamera trennt TCP nicht)
                // IDR-Boundary: ExoPlayer stoppen BEVOR er intern reconnecten kann.
                // mainHandler.post landet VOR ExoPlayers IO-Error-Handler in der Queue
                // → ExoPlayer wird via stopInternal() released → kein Auto-Reconnect
                // → kein frame_num=0-Crash → sauberer Neustart 150ms später.
                proxy.onIdrBoundary = {
                    val pUrl = "rtsp://127.0.0.1:15554/live/tcp/ch1"
                    mainHandler.post {
                        if (running && currentUrl.isNotEmpty()) {
                            AppLog.i(TAG, "IDR-Boundary: stopInternal() + Neustart in 150ms")
                            stopInternal()
                            mainHandler.postDelayed({
                                if (currentUrl.isNotEmpty()) {
                                    decodeErrorRestarts = 0
                                    startInternal(pUrl, useTcp = true)
                                }
                            }, 150)
                        }
                    }
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

        AppLog.i(TAG, "start: $url (SV.surface=${surfaceView.holder.surface != null}) (RTP über ${if (useTcp) "TCP" else "UDP"})")
        onStatus("RTSP: Verbinde (${if (useTcp) "TCP" else "UDP"}) zu $url")

        // Capture-Thread für JPEG-Kompression + PixelCopy-Callback
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

        // SOFTWARE-Decoder erzwingen — NOTWENDIG für PixelCopy auf Samsung!
        //
        // Root Cause (v2.3.54-Analyse): Qualcomm Hardware-Decoder (OMX.qcom.video.decoder.avc)
        // schreibt decoded Frames in einen dedizierten Hardware-Overlay-Layer von SurfaceFlinger.
        // PixelCopy.request(SurfaceView) liest den normalen SurfaceView-SurfaceControl-Layer —
        // der bei Hardware-Overlay-Nutzung LEER/SCHWARZ bleibt. onRenderedFirstFrame feuert
        // trotzdem, weil der HW-Decoder seinen Overlay beschreibt.
        //
        // Software-Decoder (c2.android.avc.decoder) schreibt direkt in den SurfaceFlinger-Layer
        // der SurfaceView → PixelCopy liest echten Inhalt.
        // Nachteil: UV=0-Bug (Cb=Cr=0) → grüner Tint. fixGreenTintIfNeeded() korrigiert das
        // zu einem nutzbaren Graustufen-Bild (Y-Kanal = Luminanz, ausreichend für Treffererkennung).
        val rendersFactory = object : DefaultRenderersFactory(context) {
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
                val swSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneledDecoder ->
                    val decoders = mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneledDecoder)
                    if (mimeType == MimeTypes.VIDEO_H264) {
                        val sw = decoders.filter { !it.hardwareAccelerated }
                        AppLog.i(TAG, "H264-Decoder: ${sw.firstOrNull()?.name ?: "(kein SW)"} " +
                            "(SW-erzwungen, ${decoders.size} gesamt, ${sw.size} SW)")
                        if (sw.isNotEmpty()) return@MediaCodecSelector sw
                        // Fallback auf HW falls kein SW verfügbar (unwahrscheinlich auf Android 8+)
                        AppLog.w(TAG, "Kein Software-Decoder verfügbar — HW-Fallback (PixelCopy evtl. schwarz)")
                    }
                    decoders
                }
                super.buildVideoRenderers(context, extensionRendererMode, swSelector,
                    enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            }
        }

        val exo = ExoPlayer.Builder(context, rendersFactory)
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
        // SurfaceView: nativer Video-Surface → Hardware-Compositor rendert Frames
        // PixelCopy liest dann aus dem Compositor-Buffer (korrekte Pixel, kein Samsung-Bug)
        exo.setVideoSurfaceView(surfaceView)
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
                // Guard: stopInternal() via onIdrBoundary setzt running=false und released den Player.
                // Listener-Callbacks können trotzdem noch feuern — dann ignorieren.
                if (!running) {
                    AppLog.i(TAG, "onPlayerError nach stopInternal() — ignoriert (${error.errorCodeName})")
                    return
                }
                AppLog.e(TAG, "ExoPlayer Fehler: ${error.errorCodeName} — ${error.message}", error)
                lastError = "${error.errorCodeName}: ${error.message}"
                onStatus("RTSP-Fehler: ${error.errorCodeName}")

                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                    && sdpProxy != null) {
                    // SW-Decoder (c2.android.avc.decoder) kann an Segment-Boundaries crashen
                    // (frame_num-Reset der Kamera). Proxy-Session wird neu gestartet → Kamera
                    // liefert frische SPS+PPS+IDR → Decoder initialisiert sauber.
                    // Verzögerung 1.5s: Kamera braucht einen Moment nach ihrem Segment-Ende.
                    decodeErrorRestarts++
                    // Proaktiver IDR-Boundary-Disconnect (RtspSdpProxy) sollte DECODING_FAILED
                    // normalerweise verhindern. Falls er doch auftritt: 500ms Delay statt 1.5s —
                    // die Kamera braucht keinen langen Vorlauf, RTSP PLAY liefert sofort IDR.
                    AppLog.i(TAG, "DECODING_FAILED → RecycleExoPlayer #$decodeErrorRestarts (500ms Delay)")
                    if (running && currentUrl.isNotEmpty() && decodeErrorRestarts <= MAX_DECODE_RESTARTS) {
                        mainHandler.postDelayed({
                            if (currentUrl.isEmpty()) return@postDelayed
                            recycleExoPlayer("rtsp://127.0.0.1:15554/live/tcp/ch1")
                        }, 500)
                    }
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

            override fun onRenderedFirstFrame() {
                AppLog.i(TAG, "onRenderedFirstFrame — erste Frame in SurfaceView gerendert")
            }
        })

        exo.prepare()

        // Frame-Polling starten (200ms Verzögerung — Decoder braucht erste Frames)
        mainHandler.postDelayed(captureRunnable, 200L)

        // Watchdog: keine Frames nach 30s → UDP-Fallback
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
     * ExoPlayer recyceln — Proxy-Neustart inklusive, damit Kamera frische SPS+PPS+IDR sendet.
     */
    private fun recycleExoPlayer(proxyUrl: String) {
        if (!running || currentUrl.isEmpty()) return
        // decodeErrorRestarts wird bereits vom Aufrufer (onPlayerError) inkrementiert — KEIN
        // weiteres ++ hier, da das sonst zu doppeltem Zählen führt und MAX_DECODE_RESTARTS
        // zu früh erreicht wird.
        AppLog.i(TAG, "ExoPlayer-Recycle #$decodeErrorRestarts → proxyUrl=$proxyUrl")
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
