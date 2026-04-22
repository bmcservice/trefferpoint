package de.bmcservice.trefferpoint

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * TrefferPoint Android App — Schritt 1 (WebView-Grundgerüst)
 *
 * Status:
 *   ✓ WebView lädt index.html aus assets
 *   ✓ MjpegServer auf 127.0.0.1:8888 bereit (noch ohne echte Frames)
 *   ☐ UVC-Kamera-Anbindung (Schritt 2)
 *
 * Endziel:
 *   USB-C Kamera → UVC-Bibliothek → JPEG-Frames
 *                                     ↓
 *   WebView ← http://127.0.0.1:8888/video ← MjpegServer
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrefferPoint"
        private const val MJPEG_PORT = 8888
    }

    private lateinit var webView: WebView
    private val mjpegServer = MjpegServer(MJPEG_PORT)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        WebView.setWebContentsDebuggingEnabled(true)

        webView.loadUrl("file:///android_asset/trefferpoint/index.html")

        // MJPEG-Server bereit machen (auch ohne Kamera — für Tests)
        try {
            mjpegServer.start()
            Log.i(TAG, "MJPEG server bereit auf Port $MJPEG_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG server konnte nicht starten", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegServer.stop()
    }
}
