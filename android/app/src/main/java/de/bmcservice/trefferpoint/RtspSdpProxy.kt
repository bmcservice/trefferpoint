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

    fun start(): String {
        serverSock = ServerSocket(proxyPort)
        active = true
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
        var cameraSock: Socket? = null
        var trackUrl = if (cameraPort == 554)
            "rtsp://$cameraHost/live/tcp/ch1/video/track0"
        else
            "rtsp://$cameraHost:$cameraPort/live/tcp/ch1/video/track0"

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
                    if (setupUrl.startsWith("rtsp://")) trackUrl = setupUrl
                    camReq = Regex("Transport:[^\r\n]*", RegexOption.IGNORE_CASE)
                        .replace(camReq, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
                    AppLog.i(TAG, "SETUP → Kamera (TCP-Interleaved erzwungen)")
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
                    else       -> resp
                }
                cOut.write(fwd.toByteArray(Charsets.UTF_8))
                cOut.flush()

                if (method == "PLAY" && resp.contains("RTSP/1.0 200")) {
                    clientSock.soTimeout = 0
                    cameraSock!!.soTimeout = 0
                    AppLog.i(TAG, "PLAY OK — starte RTP-Relay (trackUrl=$trackUrl)")
                    break@loop
                }
            }

            // RTP-Relay-Loop: seqState hält den nächsten zu sendenden Sequenz-Counter.
            // Durch Weitergabe über alle relay()-Aufrufe bleiben Sequenznummern
            // monoton, auch wenn die Kamera bei jedem Segment von vorne beginnt.
            // seqState[0] = -1 bedeutet "noch nicht initialisiert".
            val seqState = IntArray(1) { -1 }
            var reconnects = 0
            while (true) {
                val cameraClosedFirst = relay(
                    cIn, cOut,
                    cameraSock!!.getInputStream(),
                    cameraSock!!.getOutputStream(),
                    seqState
                )
                if (!cameraClosedFirst) break  // ExoPlayer hat Verbindung getrennt

                reconnects++
                AppLog.i(TAG, "Kamera-Segment-Ende → transparenter Reconnect #$reconnects")
                try { cameraSock?.close() } catch (_: Exception) {}

                cameraSock = Socket(cameraHost, cameraPort)
                if (!doCameraHandshake(cameraSock!!, trackUrl)) {
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
        seqState: IntArray
    ): Boolean {
        var cameraClosedFirst = false
        var firstChunkLogged = false
        var totalBytes = 0L
        val cOutLock = Any()
        val mBitFixed = mutableSetOf<Int>()  // NAL-Typen, für die M-bit bereits geloggt wurde

        // Diagnose: NAL-Typ-Verteilung + M-bit-Statistik.
        // nalCounts[t] = (total, with M=1 vor Fix, with FU-A end-bit) für NAL-Typ t.
        // Für FU-A (Typ 28) wird der ORIGINAL-Typ aus FU-Header[17]&0x1F gezählt, nicht 28.
        val nalTotal = IntArray(32)
        val nalWithM = IntArray(32)
        val fuaEndBits = IntArray(32)  // FU-A Pakete mit E-Bit gesetzt, indexiert nach FU-Typ

        // IDR-Hack: Wireshark-Capture der Viidure-App hat gezeigt: SGK-Cams senden NIE
        // einen IDR-Keyframe (NAL=5), nur P-Frames (NAL=1) und SPS (NAL=7). Viidure
        // dekodiert mit FFmpeg (toleriert das), unser ExoPlayer/MediaCodec verlangt
        // strikt einen IDR und hängt sonst ewig in BUFFERING.
        //
        // Workaround: Wir labeln ALLE FU-A-Fragmente mit Original-Typ=1 (P) auf 5 (IDR)
        // um. Der Decoder sieht dann jedes P-Frame als Sync-Sample und beginnt sofort
        // zu dekodieren. Das erste Bild ist evtl. grünes Rauschen (P-Frame braucht
        // einen Vorgänger den's nicht gibt), aber spätestens nach 1-2 Frames konvergiert
        // das Bild auf den korrekten Inhalt — gleicher Mechanismus wie bei FFmpeg.
        var idrRelabeled = 0

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

                // IDR-Hack: P-Frame (NAL=1) → IDR (NAL=5) umlabeln.
                // FU-A Fragmente (Typ 28): unteren 5 Bits des FU-Headers[17] umsetzen.
                // Single-NAL Pakete (Typ 1 direkt in pktBuf[16]): die unteren 5 Bits dort.
                // Die NRI-Bits (Bits 5-6) lassen wir unverändert — die signalisieren
                // nur Importance, nicht den Frame-Typ.
                if (ch % 2 == 0 && len >= 13) {
                    val nt = pktBuf[16].toInt() and 0x1F
                    if (nt == 28 && len >= 14) {
                        val fuOrig = pktBuf[17].toInt() and 0x1F
                        if (fuOrig == 1) {
                            // FU-Header: S(1) | E(1) | R(1) | Type(5) — Typ-Bits auf 5
                            pktBuf[17] = ((pktBuf[17].toInt() and 0xE0) or 5).toByte()
                            // FU-Indicator (pktBuf[16]) NRI-Bits auf 11 (höchste Importance, wie IDR)
                            pktBuf[16] = ((pktBuf[16].toInt() and 0x9F) or 0x60).toByte()
                            idrRelabeled++
                        }
                    } else if (nt == 1) {
                        // Single-NAL P-Frame → als IDR umlabeln
                        pktBuf[16] = ((pktBuf[16].toInt() and 0x80) or 0x65).toByte()
                        // = F(0)|NRI(11)|Type(5) bei NRI 11
                        idrRelabeled++
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

                synchronized(cOutLock) { cOut.write(pktBuf, 0, 4 + len); cOut.flush() }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "RTP-Relay Kamera→Client: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            cameraClosedFirst = true
        }

        AppLog.i(TAG, "RTP-Relay: ${totalBytes}B von Kamera | cameraFirst=$cameraClosedFirst")

        // NAL-Statistik dumpen (nur Typen die tatsächlich auftraten)
        val stats = (0 until 32)
            .filter { nalTotal[it] > 0 }
            .joinToString(" ") { t ->
                val desc = when (t) { 1->"P"; 5->"IDR"; 6->"SEI"; 7->"SPS"; 8->"PPS"; 9->"AUD"; else->"t$t" }
                "$desc=${nalTotal[t]}(M=${nalWithM[t]},E=${fuaEndBits[t]})"
            }
        if (stats.isNotEmpty()) AppLog.i(TAG, "NAL-Stats: $stats")
        if (idrRelabeled > 0) AppLog.i(TAG, "IDR-Relabel: $idrRelabeled P-Frame-Pakete als IDR markiert")

        t.join(500)
        return cameraClosedFirst
    }
}
