package de.bmcservice.trefferpoint

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * RTSP-Pipeline OHNE ExoPlayer — direkter MediaCodec-Pfad.
 *
 * Architektur:
 * ```
 *   SGK GK720X → RtspSdpProxy:15554 ← uns selbst connectieren
 *                                      ↓
 *   RTP-Reader-Thread: Interleaved $ ch len payload
 *                                      ↓
 *   FU-A Reassembler: Fragmente → komplette NAL-Units
 *                                      ↓
 *   MediaCodec (video/avc) → setOutputSurface(imageReader.surface)
 *                                      ↓
 *   Bei IDR-Boundary: decoder.flush() (~50ms statt ExoPlayer-Recycle 1.5s)
 *                                      ↓
 *   ImageReader 960×540 YUV_420_888 → NV21 → JPEG → onJpegFrame
 * ```
 *
 * **Vorteile gegenüber ExoPlayer-Pfad (v2.3.74-87):**
 *   - Volle Kontrolle über Decoder-Lifecycle
 *   - Bei IDR-Reset: `MediaCodec.flush()` statt ExoPlayer-Recycle
 *   - Kein Audio-Decoder-Risiko (wir füttern nur Video-NAL)
 *   - Async-Adapter implizit (wir bedienen In/Out-Buffers manuell)
 *   - Kein RtpH264Reader-SPS/PPS-Format-Change-Bug
 *
 * **Voraussetzungen:**
 *   - RtspSdpProxy läuft bereits auf 127.0.0.1:15554 (von außen gestartet)
 *   - SPS+PPS verfügbar via sprop-parameter-sets im SDP der Proxy-DESCRIBE-Antwort
 */
