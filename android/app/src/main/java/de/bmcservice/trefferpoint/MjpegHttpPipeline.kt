package de.bmcservice.trefferpoint

import android.content.Context
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

/**
 * HTTP-MJPEG Pipeline für SGK GoPlus / Generalplus GK720X WLAN-Kameras.
 *
 * Hintergrund (gefunden per Reverse-Engineering der Viidure-App v3.3.1):
 *   Die Viidure-App nutzt NICHT die `/app/getmediainfo`-RTSP-URL auf Port 554
 *   (die liefert nie IDR-Frames → MediaCodec-Decode unmöglich), sondern stattdessen
 *   einen MJPEG-over-HTTP Stream auf Port 8080 (mjpg-streamer Format).
 *
 * URL-Schema: http://192.168.0.1:8080/?action=stream
 * Response: HTTP/1.0 200 OK, Content-Type: multipart/x-mixed-replace; boundary=...
 * Body: wiederholte Frames im Format
 *   --boundary\r\n
 *   Content-Type: image/jpeg\r\n
 *   Content-Length: NNN\r\n
 *   \r\n
 *   <JPEG-Daten NNN Bytes>\r\n
 *
 * Vorteile gegenüber RTSP:
 *   - Keine H.264/RTP/SDP-Komplexität
 *   - Keine IDR-/Keyframe-Probleme (jedes Frame ist vollständig)
 *   - Direkt JPEG → onJpegFrame Callback (wie bei UVC)
 *
 * Optional: HTTP-Heartbeat auf `http://host:8080/` parallel offen halten.
 */
