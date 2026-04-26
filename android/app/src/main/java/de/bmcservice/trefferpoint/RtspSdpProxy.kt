package de.bmcservice.trefferpoint

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimaler RTSP-Proxy der die DESCRIBE-Antwort der SGK/Viidure-Kamera patcht
 * und transparente Kamera-Reconnects durchführt.
 *
 * Firmware-Bug 1: beide Tracks im SDP haben "a=recvonly" statt "a=sendonly".
 *   → Proxy entfernt "a=recvonly" vor Weiterleitung an ExoPlayer.
 *
 * Firmware-Bug 2: Kamera trennt TCP-Verbindung nach ~4-5s (Segment-Streaming).
 *   → Proxy reconnectet sofort zur Kamera, ExoPlayer sieht kontinuierlichen Stream.
 *
 * Firmware-Bug 3: Kamera setzt RTP-Sequenznummern bei jedem Segment auf den
 *   Initialwert (256) zurück. ExoPlayer interpretiert dies als "bereits empfangen"
 *   und verwirft alle Pakete des zweiten Segments → BUFFERING bleibt hängen.
 *   → Proxy patcht Sequenznummern paketweise und hält sie über Reconnects hinweg
 *     monoton aufsteigend.
 */
class RtspSdpProxy(
    private val cameraHost: String,
    private val cameraPort: Int = 554,
    private val proxyPort: Int = 15554
) {
    companion object { private const val TAG = "RtspSdpProxy" }

    @Volatile private var active = false
    private var serverSock: ServerSocket? = null

    // Persistenter State: true sobald der erste echte IDR der Kamera an ExoPlayer
    // weitergeleitet wurde. Aktiviert ab diesem Punkt SPS/PPS-Dedup (verwerfe
    // wiederholte SPS+PPS an Segment-Grenzen, damit ExoPlayer keinen Decoder-Reset versucht).
    @Volatile private var idrEverInjected = false

    /**
     * Wird am Anfang jedes Kamera-Reconnects (Segment-Boundary) aufgerufen —
     * BEVOR der Proxy zur Kamera reconnectet und BEVOR der Decoder die neuen
     * P-Frames mit frame_num=0 sieht. RtspPipeline nutzt das, um ExoPlayer
     * proaktiv neu zu starten, statt auf den Decoder-Crash zu warten.
     *
     * Rückgabe true: Session sofort abbrechen (kein weiterer doCameraHandshake).
     * Rückgabe false / null: Normal weiter reconnecten (transparenter Reconnect).
     */
    var onSegmentBoundary: (() -> Boolean)? = null

    fun start(): String {
        serverSock = ServerSocket(proxyPort)
        active = true
        idrEverInjected = false  // neue Session — IDR-Relabel wieder erlauben
        thread(name = "rtsp-proxy-accept") {
            try {
                while (active) {
                    val client = serverSock!!.accept()
                    thread(name = "rtsp-proxy-conn") { session(client) }
                }
            } catch (_: Exception) {}
        }
        AppLog.i(TAG, "SDP-Proxy läuft: localhost:$proxyPort → $cameraHost:$cameraPort")
        return "rtsp://127.0.0.1:$proxyPort/live/tcp/ch1"
    }

    fun stop() {
        active = false
        try { serverSock?.close() } catch (_: Exception) {}
    }

    private fun session(clientSock: Socket) {
        // Jede neue ExoPlayer-Session bekommt frische IDR-Initialisierung.
        // Hintergrund: ExoPlayer-Restart (nach Segment-Boundary-Callback) öffnet
        // eine neue TCP-Verbindung → neues session()-Call → idrEverInjected muss false
        // sein, damit SPS/PPS weitergeleitet und IDR injiziert wird.
        idrEverInjected = false
        var cameraSock: Socket? = null
        val defaultVideoTrackUrl = if (cameraPort == 554)
            "rtsp://$cameraHost/live/tcp/ch1/video/track0"
        else
            "rtsp://$cameraHost:$cameraPort/live/tcp/ch1/video/track0"
        var trackUrl = defaultVideoTrackUrl
        // Firmware-Bug #7: ExoPlayer SETUPs Video-Track zuerst, dann Audio-Track.
        // trackUrl zeigt nach dem letzten SETUP auf den Audio-Track. Für transparente
        // Reconnects MUSS jedoch der Video-Track neu geöffnet werden, sonst schickt
        // die Kamera nach dem Reconnect nur Audio → kein Video → frameCount bleibt bei 1.
        var videoTrackUrl = defaultVideoTrackUrl
        // Wird beim SETUP gesetzt, damit die SETUP-Antwort an ExoPlayer korrekt gepatcht wird.
        var lastSetupIsAudio = false

        try {
            cameraSock = Socket(cameraHost, cameraPort)
            clientSock.soTimeout = 15000
            cameraSock!!.soTimeout = 15000
            val cIn  = clientSock.getInputStream()
            val cOut = clientSock.getOutputStream()

            // Erster Connect: ExoPlayer-RTSP-Requests als Proxy weiterleiten
            loop@ while (true) {
                val req = readMsg(cIn) ?: break
                val method = req.substringBefore(" ")

                var camReq = req.replaceFirst(
                    "rtsp://127.0.0.1:$proxyPort",
                    if (cameraPort == 554) "rtsp://$cameraHost"
                    else "rtsp://$cameraHost:$cameraPort"
                )
                if (method == "SETUP") {
                    val setupUrl = camReq.substringAfter("SETUP ").substringBefore(" RTSP")
                    if (setupUrl.startsWith("rtsp://")) {
                        trackUrl = setupUrl
                        // Video-Track-URL nur für nicht-Audio-Tracks merken
                        if (!setupUrl.contains("/audio/", ignoreCase = true)) {
                            videoTrackUrl = setupUrl
                        }
                    }
                    lastSetupIsAudio = setupUrl.contains("/audio/", ignoreCase = true)
                    // Firmware-Bug #7b: Kamera weist Video und Audio beide interleaved=0-1 zu
                    // → Channel-Kollision: Audio-Pakete landen auf dem Video-Channel (ch=0).
                    // Fix: Video bekommt ch0-1, Audio bekommt ch2-3. In relay() werden
                    // Audio-Pakete (PT≠96) auf ch=0 nach ch=2 umgemappt.
                    val interleavedCh = if (lastSetupIsAudio) "2-3" else "0-1"
                    camReq = Regex("Transport:[^\r\n]*", RegexOption.IGNORE_CASE)
                        .replace(camReq, "Transport: RTP/AVP/TCP;unicast;interleaved=$interleavedCh")
                    AppLog.i(TAG, "SETUP → Kamera (${if (lastSetupIsAudio) "Audio ch2-3" else "Video ch0-1"})")
                }
                cameraSock!!.getOutputStream().write(camReq.toByteArray(Charsets.UTF_8))
                cameraSock!!.getOutputStream().flush()

                val resp = readMsg(cameraSock!!.getInputStream()) ?: break
                if (method == "SETUP" || method == "PLAY") {
                    val status = resp.substringBefore("\r\n").take(60)
                    val transport = resp.lineSequence()
                        .firstOrNull { it.startsWith("Transport:", true) }?.take(100) ?: ""
                    AppLog.i(TAG, "Kamera→$method: $status${if (transport.isNotEmpty()) " | $transport" else ""}")
                }

                val fwd = when (method) {
                    "DESCRIBE" -> patchSdp(resp)
                    "PLAY"     -> patchPlayResponse(resp)
                    // Sicherstellen dass ExoPlayer die korrekten Interleaved-Channels sieht:
                    // Video → 0-1, Audio → 2-3 (auch wenn Kamera immer 0-1 antwortet)
                    "SETUP"    -> if (lastSetupIsAudio) patchSetupInterleaved(resp, "2-3") else resp
                    else       -> resp
                }
                cOut.write(fwd.toByteArray(Charsets.UTF_8))
                cOut.flush()

                if (method == "PLAY" && resp.contains("RTSP/1.0 200")) {
                    clientSock.soTimeout = 0
                    cameraSock!!.soTimeout = 0
                    AppLog.i(TAG, "PLAY OK — starte RTP-Relay (videoTrack=$videoTrackUrl)")
                    break@loop
                }
            }

            // RTP-Relay-Loop: seqState hält den nächsten zu sendenden Sequenz-Counter.
            // Durch Weitergabe über alle relay()-Aufrufe bleiben Sequenznummern
            // monoton, auch wenn die Kamera bei jedem Segment von vorne beginnt.
            // seqState[0] = -1 bedeutet "noch nicht initialisiert".
            val seqState = IntArray(1) { -1 }
            // tsState: Continuity der RTP-Timestamps über Reconnects.
            // Kamera setzt Timestamps bei jedem Segment auf eigenen Wert → ExoPlayer
            // interpretiert das als Discontinuity → Decoder-Flush → IllegalStateException.
            // tsState[0] = aktueller Offset, der zu Kamera-Timestamps addiert wird.
            // tsState[1] = letzter ausgegebener Timestamp (für nahtlose Fortsetzung).
            // Beide auf -1 initialisiert: noch keine Timestamps gesehen.
            val tsState = LongArray(2) { -1L }
            var reconnects = 0
            while (true) {
                val cameraClosedFirst = relay(
                    cIn, cOut,
                    cameraSock!!.getInputStream(),
                    cameraSock!!.getOutputStream(),
                    seqState,
                    tsState
                )
                if (!cameraClosedFirst) break  // ExoPlayer hat Verbindung getrennt

                reconnects++
                AppLog.i(TAG, "Kamera-Segment-Ende → Reconnect #$reconnects (Callback + Kamera-Reconnect)")
                try { cameraSock?.close() } catch (_: Exception) {}

                // Segment-Boundary-Callback VOR Kamera-Reconnect:
                // RtspPipeline kann ExoPlayer jetzt neu starten, bevor die P-Frames
                // des neuen Segments mit frame_num=0 den Decoder erreichen.
                // Gibt der Callback true zurück → Session sofort beenden, neuer ExoPlayer
                // öffnet eine neue TCP-Verbindung → neues session() mit idrEverInjected=false.
                val abortForRecycle = onSegmentBoundary?.invoke() ?: false
                if (abortForRecycle) {
                    AppLog.i(TAG, "Segment-Boundary: Session-Abort für ExoPlayer-Recycle")
                    break
                }

                cameraSock = Socket(cameraHost, cameraPort)
                // Reconnect mit Video-Track-URL (nicht Audio!) — Bug #7 Fix
                if (!doCameraHandshake(cameraSock!!, videoTrackUrl)) {
                    AppLog.w(TAG, "Reconnect #$reconnects fehlgeschlagen — Stream Ende")
                    break
                }
                AppLog.i(TAG, "Reconnect #$reconnects OK → setze RTP-Relay fort (nextSeq=${seqState[0] and 0xFFFF})")
            }

        } catch (e: Exception) {
            AppLog.w(TAG, "Session Fehler: ${e.message}")
        } finally {
            try { clientSock.close() } catch (_: Exception) {}
            try { cameraSock?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Eigener RTSP-Handshake direkt mit der Kamera (ohne ExoPlayer-Beteiligung).
     * Wird für transparente Reconnects verwendet.
     */
    private fun doCameraHandshake(sock: Socket, trackUrl: String): Boolean {
        sock.soTimeout = 5000
        val `in` = sock.getInputStream()
        val out  = sock.getOutputStream()
        val streamUrl = if (cameraPort == 554)
            "rtsp://$cameraHost/live/tcp/ch1"
        else
            "rtsp://$cameraHost:$cameraPort/live/tcp/ch1"

        fun send(msg: String) { out.write(msg.toByteArray(Charsets.UTF_8)); out.flush() }

        send("OPTIONS $streamUrl RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint\r\n\r\n")
        readMsg(`in`) ?: return false

        send("DESCRIBE $streamUrl RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: TrefferPoint\r\nAccept: application/sdp\r\n\r\n")
        readMsg(`in`) ?: return false

        send("SETUP $trackUrl RTSP/1.0\r\nCSeq: 3\r\nUser-Agent: TrefferPoint\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
        val setupResp = readMsg(`in`) ?: return false
        val sid = Regex("Session:\\s*([^\r\n;]+)").find(setupResp)?.groupValues?.get(1) ?: return false

        send("PLAY $streamUrl RTSP/1.0\r\nCSeq: 4\r\nSession: $sid\r\nUser-Agent: TrefferPoint\r\n\r\n")
        val playResp = readMsg(`in`) ?: return false
        if (!playResp.contains("RTSP/1.0 200")) return false

        sock.soTimeout = 0
        return true
    }

    /** Liest genau eine RTSP-Nachricht (Header + Body per Content-Length). */
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

    /** Liest eine CRLF-terminierte Zeile Byte für Byte (kein Look-ahead). */
    private fun readCrlfLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\r'.code) {
                val n = input.read()
                if (n < 0 || n == '\n'.code) return sb.toString()
                sb.append(n.toChar())
            } else if (b == '\n'.code) {
                return sb.toString()
            } else {
                sb.append(b.toChar())
            }
        }
    }

    /**
     * Schreibt in der PLAY-Antwort alle Kamera-URLs auf Proxy-URLs um.
     *
     * Firmware-Bug 4: Die PLAY-Antwort enthält RTP-Info mit der Kamera-IP:
     *   RTP-Info: url=rtsp://192.168.0.1/live/tcp/ch1/video/track0;seq=256;rtptime=...
     * ExoPlayer versucht, diese URL mit der SETUP-URL (rtsp://127.0.0.1:15554/...) zu matchen.
     * Bei URL-Mismatch ignoriert ExoPlayer seq= und rtptime= → RTP-Receiver-Initialisierung
     * mit Standardwerten → alle Pakete werden als "zu alt" verworfen → BUFFERING hängt.
     */
    private fun patchPlayResponse(response: String): String {
        val cameraBase = if (cameraPort == 554) "rtsp://$cameraHost" else "rtsp://$cameraHost:$cameraPort"
        val proxyBase  = "rtsp://127.0.0.1:$proxyPort"
        val patched = response.replace(cameraBase, proxyBase)

        val rtpInfo = patched.lineSequence()
            .firstOrNull { it.startsWith("RTP-Info:", ignoreCase = true) }?.take(150) ?: ""
        AppLog.i(TAG, "PLAY-Antwort RTP-Info: ${rtpInfo.ifEmpty { "(kein RTP-Info Header)" }}")
        return patched
    }

    /** Patcht interleaved=X-Y in einer SETUP-Antwort auf den gewünschten Wert. */
    private fun patchSetupInterleaved(response: String, newInterleaved: String): String {
        return Regex("interleaved=\\d+-\\d+", RegexOption.IGNORE_CASE)
            .replace(response, "interleaved=$newInterleaved")
    }

    /** Entfernt alle "a=recvonly"-Zeilen aus dem SDP und aktualisiert Content-Length. */
    private fun patchSdp(response: String): String {
        val cut = response.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: return response
        val headers = response.substring(0, cut + 4)
        var sdp = response.substring(cut + 4)
        sdp = sdp.replace("\r\na=recvonly", "")
        sdp = sdp.replace("\na=recvonly", "")
        if (sdp.startsWith("a=recvonly")) sdp = sdp.replaceFirst(Regex("^a=recvonly\r?\n"), "")
        val newLen = sdp.toByteArray(Charsets.UTF_8).size
        val newHeaders = Regex("Content-Length:\\s*\\d+", RegexOption.IGNORE_CASE)
            .replace(headers, "Content-Length: $newLen")
        AppLog.i(TAG, "SDP gepatcht: a=recvonly entfernt, Content-Length=$newLen")
        AppLog.i(TAG, "Gepatchtes SDP:\n$sdp")
        return newHeaders + sdp
    }

    /**
     * Paketweises TCP-Interleaved-Relay.
     *
     * Liest RTP-Pakete von der Kamera paketweise ($ ch lenHi lenLo payload),
     * schreibt für gerade Kanäle (RTP-Daten) die Sequenznummer mit dem
     * gemeinsamen seqState-Zähler um, und leitet das Paket an ExoPlayer weiter.
     *
     * seqState[0]:
     *   -1  = noch nicht initialisiert (erste Paket der gesamten Session)
     *   ≥0  = nächste zu verwendende Ausgabe-Sequenznummer
     *
     * Durch Weitergabe von seqState über mehrere relay()-Aufrufe hinweg bleibt
     * der Zähler über Kamera-Reconnects monoton — ExoPlayer sieht keine Lücken.
     *
     * Gibt true zurück wenn die Kamera die Verbindung getrennt hat,
     * false wenn ExoPlayer (der Client) getrennt hat.
     */
    private fun relay(
        cIn: InputStream, cOut: OutputStream,
        camIn: InputStream, camOut: OutputStream,
        seqState: IntArray,
        tsState: LongArray
    ): Boolean {
        var cameraClosedFirst = false
        var firstChunkLogged = false
        var totalBytes = 0L
        val cOutLock = Any()
        val mBitFixed = mutableSetOf<Int>()  // NAL-Typen, für die M-bit bereits geloggt wurde
        var spsPpsDropped = 0                // Anzahl verworfener SPS/PPS-Pakete (nach erstem IDR)
        // Timestamp-Continuity: berechne Offset beim ersten Paket dieses Segments.
        // Innerhalb eines relay()-Calls bleibt der Offset konstant → relative Frame-
        // Abstände (90kHz-Ticks) der Kamera bleiben erhalten, nur die Basis verschiebt
        // sich so dass der Stream nahtlos an das vorherige Segment anschlieszt.
        var tsOffsetCalculated = false
        // Frame-Spacing für nahtlose Fortsetzung: 3000 Ticks @ 90kHz = 33ms ≈ 30fps
        val tsFrameInterval = 3000L

        // Diagnose: NAL-Typ-Verteilung + M-bit-Statistik.
        // nalCounts[t] = (total, with M=1 vor Fix, with FU-A end-bit) für NAL-Typ t.
        // Für FU-A (Typ 28) wird der ORIGINAL-Typ aus FU-Header[17]&0x1F gezählt, nicht 28.
        val nalTotal = IntArray(32)
        val nalWithM = IntArray(32)
        val fuaEndBits = IntArray(32)  // FU-A Pakete mit E-Bit gesetzt, indexiert nach FU-Typ
        var spsToIdrRelabeled = 0  // Firmware-Bug #9: FU-A(SPS→IDR) Zähler

        // Gepufferter Lese-Stream (einzelne Byte-Leseanfragen ohne OS-Overhead)
        val cam = BufferedInputStream(camIn, 65536)

        // Einmal allokierter Sende-Puffer: 4 Byte TCP-Interleaved-Header + max. 64 KB Payload
        val pktBuf = ByteArray(4 + 65536)

        // Richtung ExoPlayer→Kamera (RTCP + abgefangene Keepalives)
        val t = thread(name = "proxy-c2cam") {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = cIn.read(buf).also { if (it <= 0) return@thread }
                    if (buf[0] == 0x24.toByte()) {
                        camOut.write(buf, 0, n)
                        camOut.flush()
                    } else {
                        val msg = String(buf, 0, n, Charsets.UTF_8)
                        val cseq = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(msg)?.groupValues?.get(1) ?: "0"
                        AppLog.i(TAG, "Keepalive abgefangen: ${msg.substringBefore(" ").take(20)} CSeq=$cseq")
                        val resp = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n".toByteArray()
                        synchronized(cOutLock) { cOut.write(resp); cOut.flush() }
                    }
                }
            } catch (_: Exception) {}
        }

        // Richtung Kamera→ExoPlayer (paketweises Lesen + Seq-Rewrite)
        try {
            while (true) {
                // TCP-Interleaved Sync-Byte
                val dollar = cam.read()
                if (dollar < 0) { cameraClosedFirst = true; break }
                if (dollar != 0x24) {
                    AppLog.w(TAG, "TCP-Interleaved Sync: 0x%02X statt 0x24".format(dollar))
                    continue
                }

                val ch    = cam.read(); if (ch    < 0) { cameraClosedFirst = true; break }
                val lenHi = cam.read(); if (lenHi < 0) { cameraClosedFirst = true; break }
                val lenLo = cam.read(); if (lenLo < 0) { cameraClosedFirst = true; break }
                val len = (lenHi shl 8) or lenLo
                if (len <= 0 || len > 65536) {
                    AppLog.w(TAG, "Ungültige RTP-Paketgröße: $len — überspringe")
                    continue
                }

                // Payload in pktBuf ab Offset 4 einlesen (0..3 = TCP-Header)
                pktBuf[0] = 0x24.toByte(); pktBuf[1] = ch.toByte()
                pktBuf[2] = lenHi.toByte(); pktBuf[3] = lenLo.toByte()
                var read = 0
                while (read < len) {
                    val n = cam.read(pktBuf, 4 + read, len - read)
                    if (n < 0) { cameraClosedFirst = true; break }
                    read += n
                }
                if (read < len) { cameraClosedFirst = true; break }
                totalBytes += 4 + len

                // Audio-Paket-Erkennung: Kamera sendet Video (PT=96) und Audio (PT=97) beide
                // auf Interleaved-Channel 0 (Firmware-Bug #7b). Wir biegen Audio-Pakete auf
                // ch=2 um (was ExoPlayer durch die gepatchte SETUP-Antwort erwartet), damit
                // Audio und Video sauber getrennt sind und keine Seq/IDR-Verarbeitung für
                // Audio-Pakete stattfindet.
                // RTP-Byte 1 (= pktBuf[5]): Bit 7 = M-Flag, Bits 6-0 = Payload-Type
                val isAudioPacket = ch == 0 && len >= 2 && (pktBuf[5].toInt() and 0x7F) != 96
                if (isAudioPacket) {
                    pktBuf[1] = 2.toByte()  // Channel 0 → 2 für Audio
                }

                // Firmware-Bug #9: SGK GK720X sendet IDR-Frames als FU-A(type=7=SPS).
                // Die echten SPS/PPS kommen ausschließlich über sprop-parameter-sets im SDP —
                // im RTP-Stream gibt es keine echten SPS-Pakete (verifiziert: echte SPS sind
                // ~20 Byte, die Kamera sendet FU-A(type=7) mit ~14 KB = IDR-Größe).
                // Fix: FU-A(type=7) → FU-A(type=5=IDR) BEVOR SPS/PPS-Dedup läuft,
                // sonst werden IDR-Frames als SPS verworfen und ExoPlayer bekommt kein IDR.
                if (!isAudioPacket && ch % 2 == 0 && len >= 14) {
                    val ntCheck = pktBuf[16].toInt() and 0x1F
                    if (ntCheck == 28 && (pktBuf[17].toInt() and 0x1F) == 7) {
                        // FU-A: type=7 (SPS) → type=5 (IDR), NRI → 11 (höchste Priorität)
                        pktBuf[17] = ((pktBuf[17].toInt() and 0xE0) or 5).toByte()
                        pktBuf[16] = ((pktBuf[16].toInt() and 0x9F) or 0x60).toByte()
                        spsToIdrRelabeled++
                        if (spsToIdrRelabeled == 1)
                            AppLog.i(TAG, "Bug #9: FU-A(SPS→IDR) — SGK sendet IDR als SPS, korrigiere")

                        // IDR-Segment-Grenze: proaktiver Disconnect (verhindert DECODING_FAILED)
                        // Beim ersten IDR-Paket eines Folge-Segments (idrEverInjected=true):
                        // Loop beenden ohne cameraClosedFirst=true → session() sieht Client-Disconnect
                        // → ExoPlayer recycelt in 200ms statt auf DECODING_FAILED nach ~7s zu warten.
                        // cameraClosedFirst bleibt false (initialer Wert) → session() bricht ab.
                        if (spsToIdrRelabeled == 1 && idrEverInjected) {
                            AppLog.i(TAG, "IDR-Segment-Grenze: proaktiver Proxy-Disconnect → ExoPlayer-Recycle")
                            break
                        }
                    }
                }

                // SPS/PPS-Dedup (Firmware-Bug #8): Kamera resendet SPS+PPS vor jedem
                // Segment. ExoPlayer's RtpH264Reader interpretiert das als Format-Change
                // und versucht den Decoder neu zu konfigurieren → IllegalStateException
                // beim native_dequeueInputBuffer → ERROR_CODE_DECODING_FAILED nach ~2s
                // im 2. Segment. Fix: Sobald idrEverInjected=true (= Decoder hat einen
                // sauberen IDR bekommen), alle weiteren SPS-/PPS-Pakete verwerfen.
                // Nach Bug-#9-Fix gibt es keine echten SPS im RTP-Stream → Dedup ist No-Op,
                // aber bleibt für Robustheit (echte SPS-Updates bei Auflösungswechsel).
                if (!isAudioPacket && idrEverInjected && ch % 2 == 0 && len >= 13) {
                    val nt = pktBuf[16].toInt() and 0x1F
                    val isSpsOrPps = when (nt) {
                        7, 8 -> true  // Single-NAL SPS oder PPS
                        28   -> len >= 14 && ((pktBuf[17].toInt() and 0x1F).let { it == 7 || it == 8 })
                        else -> false
                    }
                    if (isSpsOrPps) {
                        if (spsPpsDropped == 0) {
                            AppLog.i(TAG, "SPS/PPS-Dedup aktiv: verwerfe Parameter-Sets nach erstem IDR")
                        }
                        spsPpsDropped++
                        continue
                    }
                }

                // Video-spezifische Verarbeitung: Seq-Rewrite, NAL-Stats, IDR-Hack, M-Bit-Fix.
                // Wird für Audio-Pakete (bereits auf ch=2 umgebogen) übersprungen.
                if (!isAudioPacket) {
                    // RTP-Sequenznummern (gerade Kanäle = Daten, nicht RTCP) kontinuierlich halten.
                    // RTP-Seq liegt bei Offset 2..3 des RTP-Packets, d.h. pktBuf[6..7].
                    if (ch % 2 == 0 && len >= 4) {
                        if (seqState[0] < 0) {
                            // Erste Initialisierung: Kamera-Startseq übernehmen (typisch 256)
                            val camSeq = ((pktBuf[6].toInt() and 0xFF) shl 8) or (pktBuf[7].toInt() and 0xFF)
                            seqState[0] = camSeq
                        }
                        val outSeq = seqState[0] and 0xFFFF
                        pktBuf[6] = (outSeq ushr 8).toByte()
                        pktBuf[7] = (outSeq and 0xFF).toByte()
                        seqState[0]++
                    }

                    // RTP-Timestamps (Bytes 4..7 des RTP-Packets, d.h. pktBuf[8..11])
                    // umschreiben so dass sie nahtlos an das vorherige Segment anschlieszen.
                    // Beim ersten Paket eines neuen relay()-Calls den Offset einmalig
                    // berechnen, dann konstant lassen (so bleiben Frame-Intervalle erhalten).
                    if (ch % 2 == 0 && len >= 8) {
                        val tsCam = ((pktBuf[8].toLong() and 0xFF) shl 24) or
                                    ((pktBuf[9].toLong() and 0xFF) shl 16) or
                                    ((pktBuf[10].toLong() and 0xFF) shl 8) or
                                     (pktBuf[11].toLong() and 0xFF)
                        if (!tsOffsetCalculated) {
                            tsOffsetCalculated = true
                            if (tsState[1] < 0) {
                                // Erste Session-Initialisierung: kein Offset.
                                tsState[0] = 0L
                            } else {
                                // Reconnect: nahtlose Fortsetzung mit einem Frame-Tick-Abstand.
                                // tsOut = lastTsOut + tsFrameInterval
                                // tsOut = tsCam + tsState[0] → tsState[0] = lastTsOut + interval - tsCam
                                tsState[0] = tsState[1] + tsFrameInterval - tsCam
                                AppLog.i(TAG, "TS-Continuity: camTS=0x%08X offset=%d → outTS=0x%08X"
                                    .format(tsCam, tsState[0], (tsCam + tsState[0]) and 0xFFFFFFFFL))
                            }
                        }
                        val tsOut = (tsCam + tsState[0]) and 0xFFFFFFFFL
                        pktBuf[8]  = (tsOut ushr 24).toByte()
                        pktBuf[9]  = (tsOut ushr 16).toByte()
                        pktBuf[10] = (tsOut ushr 8).toByte()
                        pktBuf[11] = (tsOut and 0xFF).toByte()
                        tsState[1] = tsOut  // immer letzten ausgegebenen TS speichern
                    }

                    // Diagnose-Zählung (nur RTP-Daten, nicht RTCP)
                    if (ch % 2 == 0 && len >= 13) {
                        val nt = pktBuf[16].toInt() and 0x1F
                        val mSet = (pktBuf[5].toInt() and 0x80) != 0
                        if (nt == 28 && len >= 14) {
                            val fuOrig = pktBuf[17].toInt() and 0x1F
                            val fuEnd = (pktBuf[17].toInt() and 0x40) != 0
                            if (fuOrig in 0..31) {
                                nalTotal[fuOrig]++
                                if (mSet) nalWithM[fuOrig]++
                                if (fuEnd) fuaEndBits[fuOrig]++
                            }
                        } else if (nt in 0..31) {
                            nalTotal[nt]++
                            if (mSet) nalWithM[nt]++
                        }
                    }

                    // IDR-Erkennung: Ersten echten IDR-Frame der Kamera erkennen →
                    // SPS/PPS-Dedup für Folge-Segmente aktivieren.
                    // Die Kamera schickt SPS+PPS+IDR am Anfang jedes ~5s-Segments.
                    // Nach dem ersten weitergeleiteten IDR können folgende SPS/PPS-Pakete
                    // verworfen werden, da ExoPlayer sonst einen Decoder-Reset versucht
                    // → IllegalStateException → DECODING_FAILED.
                    if (!isAudioPacket && !idrEverInjected && ch % 2 == 0 && len >= 13) {
                        val nt = pktBuf[16].toInt() and 0x1F
                        // Single-NAL IDR (type=5) oder FU-A-Start-Fragment eines IDR
                        val isRealIdr = nt == 5 ||
                            (nt == 28 && len >= 14 &&
                             (pktBuf[17].toInt() and 0x1F) == 5 &&
                             (pktBuf[17].toInt() and 0x80) != 0)  // FU-A Start-Bit
                        if (isRealIdr) {
                            idrEverInjected = true
                            AppLog.i(TAG, "Erster IDR-Frame erkannt → SPS/PPS-Dedup ab jetzt aktiv")
                        }
                    }

                    // M-bit Fix (Firmware-Bug #5): SGK setzt M=0 auf allen Paketen.
                    // ExoPlayer's RtpH264Reader emittiert Samples NUR wenn M=1 (letztes Paket
                    // eines Access Units) ODER FU-A End-Bit gesetzt ist. Ohne M=1 bleibt der
                    // Sample-Buffer leer → ExoPlayer hängt ewig in BUFFERING.
                    // Fix: M=1 setzen für NAL-Typen, die einen kompletten Frame signalisieren.
                    if (ch % 2 == 0 && len >= 13) {
                        val nalType = pktBuf[16].toInt() and 0x1F
                        val mAlreadySet = (pktBuf[5].toInt() and 0x80) != 0
                        if (!mAlreadySet) {
                            val shouldFix = when (nalType) {
                                1, 5 -> true  // Non-IDR / IDR als Single-NAL-Paket = vollständiger Frame
                                28   -> len >= 14 && (pktBuf[17].toInt() and 0x40) != 0  // FU-A End-Bit
                                else -> false
                            }
                            if (shouldFix) {
                                pktBuf[5] = (pktBuf[5].toInt() or 0x80).toByte()
                                if (mBitFixed.add(nalType)) {
                                    AppLog.i(TAG, "M-bit Fix: NAL=$nalType → M=1 (Firmware-Bug #5)")
                                }
                            }
                        }
                    }

                    if (!firstChunkLogged) {
                        val hex = (0 until minOf(4 + len, 16))
                            .joinToString(" ") { "%02X".format(pktBuf[it].toInt() and 0xFF) }
                        // NAL-Typ aus erstem Byte nach 12-Byte RTP-Header (pktBuf[4+12] = pktBuf[16])
                        val nalDesc = if (ch % 2 == 0 && len >= 13) {
                            val nalByte = pktBuf[16].toInt() and 0xFF
                            val nalType = nalByte and 0x1F
                            when (nalType) {
                                1  -> "NAL=NonIDR(P/B)"
                                5  -> "NAL=IDR(Keyframe)"
                                7  -> "NAL=SPS"
                                8  -> "NAL=PPS"
                                28 -> if (len >= 14) {
                                    val fu = pktBuf[17].toInt() and 0xFF
                                    "NAL=FU-A(start=${(fu and 0x80)!=0},type=${fu and 0x1F}${if((fu and 0x1F)==5)"/IDR" else ""})"
                                } else "NAL=FU-A"
                                else -> "NAL=type$nalType"
                            }
                        } else ""
                        AppLog.i(TAG, "Erster RTP-Chunk: ${4 + len}B → $hex ${nalDesc}".trim())
                        firstChunkLogged = true
                    }
                } // end !isAudioPacket

                synchronized(cOutLock) { cOut.write(pktBuf, 0, 4 + len); cOut.flush() }
            }
        } catch (e: Exception) {
            // "Broken pipe" / "EPIPE" → ExoPlayer hat die Verbindung getrennt (Client-seitig).
            // cameraClosedFirst=false → session() beendet die Reconnect-Schleife sofort.
            // Andere Exceptions (Connection reset, timeout) → kameraseitig → cameraClosedFirst=true.
            val msg = e.message ?: ""
            cameraClosedFirst = !msg.contains("Broken pipe", ignoreCase = true) &&
                                !msg.contains("EPIPE",        ignoreCase = true)
            AppLog.w(TAG, "RTP-Relay Kamera→Client: ${e.javaClass.simpleName}: ${msg.take(60)} → cameraFirst=$cameraClosedFirst")
        }

        AppLog.i(TAG, "RTP-Relay: ${totalBytes}B von Kamera | cameraFirst=$cameraClosedFirst" +
                if (spsPpsDropped > 0) " | SPS/PPS-Dedup=$spsPpsDropped" else "")

        // NAL-Statistik dumpen (nur Typen die tatsächlich auftraten)
        val stats = (0 until 32)
            .filter { nalTotal[it] > 0 }
            .joinToString(" ") { t ->
                val desc = when (t) { 1->"P"; 5->"IDR"; 6->"SEI"; 7->"SPS"; 8->"PPS"; 9->"AUD"; else->"t$t" }
                "$desc=${nalTotal[t]}(M=${nalWithM[t]},E=${fuaEndBits[t]})"
            }
        if (stats.isNotEmpty()) AppLog.i(TAG, "NAL-Stats: $stats")
        if (spsToIdrRelabeled > 0) AppLog.i(TAG, "Bug #9: $spsToIdrRelabeled FU-A(SPS→IDR) Pakete umgelabelt")

        t.join(500)
        return cameraClosedFirst
    }
}
