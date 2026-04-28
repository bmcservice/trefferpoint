package de.bmcservice.trefferpoint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import java.net.Inet4Address
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.util.Base64
import android.content.ContentValues
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    @Volatile private var activeMode: String = "none"  // "usb" | "rtsp" | "mjpeg" | "none"

    private var rtspPipeline: RtspMediaCodecPipeline? = null
    private var mjpegPipeline: MjpegHttpPipeline? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    // Developer-only HTTP-Server (Live-Sibling über USB-adb-forward für PC-Browser).
    // Wird nur in DEBUG-Builds gestartet — siehe Feature-Trennung in CLAUDE.md.
    private var devHttpServer: DevHttpServer? = null

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

        // ── Developer-only: MJPEG-Server auf Port 8090 starten ──
        // PC-Browser-Zugriff via USB:  adb forward tcp:8090 tcp:8090
        // → http://localhost:8090/ zeigt Live-Sicht der gerade aktiven Pipeline.
        if (BuildConfig.DEBUG) {
            val server = DevHttpServer(
                getCurrentJpeg = {
                    // Priorität: aktiver Modus → entsprechende Pipeline.
                    when (activeMode) {
                        "rtsp" -> rtspPipeline?.lastFrameJpeg
                        "mjpeg" -> mjpegPipeline?.lastFrameJpeg
                        // USB hat keinen lastFrameJpeg-Speicher; fallback frei.
                        else -> rtspPipeline?.lastFrameJpeg ?: mjpegPipeline?.lastFrameJpeg
                    }
                },
                getStatusJson = {
                    val rtspFC = rtspPipeline?.frameCount ?: 0
                    val mjpegFC = mjpegPipeline?.frameCount ?: 0
                    val rtspErr = rtspPipeline?.lastError?.let { ",\"rtspError\":\"${it.replace("\"","'")}\"" } ?: ""
                    """{"mode":"$activeMode","rtspFrames":$rtspFC,"mjpegFrames":$mjpegFC,""" +
                            """"frameCount":$frameCount,"lastFrameBytes":$lastFrameSize,""" +
                            """"version":"${BuildConfig.VERSION_NAME}"$rtspErr}"""
                }
            )
            server.start(8090)
            devHttpServer = server
        }
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = tts?.setLanguage(Locale.GERMAN)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    AppLog.w(TAG, "TTS: Deutsch nicht verfügbar — Fallback auf Default")
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(1.1f)
                ttsReady = true
                AppLog.i(TAG, "TTS initialisiert")
            } else {
                AppLog.w(TAG, "TTS init fehlgeschlagen (status=$status)")
            }
        }
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
                    // Falls RTSP/MJPEG noch lief, stoppen — USB hat Vorrang bei direktem Anschluss
                    rtspPipeline?.stop()
                    rtspPipeline = null
                    mjpegPipeline?.stop()
                    mjpegPipeline = null

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
                    autoModesDisabled = false
                    uvcFrameCount = 0L
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

    @Volatile private var autoModesDisabled = false
    @Volatile private var uvcFrameCount = 0L
    // v2.3.118: letztes UVC-Frame für sgkSaveFrame() — rtsp/mjpeg hatten bereits lastFrameJpeg,
    // USB-C-Kamera wurde nie gespeichert → sgkSaveFrame() lieferte immer "Fehler: kein Frame verfügbar"
    // obwohl die Kamera live lief. Benutzer sah ✓ im UI aber kein File wurde geschrieben.
    @Volatile private var lastUvcFrameJpeg: ByteArray? = null

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
                uvcFrameCount++
                lastUvcFrameJpeg = jpeg   // v2.3.118: für sgkSaveFrame() im USB-Modus
                lastFrameSize = jpeg.size
                if (!logged) {
                    AppLog.i(TAG, "Erster Frame: ${bytes.size}B roh → ${jpeg.size}B JPEG @ ${w}x$h")
                    logged = true
                }
                // Nach ~2 Sekunden (60 Frames @ 30fps) Auto-Exposure fixieren.
                // Sonst pendelt die Kamera bei Spektiv-Szenen (dunkler Spiegel + heller Hintergrund)
                // ununterbrochen zwischen zu hell und zu dunkel → starkes Flackern.
                if (!autoModesDisabled && uvcFrameCount == 60L) {
                    autoModesDisabled = true
                    freezeCameraAutoModes(helper)
                }
                pushFrameToWebView(jpeg)
            } catch (e: Exception) {
                AppLog.e(TAG, "Frame-Verarbeitung fehlgeschlagen", e)
            }
        }
    }

    private fun freezeCameraAutoModes(helper: ICameraHelper) {
        val uvc = helper.uvcControl
        if (uvc == null) {
            AppLog.w(TAG, "UVC-Control nicht verfügbar — Kamera bleibt im Auto-Modus")
            return
        }
        // Auto-Exposure → Manual Mode (1): friert aktuelle Belichtungszeit ein
        try {
            uvc.setAutoExposureMode(1)
            AppLog.i(TAG, "UVC: Auto-Exposure auf MANUAL gesetzt (Flacker-Fix)")
        } catch (e: Exception) {
            // Wenn Manual nicht unterstützt, probier Shutter-Priority (4) = feste Shutter-Zeit
            try {
                uvc.setAutoExposureMode(4)
                AppLog.i(TAG, "UVC: Auto-Exposure auf SHUTTER_PRIORITY gesetzt")
            } catch (e2: Exception) {
                AppLog.w(TAG, "UVC: setAutoExposureMode nicht unterstützt (${e.message})")
            }
        }
        // Auto-White-Balance aus — verhindert Farb-Pendeln
        try {
            uvc.setWhiteBalanceAuto(false)
            AppLog.i(TAG, "UVC: Auto-White-Balance aus")
        } catch (e: Exception) {
            AppLog.w(TAG, "UVC: setWhiteBalanceAuto nicht unterstützt: ${e.message}")
        }
        // Gain (AGC) einfrieren — laufende AGC ist häufigste Flacker-Ursache bei Spektiv-Szenen
        try {
            val g = uvc.gain
            uvc.setGain(g)
            AppLog.i(TAG, "UVC: Gain fixiert auf $g")
        } catch (e: Exception) {
            AppLog.w(TAG, "UVC: Gain-Fixierung nicht unterstützt: ${e.message}")
        }
    }

    // Drop-Frame-Strategie für WebView-Push (v2.3.98+):
    // Hard-Throttle auf max ~25 fps zur WebView. Deterministisch, kein Callback-Race
    // mehr (in v2.3.96/97 blieb webViewPushPending manchmal hängen → Flackern).
    // Pipeline + DevHttpServer kriegen weiterhin alle Frames (volle 30 fps).
    @Volatile private var webViewDropped = 0L
    private var lastWebViewPushNs = 0L
    private val webViewMinIntervalNs = 40_000_000L  // 40ms = max 25 fps

    private fun pushFrameToWebView(jpeg: ByteArray) {
        val now = System.nanoTime()
        if (now - lastWebViewPushNs < webViewMinIntervalNs) {
            webViewDropped++
            if (webViewDropped == 1L || webViewDropped % 90L == 0L) {
                AppLog.i(TAG, "WebView-Drop: $webViewDropped Frames übersprungen (Throttle 25fps)")
            }
            return
        }
        lastWebViewPushNs = now
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "window.tpReceiveFrame && window.tpReceiveFrame('$b64')",
                    null
                )
            } catch (_: Exception) {}
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
        try { devHttpServer?.stop() } catch (_: Exception) {}
        try { rtspPipeline?.stop() } catch (_: Exception) {}
        try { mjpegPipeline?.stop() } catch (_: Exception) {}
        try { dummySurface?.release() } catch (_: Exception) {}
        try { dummySurfaceTex?.release() } catch (_: Exception) {}
        try { cameraHelper?.closeCamera() } catch (_: Exception) {}
        try { cameraHelper?.release() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
    }

    // ── JavaScript-Bridge ────────────────────────────────────────────────────

    inner class TrefferPointBridge {
        @JavascriptInterface
        fun getLog(): String = AppLog.snapshot()

        @JavascriptInterface
        fun getStatus(): String {
            val rtspFC = rtspPipeline?.frameCount ?: 0
            val mjpegFC = mjpegPipeline?.frameCount ?: 0
            val rtspErr = rtspPipeline?.lastError?.let { ",\"rtspError\":\"${it.replace("\"","'")}\"" } ?: ""
            val mjpegErr = mjpegPipeline?.lastError?.let { ",\"mjpegError\":\"${it.replace("\"","'")}\"" } ?: ""
            val connected = cameraOpened || activeMode == "rtsp" || activeMode == "mjpeg"
            return """{"mode":"$activeMode","cameraConnected":$connected,"frameCount":$frameCount,"rtspFrameCount":$rtspFC,"mjpegFrameCount":$mjpegFC,"lastFrameBytes":$lastFrameSize$rtspErr$mjpegErr}"""
        }

        @JavascriptInterface
        fun getVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        /**
         * Liefert die Gateway-IP des aktuell verbundenen WLAN-Netzes.
         * Nutzt ConnectivityManager + LinkProperties — weil WifiManager.getDhcpInfo() deprecated ist
         * und oft stale Daten vom vorherigen Netz liefert.
         */
        @JavascriptInterface
        fun getWifiGateway(): String {
            try {
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                // Alle aktiven Netzwerke durchgehen und das WIFI-Transport-Netz finden
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp = cm.getLinkProperties(network) ?: continue
                    for (route in lp.routes) {
                        if (route.isDefaultRoute) {
                            val gw = route.gateway
                            if (gw is Inet4Address) {
                                val ip = gw.hostAddress ?: continue
                                AppLog.i(TAG, "getWifiGateway via ConnectivityManager: $ip")
                                return ip
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "getWifiGateway via ConnectivityManager fehlgeschlagen", e)
            }
            // Fallback: der alte (unzuverlässige) Weg via DhcpInfo — besser als gar nichts
            return try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val gw = wifi.dhcpInfo?.gateway ?: 0
                if (gw == 0) "" else
                    "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
            } catch (e: Exception) {
                AppLog.e(TAG, "getWifiGateway Fallback fehlgeschlagen", e)
                ""
            }
        }

        /**
         * Stream starten. URL-Schema entscheidet die Pipeline:
         *   - http://...:8080/?action=stream  →  MjpegHttpPipeline (SGK GoPlus / Generalplus)
         *   - rtsp://...                      →  RtspMediaCodecPipeline (direkter MediaCodec)
         *   - leer                            →  Default für SGK: MJPEG auf Gateway:8080
         *
         * Hintergrund: Reverse-Engineering der Viidure-App v3.3.1 hat gezeigt dass
         * die SGK GK720X Live-Frames als MJPEG-over-HTTP auf Port 8080 liefert.
         * Der `/app/getmediainfo`-Hint mit RTSP auf 554 liefert nie IDR-Keyframes.
         */
        @JavascriptInterface
        fun startRtsp(url: String) {
            runOnUiThread {
                try {
                    // Bestehende Pipelines stoppen
                    rtspPipeline?.stop()
                    rtspPipeline = null
                    mjpegPipeline?.stop()
                    mjpegPipeline = null
                    try { cameraHelper?.closeCamera() } catch (_: Exception) {}
                    cameraOpened = false

                    val finalUrl = if (url.isNotBlank()) url else {
                        // Default: MJPEG auf Gateway:8080 (SGK-Standard)
                        val gw = getWifiGateway().ifBlank { "192.168.0.1" }
                        "http://$gw:8080/?action=stream"
                    }

                    val onJpeg: (ByteArray) -> Unit = { jpeg ->
                        frameCount++
                        lastFrameSize = jpeg.size
                        pushFrameToWebView(jpeg)
                    }
                    val onStatus: (String) -> Unit = { status -> AppLog.i(TAG, "Stream: $status") }

                    val isMjpeg = finalUrl.startsWith("http://", ignoreCase = true) ||
                                  finalUrl.startsWith("https://", ignoreCase = true)
                    if (isMjpeg) {
                        AppLog.i(TAG, "startStream (MJPEG): $finalUrl")
                        val p = MjpegHttpPipeline(applicationContext, onJpeg, onStatus)
                        mjpegPipeline = p
                        activeMode = "mjpeg"
                        p.start(finalUrl)
                    } else {
                        AppLog.i(TAG, "startStream (RTSP): $finalUrl — direkter MediaCodec-Pfad")
                        val p = RtspMediaCodecPipeline(applicationContext, onJpeg, onStatus)
                        // Mail-Socket-Nachrichten der Kamera an JS weiterleiten (REC-Status etc.)
                        p.onMailMessage = { msg ->
                            val escaped = msg
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.tpOnSgkMail && window.tpOnSgkMail('$escaped')",
                                    null
                                )
                            }
                        }
                        rtspPipeline = p
                        activeMode = "rtsp"
                        p.start(finalUrl)
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "startRtsp Exception", e)
                }
            }
        }

        /**
         * Scannt eine IP (oder Auto=Gateway) nach Kamera-Streams.
         * Ergebnis wird asynchron via `window.tpOnScanResult(json, done)` an JS zurückgegeben.
         * - `json` ist String mit JSONArray: [{url, port, proto, detail}, ...]
         * - `done=true` signalisiert Scan-Ende. Während des Laufs kommen mehrere Progress-Calls.
         */
        @JavascriptInterface
        fun scanForCameras(host: String) {
            scanJob?.cancel()
            val target = host.ifBlank { getWifiGateway() }
            if (target.isBlank()) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.tpOnScanResult && window.tpOnScanResult('[]', true, 'Keine IP — WLAN nicht verbunden?')",
                        null
                    )
                }
                return
            }
            scanJob = ioScope.launch {
                try {
                    val json = CameraScanner.scan(target) { progress ->
                        val escaped = progress.replace("'", "\\'")
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.tpOnScanResult && window.tpOnScanResult('[]', false, '$escaped')",
                                null
                            )
                        }
                    }
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.tpOnScanResult && window.tpOnScanResult('$escaped', true, 'Fertig')",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Scan Exception", e)
                    val msg = (e.message ?: "Fehler").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.tpOnScanResult && window.tpOnScanResult('[]', true, '$msg')",
                            null
                        )
                    }
                }
            }
        }

        /**
         * Setzt Belichtungszeit manuell fest (in ms).
         * Indoor-Kunstlicht: 10/20 ms (Vielfaches von 10ms bei 100Hz-Halbwellen) vermeidet
         * Flicker/Banding. UVC-Unit = 100µs → ms×10.
         */
        @JavascriptInterface
        fun setExposureTime(millis: Int) {
            val helper = cameraHelper ?: return
            runOnUiThread {
                try {
                    val uvc = helper.uvcControl
                    if (uvc == null) {
                        AppLog.w(TAG, "setExposureTime: UVC-Control nicht verfügbar")
                        return@runOnUiThread
                    }
                    // Auto-Auto-Lock (Flacker-Fix) übersteuern
                    autoModesDisabled = true
                    try { uvc.setAutoExposureMode(1) } catch (_: Exception) {}  // MANUAL
                    try { uvc.setExposureTimeAbsolute(millis * 10) } catch (e: Exception) {
                        AppLog.w(TAG, "setExposureTimeAbsolute fehlgeschlagen: ${e.message}")
                    }
                    try { uvc.setGain(uvc.gain) } catch (_: Exception) {}
                    AppLog.i(TAG, "UVC: Exposure manuell auf ${millis} ms (${millis * 10} UVC-Units)")
                } catch (e: Exception) {
                    AppLog.e(TAG, "setExposureTime Exception", e)
                }
            }
        }

        /** Belichtung zurück auf Auto-Modus. */
        @JavascriptInterface
        fun setExposureAuto() {
            val helper = cameraHelper ?: return
            runOnUiThread {
                try {
                    val uvc = helper.uvcControl ?: return@runOnUiThread
                    try { uvc.setAutoExposureMode(2) } catch (_: Exception) {}  // AUTO
                    autoModesDisabled = false  // Re-Enable Auto-Lock-Logik
                    AppLog.i(TAG, "UVC: Exposure zurück auf AUTO")
                } catch (e: Exception) {
                    AppLog.e(TAG, "setExposureAuto Exception", e)
                }
            }
        }

        /** Setzt UVC-Gain manuell (0–255 typisch). */
        @JavascriptInterface
        fun setUvcGain(value: Int) {
            val helper = cameraHelper ?: return
            runOnUiThread {
                try {
                    val uvc = helper.uvcControl ?: return@runOnUiThread
                    uvc.setGain(value)
                    AppLog.i(TAG, "UVC: Gain manuell auf $value")
                } catch (e: Exception) {
                    AppLog.e(TAG, "setUvcGain Exception", e)
                }
            }
        }

        // v2.3.119: UVC-Bild-Controls (Helligkeit, Kontrast, Sättigung, Schärfe, Zoom)
        // Ermöglicht dem Schützen Kamera-Anpassungen direkt aus dem Quick-Panel.

        /** Gibt UVC-Capabilities als JSON zurück — JS zeigt nur die vorhandenen Slider. */
        @JavascriptInterface
        fun getUvcImageControls(): String {
            val uvc = cameraHelper?.uvcControl
                ?: return "{\"available\":false}"
            val result = StringBuilder("{\"available\":true")
            fun tryRange(name: String, getter: () -> Int?, min: Int, max: Int) {
                try {
                    val cur = getter()
                    if (cur != null) result.append(",\"$name\":{\"min\":$min,\"max\":$max,\"cur\":$cur}")
                } catch (_: Exception) {}
            }
            tryRange("brightness", { uvc.brightness }, 0, 255)
            tryRange("contrast",   { uvc.contrast   }, 0, 255)
            tryRange("saturation", { uvc.saturation }, 0, 255)
            tryRange("sharpness",  { uvc.sharpness  }, 0, 255)
            tryRange("hue",        { uvc.hue        }, -180, 180)
            result.append("}")
            return result.toString()
        }

        /** Setzt einen UVC-Bild-Parameter (key: brightness|contrast|saturation|sharpness|hue). */
        @JavascriptInterface
        fun setUvcImageControl(key: String, value: Int) {
            val helper = cameraHelper ?: return
            runOnUiThread {
                try {
                    val uvc = helper.uvcControl ?: return@runOnUiThread
                    when (key) {
                        "brightness"  -> uvc.setBrightness(value)
                        "contrast"    -> uvc.setContrast(value)
                        "saturation"  -> uvc.setSaturation(value)
                        "sharpness"   -> uvc.setSharpness(value)
                        "hue"         -> uvc.setHue(value)
                        else -> AppLog.w(TAG, "setUvcImageControl: unbekannter Key '$key'")
                    }
                    AppLog.i(TAG, "UVC: $key = $value")
                } catch (e: Exception) {
                    AppLog.w(TAG, "setUvcImageControl($key=$value) fehlgeschlagen: ${e.message}")
                }
            }
        }

        /** Native TTS — verlässlicher als Web-Speech im WebView. */
        @JavascriptInterface
        fun speak(text: String) {
            if (text.isBlank()) return
            val engine = tts
            if (engine == null || !ttsReady) {
                AppLog.w(TAG, "TTS nicht bereit — '$text' übersprungen")
                return
            }
            try {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tp-${System.currentTimeMillis()}")
            } catch (e: Exception) {
                AppLog.e(TAG, "TTS speak Exception", e)
            }
        }

        @JavascriptInterface
        fun stopRtsp() {
            runOnUiThread {
                try {
                    rtspPipeline?.stop()
                    rtspPipeline = null
                    mjpegPipeline?.stop()
                    mjpegPipeline = null
                    if (activeMode == "rtsp" || activeMode == "mjpeg") activeMode = "none"
                    AppLog.i(TAG, "stopRtsp")
                } catch (e: Exception) { AppLog.e(TAG, "stopRtsp Exception", e) }
            }
        }

        /**
         * Speichert das letzte JPEG-Frame der aktiven RTSP-Pipeline in Downloads/TrefferPoint/.
         * Rückgabe: Pfad oder Fehler-String. Genutzt vom Snapshot-Button im UI.
         */
        @JavascriptInterface
        fun sgkSaveFrame(): String {
            // v2.3.118: lastUvcFrameJpeg als Fallback für USB-C-Kamera-Modus
            val jpeg = rtspPipeline?.lastFrameJpeg ?: mjpegPipeline?.lastFrameJpeg ?: lastUvcFrameJpeg
                ?: return "Fehler: kein Frame verfügbar"
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(java.util.Date())
            return saveBytesToDownloads("snapshot_$ts.jpg", jpeg, "image/jpeg")
        }

        /** Öffnet eine URL im System-Browser (für Update-Download etc.). */
        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "openUrl fehlgeschlagen: $url", e)
            }
        }

        /**
         * Vollscan: scannt alle 254 IPs im Subnetz der Gateway-IP.
         * Ergebnis asynchron via window.tpOnScanResult (gleicher Callback wie scanForCameras).
         * Nach Abschluss: Scan-Log automatisch in Downloads/TrefferPoint/ gespeichert.
         */
        @JavascriptInterface
        fun scanSubnet(host: String) {
            scanJob?.cancel()
            val target = host.ifBlank { getWifiGateway() }
            if (target.isBlank()) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.tpOnScanResult && window.tpOnScanResult('[]', true, 'Keine IP — WLAN nicht verbunden?')",
                        null
                    )
                }
                return
            }
            scanJob = ioScope.launch {
                try {
                    val json = CameraScanner.scanSubnet(target) { progress ->
                        val escaped = progress.replace("'", "\\'")
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.tpOnScanResult && window.tpOnScanResult('[]', false, '$escaped')",
                                null
                            )
                        }
                    }
                    // Scan-Log automatisch speichern
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                    val logJson = "{\"type\":\"scan_subnet\",\"timestamp\":\"$ts\",\"results\":$json,\"appLog\":${
                        org.json.JSONObject.quote(AppLog.snapshot())
                    }}"
                    saveToDownloads("scan_$ts.json", logJson)

                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.tpOnScanResult && window.tpOnScanResult('$escaped', true, 'Vollscan fertig')",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "scanSubnet Exception", e)
                    val msg = (e.message ?: "Fehler").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.tpOnScanResult && window.tpOnScanResult('[]', true, '$msg')",
                            null
                        )
                    }
                }
            }
        }

        /**
         * Speichert einen JSON-Testbericht nach Downloads/TrefferPoint/.
         * Gibt den Dateinamen zurück (Erfolg) oder "Fehler: ..." (Misserfolg).
         */
        @JavascriptInterface
        fun saveTestbericht(json: String): String {
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            return saveToDownloads("trefferpoint_$ts.json", json)
        }
    }

    private fun saveToDownloads(filename: String, content: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/TrefferPoint")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return "Fehler: URI null"
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val sub = java.io.File(dir, "TrefferPoint").also { it.mkdirs() }
                java.io.File(sub, filename).writeText(content)
            }
            AppLog.i(TAG, "Gespeichert: $filename")
            filename
        } catch (e: Exception) {
            AppLog.e(TAG, "saveToDownloads fehlgeschlagen: $filename", e)
            "Fehler: ${e.message}"
        }
    }

    /** Binäre Variante von saveToDownloads — für Snapshots (JPEG). */
    private fun saveBytesToDownloads(filename: String, bytes: ByteArray, mime: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/TrefferPoint")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return "Fehler: URI null"
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val sub = java.io.File(dir, "TrefferPoint").also { it.mkdirs() }
                java.io.File(sub, filename).writeBytes(bytes)
            }
            AppLog.i(TAG, "Gespeichert: $filename (${bytes.size}B)")
            filename
        } catch (e: Exception) {
            AppLog.e(TAG, "saveBytesToDownloads fehlgeschlagen: $filename", e)
            "Fehler: ${e.message}"
        }
    }

}
