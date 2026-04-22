package de.bmcservice.trefferpoint

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kleiner MJPEG-Server auf localhost.
 * Nimmt JPEG-Frames von der UVC-Kamera und serviert sie als
 * multipart/x-mixed-replace Stream an alle verbundenen Clients (WebView).
 *
 * Endpunkte:
 *   /          → HTML-Viewer zum Debuggen
 *   /video     → MJPEG-Stream (kompatibel mit TrefferPoint's Stream-Modus)
 */
class MjpegServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val TAG = "MjpegServer"
    private val BOUNDARY = "trefferpointframe"
    private val clients = CopyOnWriteArrayList<PipedOutputStream>()

    /** Letzten Frame merken, damit neue Clients sofort was sehen. */
    @Volatile private var lastFrame: ByteArray? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.i(TAG, "Request: $uri")

        return when (uri) {
            "/video", "/video.mjpg", "/stream", "/mjpeg" -> serveMjpeg()
            "/snapshot", "/jpg" -> serveSnapshot()
            else -> serveHtmlViewer()
        }
    }

    private fun serveHtmlViewer(): Response {
        val html = """
            <!DOCTYPE html><html><head><title>TrefferPoint Stream</title>
            <style>body{margin:0;background:#080a0d;color:#c8a84b;font-family:monospace}
            img{display:block;margin:0 auto;max-width:100%}</style></head>
            <body><h3 style="text-align:center">TrefferPoint UVC Stream</h3>
            <img src="/video" /></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        clients.add(pipeOut)
        Log.i(TAG, "MJPEG Client verbunden (${clients.size} gesamt)")

        // Letzten Frame sofort schicken
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

    /** Neuen JPEG-Frame an alle Clients pushen. */
    fun pushFrame(jpeg: ByteArray) {
        lastFrame = jpeg
        val dead = mutableListOf<PipedOutputStream>()
        for (client in clients) {
            try {
                writeFrameTo(client, jpeg)
            } catch (e: IOException) {
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) {
            clients.removeAll(dead)
            dead.forEach { try { it.close() } catch (_: Exception) {} }
            Log.i(TAG, "${dead.size} Client(s) getrennt (${clients.size} übrig)")
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