class MjpegHttpPipeline(
    private val context: Context,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "MjpegHttpPipeline"
        private const val SOCK_TIMEOUT_MS = 8000
        private const val MAX_FRAME_BYTES = 5 * 1024 * 1024  // 5 MB Sicherheits-Limit pro Frame
    }

    @Volatile private var running = false
    @Volatile var frameCount: Long = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastFrameBytes: Int = 0; private set

    private var streamThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var streamSocket: Socket? = null

    fun start(url: String) {
        if (running) stop()
        running = true
        lastError = null
        frameCount = 0

        AppLog.i(TAG, "start: $url")
        onStatus("MJPEG: Verbinde zu $url")

        streamThread = thread(start = true, name = "mjpeg-stream") {
            runStream(url)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        AppLog.i(TAG, "stop")
        try { streamSocket?.close() } catch (_: Exception) {}
        streamSocket = null
        try { streamThread?.join(500) } catch (_: Exception) {}
        streamThread = null
        try { heartbeatThread?.interrupt() } catch (_: Exception) {}
        heartbeatThread = null
    }

    private fun runStream(url: String) {
        try {
            val uri = URI(url)
            val host = uri.host ?: return
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                       (uri.rawQuery?.let { "?$it" } ?: "")

            // Optionalen Heartbeat starten (manche SGK-Firmwares brauchen periodische
            // HTTP-Anfragen damit die Cam den Stream nicht abbricht)
            startHeartbeat(host, port)

            while (running) {
                try {
                    AppLog.i(TAG, "Verbinde zu $host:$port$path …")
                    onStatus("MJPEG: Connect $host:$port")
                    val sock = Socket()
                    sock.connect(InetSocketAddress(host, port), 4000)
                    sock.soTimeout = SOCK_TIMEOUT_MS
                    streamSocket = sock

                    sendHttpGet(sock.getOutputStream(), host, path)
                    val input = BufferedInputStream(sock.getInputStream(), 65536)

                    val (status, headers) = readHttpHeader(input)
                    AppLog.i(TAG, "HTTP $status, Content-Type: ${headers["content-type"] ?: "(none)"}")
                    if (!status.startsWith("HTTP/") || !status.contains(" 200")) {
                        AppLog.w(TAG, "HTTP-Fehler: $status — versuche Reconnect in 2s")
                        Thread.sleep(2000)
                        continue
                    }

                    val ct = headers["content-type"] ?: ""
                    val boundary = parseBoundary(ct)
                    if (boundary == null) {
                        AppLog.w(TAG, "Kein boundary in Content-Type: '$ct' — nicht MJPEG?")
                        Thread.sleep(2000)
                        continue
                    }
                    AppLog.i(TAG, "MJPEG-Boundary: '$boundary' — starte Frame-Loop")
                    onStatus("MJPEG-Stream aktiv")

                    readFrames(input, boundary)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Stream-Fehler: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    onStatus("MJPEG-Fehler: ${e.javaClass.simpleName}")
                } finally {
                    try { streamSocket?.close() } catch (_: Exception) {}
                    streamSocket = null
                }
                if (running) {
                    AppLog.i(TAG, "Reconnect in 1500ms")
                    try { Thread.sleep(1500) } catch (_: Exception) { return }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "runStream fatal", e)
            lastError = e.message
        }
    }

    private fun sendHttpGet(out: OutputStream, host: String, path: String) {
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.0\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: Lavf58.76.100\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(req.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Liest HTTP-Statuszeile + Header bis CRLF CRLF. */
    private fun readHttpHeader(input: InputStream): Pair<String, Map<String, String>> {
        val status = readLine(input) ?: return "" to emptyMap()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return status to headers
    }

    /** CRLF-terminierte Zeile lesen (LF allein wird ebenfalls akzeptiert). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val n = input.read()
                if (n < 0 || n == '\n'.code) return sb.toString()
                sb.append(n.toChar())
            } else if (b == '\n'.code) {
                return sb.toString()
            } else {
                sb.append(b.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }
    }

    /** Aus 'multipart/x-mixed-replace; boundary=foo' den Boundary-String ziehen. */
    private fun parseBoundary(contentType: String): String? {
        val parts = contentType.split(';')
        for (p in parts) {
            val tp = p.trim()
            if (tp.startsWith("boundary=", ignoreCase = true)) {
                var b = tp.substring(9).trim()
                if (b.startsWith("\"") && b.endsWith("\"") && b.length >= 2) {
                    b = b.substring(1, b.length - 1)
                }
                return b
            }
        }
        return null
    }

    /**
     * Liest fortlaufend MJPEG-Frames aus dem Multipart-Body.
     * Format pro Frame:
     *   [optional führendes \r\n]
     *   --boundary\r\n
     *   Content-Type: image/jpeg\r\n
     *   Content-Length: NNN\r\n
     *   \r\n
     *   <JPEG NNN bytes>
     *   \r\n
     */
    private fun readFrames(input: InputStream, boundary: String) {
        val boundaryMarker = "--$boundary"
        while (running) {
            // Skip bis Boundary-Zeile gefunden
            var foundBoundary = false
            for (attempt in 0 until 50) {
                val line = readLine(input) ?: return
                if (line.startsWith(boundaryMarker)) {
                    foundBoundary = true
                    break
                }
            }
            if (!foundBoundary) {
                AppLog.w(TAG, "Boundary nicht gefunden — Stream beenden")
                return
            }

            // Frame-Header lesen bis Leerzeile
            var contentLength = -1
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: -1
                    }
                }
            }

            if (contentLength <= 0 || contentLength > MAX_FRAME_BYTES) {
                AppLog.w(TAG, "Ungültige Content-Length: $contentLength — Stream-Ende")
                return
            }

            // JPEG-Bytes komplett lesen
            val jpeg = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(jpeg, read, contentLength - read)
                if (n < 0) {
                    AppLog.w(TAG, "EOF mitten im JPEG (read=$read/$contentLength)")
                    return
                }
                read += n
            }

            // Kurze Sanity-Check: JPEG beginnt mit FF D8, endet mit FF D9
            val isValidJpeg = jpeg.size >= 4 &&
                (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8 &&
                (jpeg[jpeg.size - 2].toInt() and 0xFF) == 0xFF && (jpeg[jpeg.size - 1].toInt() and 0xFF) == 0xD9
            if (!isValidJpeg) {
                AppLog.w(TAG, "Frame ${frameCount + 1} ist kein gültiges JPEG (size=${jpeg.size}) — überspringe")
            } else {
                frameCount++
                lastFrameBytes = jpeg.size
                if (frameCount == 1L) {
                    AppLog.i(TAG, "Erster MJPEG-Frame: ${jpeg.size}B")
                }
                onJpegFrame(jpeg)
            }

            // Trailing \r\n nach JPEG verwerfen (best effort, manche Server lassen es weg)
            try {
                input.mark(2)
                val a = input.read()
                if (a < 0) return
                if (a == '\r'.code) {
                    val b = input.read()
                    if (b < 0) return
                    if (b != '\n'.code) {
                        // war kein \r\n — wir sind schon mitten in der nächsten Boundary
                        // Nicht kritisch, readLine() oben überspringt sowieso bis Boundary
                    }
                } else {
                    input.reset()
                }
            } catch (_: Exception) {
                // Mark/reset auf BufferedInputStream verfügbar — Fallback ignorieren
            }
        }
    }

    /**
     * Periodischer HTTP-Heartbeat — laut Viidure-Reverse-Engineering nutzt die
     * App `http://host:8080/` um die Cam zu pingen während der Stream läuft.
     * Wir schicken alle 3s einen GET, ignorieren die Antwort.
     */
    private fun startHeartbeat(host: String, port: Int) {
        try { heartbeatThread?.interrupt() } catch (_: Exception) {}
        heartbeatThread = thread(start = true, name = "mjpeg-heartbeat") {
            while (running) {
                try {
                    val url = URL("http://$host:$port/")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Lavf58.76.100")
                    conn.responseCode  // Abruf erzwingt Request
                    conn.disconnect()
                } catch (_: Exception) {
                    // Heartbeat-Fehler ignorieren, der Stream-Loop kümmert sich um Reconnect
                }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@thread }
            }
        }
    }
}
