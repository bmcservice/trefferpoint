package de.bmcservice.trefferpoint

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
        // Track-URL aus dem ersten SETUP merken — für Reconnects benötigt
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
                    // Track-URL aus dem (umgeschriebenen) SETUP extrahieren
                    val setupUrl = camReq.substringAfter("SETUP ").substringBefore(" RTSP")
                    if (setupUrl.startsWith("rtsp://")) trackUrl = setupUrl
                    // TCP-Interleaved erzwingen
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

                val fwd = if (method == "DESCRIBE") patchSdp(resp) else resp
                cOut.write(fwd.toByteArray(Charsets.UTF_8))
                cOut.flush()

                if (method == "PLAY" && resp.contains("RTSP/1.0 200")) {
                    clientSock.soTimeout = 0
                    cameraSock!!.soTimeout = 0
                    AppLog.i(TAG, "PLAY OK — starte RTP-Relay (trackUrl=$trackUrl)")
                    break@loop
                }
            }

            // RTP-Relay-Loop mit transparentem Kamera-Reconnect
            var reconnects = 0
            while (true) {
                val cameraClosedFirst = relay(
                    cIn, cOut,
                    cameraSock!!.getInputStream(),
                    cameraSock!!.getOutputStream()
                )
                if (!cameraClosedFirst) break  // ExoPlayer hat Verbindung getrennt

                // Kamera hat Segment beendet → sofort neue RTSP-Session aufbauen
                reconnects++
                AppLog.i(TAG, "Kamera-Segment-Ende → transparenter Reconnect #$reconnects")
                try { cameraSock?.close() } catch (_: Exception) {}

                cameraSock = Socket(cameraHost, cameraPort)
                if (!doCameraHandshake(cameraSock!!, trackUrl)) {
                    AppLog.w(TAG, "Reconnect #$reconnects fehlgeschlagen — Stream Ende")
                    break
                }
                AppLog.i(TAG, "Reconnect #$reconnects OK → setze RTP-Relay fort")
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
     * Transparentes bidirektionales Byte-Relay für die RTP-Streaming-Phase.
     * Gibt true zurück wenn die Kamera die Verbindung getrennt hat,
     * false wenn ExoPlayer (der Client) getrennt hat.
     *
     * RTSP-Keepalives (OPTIONS/GET_PARAMETER) von ExoPlayer werden abgefangen
     * und lokal beantwortet — die Kamera sieht diese Nachrichten nie.
     */
    private fun relay(
        cIn: InputStream, cOut: OutputStream,
        camIn: InputStream, camOut: OutputStream
    ): Boolean {
        var cameraClosedFirst = false

        // Richtung ExoPlayer→Kamera (RTCP + abgefangene Keepalives)
        val t = thread(name = "proxy-c2cam") {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = try { cIn.read(buf) } catch (e: Exception) {
                        AppLog.w(TAG, "proxy-c2cam cIn: ${e.javaClass.simpleName}: ${e.message?.take(50)}")
                        break
                    }
                    if (n <= 0) { AppLog.i(TAG, "proxy-c2cam: ExoPlayer EOF"); break }

                    if (buf[0] == 0x24.toByte()) {
                        // TCP-Interleaved RTCP → an Kamera weiterleiten
                        camOut.write(buf, 0, n)
                        camOut.flush()
                    } else {
                        // RTSP-Keepalive (OPTIONS/GET_PARAMETER) → lokal beantworten
                        val msg = String(buf, 0, n, Charsets.UTF_8)
                        val cseq = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(msg)?.groupValues?.get(1) ?: "0"
                        val method = msg.substringBefore(" ").take(20)
                        AppLog.i(TAG, "Keepalive abgefangen: $method CSeq=$cseq")
                        cOut.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n".toByteArray())
                        cOut.flush()
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "proxy-c2cam Ende: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            }
        }

        // Richtung Kamera→ExoPlayer (RTP-Daten)
        var totalBytes = 0L
        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = try {
                    camIn.read(buf)
                } catch (e: Exception) {
                    AppLog.w(TAG, "camIn.read Ende: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                    cameraClosedFirst = true
                    break
                }
                if (n <= 0) { AppLog.i(TAG, "cam→client: EOF (n=$n)"); cameraClosedFirst = true; break }

                if (totalBytes == 0L) {
                    val hex = buf.take(minOf(n, 16))
                        .joinToString(" ") { "%02X".format(it.toInt().and(0xFF)) }
                    AppLog.i(TAG, "Erster RTP-Chunk: ${n}B → $hex")
                }
                totalBytes += n

                try {
                    cOut.write(buf, 0, n)
                    cOut.flush()
                } catch (e: Exception) {
                    AppLog.w(TAG, "cOut.write Ende: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                    break
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "RTP-Relay outer: ${e.javaClass.simpleName}")
        }
        AppLog.i(TAG, "RTP-Relay: ${totalBytes}B von Kamera | cameraFirst=$cameraClosedFirst")
        t.join(500)
        return cameraClosedFirst
    }
}
