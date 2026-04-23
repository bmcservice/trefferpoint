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

    /** Gängige Standard-Gateway-IPs von Kamera-Hotspots (wenn Gateway-Detection spinnt). */
    private val CAMERA_SUBNET_GUESSES = listOf(
        "192.168.0.1",   // Viidure & viele generische OEMs
        "192.168.1.1",
        "192.168.4.1",   // ESP32-basiert
        "192.168.8.1",
        "192.168.25.1",  // Huawei/ZTE Dongles
        "10.0.0.1",
        "172.16.0.1"
    )

    /**
     * Führt den kompletten Scan durch. Muss im IO-Kontext laufen.
     * Scannt sowohl die übergebene IP (wenn vorhanden) als auch gängige Kamera-Gateway-IPs
     * parallel — um Android-DHCP-Info-Quirks (veraltete Gateway-IP vom vorherigen Netz)
     * zu umgehen.
     *
     * Callback erhält JSON als String: [{"url":"...","port":554,"proto":"rtsp","detail":"..."}]
     */
    suspend fun scan(host: String, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        // Ziel-IPs zusammenstellen: die übergebene + Standard-Kamera-Subnetze.
        // Das löst die "Gateway liefert Home-Router-IP"-Falle.
        val targets = buildList {
            if (host.isNotBlank()) add(host)
            CAMERA_SUBNET_GUESSES.forEach { if (it != host) add(it) }
        }
        AppLog.i(TAG, "Scan gestartet für ${targets.size} IP(s): ${targets.joinToString()}")
        onProgress("Scanne ${targets.size} IPs parallel…")

        // Schritt 1: Für alle IPs parallel Port-Probes
        val ipPortMap = mutableMapOf<String, List<Int>>()
        coroutineScope {
            targets.map { ip ->
                async {
                    val open = coroutineScope {
                        TCP_PORTS.map { port ->
                            async { if (isPortOpen(ip, port, 500)) port else null }
                        }.awaitAll().filterNotNull()
                    }
                    ip to open
                }
            }.awaitAll().forEach { (ip, open) ->
                if (open.isNotEmpty()) {
                    ipPortMap[ip] = open
                    AppLog.i(TAG, "Offene Ports auf $ip: ${open.joinToString()}")
                }
            }
        }

        if (ipPortMap.isEmpty()) {
            onProgress("Keine offenen Ports gefunden.")
            return@withContext JSONArray().toString()
        }
        onProgress("Ports gefunden auf: ${ipPortMap.keys.joinToString()} — probe Protokolle…")

        // Schritt 2: Protokoll-Probes — SEQUENZIELL pro IP (viele OEM-Kameras verkraften
        // keine parallelen TCP-Verbindungen und zerbrechen dann komplett).
        val results = JSONArray()
        for ((ip, ports) in ipPortMap) {
            for (port in ports) {
                val hits = when (port) {
                    554, 8554 -> probeRtsp(ip, port)
                    80, 81, 8080, 8081, 8888 -> probeHttpMjpeg(ip, port)
                    else -> emptyList()
                }
                hits.forEach { results.put(it) }
            }
        }

        AppLog.i(TAG, "Scan fertig — ${results.length()} Stream(s) gefunden")
        onProgress("${results.length()} Stream(s) gefunden")
        results.toString()
    }

    /** Prüft ob ein Port via TCP-Connect offen ist. */
    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /**
     * RTSP-Probe: OPTIONS-Request auf jeden Kandidaten-Pfad.
     * Erfolg: 200-Status mit Public-Header der OPTIONS/DESCRIBE/PLAY beinhaltet.
     */
    private fun probeRtsp(host: String, port: Int): List<JSONObject> {
        val hits = mutableListOf<JSONObject>()
        for (path in RTSP_PATHS) {
            if (hits.isNotEmpty()) break
            val url = "rtsp://$host:$port$path"
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.soTimeout = 2000
                // User-Agent Lavf58.76.100 — per TPScanner-Test als akzeptiert bestätigt
                val req = "OPTIONS $url RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Lavf58.76.100\r\n\r\n"
                socket.getOutputStream().write(req.toByteArray())
                socket.getOutputStream().flush()

                val buf = ByteArray(4096)
                val n = socket.getInputStream().read(buf)
                if (n > 0) {
                    val resp = String(buf, 0, n, Charsets.US_ASCII)
                    val firstLine = resp.lineSequence().firstOrNull()?.trim() ?: ""
                    if (firstLine.startsWith("RTSP/") && !firstLine.contains("404")) {
                        hits.add(JSONObject().apply {
                            put("url", url)
                            put("port", port)
                            put("proto", "rtsp")
                            put("detail", firstLine)
                        })
                        AppLog.i(TAG, "✓ RTSP $path → $firstLine")
                    } else {
                        AppLog.i(TAG, "✗ RTSP $path → $firstLine")
                    }
                } else {
                    AppLog.i(TAG, "✗ RTSP $path → keine Daten (n=$n)")
                }
            } catch (e: Exception) {
                AppLog.i(TAG, "✗ RTSP $path → ${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
            // Kleine Pause zwischen Probes — manche Kameras brauchen sonst Zeit
            try { Thread.sleep(100) } catch (_: Exception) {}
        }
        return hits
    }

    /**
     * HTTP-Probe: GET auf Kandidaten-Pfade.
     * Erfolg: Status 200 mit Content-Type beginnend mit "multipart/" oder "image/".
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
                conn.setRequestProperty("User-Agent", "Lavf58.76.100")
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
