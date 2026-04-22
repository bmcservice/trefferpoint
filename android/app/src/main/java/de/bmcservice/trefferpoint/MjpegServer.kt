package de.bmcservice.trefferpoint

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lokaler HTTP-Server der BEIDES macht:
 *   1. TrefferPoint-HTML aus assets/ serviert (→ gleiche Origin wie /video)
 *   2. JPEG-Frames der UVC-Kamera als multipart/x-mixed-replace Stream
 *
 * Vorteil gleiche Origin: keine CORS-Probleme im WebView, kein file://→http:// Block.
 *
 * Endpunkte:
 *   /                         index.html (Asset)
 *   /manifest.json            manifest.json (Asset)
 *   /sw.js, /version.json     weitere Assets (by extension)
 *   /video, /stream, /mjpeg   MJPEG-Stream
 *   /snapshot, /jpg           letztes JPEG als Einzelbild
 *   /status                   JSON mit cam-Status (für Diagnose)
 */
class MjpegServer(port: Int, private val appContext: Context?) : NanoHTTPD("127.0.0.1", port) {

    constructor(port: Int) : this(port, null)

    private val TAG = "MjpegServer"
    private val BOUNDARY = "trefferpointframe"
    private val clients = CopyOnWriteArrayList<PipedOutputStream>()

    @Volatile private var lastFrame: ByteArray? = null
    @Volatile private var frameCount: Long = 0

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: $uri")

        return when {
            uri == "/video" || uri == "/video.mjpg" || uri == "/stream" || uri == "/mjpeg" -> serveMjpeg()
            uri == "/snapshot" || uri == "/jpg" -> serveSnapshot()
            uri == "/status" -> serveStatus()
            uri == "/log" -> serveLog()
            uri == "/" || uri == "/index.html" -> serveAsset("trefferpoint/index.html", "text/html; charset=utf-8")
            uri == "/manifest.json" -> serveAsset("trefferpoint/manifest.json", "application/json")
            uri.endsWith(".html") -> serveAsset("trefferpoint${uri}", "text/html; charset=utf-8")
            uri.endsWith(".css") -> serveAsset("trefferpoint${uri}", "text/css")
            uri.endsWith(".js") -> serveAsset("trefferpoint${uri}", "application/javascript")
            uri.endsWith(".png") -> serveAsset("trefferpoint${uri}", "image/png")
            uri.endsWith(".svg") -> serveAsset("trefferpoint${uri}", "image/svg+xml")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $uri")
        }
    }

    private fun serveAsset(assetPath: String, mime: String): Response {
        val ctx = appContext ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain", "Context nicht gesetzt"
        )
        return try {
            val stream = ctx.assets.open(assetPath)
            val bytes = stream.readBytes()
            stream.close()
            val resp = newFixedLengthResponse(
                Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()
            )
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Cache-Control", "no-cache")
            resp
        } catch (e: IOException) {
            AppLog.e(TAG, "Asset $assetPath nicht gefunden", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset nicht gefunden: $assetPath")
        }
    }

    private fun serveLog(): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", AppLog.snapshot())
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun serveStatus(): Response {
        val json = """{"cameraConnected": ${lastFrame != null}, "frameCount": $frameCount, "clients": ${clients.size}}"""
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveSnapshot(): Response {
        val frame = lastFrame
        return if (frame != null) {
            newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                frame.inputStream(), frame.size.toLong()
            )
        } else {
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain",
                "Kein Frame verfügbar"
            )
        }
    }

    private fun serveMjpeg(): Response {
        if (lastFrame == null) {
            AppLog.w(TAG, "MJPEG angefragt aber keine Kamera-Frames vorhanden → 503")
            val msg = "Keine Kamera verbunden. USB-C Kamera anstecken und App neu öffnen."
            val resp = newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", msg)
            resp.addHeader("Access-Control-Allow-Origin", "*")
            return resp
        }

        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        clients.add(pipeOut)
        AppLog.i(TAG, "MJPEG Client verbunden (${clients.size} gesamt)")

        lastFrame?.let { writeFrameTo(pipeOut, it) }

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            pipeIn
        )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Connection", "close")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    fun pushFrame(jpeg: ByteArray) {
        lastFrame = jpeg
        frameCount++
        val dead = mutableListOf<PipedOutputStream>()
        for (client in clients) {
            try { writeFrameTo(client, jpeg) }
            catch (e: IOException) { dead.add(client) }
        }
        if (dead.isNotEmpty()) {
            clients.removeAll(dead)
            dead.forEach { try { it.close() } catch (_: Exception) {} }
            AppLog.i(TAG, "${dead.size} Client(s) getrennt (${clients.size} übrig)")
        }
    }

    private fun writeFrameTo(out: PipedOutputStream, jpeg: ByteArray) {
        val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(jpeg)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
