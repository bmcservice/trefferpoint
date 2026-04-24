package de.bmcservice.trefferpoint

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimaler RTSP-Proxy der die DESCRIBE-Antwort der SGK/Viidure-Kamera patcht.
 *
 * Bug in der Kamera-Firmware: beide Tracks im SDP haben "a=recvonly" statt "a=sendonly".
 * RFC-konformes Verhalten von ExoPlayer: "recvonly" = Server empfängt nur → ExoPlayer
 * erwartet keine eingehenden RTP-Pakete → BUFFERING für immer, frameCount = 0.
 *
 * Lösung: Proxy entfernt "a=recvonly" vor der Weiterleitung an ExoPlayer.
 * Danach ist die Richtung implizit "sendrecv" (Standard wenn kein Attribut vorhanden).
 */
class RtspSdpProxy(
    private val cameraHost: String,
    private val cameraPort: Int = 554,
    private val proxyPort: Int = 15554
) {
    companion object { private const val TAG = "RtspSdpProxy" }

    @Volatile private var active = false
    private var serverSock: ServerSocket? = null

    /** Startet den Proxy und liefert die lokale RTSP-URL zurück. */
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
        try {
            cameraSock = Socket(cameraHost, cameraPort)
            clientSock.soTimeout = 15000
            cameraSock.soTimeout = 15000
            val cIn  = clientSock.getInputStream()
            val cOut = clientSock.getOutputStream()
            val camIn  = cameraSock.getInputStream()
            val camOut = cameraSock.getOutputStream()

            while (true) {
                val req = readMsg(cIn) ?: break
                val method = req.substringBefore(" ")

                // URL in Anfrage-Zeile umschreiben: 127.0.0.1:proxyPort → cameraHost
                var camReq = req.replaceFirst(
                    "rtsp://127.0.0.1:$proxyPort",
                    if (cameraPort == 554) "rtsp://$cameraHost"
                    else "rtsp://$cameraHost:$cameraPort"
                )

                // SETUP: Transport explizit auf TCP-Interleaved forcieren —
                // einige Kameras ignorieren den Transport-Wunsch des Clients und
                // antworten mit UDP, obwohl TCP angefordert wurde.
                if (method == "SETUP") {
                    camReq = Regex("Transport:[^\r\n]*", RegexOption.IGNORE_CASE)
                        .replace(camReq, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
                    AppLog.i(TAG, "SETUP → Kamera (TCP-Interleaved erzwungen)")
                }

                camOut.write(camReq.toByteArray(Charsets.UTF_8))
                camOut.flush()

                val resp = readMsg(camIn) ?: break

                // Vollständige Kamera-Antwort auf SETUP und PLAY loggen
                if (method == "SETUP" || method == "PLAY") {
                    val statusLine = resp.substringBefore("\r\n").take(60)
                    val transport = resp.lineSequence()
                        .firstOrNull { it.startsWith("Transport:", ignoreCase = true) }
                        ?.take(120) ?: "(kein Transport-Header)"
                    AppLog.i(TAG, "Kamera→$method: $statusLine | $transport")
                }

                // DESCRIBE-Antwort: a=recvonly aus SDP entfernen
                val fwd = if (method == "DESCRIBE") patchSdp(resp) else resp
                cOut.write(fwd.toByteArray(Charsets.UTF_8))
                cOut.flush()

                // Nach PLAY 200 OK: transparentes Byte-Relay (RTP-Interleaved)
                if (method == "PLAY" && resp.contains("RTSP/1.0 200")) {
                    clientSock.soTimeout = 0
                    cameraSock.soTimeout = 0
                    AppLog.i(TAG, "PLAY OK — wechsle in RTP-Relay-Modus")
                    relay(cIn, cOut, camIn, camOut)
                    break
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Session Fehler: ${e.message}")
        } finally {
            try { clientSock.close() } catch (_: Exception) {}
            try { cameraSock?.close() } catch (_: Exception) {}
        }
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

    /** Transparentes bidirektionales Byte-Relay für die RTP-Streaming-Phase. */
    private fun relay(cIn: InputStream, cOut: OutputStream, camIn: InputStream, camOut: OutputStream) {
        // Richtung ExoPlayer→Kamera (RTCP + Keepalives)
        // ExoPlayer sendet nach ~5s einen OPTIONS-Keepalive über den Relay.
        // Die SGK-Kamera versteht keine RTSP-Nachrichten während sie streamt → TCP RST.
        // Lösung: '$'-Frames (TCP-Interleaved RTCP) normal weiterleiten,
        //         RTSP-Textnachrichten (OPTIONS/GET_PARAMETER) lokal beantworten.
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
                        // RTSP-Keepalive (OPTIONS / GET_PARAMETER) → lokal beantworten
                        val msg = String(buf, 0, n, Charsets.UTF_8)
                        val cseq = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(msg)?.groupValues?.get(1) ?: "0"
                        val method = msg.substringBefore(" ").take(20)
                        AppLog.i(TAG, "Keepalive abgefangen: $method CSeq=$cseq — lokal beantwortet")
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
                    break
                }
                if (n <= 0) { AppLog.i(TAG, "cam→client: Kamera EOF (n=$n)"); break }

                if (totalBytes == 0L) {
                    // TCP-Interleaved: '$' [ch] [len-hi] [len-lo] [payload...]
                    val hex = buf.take(minOf(n, 12))
                        .joinToString(" ") { "%02X".format(it.toInt().and(0xFF)) }
                    AppLog.i(TAG, "Erster RTP-Chunk: ${n}B → $hex")
                }
                totalBytes += n

                try {
                    cOut.write(buf, 0, n)
                    cOut.flush()
                } catch (e: Exception) {
                    AppLog.w(TAG, "cOut.write Ende: ${e.javaClass.simpleName}: ${e.message?.take(60)} — ExoPlayer hat Verbindung getrennt")
                    break
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "RTP-Relay outer Ende: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
        }
        AppLog.i(TAG, "RTP-Relay beendet: ${totalBytes}B gesamt von Kamera empfangen")
        t.join(500)
    }
}
