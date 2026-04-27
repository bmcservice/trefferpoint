package de.bmcservice.trefferpoint

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Developer-only HTTP-Server der das aktuelle Pipeline-JPEG als MJPEG-Stream
 * und Einzelbild-Snapshots ausliefert. Dient zur **Live-Diagnose vom PC** während
 * Schießstand-Tests.
 *
 * Nutzung vom PC (Tablet per USB angeschlossen):
 *
 *   adb forward tcp:8090 tcp:8090
 *   → Browser: http://localhost:8090/        (HTML-Embed-Sicht)
 *              http://localhost:8090/stream  (rohes MJPEG)
 *              http://localhost:8090/snapshot (einzelnes JPEG)
 *              http://localhost:8090/status  (JSON-Status)
 *
 * **Bewusst minimalistisch:** kein Framework, kein NanoHTTPD. Plain Sockets,
 * pro Verbindung ein Thread. Server läuft NUR in DEBUG-Builds (per BuildConfig
 * gesteuert in MainActivity).
 *
 * Quelle der Frames: ein Lambda das von außen `lastFrameJpeg` der gerade
 * aktiven Pipeline (RTSP, MJPEG oder USB) zurückgibt.
 */
class DevHttpServer(
    private val getCurrentJpeg: () -> ByteArray?,
    private val getStatusJson: () -> String = { """{"status":"unknown"}""" }
) {
    companion object {
        private const val TAG = "DevHttpServer"
        private const val MJPEG_BOUNDARY = "tpframe"
        private const val MJPEG_INTERVAL_MS = 50L  // ~20 fps Browser-Throttle
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    @Volatile var port: Int = -1; private set

    fun start(port: Int = 8090) {
        if (running.get()) return
        running.set(true)
        thread(name = "dev-http-accept", isDaemon = true) {
            try {
                serverSocket = ServerSocket(port)
                this.port = port
                AppLog.i(TAG, "DevHttpServer läuft auf Port $port — " +
                    "PC-Zugriff: 'adb forward tcp:$port tcp:$port' → http://localhost:$port/")
                while (running.get()) {
                    val client = try { serverSocket!!.accept() } catch (_: Exception) {
                        if (running.get()) AppLog.w(TAG, "accept Fehler")
                        break
                    }
                    thread(name = "dev-http-conn", isDaemon = true) {
                        try { handleClient(client) }
                        catch (e: Exception) { AppLog.w(TAG, "Connection: ${e.message}") }
                        finally { try { client.close() } catch (_: Exception) {} }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) AppLog.e(TAG, "Server-Fehler", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        port = -1
        AppLog.i(TAG, "DevHttpServer gestoppt")
    }

    private fun handleClient(sock: Socket) {
        sock.soTimeout = 30000
        val br = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val out = sock.getOutputStream()
        val firstLine = br.readLine() ?: return
        val parts = firstLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") {
            writeStatusLine(out, 405, "Method Not Allowed")
            return
        }
        val path = parts[1].substringBefore('?')
        // HTTP-Headers überspringen (bis Leerzeile)
        while (true) {
            val line = br.readLine() ?: break
            if (line.isEmpty()) break
        }

        when {
            path == "/stream" -> serveMjpeg(out)
            path == "/snapshot" -> serveSnapshot(out)
            path == "/status" -> serveStatus(out)
            path == "/" || path == "/index.html" -> serveIndex(out)
            else -> serve404(out)
        }
    }

    private fun serveMjpeg(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: TrefferPoint-Dev\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()
        AppLog.i(TAG, "MJPEG-Stream gestartet (Browser verbunden)")

        var lastSent: ByteArray? = null
        var sentCount = 0L
        try {
            while (running.get()) {
                val jpeg = getCurrentJpeg()
                if (jpeg != null && jpeg !== lastSent) {
                    val partHeader = "--$MJPEG_BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n\r\n"
                    out.write(partHeader.toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    lastSent = jpeg
                    sentCount++
                    if (sentCount == 1L || sentCount % 100L == 0L) {
                        AppLog.i(TAG, "MJPEG: $sentCount Frames gesendet")
                    }
                }
                Thread.sleep(MJPEG_INTERVAL_MS)
            }
        } catch (e: Exception) {
            AppLog.i(TAG, "MJPEG-Stream beendet ($sentCount Frames): ${e.message}")
        }
    }

    private fun serveSnapshot(out: OutputStream) {
        val jpeg = getCurrentJpeg()
        if (jpeg == null) {
            writeStatusLine(out, 503, "Service Unavailable")
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: TrefferPoint-Dev\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(jpeg)
        out.flush()
    }

    private fun serveStatus(out: OutputStream) {
        val body = getStatusJson()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: TrefferPoint-Dev\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serveIndex(out: OutputStream) {
        val body = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>TrefferPoint Dev Stream</title>
<style>
  body{margin:0;background:#0d0d0d;color:#eee;font-family:monospace;padding:8px}
  h1{font-size:14px;margin:0 0 8px;color:#c8a84b}
  .stage{display:flex;justify-content:center;align-items:center;overflow:hidden}
  img{max-width:100%;display:block;border:1px solid #333;transition:transform .2s}
  .row{display:flex;gap:8px;margin-top:8px;flex-wrap:wrap;align-items:center}
  .row a,.row button{color:#c8a84b;background:#0d0d0d;text-decoration:none;padding:6px 10px;border:1px solid #333;font-family:monospace;cursor:pointer}
  .row button:hover{background:#1a1a1a}
  pre{background:#1a1a1a;padding:8px;font-size:11px;color:#9d9;margin-top:8px}
</style></head><body>
<h1>TrefferPoint — Developer Stream Sibling</h1>
<div class="stage"><img id="liveStream" src="/stream" alt="MJPEG Stream"/></div>
<div class="row">
  <button id="btnRotate">↻ Bild drehen (<span id="rotState">0°</span>)</button>
  <a href="/stream" target="_blank">Raw Stream</a>
  <a href="/snapshot" target="_blank">Snapshot</a>
  <a href="/status" target="_blank">Status JSON</a>
  <a href="https://bmcservice.github.io/trefferpoint" target="_blank">→ TrefferPoint PWA</a>
</div>
<pre id="status">Status …</pre>
<script>
  const img = document.getElementById('liveStream');
  const rotLabel = document.getElementById('rotState');
  let rot = parseInt(localStorage.getItem('tpDevRot') || '0', 10);
  function applyRot() {
    img.style.transform = 'rotate(' + rot + 'deg)';
    rotLabel.textContent = rot + '°';
    localStorage.setItem('tpDevRot', String(rot));
  }
  document.getElementById('btnRotate').addEventListener('click', () => {
    rot = (rot + 90) % 360;
    applyRot();
  });
  applyRot();
  setInterval(async () => {
    try {
      const r = await fetch('/status');
      document.getElementById('status').textContent = await r.text();
    } catch(e) { document.getElementById('status').textContent = 'Status-Fehler: ' + e; }
  }, 1000);
</script>
</body></html>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: TrefferPoint-Dev\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serve404(out: OutputStream) {
        writeStatusLine(out, 404, "Not Found")
    }

    private fun writeStatusLine(out: OutputStream, code: Int, msg: String) {
        val body = "$code $msg"
        val response = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n$body"
        out.write(response.toByteArray())
        out.flush()
    }
}
