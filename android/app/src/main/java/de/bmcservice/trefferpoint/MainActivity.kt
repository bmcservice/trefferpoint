package de.bmcservice.trefferpoint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Base64
import android.view.Surface
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import com.serenegiant.utils.UVCUtils
import java.nio.ByteBuffer

/**
 * TrefferPoint Android App — Architektur via JavaScriptInterface (kein HTTP-Server mehr).
 *
 *   USB-C Kamera (UVC) → CameraHelper (shiyinghan/UVCAndroid)
 *        → NV21 Frames → JPEG-Konvertierung
 *        → Base64 → WebView.evaluateJavascript("window.tpReceiveFrame(...)")
 *        → JS erzeugt Blob-URL → imgEl.src → Canvas → Detection
 *
 * WebView lädt index.html direkt aus assets (file://).
 * JS-Brücke `tpBridge` erlaubt TrefferPoint Zugriff auf getLog(), getStatus(), getVersion().
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrefferPoint"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private lateinit var webView: WebView

    private var cameraHelper: ICameraHelper? = null
    private var cameraOpened = false
    private var deviceSelected = false
    private var dummySurface: Surface? = null
    private var dummySurfaceTex: SurfaceTexture? = null

    @Volatile private var frameCount: Long = 0
    @Volatile private var lastFrameSize: Int = 0
    @Volatile private var activeMode: String = "none"  // "usb" | "rtsp" | "none"

    private var rtspPipeline: RtspPipeline? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        AppLog.i(TAG, "=== onCreate — TrefferPoint startet ===")
        AppLog.i(TAG, "Launch intent: ${intent?.action}")

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()
                AppLog.i(TAG, "WebView Permission Request: ${request.resources.joinToString()} → grant ${allowed.joinToString()}")
                request.grant(allowed)
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)

        // JavaScript-Bridge: JS greift via window.tpBridge auf App-Infos zu
        webView.addJavascriptInterface(TrefferPointBridge(), "tpBridge")

        // TrefferPoint-HTML aus Assets laden
        webView.loadUrl("file:///android_asset/trefferpoint/index.html")

        // Runtime-Kamera-Permission für den "Kamera"-Tab
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
        }

        UVCUtils.init(this)
        initCamera()
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i(TAG, "onNewIntent: ${intent.action}")
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        attachAlreadyConnectedCamera()
    }

    // ── USB-Device-Verwaltung ────────────────────────────────────────────────

    private fun handleUsbIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        @Suppress("DEPRECATION")
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            AppLog.w(TAG, "USB_DEVICE_ATTACHED Intent aber kein Device im Extra")
            return
        }
        AppLog.i(TAG, "USB-Intent Device: ${device.productName} VID=${device.vendorId} PID=${device.productId}")
        if (deviceSelected) { AppLog.i(TAG, "  (schon selected — überspringe)"); return }
        try {
            cameraHelper?.selectDevice(device)
            deviceSelected = true
        } catch (e: Exception) {
            AppLog.e(TAG, "selectDevice via Intent fehlgeschlagen", e)
        }
    }

    private fun attachAlreadyConnectedCamera() {
        val helper = cameraHelper ?: return
        if (cameraOpened || deviceSelected) return
        try {
            val devices = helper.deviceList
            AppLog.i(TAG, "onResume enumerate — ${devices?.size ?: 0} USB-Device(s) sichtbar")
            devices?.forEachIndexed { i, d ->
                AppLog.i(TAG, "  [$i] ${d.productName} VID=${d.vendorId} PID=${d.productId}")
            }
            val first = devices?.firstOrNull() ?: return
            AppLog.i(TAG, "Starte Kamera: ${first.productName}")
            helper.selectDevice(first)
            deviceSelected = true
        } catch (e: Exception) {
            AppLog.e(TAG, "attachAlreadyConnectedCamera fehlgeschlagen", e)
        }
    }

    // ── Kamera-Lifecycle ─────────────────────────────────────────────────────

    private fun initCamera() {
        cameraHelper = CameraHelper().also { helper ->
            helper.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    AppLog.i(TAG, "→ onAttach: ${device?.productName}")
                    if (deviceSelected) return
                    device?.let { helper.selectDevice(it); deviceSelected = true }
                }

                override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                    AppLog.i(TAG, "→ onDeviceOpen: ${device?.productName} firstOpen=$isFirstOpen")
                    try {
                        helper.openCamera(UVCParam())
                    } catch (e: Exception) {
                        AppLog.e(TAG, "openCamera() Exception", e)
                    }
                }

                override fun onCameraOpen(device: UsbDevice?) {
                    val size = helper.previewSize
                    val w = size?.width ?: PREVIEW_WIDTH
                    val h = size?.height ?: PREVIEW_HEIGHT
                    AppLog.i(TAG, "→ onCameraOpen: ${device?.productName} — ${w}x${h}")
                    cameraOpened = true
                    activeMode = "usb"
                    // Falls RTSP noch lief, stoppen — USB hat Vorrang bei direktem Anschluss
                    rtspPipeline?.stop()
                    rtspPipeline = null

                    // Offizielle Reihenfolge: startPreview → addSurface → setFrameCallback
                    try { helper.startPreview(); AppLog.i(TAG, "   1) startPreview()") }
                    catch (e: Exception) { AppLog.e(TAG, "startPreview() Exception", e) }

                    try {
                        val tex = SurfaceTexture(0).apply { setDefaultBufferSize(w, h) }
                        val surf = Surface(tex)
                        dummySurfaceTex = tex
                        dummySurface = surf
                        helper.addSurface(surf, false)
                        AppLog.i(TAG, "   2) addSurface (${w}x${h})")
                    } catch (e: Exception) { AppLog.e(TAG, "addSurface Exception", e) }

                    try {
                        helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                        AppLog.i(TAG, "   3) setFrameCallback(NV21)")
                    } catch (e: Exception) { AppLog.e(TAG, "setFrameCallback Exception", e) }
                }

                override fun onCameraClose(device: UsbDevice?) {
                    AppLog.i(TAG, "→ onCameraClose")
                    cameraOpened = false
                }

                override fun onDeviceClose(device: UsbDevice?) {
                    AppLog.i(TAG, "→ onDeviceClose")
                }

                override fun onDetach(device: UsbDevice?) {
                    AppLog.i(TAG, "→ onDetach")
                    cameraOpened = false
                    deviceSelected = false
                }

                override fun onCancel(device: UsbDevice?) {
                    AppLog.w(TAG, "→ onCancel (Permission abgelehnt?) ${device?.productName}")
                }
            })
        }
    }

    // ── Frame → JS-Bridge ────────────────────────────────────────────────────

    private val frameCallback = object : IFrameCallback {
        private var logged = false
        override fun onFrame(frame: ByteBuffer) {
            try {
                val helper = cameraHelper ?: return
                val size = helper.previewSize
                val w = size?.width ?: PREVIEW_WIDTH
                val h = size?.height ?: PREVIEW_HEIGHT
                val bytes = ByteArray(frame.remaining())
                frame.get(bytes)
                val jpeg = if (isJpeg(bytes)) bytes else nv21ToJpeg(bytes, w, h)
                frameCount++
                lastFrameSize = jpeg.size
                if (!logged) {
                    AppLog.i(TAG, "Erster Frame: ${bytes.size}B roh → ${jpeg.size}B JPEG @ ${w}x$h")
                    logged = true
                }
                pushFrameToWebView(jpeg)
            } catch (e: Exception) {
                AppLog.e(TAG, "Frame-Verarbeitung fehlgeschlagen", e)
            }
        }
    }

    private fun pushFrameToWebView(jpeg: ByteArray) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        runOnUiThread {
            // Stringkonkatenation via ' + ' vermeidet String-Escaping-Probleme.
            // Base64 ist safe in JS-String (keine Quotes/Backslashes).
            webView.evaluateJavascript(
                "window.tpReceiveFrame && window.tpReceiveFrame('$b64')",
                null
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isJpeg(data: ByteArray): Boolean =
        data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuv = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, width, height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
        return out.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { rtspPipeline?.stop() } catch (_: Exception) {}
        try { dummySurface?.release() } catch (_: Exception) {}
        try { dummySurfaceTex?.release() } catch (_: Exception) {}
        try { cameraHelper?.closeCamera() } catch (_: Exception) {}
        try { cameraHelper?.release() } catch (_: Exception) {}
    }

    // ── JavaScript-Bridge ────────────────────────────────────────────────────

    inner class TrefferPointBridge {
        @JavascriptInterface
        fun getLog(): String = AppLog.snapshot()

        @JavascriptInterface
        fun getStatus(): String {
            val rtspFC = rtspPipeline?.frameCount ?: 0
            val rtspErr = rtspPipeline?.lastError?.let { ",\"rtspError\":\"${it.replace("\"","'")}\"" } ?: ""
            return """{"mode":"$activeMode","cameraConnected":${cameraOpened || activeMode == "rtsp"},"frameCount":$frameCount,"rtspFrameCount":$rtspFC,"lastFrameBytes":$lastFrameSize$rtspErr}"""
        }

        @JavascriptInterface
        fun getVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        /** Liefert die Gateway-IP des aktuell verbundenen WLANs als String, oder "" wenn keine. */
        @JavascriptInterface
        fun getWifiGateway(): String {
            return try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val gw = wifi.dhcpInfo?.gateway ?: 0
                if (gw == 0) "" else
                    "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
            } catch (e: Exception) {
                AppLog.e(TAG, "getWifiGateway fehlgeschlagen", e)
                ""
            }
        }

        /** RTSP-Stream starten. Wenn url leer, wird Gateway-IP + Standard-Pfade probiert. */
        @JavascriptInterface
        fun startRtsp(url: String) {
            runOnUiThread {
                try {
                    // Bestehende Pipelines stoppen
                    rtspPipeline?.stop()
                    rtspPipeline = null
                    try { cameraHelper?.closeCamera() } catch (_: Exception) {}
                    cameraOpened = false

                    val finalUrl = if (url.isNotBlank()) url else {
                        val gw = getWifiGateway()
                        if (gw.isNotBlank()) "rtsp://$gw:554/live/tcp/ch1"
                        else "rtsp://192.168.0.1:554/live/tcp/ch1"
                    }

                    AppLog.i(TAG, "startRtsp: $finalUrl")
                    val pipeline = RtspPipeline(
                        applicationContext,
                        onJpegFrame = { jpeg ->
                            frameCount++
                            lastFrameSize = jpeg.size
                            pushFrameToWebView(jpeg)
                        },
                        onStatus = { status -> AppLog.i(TAG, "RTSP: $status") }
                    )
                    rtspPipeline = pipeline
                    activeMode = "rtsp"
                    pipeline.start(finalUrl)
                } catch (e: Exception) {
                    AppLog.e(TAG, "startRtsp Exception", e)
                }
            }
        }

        @JavascriptInterface
        fun stopRtsp() {
            runOnUiThread {
                try {
                    rtspPipeline?.stop()
                    rtspPipeline = null
                    if (activeMode == "rtsp") activeMode = "none"
                    AppLog.i(TAG, "stopRtsp")
                } catch (e: Exception) { AppLog.e(TAG, "stopRtsp Exception", e) }
            }
        }
    }
}