class RtspMediaCodecPipeline(
    private val context: Context,
    private val outputSurface: Surface,
    private val onStatus: (String) -> Unit,
    private val onFrameRendered: (width: Int, height: Int) -> Unit
) {
    companion object {
        private const val TAG = "RtspMediaCodec"
        // Fallback-Auflösung wenn SPS-Parse fehlschlägt (= ch1-Default)
        private const val FALLBACK_WIDTH = 960
        private const val FALLBACK_HEIGHT = 540
        // JPEG_QUALITY: v2.3.153 50 (vorher 80). Bei 1920×1080 erzeugt Quality 80
        // ~85 KB/Frame; nach Base64 ~113 KB Argument an webView.evaluateJavascript().
        // Auf der ETF150-Cam beobachtet (v2.3.152): 3-5 s Lag im Live-Bild,
        // wiederholte Stream-Freezes → WebView-IPC ist der Bottleneck.
        // Quality 50 → ~30-40 KB/Frame (~3× weniger Bridge-Last), für Aiming optisch
        // unverändert wahrnehmbar, für Differenz-Detection völlig ausreichend
        // (Frame-Diff wird nach Schwellwert binärisiert).
        private const val JPEG_QUALITY = 50
        private const val MAX_IMAGES = 6
        private const val NAL_BUFFER_SIZE = 256 * 1024  // max 256 KB pro NAL-Unit
        // PTS-Konversion: RTP-Timestamp ist in 90kHz, MediaCodec PTS in µs
        private const val RTP_TICKS_PER_SECOND = 90000L
    }

    // Tatsächliche Auflösung des aktuellen Streams (aus SPS extrahiert).
    // Wird in startInternal() vor configureDecoder() gesetzt.
    @Volatile private var imageWidth = FALLBACK_WIDTH
    @Volatile private var imageHeight = FALLBACK_HEIGHT

    @Volatile var frameCount: Long = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastFrameJpeg: ByteArray? = null; private set
    val videoWidth: Int get() = imageWidth
    val videoHeight: Int get() = imageHeight

    @Volatile private var running = false
    @Volatile private var currentUrl: String = ""
    // v2.3.156: Watchdog gegen MediaCodec-Decoder-Stall.
    // Symptom (heute am Stand reproduziert): nach 1000-2000 Frames spammt der Decoder
    // `IllegalStateException` aus `dequeueInputBuffer` ("queueNal Fehler: null"),
    // frameCount stagniert, Bild friert. Self-Recycle bei DECODING_FAILED greift
    // dabei nicht (Fehler ist input-side, nicht output-side).
    // Watchdog: prüft alle 2 s, ob frameCount steigt. Bei >4 s Stall → komplett-restart.
    private var watchdogThread: Thread? = null

    // Kamera-spezifische Pfade (für interne Proxy-Verbindung)
    private var sdpProxy: RtspSdpProxy? = null
    private var rtspSocket: Socket? = null
    private var mailSocket: Socket? = null

    // MediaCodec + ImageReader
    private var decoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    // Separater Encoder-Thread: YUV→JPEG läuft hier, damit der ImageReader-Listener
    // schnell freigegeben wird und der Decoder-Output-Loop nicht blockiert.
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    @Volatile private var encodePending: Boolean = false
    @Volatile private var encodeDropped: Long = 0L
    private var decoderInputThread: Thread? = null
    private var decoderOutputThread: Thread? = null

    // FU-A Reassembly-State
    private val nalAccu = ByteArrayOutputStream(NAL_BUFFER_SIZE)
    private var nalAccuType: Int = 0
    private var nalAccuRtpTs: Long = -1L

    // PTS-Continuity über IDR-Boundaries hinweg
    private var firstRtpTs: Long = -1L
    private var lastSeenRtpTs: Long = -1L
    private var ptsBaseUs: Long = 0L  // wird nach flush() weiter geführt

    // SPS/PPS für CSD und Re-Configure nach flush()
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    /** Mail-Socket-Forwarding (Battery/REC/SD-Status der Kamera) */
    var onMailMessage: ((String) -> Unit)? = null

    fun start(url: String) {
        currentUrl = url
        thread(start = true, name = "rtsp-mc-wakeup") {
            try {
                startInternal(url)
            } catch (e: Exception) {
                AppLog.e(TAG, "Pipeline-Start fehlgeschlagen", e)
                lastError = e.message ?: "Unbekannter Fehler"
                onStatus("RTSP-Fehler: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        currentUrl = ""
        try { rtspSocket?.close() } catch (_: Exception) {}
        rtspSocket = null
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        imageHandler = null
        try { encoderThread?.quitSafely() } catch (_: Exception) {}
        encoderThread = null
        encoderHandler = null
        try { sdpProxy?.stop() } catch (_: Exception) {}
        sdpProxy = null
        AppLog.i(TAG, "stop")
    }

    private fun startInternal(url: String) {
        // 1. Kamera-Discovery via HTTP-Wakeup (wie in alter Pipeline)
        val hostMatch = Regex("rtsp://([^:/]+)").find(url)
        val host = hostMatch?.groupValues?.get(1) ?: error("Ungültige RTSP-URL: $url")

        // v2.3.150: Multi-Cam-Pfad. Der SGK-Proxy enthält 7 SGK/Viidure-spezifische
        // Firmware-Workarounds (Sequenz-Rewrite, Segment-Reconnect, IDR-Inject,
        // recvonly-Patch, /tcp/ch1-Pfad-Hartkodierung u.a.) und ist NICHT generisch.
        // Andere RTSP-Cams (Apexel ETF150, künftige Modelle) verbinden direkt zur
        // Kamera:554 mit der vom User gegebenen URL.
        // Heuristik: SGK-URLs enthalten den Pfad-Marker /tcp/ch<N>; andere nicht.
        val useSgkProxy = url.contains(Regex("/tcp/ch[0-9]"))

        sdpProxy?.stop()
        sdpProxy = null
        try { mailSocket?.close() } catch (_: Exception) {}
        mailSocket = null

        if (host.startsWith("192.168.")) {
            wakeupCameraHttp(host)
            if (useSgkProxy) {
                sdpProxy = RtspSdpProxy(host)
                sdpProxy!!.start()
                // Achtung: Smoother bleibt im Proxy implementiert aber deaktiviert (Flag).
                openMailSocket(host, 6035)
            }
        }

        running = true
        lastError = null
        frameCount = 0
        firstRtpTs = -1L
        lastSeenRtpTs = -1L
        ptsBaseUs = 0L
        nalAccu.reset()

        // 2. RTSP-Handshake (DESCRIBE, SETUP Video, PLAY)
        // SGK: über lokalen Proxy auf 127.0.0.1:15554 mit hartkodiertem /live/tcp/ch1.
        // Andere Cams (ETF150 etc.): direkt an Cam:554 mit User-URL als Aggregate.
        val (sockHost, sockPort) = if (useSgkProxy) "127.0.0.1" to 15554 else host to 554
        AppLog.i(TAG, if (useSgkProxy)
            "Starte RTSP-Handshake via SGK-Proxy auf 127.0.0.1:15554"
        else
            "Starte RTSP-Handshake direkt an $sockHost:$sockPort (kein SGK-Proxy)"
        )
        onStatus(if (useSgkProxy) "RTSP: Verbinde via Proxy …" else "RTSP: Verbinde direkt …")
        val sock = Socket()
        sock.connect(InetSocketAddress(sockHost, sockPort), 5000)
        sock.soTimeout = 15000
        rtspSocket = sock

        val streamUrl = if (useSgkProxy) "rtsp://127.0.0.1:15554/live/tcp/ch1" else url
        val rIn = sock.getInputStream()
        val rOut = sock.getOutputStream()

        // OPTIONS
        send(rOut, "OPTIONS $streamUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint-MC\r\n\r\n")
        readMsg(rIn) ?: error("OPTIONS keine Antwort")

        // DESCRIBE → SDP für SPS/PPS extrahieren (optional)
        send(rOut, "DESCRIBE $streamUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: TrefferPoint-MC\r\nAccept: application/sdp\r\n\r\n")
        val describeResp = readMsg(rIn) ?: error("DESCRIBE keine Antwort")
        parseSpropParameterSets(describeResp)
        // v2.3.148: ETF150 sendet keine sprop-parameter-sets im SDP — SPS/PPS kommen
        // inline im Stream als NAL-Type 7/8 (oder STAP-A). Wir starten dann mit
        // Fallback-Auflösung und konfigurieren den Decoder erst NACH dem Empfang von
        // SPS+PPS aus dem Stream (siehe maybeConfigureDecoderFromStreamCsd).
        val hasInlineCsd = (spsBytes != null && ppsBytes != null)
        if (hasInlineCsd) {
            val res = H264SpsParser.parseFromNal(spsBytes!!)
            if (res != null) {
                imageWidth = res.width
                imageHeight = res.height
                AppLog.i(TAG, "Stream-Auflösung aus SDP-SPS: $res")
            } else {
                imageWidth = FALLBACK_WIDTH
                imageHeight = FALLBACK_HEIGHT
                AppLog.w(TAG, "SDP-SPS-Parse fehlgeschlagen — Fallback ${imageWidth}x${imageHeight}")
            }
        } else {
            imageWidth = FALLBACK_WIDTH
            imageHeight = FALLBACK_HEIGHT
            AppLog.i(TAG, "SDP ohne sprop-parameter-sets (ETF150-Style) — sammle SPS/PPS aus Stream, " +
                "Default-Maße ${imageWidth}x${imageHeight}")
        }

        // SETUP nur Video, Audio ignorieren um Decoder-Stabilität zu sichern
        // (ExoPlayer's Audio-Decoder destabilisierte früher den Video-Decoder).
        // v2.3.149: SETUP-URL aus SDP `a=control:` parsen statt hartkodierten `/video/track0`.
        // SGK liefert relativ wie `video/track0`, ETF150 liefert query-style `?ctype=video`.
        // java.net.URI.resolve() handhabt beide RFC-3986-konform.
        val setupUrl = resolveVideoControlUrl(describeResp, streamUrl)
        AppLog.i(TAG, "SETUP-URL: $setupUrl")
        send(rOut, "SETUP $setupUrl RTSP/1.0\r\nCSeq: 3\r\nUser-Agent: TrefferPoint-MC\r\n" +
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
        val setupResp = readMsg(rIn) ?: error("SETUP keine Antwort")
        val sid = Regex("Session:\\s*([^\r\n;]+)").find(setupResp)?.groupValues?.get(1)
            ?: error("SETUP keine Session-ID")

        // PLAY
        send(rOut, "PLAY $streamUrl RTSP/1.0\r\nCSeq: 4\r\nSession: $sid\r\nUser-Agent: TrefferPoint-MC\r\n\r\n")
        val playResp = readMsg(rIn) ?: error("PLAY keine Antwort")
        if (!playResp.contains("RTSP/1.0 200")) {
            error("PLAY abgelehnt: ${playResp.take(80)}")
        }
        sock.soTimeout = 0  // RTP-Stream ist persistent
        AppLog.i(TAG, "PLAY OK — RTSP-Handshake abgeschlossen")
        onStatus("RTSP: Stream verbunden")

        startWatchdog()

        // 3. ImageReader + Decoder konfigurieren
        // v2.3.148: Bei ETF150 (kein SDP-CSD) werden ImageReader UND Decoder erst nach
        // Empfang von SPS+PPS aus dem Stream konfiguriert (lazy in captureCsdNal),
        // damit die echte Stream-Auflösung verwendet wird.
        if (hasInlineCsd) {
            configureDecoder()
        } else {
            AppLog.i(TAG, "Decoder wartet auf erstes SPS+PPS aus dem Stream")
        }

        // 4. RTP-Reader-Thread starten
        decoderInputThread = thread(name = "rtsp-mc-reader", start = true) {
            try {
                rtpReaderLoop(rIn)
            } catch (e: Exception) {
                if (running) AppLog.e(TAG, "RTP-Reader Fehler", e)
            }
        }
    }

    private fun setupImageReader() {
        imageThread = HandlerThread("RtspMediaCodecImg").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        imageReader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                imageWidth, imageHeight, ImageFormat.YUV_420_888, MAX_IMAGES,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
        } else {
            ImageReader.newInstance(imageWidth, imageHeight, ImageFormat.YUV_420_888, MAX_IMAGES)
        }
        // v2.3.157: JPEG-Encode-Throttling. Bei 1080p kostet YuvImage→JPEG ~30-40 ms,
        // ImageReader-Listener läuft synchron auf imageHandler. Mit jedem Frame
        // einzeln war die Pipeline bei 30 fps-Eingang nur 22 fps-Output → ~8 fps
        // Frames pro Sekunde Backlog → wachsendes Lag (am Stand: 5+ Sekunden).
        // Skip jeden 2. Frame: image.close() sofort, kein JPEG-Encode. So kann der
        // Decoder seine Output-Buffer schneller recyclen → kein Backlog.
        // 15 fps JPEG für DevHttpServer/MJPEG ist mehr als genug fürs Display.
        var encodeCounter = 0L
        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                // v2.3.158: acquireLatestImage statt acquireNextImage — verwirft
                // automatisch alle veralteten Frames im Reader-Buffer und liefert nur
                // den aktuellsten. Damit bricht der Pipeline-Backlog auch dann zusammen,
                // wenn JPEG-Encode zwischendurch zu langsam war (Codex-Empfehlung —
                // Android-Doku warnt explizit vor acquireNextImage in Realtime-Pfaden).
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    encodeCounter++
                    val skip = (encodeCounter and 1L) == 0L  // jeden 2. Frame skippen
                    if (skip) return@setOnImageAvailableListener
                    val w = image.width
                    val h = image.height
                    val nv21 = yuv420ToNv21(image)
                    val jpeg = nv21ToJpeg(nv21, w, h)
                    lastFrameJpeg = jpeg
                    frameCount++
                    if (frameCount == 1L) {
                        AppLog.i(TAG, "Erster RTSP-Frame: ${jpeg.size}B JPEG @ ${w}x${h}")
                        onStatus("RTSP-Stream aktiv")
                    } else if (frameCount <= 5L || frameCount % 30L == 0L) {
                        AppLog.i(TAG, "Frame #$frameCount: ${jpeg.size}B JPEG (skip ratio 1:1)")
                    }
                    // v2.3.159: kein onJpegFrame-Callback mehr — Display geht direkt
                    // via SurfaceView, Detection holt Pixel via PixelCopy on-demand.
                    // setupImageReader() wird in startInternal nicht mehr aufgerufen,
                    // diese Methode ist Dead Code (Cleanup in einem späteren Commit).
                } finally {
                    image.close()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Image-Verarbeitung fehlgeschlagen", e)
            }
        }, imageHandler)
    }

    private fun configureDecoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, imageWidth, imageHeight)
        // CSD: SPS und PPS in Annex-B-Format (mit Start-Code 0x00000001)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(toAnnexB(spsBytes!!)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(toAnnexB(ppsBytes!!)))
        // v2.3.157: Low-Latency-Hint (Android R+). Sagt dem Decoder: kein
        // B-Frame-Reorder-Puffer, sofort Output liefern statt mehrere Frames
        // im Decoder-internen Reorder-Buffer zwischenzulagern.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // Operating-Rate-Hint: Decoder soll 60 fps schaffen können → mehr CPU-Budget.
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
        // Realtime-Priorität (0=realtime, 1=non-realtime).
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)

        // SW-Decoder explizit wählen — HW-Decoder schreibt nicht in CPU-lesbare Surfaces
        // (Adreno HW-Overlay-Bypass — bewiesen in v2.3.71-76).
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder!!.configure(format, outputSurface, null, 0)
        decoder!!.start()
        AppLog.i(TAG, "MediaCodec gestartet: ${decoder!!.name}, ${imageWidth}x${imageHeight}")

        // Output-Buffer-Loop in eigenem Thread (release direkt auf die SurfaceView)
        decoderOutputThread = thread(name = "rtsp-mc-output", start = true) {
            try {
                outputBufferLoop()
            } catch (e: Exception) {
                if (running) AppLog.e(TAG, "Output-Buffer-Loop Fehler", e)
            }
        }
    }

    private fun outputBufferLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = decoder ?: break
            // 5ms Timeout statt 100ms: niedrige Latenz, Loop reagiert schnell auf neue Frames.
            // Bei "INFO_TRY_AGAIN_LATER" minimaler 1ms-Sleep um CPU nicht voll auszulasten.
            val idx = try { codec.dequeueOutputBuffer(info, 5_000L) } catch (e: Exception) {
                // Async-Adapter kann transiente IllegalStateException werfen ohne dass
                // der Decoder defekt ist (interne Sync zwischen In/Out-Threads).
                // Schweigend Wegswallow, kurzer Sleep, Loop läuft weiter — wenn der Decoder
                // wirklich tot wäre, würden wir CodecException(0x80000000) sehen.
                Thread.sleep(10)
                continue
            }
            when {
                idx >= 0 -> {
                    // Render direkt in die SurfaceView.
                    try { codec.releaseOutputBuffer(idx, true) } catch (_: Exception) {}
                    frameCount++
                    onFrameRendered(imageWidth, imageHeight)
                    if (frameCount == 1L) {
                        AppLog.i(TAG, "Erster RTSP-Frame direkt auf SurfaceView @ ${imageWidth}x${imageHeight}")
                        onStatus("RTSP-Stream aktiv")
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    AppLog.i(TAG, "Decoder Output-Format-Wechsel: ${codec.outputFormat}")
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Kein Output bereit — minimal warten damit CPU nicht 100% läuft.
                    Thread.sleep(1)
                }
            }
        }
    }

    /** RTP-Reader: liest TCP-Interleaved-Pakete vom Proxy und reassembliert NAL-Units. */
    private fun rtpReaderLoop(rIn: InputStream) {
        val cam = BufferedInputStream(rIn, 65536)
        val pktBuf = ByteArray(65540)
        while (running) {
            val dollar = cam.read()
            if (dollar < 0) break
            if (dollar != 0x24) continue
            val ch = cam.read(); if (ch < 0) break
            val lenHi = cam.read(); if (lenHi < 0) break
            val lenLo = cam.read(); if (lenLo < 0) break
            val len = (lenHi shl 8) or lenLo
            if (len <= 0 || len > 65536) continue
            var read = 0
            while (read < len) {
                val n = cam.read(pktBuf, read, len - read)
                if (n < 0) break
                read += n
            }
            if (read < len) break

            // Nur Video-Channel verarbeiten (ch=0 = Video-RTP, ch=1 = Video-RTCP)
            if (ch != 0 || len < 12) continue

            // RTP-Header
            // pktBuf[0] = V/P/X/CC, pktBuf[1] = M/PT, pktBuf[2..3] = seq, pktBuf[4..7] = ts
            val rtpTs = ((pktBuf[4].toLong() and 0xFF) shl 24) or
                        ((pktBuf[5].toLong() and 0xFF) shl 16) or
                        ((pktBuf[6].toLong() and 0xFF) shl 8) or
                         (pktBuf[7].toLong() and 0xFF)
            val payloadOff = 12  // ohne CSRC/Extensions (typisch)
            if (len < payloadOff + 1) continue

            val nalHdr = pktBuf[payloadOff].toInt() and 0xFF
            val nalType = nalHdr and 0x1F

            when (nalType) {
                in 1..23 -> {
                    // Single NAL Unit
                    val nal = pktBuf.copyOfRange(payloadOff, len)
                    handleNalUnit(nal, rtpTs)
                }
                28 -> {
                    // FU-A
                    if (len < payloadOff + 2) continue
                    val fuHdr = pktBuf[payloadOff + 1].toInt() and 0xFF
                    val sBit = (fuHdr and 0x80) != 0
                    val eBit = (fuHdr and 0x40) != 0
                    val origType = fuHdr and 0x1F
                    if (sBit) {
                        // Erstes Fragment: NAL-Header rekonstruieren
                        // NAL-Header = (FU-Indicator NRI/F bits) | origType
                        val reconstructedHdr = (nalHdr and 0xE0) or origType
                        nalAccu.reset()
                        nalAccu.write(reconstructedHdr)
                        nalAccu.write(pktBuf, payloadOff + 2, len - payloadOff - 2)
                        nalAccuType = origType
                        nalAccuRtpTs = rtpTs
                    } else {
                        // Folge-Fragment
                        nalAccu.write(pktBuf, payloadOff + 2, len - payloadOff - 2)
                    }
                    if (eBit && nalAccu.size() > 0) {
                        // Ende: NAL-Unit komplett
                        val nal = nalAccu.toByteArray()
                        nalAccu.reset()
                        handleNalUnit(nal, nalAccuRtpTs)
                    }
                }
                24 -> {
                    // STAP-A: Aggregat mehrerer NAL-Units. Format:
                    // [STAP-A header | nal1_size:2byte | nal1_bytes | nal2_size:2byte | nal2_bytes ...]
                    // Bei ETF150 kommen SPS+PPS oft als STAP-A am Stream-Anfang.
                    parseStapAForCsd(pktBuf, payloadOff + 1, len - payloadOff - 1, rtpTs)
                }
                else -> {
                    if (frameCount < 5L) AppLog.i(TAG, "Unbekannter NAL-Type: $nalType")
                }
            }
        }
        AppLog.i(TAG, "RTP-Reader-Loop beendet")
    }

    private fun handleNalUnit(nal: ByteArray, rtpTs: Long) {
        val type = nal[0].toInt() and 0x1F
        // v2.3.148: SPS (7) / PPS (8) NICHT mehr verwerfen — bei ETF150 (kein SDP-CSD)
        // werden sie hier aufgefangen und Decoder lazy-konfiguriert.
        if (type == 7 || type == 8) {
            captureCsdNal(type, nal)
            return
        }

        // Wenn Decoder noch nicht konfiguriert ist (warte auf SPS+PPS), Frame verwerfen.
        if (decoder == null) return

        // Kein präventives flush() bei IDR mehr (war Auslöser des "Pulsierens" alle 3s).
        // Der direkte MediaCodec sollte IDRs spec-konform handhaben (wir füttern komplette
        // NAL-Units mit korrekter PTS). Bei Crash → reaktiv im OutputLoop flushen (TODO).
        val isIdr = (type == 5)

        // PTS-Berechnung (RTP-TS in 90kHz → µs, monoton via Offset)
        if (firstRtpTs < 0) {
            firstRtpTs = rtpTs
            lastSeenRtpTs = rtpTs
        }
        var deltaTicks = (rtpTs - lastSeenRtpTs) and 0xFFFFFFFFL
        if (deltaTicks > Int.MAX_VALUE.toLong()) deltaTicks = 3000L  // großer Sprung → 33ms-Default
        ptsBaseUs += (deltaTicks * 1_000_000L) / RTP_TICKS_PER_SECOND
        lastSeenRtpTs = rtpTs

        // Annex-B Format mit Start-Code (0x00000001) prepend
        queueNalToDecoder(nal, ptsBaseUs, isIdr)
    }

    /**
     * v2.3.156: Frame-Stall-Watchdog. Läuft als Daemon-Thread während running=true.
     * Prüft alle 2 s ob frameCount steigt. 4 s ohne neuen Frame → restart der Pipeline.
     * Aufgerufen am Ende von startInternal() nach erfolgreichem PLAY.
     */
    private fun startWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = thread(start = true, name = "rtsp-watchdog", isDaemon = true) {
            var lastFrameCount = frameCount
            var lastFrameTime = SystemClock.elapsedRealtime()
            while (running) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (!running) break
                val now = SystemClock.elapsedRealtime()
                val current = frameCount
                if (current != lastFrameCount) {
                    lastFrameCount = current
                    lastFrameTime = now
                    continue
                }
                val stallMs = now - lastFrameTime
                if (stallMs >= 4000L) {
                    AppLog.w(TAG, "Watchdog: kein Frame seit ${stallMs}ms (frameCount=$current) — Auto-Restart")
                    onStatus("RTSP: Pipeline-Stall — Auto-Restart …")
                    val url = currentUrl
                    // Restart in eigenem Thread weil stop() den Watchdog (=this thread) beendet
                    if (url.isNotEmpty()) {
                        thread(start = true, name = "rtsp-restart", isDaemon = true) {
                            try {
                                stop()
                                Thread.sleep(800)
                                start(url)
                            } catch (e: Exception) {
                                AppLog.e(TAG, "Watchdog-Restart fehlgeschlagen", e)
                            }
                        }
                    }
                    break  // Watchdog endet, neuer wird beim erfolgreichen Restart gestartet
                }
            }
        }
    }

    private fun queueNalToDecoder(nal: ByteArray, ptsUs: Long, isKeyframe: Boolean) {
        val codec = decoder ?: return
        try {
            // Großzügiger Timeout (100ms) damit der Reader nicht NALs verwirft
            // wenn der Decoder kurzzeitig keinen freien Input-Buffer hat. Bei 30 fps
            // = 33ms Frame-Spacing — 100ms gibt 3-Frame-Puffer für Decoder-Stalls.
            val idx = codec.dequeueInputBuffer(100_000L)
            if (idx < 0) {
                if (frameCount < 10L) AppLog.w(TAG, "dequeueInputBuffer timeout (Frames=$frameCount)")
                return
            }
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            // Start-Code 0x00000001
            buf.put(0x00); buf.put(0x00); buf.put(0x00); buf.put(0x01)
            buf.put(nal)
            val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(idx, 0, 4 + nal.size, ptsUs, flags)
        } catch (e: Exception) {
            AppLog.w(TAG, "queueNal Fehler: ${e.message}")
        }
    }

    /**
     * v2.3.148: SPS (Type 7) oder PPS (Type 8) aus dem Stream auffangen.
     * Wird ausschließlich für den ETF150-Pfad (kein SDP-CSD) genutzt — bei SGK
     * sind die Werte schon vor dem Reader-Loop gesetzt.
     * Beim ersten kompletten SPS+PPS-Paar wird configureDecoder() aufgerufen.
     */
    private fun captureCsdNal(type: Int, nal: ByteArray) {
        if (decoder != null) return  // Decoder bereits konfiguriert, neues SPS/PPS ignorieren
        when (type) {
            7 -> if (spsBytes == null) {
                spsBytes = nal
                AppLog.i(TAG, "Stream-SPS aufgefangen (${nal.size}B)")
            }
            8 -> if (ppsBytes == null) {
                ppsBytes = nal
                AppLog.i(TAG, "Stream-PPS aufgefangen (${nal.size}B)")
            }
        }
        if (spsBytes != null && ppsBytes != null && decoder == null) {
            // Auflösung neu aus SPS extrahieren (überschreibt Fallback-Werte)
            try {
                val res = H264SpsParser.parseFromNal(spsBytes!!)
                if (res != null) {
                    if (res.width != imageWidth || res.height != imageHeight) {
                        AppLog.i(TAG, "Stream-Auflösung aus SPS: $res (war ${imageWidth}x${imageHeight})")
                        imageWidth = res.width
                        imageHeight = res.height
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "SPS-Parse Fehler: ${e.message}")
            }
            try {
                configureDecoder()
            } catch (e: Exception) {
                AppLog.e(TAG, "Lazy Decoder-Konfig fehlgeschlagen", e)
            }
        }
    }

    /**
     * v2.3.148: STAP-A Aggregat-Paket parsen, einzelne NALs einzeln durch handleNalUnit.
     * Format ab off: [size_hi, size_lo, nal_bytes(size), size_hi, size_lo, nal_bytes(size), ...]
     */
    private fun parseStapAForCsd(buf: ByteArray, off: Int, length: Int, rtpTs: Long) {
        var p = off
        val end = off + length
        while (p + 2 <= end) {
            val size = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
            p += 2
            if (size <= 0 || p + size > end) break
            val nal = buf.copyOfRange(p, p + size)
            p += size
            if (frameCount == 0L) {
                val t = nal[0].toInt() and 0x1F
                AppLog.i(TAG, "STAP-A NAL extrahiert: type=$t size=$size")
            }
            handleNalUnit(nal, rtpTs)
        }
    }

    /**
     * v2.3.149: Video-Track-Control-URL aus DESCRIBE-Antwort extrahieren.
     * Quelle ist `a=control:` im m=video-Block; wird relativ zur `Content-Base:` aufgelöst.
     * Fallback auf `<streamUrl>/video/track0` (SGK-Default) wenn nichts parsbar ist.
     *
     * Beispiele:
     *   ETF150:  Content-Base: rtsp://192.168.10.1/live/  +  a=control:?ctype=video
     *            → rtsp://192.168.10.1/live/?ctype=video
     *   SGK:     Content-Base: rtsp://192.168.0.1:554/live/tcp/ch1/  +  a=control:video/track0
     *            → rtsp://192.168.0.1:554/live/tcp/ch1/video/track0
     */
    private fun resolveVideoControlUrl(describeResp: String, streamUrl: String): String {
        try {
            // 1. Content-Base aus Header
            val contentBase = Regex("Content-Base:\\s*([^\r\n]+)", RegexOption.IGNORE_CASE)
                .find(describeResp)?.groupValues?.get(1)?.trim()

            // 2. SDP-Body finden (nach Header-Trenner \r\n\r\n)
            val sdpStart = describeResp.indexOf("\r\n\r\n").let { if (it >= 0) it + 4 else 0 }
            val sdp = describeResp.substring(sdpStart)

            // 3. m=video-Section isolieren (alles bis zum nächsten m= oder Ende)
            val videoIdx = sdp.indexOf("m=video")
            if (videoIdx < 0) return "$streamUrl/video/track0"
            val nextMediaIdx = sdp.indexOf("\nm=", videoIdx + 1).let { if (it < 0) sdp.length else it }
            val videoSection = sdp.substring(videoIdx, nextMediaIdx)

            // 4. a=control: in der Video-Section
            val control = Regex("a=control:([^\r\n]+)").find(videoSection)
                ?.groupValues?.get(1)?.trim()
                ?: return "$streamUrl/video/track0"

            // 5. Resolve relativ zu Content-Base (oder streamUrl als Fallback)
            val baseStr = contentBase ?: streamUrl
            return when {
                control.startsWith("rtsp://", ignoreCase = true) -> control
                else -> {
                    // RFC-3986 URI-Resolution via java.net.URI
                    java.net.URI(baseStr).resolve(control).toString()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resolveVideoControlUrl Fehler: ${e.message} — Fallback /video/track0")
            return "$streamUrl/video/track0"
        }
    }

    /** SPS/PPS aus DESCRIBE-SDP-Antwort extrahieren (sprop-parameter-sets). */
    private fun parseSpropParameterSets(describeResp: String) {
        val match = Regex("sprop-parameter-sets=([A-Za-z0-9+/=]+)(?:,([A-Za-z0-9+/=]+))?")
            .find(describeResp) ?: return
        val sps = Base64.decode(match.groupValues[1], Base64.NO_WRAP or Base64.NO_PADDING)
        val pps = if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
            Base64.decode(match.groupValues[2], Base64.NO_WRAP or Base64.NO_PADDING)
        } else null
        spsBytes = sps
        ppsBytes = pps
        AppLog.i(TAG, "SDP: SPS=${sps.size}B PPS=${pps?.size ?: 0}B extrahiert")
    }

    private fun toAnnexB(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    /** NV21-Bytes → JPEG via YuvImage.compressToJpeg. Auslagernbar auf encoderThread. */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(width * height / 2)
        yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val planes = image.planes
        // Y-Plane
        copyPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
            width, height, nv21, 0, 1)
        // V- + U-Plane interleaved (NV21 = Y... VU VU VU)
        copyPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
            width / 2, height / 2, nv21, ySize, 2)
        copyPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
            width / 2, height / 2, nv21, ySize + 1, 2)
        return nv21
    }

    private fun copyPlane(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        width: Int, height: Int,
        output: ByteArray, outputOffset: Int, outputPixelStride: Int
    ) {
        val rowBuf = ByteArray(rowStride)
        var dst = outputOffset
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            val toRead = minOf(rowStride, buffer.remaining())
            buffer.get(rowBuf, 0, toRead)
            for (col in 0 until width) {
                output[dst + col * outputPixelStride] = rowBuf[col * pixelStride]
            }
            dst += width * outputPixelStride
        }
    }

    // ── HTTP-Wakeup + Mail-Socket (von alter Pipeline portiert) ──
    private fun wakeupCameraHttp(host: String) {
        AppLog.i(TAG, "HTTP Wake-Up auf $host…")
        val endpoints = listOf(
            "http://$host:80/app/getdeviceattr",
            "http://$host:80/app/capability",
            "http://$host:80/app/getproductinfo",
            "http://$host:80/app/getmediainfo",
            "http://$host:80/app/setrec?rec=0"
        )
        for (ep in endpoints) {
            try {
                val conn = java.net.URL(ep).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Lavf58.76.100")
                val code = conn.responseCode
                conn.disconnect()
                AppLog.i(TAG, "  $ep → $code")
            } catch (_: Exception) {}
        }
    }

    private fun openMailSocket(host: String, port: Int) {
        try { mailSocket?.close() } catch (_: Exception) {}
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
                        val msg = String(buf, 0, n, Charsets.UTF_8).trim().take(512)
                        AppLog.i(TAG, "Mail-RX: $msg")
                        onMailMessage?.invoke(msg)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Mail-Socket öffnen fehlgeschlagen: ${e.message}")
        }
    }

    // ── RTSP-Helper ──
    private fun send(out: OutputStream, msg: String) {
        out.write(msg.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun readMsg(input: InputStream): String? {
        val sb = StringBuilder()
        var cl = 0
        while (true) {
            val line = readCrlfLine(input) ?: return null
            sb.append(line).append("\r\n")
            if (line.startsWith("content-length:", ignoreCase = true))
                cl = line.substringAfter(":").trim().toIntOrNull() ?: 0
            if (line.isEmpty()) break
        }
        if (cl > 0) {
            val body = ByteArray(cl)
            var n = 0
            while (n < cl) {
                val r = input.read(body, n, cl - n)
                if (r < 0) break
                n += r
            }
            sb.append(String(body, 0, n, Charsets.UTF_8))
        }
        return sb.toString()
    }

    private fun readCrlfLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (prev == 0x0D && b == 0x0A) {
                return sb.dropLast(1).toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
