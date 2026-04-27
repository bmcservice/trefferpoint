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
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtspMediaCodec"
        // Fallback-Auflösung wenn SPS-Parse fehlschlägt (= ch1-Default)
        private const val FALLBACK_WIDTH = 960
        private const val FALLBACK_HEIGHT = 540
        // JPEG_QUALITY: 80 statt 90 → ~25% schneller, Bildqualität für Treffererkennung
        // völlig ausreichend (Treffer-Detection braucht keine fotografische Schärfe).
        private const val JPEG_QUALITY = 80
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

    @Volatile private var running = false
    @Volatile private var currentUrl: String = ""

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
        if (host.startsWith("192.168.")) {
            wakeupCameraHttp(host)
            sdpProxy?.stop()
            sdpProxy = RtspSdpProxy(host)
            sdpProxy!!.start()
            // Achtung: Smoother bleibt im Proxy implementiert aber deaktiviert (Flag).
            openMailSocket(host, 6035)
        }

        running = true
        lastError = null
        frameCount = 0
        firstRtpTs = -1L
        lastSeenRtpTs = -1L
        ptsBaseUs = 0L
        nalAccu.reset()

        // 2. RTSP-Handshake mit Proxy (DESCRIBE, SETUP Video, PLAY)
        AppLog.i(TAG, "Starte eigenen RTSP-Handshake auf 127.0.0.1:15554")
        onStatus("RTSP: Verbinde via Proxy …")
        val sock = Socket()
        sock.connect(InetSocketAddress("127.0.0.1", 15554), 5000)
        sock.soTimeout = 15000
        rtspSocket = sock

        val streamUrl = "rtsp://127.0.0.1:15554/live/tcp/ch1"
        val rIn = sock.getInputStream()
        val rOut = sock.getOutputStream()

        // OPTIONS
        send(rOut, "OPTIONS $streamUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint-MC\r\n\r\n")
        readMsg(rIn) ?: error("OPTIONS keine Antwort")

        // DESCRIBE → SDP für SPS/PPS extrahieren
        send(rOut, "DESCRIBE $streamUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: TrefferPoint-MC\r\nAccept: application/sdp\r\n\r\n")
        val describeResp = readMsg(rIn) ?: error("DESCRIBE keine Antwort")
        parseSpropParameterSets(describeResp)
        if (spsBytes == null || ppsBytes == null) {
            error("Konnte SPS/PPS nicht aus SDP extrahieren")
        }
        // Auflösung aus SPS extrahieren — wir konfigurieren ImageReader und MediaCodec
        // mit den echten Stream-Maßen. Bei ch1 typisch 960×540, bei ch0 evtl. 1920×1080
        // oder 2560×1440 (HD-Stream).
        val res = H264SpsParser.parseFromNal(spsBytes!!)
        if (res != null) {
            imageWidth = res.width
            imageHeight = res.height
            AppLog.i(TAG, "Stream-Auflösung aus SPS: $res")
        } else {
            imageWidth = FALLBACK_WIDTH
            imageHeight = FALLBACK_HEIGHT
            AppLog.w(TAG, "SPS-Parse fehlgeschlagen — Fallback ${imageWidth}x${imageHeight}")
        }

        // SETUP nur Video (Track 0), Audio ignorieren um Decoder-Stabilität zu sichern
        // ExoPlayer's Audio-Decoder destabilisierte den Video-Decoder → wir füttern hier
        // nur Video-NAL-Units in unseren MediaCodec.
        send(rOut, "SETUP $streamUrl/video/track0 RTSP/1.0\r\nCSeq: 3\r\nUser-Agent: TrefferPoint-MC\r\n" +
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

        // 3. ImageReader + Decoder konfigurieren
        setupImageReader()
        configureDecoder()

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
        // Synchroner Encoder-Pfad: war in v2.3.89 stabil. Async-Variante destabilisierte
        // den Decoder (dequeueOutputBuffer-Exception nach Frame 5). YUV→JPEG ist ~30ms,
        // bei 30fps-Budget knapp aber tragbar; Frame-Rate fällt ggf. auf 20fps was OK ist.
        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
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
                        AppLog.i(TAG, "Frame #$frameCount: ${jpeg.size}B JPEG")
                    }
                    onJpegFrame(jpeg)
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
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        // CSD: SPS und PPS in Annex-B-Format (mit Start-Code 0x00000001)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(toAnnexB(spsBytes!!)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(toAnnexB(ppsBytes!!)))

        // SW-Decoder explizit wählen — HW-Decoder schreibt nicht in CPU-lesbare Surfaces
        // (Adreno HW-Overlay-Bypass — bewiesen in v2.3.71-76).
        decoder = MediaCodec.createByCodecName("c2.android.avc.decoder")
        decoder!!.configure(format, imageReader!!.surface, null, 0)
        decoder!!.start()
        AppLog.i(TAG, "MediaCodec gestartet: c2.android.avc.decoder, ${imageWidth}x${imageHeight}")

        // Output-Buffer-Loop in eigenem Thread (releases zu ImageReader)
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
                    // Render in ImageReader-Surface (releaseOutputBuffer mit render=true).
                    try { codec.releaseOutputBuffer(idx, true) } catch (_: Exception) {}
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
                    // STAP-A: mehrere NALs in einem Paket — selten von Kameras genutzt, ignorieren
                    if (frameCount == 0L) AppLog.i(TAG, "STAP-A NAL-Type — übersprungen")
                }
                7, 8 -> {
                    // SPS/PPS Single-NAL — ignorieren, wir haben sie aus SDP
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
        // SPS/PPS aus dem Stream ignorieren (kommt nur am Anfang, wir haben sie aus SDP)
        if (type == 7 || type == 8) return

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
