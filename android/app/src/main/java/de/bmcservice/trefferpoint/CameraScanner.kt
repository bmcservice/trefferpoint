package de.bmcservice.trefferpoint

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Scannt eine IP-Adresse nach erreichbaren Kamera-Streams.
 *
 * 1. Port-Scan: parallele TCP-Connects auf bekannte Kamera-Ports
 * 2. Protokoll-Probes pro offenen Port:
 *    - RTSP (554, 8554): OPTIONS auf Pfad-Liste
 *    - HTTP (80, 81, 8080, 8081): GET mit MJPEG-Pfaden, Content-Type auswerten
 *
 * Ergebnis: JSON-Liste der gefundenen URLs mit Metadaten.
 */
object CameraScanner {

    private const val TAG = "CameraScanner"

    private val TCP_PORTS = intArrayOf(80, 81, 8080, 8081, 554, 8554, 8888)

    private val RTSP_PATHS = listOf(
        "/live/tcp/ch1",  // Viidure
        "/live",
        "/live/ch1",
        "/stream0",
        "/11",
        "/cam0",
        "/onvif1",
        "/"
    )

    private val MJPEG_PATHS = listOf(
        "/video",
        "/stream",
        "/mjpeg",
        "/mjpeg.cgi",
        "/snapshot",
        "/"
    )

    /**
     * Führt den kompletten Scan durch. Muss im IO-Kontext laufen.
     * Callback erhält JSON als String: [{"url":"...","port":554,"proto":"rtsp","detail":"..."}]
     */
    suspend fun scan(host: String, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        onProgress("Scanne $host ...")
        AppLog.i(TAG, "Scan gestartet für $host")

        // Parallel Port-Scan
        val openPorts = coroutineScope {
            TCP_PORTS.map { port ->
                async { if (isPortOpen(host, port, 800)) port else null }
            }.awaitAll().filterNotNull()
        }
        AppLog.i(TAG, "Offene Ports auf $host: ${openPorts.joinToString()}")
        onProgress("Offene Ports: ${openPorts.joinToString()}")

        if (openPorts.isEmpty()) {
            return@withContext JSONArray().toString()
        }

        // Protokoll-Probes
        val results = JSONArray()
        coroutineScope {
            openPorts.map { port ->
                async {
                    when (port) {
                        554, 8554 -> probeRtsp(host, port)
                        80, 81, 8080, 8081, 8888 -> probeHttpMjpeg(host, port)
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull().forEach { it.forEach { hit -> results.put(hit) } }
        }

        AppLog.i(TAG, "Scan fertig — ${results.length()} Stream(s) gefunden")
        onProgress("${results.length()} Stream(s) gefunden")
        results.toString()
    }

    /** Prüft ob ein Port via TCP-Connect offen ist. */
    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * RTSP-Probe: OPTIONS-Request auf jeden Kandidaten-Pfad.
     * Erfolg: 200-Status mit Public-Header der OPTIONS/DESCRIBE/PLAY beinhaltet.
     */
    private fun probeRtsp(host: String, port: Int): List<JSONObject> {
        val hits = mutableListOf<JSONObject>()
        for (path in RTSP_PATHS) {
            val url = "rtsp://$host:$port$path"
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 1500)
                    s.soTimeout = 1500
                    val req = "OPTIONS $url RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: TrefferPoint-Scanner\r\n\r\n"
                    s.getOutputStream().write(req.toByteArray())
                    s.getOutputStream().flush()

                    val buf = ByteArray(4096)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) {
                        val resp = String(buf, 0, n, Charsets.US_ASCII)
                        val firstLine = resp.lineSequence().firstOrNull() ?: ""
                        if (firstLine.contains("200")) {
                            hits.add(JSONObject().apply {
                                put("url", url)
                                put("port", port)
                                put("proto", "rtsp")
                                put("detail", firstLine.trim())
                            })
                            AppLog.i(TAG, "RTSP 200 auf $url")
                            // Nach erstem Treffer aufhören — sonst kriegen wir Duplikate
                            break
                        }
                    }
                }
            } catch (_: Exception) { /* Pfad nicht verfügbar — weiter */ }
        }
        return hits
    }

    /**
     * HTTP-Probe: GET auf Kandidaten-Pfade.
     * Erfolg: 200 mit Content-Type multipart/* oder image/*.
     */
    private fun probeHttpMjpeg(host: String, port: Int): List<JSONObject> {
        val hits = mutableListOf<JSONObject>()
        for (path in MJPEG_PATHS) {
            val fullUrl = "http://$host:$port$path"
            try {
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "TrefferPoint-Scanner")
                val code = conn.responseCode
                val ct = (conn.contentType ?: "").lowercase()
                conn.inputStream?.close()
                conn.disconnect()

                if (code == 200 && (ct.startsWith("multipart/") || ct.startsWith("image/"))) {
                    hits.add(JSONObject().apply {
                        put("url", fullUrl)
                        put("port", port)
                        put("proto", if (ct.startsWith("multipart/")) "mjpeg" else "jpeg")
                        put("detail", "HTTP 200 · $ct")
                    })
                    AppLog.i(TAG, "MJPEG Treffer auf $fullUrl ($ct)")
                    break
                }
            } catch (_: Exception) { /* Pfad nicht verfügbar — weiter */ }
        }
        return hits
    }
}
