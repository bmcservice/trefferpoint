package de.bmcservice.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TrefferPoint Netz-Scanner — Diagnose-Tool.
 *
 * Zeigt alle Probe-Details im Klartext: welche IPs, welche Ports offen, welche
 * RTSP/HTTP-Pfade antworten, mit welchen User-Agents, mit welchen Antworten.
 * Komplett unabhängig von TrefferPoint — zum Debuggen wenn die Hauptapp hängt.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvGateway: TextView
    private lateinit var tvOutput: TextView
    private lateinit var scroll: ScrollView
    private lateinit var etIp: EditText
    private lateinit var btnScan: Button

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMAN)

    private val USER_AGENTS = listOf(
        "Lavf57.83.100",                    // Viidure-FFmpeg — bekannter Key
        "Lavf58.76.100",                    // neueres FFmpeg
        "LibVLC/3.0.20 (LIVE555 Streaming Media v2016.11.28)",  // VLC
        "ExoPlayer"                         // was Media3 default schickt
    )

    private val RTSP_PORTS = intArrayOf(554, 8554)
    private val HTTP_PORTS = intArrayOf(80, 81, 8080, 8081, 8888)

    private val RTSP_PATHS = listOf(
        "/live/tcp/ch1",
        "/live",
        "/live/ch1",
        "/stream0",
        "/stream1",
        "/11",
        "/12",
        "/cam0",
        "/cam1",
        "/onvif1",
        "/onvif2",
        "/user=admin_password=_channel=1_stream=0.sdp",
        "/h264",
        "/h265",
        "/"
    )

    private val HTTP_PATHS = listOf(
        "/",
        "/video",
        "/stream",
        "/mjpeg",
        "/mjpeg.cgi",
        "/snapshot",
        "/snapshot.cgi",
        "/live",
        "/videostream.cgi",
        "/action/stream",
        "/cgi-bin/mjpg/video.cgi"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvGateway = findViewById(R.id.tvGateway)
        tvOutput = findViewById(R.id.tvOutput)
        scroll = findViewById(R.id.scroll)
        etIp = findViewById(R.id.etIp)
        btnScan = findViewById(R.id.btnScan)

        refreshGateway()
        btnScan.setOnClickListener {
            val manual = etIp.text.toString().trim()
            val target = manual.ifEmpty { detectGateway() }
            if (target.isBlank()) {
                append("⚠ Keine IP — Gateway nicht ermittelbar. Bitte manuell eintragen.")
            } else {
                tvOutput.text = ""
                append("=== Scan auf $target ===")
                ioScope.launch { runFullScan(target) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGateway()
    }

    private fun refreshGateway() {
        val gw = detectGateway()
        tvGateway.text = if (gw.isBlank()) "Gateway: — (nicht verbunden?)" else "Gateway: $gw"
    }

    private fun detectGateway(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (route in lp.routes) {
                    if (route.isDefaultRoute) {
                        val gw = route.gateway
                        if (gw is Inet4Address) return gw.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val gw = wifi.dhcpInfo?.gateway ?: 0
            if (gw != 0) return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        } catch (_: Exception) {}
        return ""
    }

    private fun append(line: String) {
        runOnUiThread {
            val ts = timeFmt.format(Date())
            tvOutput.append("$ts $line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private suspend fun runFullScan(host: String) {
        append("→ Port-Scan (${RTSP_PORTS.size + HTTP_PORTS.size} Ports)…")
        val openRtsp = mutableListOf<Int>()
        val openHttp = mutableListOf<Int>()
        coroutineScope {
            RTSP_PORTS.map { p -> async { if (isPortOpen(host, p, 1000)) p else null } }
                .awaitAll().filterNotNull().forEach { openRtsp.add(it) }
            HTTP_PORTS.map { p -> async { if (isPortOpen(host, p, 1000)) p else null } }
                .awaitAll().filterNotNull().forEach { openHttp.add(it) }
        }
        append("  RTSP-Ports offen: ${if (openRtsp.isEmpty()) "—" else openRtsp.joinToString()}")
        append("  HTTP-Ports offen: ${if (openHttp.isEmpty()) "—" else openHttp.joinToString()}")

        if (openRtsp.isNotEmpty()) {
            append("")
            append("=== RTSP-Probes ===")
            for (port in openRtsp) {
                for (ua in USER_AGENTS) {
                    append("--- Port $port, User-Agent: $ua ---")
                    for (path in RTSP_PATHS) {
                        val url = "rtsp://$host:$port$path"
                        val result = probeRtsp(host, port, path, ua)
                        val status = if (result.isEmpty()) "— keine Antwort" else result
                        append("  $path → $status")
                    }
                }
            }
        }

        if (openHttp.isNotEmpty()) {
            append("")
            append("=== HTTP-Probes (MJPEG / Snapshot) ===")
            for (port in openHttp) {
                for (ua in USER_AGENTS) {
                    append("--- Port $port, User-Agent: $ua ---")
                    for (path in HTTP_PATHS) {
                        val url = "http://$host:$port$path"
                        val result = probeHttp(url, ua)
                        append("  $path → $result")
                    }
                }
            }
        }

        append("")
        append("=== Fertig ===")
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) { false }
        finally { try { s.close() } catch (_: Exception) {} }
    }

    private fun probeRtsp(host: String, port: Int, path: String, ua: String): String {
        val url = "rtsp://$host:$port$path"
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 1500)
            socket.soTimeout = 1500
            val req = "OPTIONS $url RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: $ua\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray())
            socket.getOutputStream().flush()
            val buf = ByteArray(4096)
            val n = socket.getInputStream().read(buf)
            if (n <= 0) "— keine Daten"
            else {
                val resp = String(buf, 0, n, Charsets.US_ASCII)
                val firstLine = resp.lineSequence().firstOrNull()?.trim() ?: ""
                val hasPublic = resp.contains("Public:", ignoreCase = true)
                firstLine + if (hasPublic) " [OPTIONS ok]" else ""
            }
        } catch (e: Exception) {
            "Fehler: ${e.javaClass.simpleName}"
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun probeHttp(url: String, ua: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", ua)
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            val ct = (conn.contentType ?: "").lowercase()
            val loc = conn.getHeaderField("Location") ?: ""
            conn.inputStream?.close()
            conn.disconnect()
            val locMsg = if (loc.isNotEmpty()) " → $loc" else ""
            "HTTP $code · $ct$locMsg"
        } catch (e: Exception) {
            "Fehler: ${e.javaClass.simpleName}: ${e.message?.take(50) ?: ""}"
        }
    }
}
