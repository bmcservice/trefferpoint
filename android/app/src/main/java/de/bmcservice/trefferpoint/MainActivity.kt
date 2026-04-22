package de.bmcservice.trefferpoint

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
 * TrefferPoint Android App
 *
 *   USB-C Kamera (UVC) → CameraHelper (shiyinghan/UVCAndroid)
 *        → setFrameCallback → NV21 Frames
 *        → JPEG-Konvertierung
 *        → MjpegServer auf 127.0.0.1:8888 (HTML + Stream, same origin)
 *        → WebView zeigt TrefferPoint von localhost
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrefferPoint"
        private const val MJPEG_PORT = 8888
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private lateinit var webView: WebView
    private lateinit var mjpegServer: MjpegServer

    private var cameraHelper: ICameraHelper? = null
    private var cameraOpened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        AppLog.i(TAG, "=== onCreate — TrefferPoint startet ===")
        AppLog.i(TAG, "Launch intent: ${intent?.action}")

        // MJPEG-Server ZUERST — die WebView lädt HTML UND Stream davon
        mjpegServer = MjpegServer(MJPEG_PORT, applicationContext)
        try {
            mjpegServer.start()
            AppLog.i(TAG, "MJPEG server läuft auf 127.0.0.1:$MJPEG_PORT")
        } catch (e: Exception) {
            AppLog.e(TAG, "MJPEG server konnte nicht starten", e)
        }

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
        webView.loadUrl("http://127.0.0.1:$MJPEG_PORT/")

        UVCUtils.init(this)
        initCamera()

        // Kamera aus Launch-Intent abgreifen (falls via USB_DEVICE_ATTACHED gestartet)
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i(TAG, "onNewIntent: ${intent.action}")
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Fallback: auch ohne Intent proaktiv nach Geräten suchen
        attachAlreadyConnectedCamera()
    }

    /**
     * Wenn Android die App via USB_DEVICE_ATTACHED startet, liegt das UsbDevice
     * im Intent. Direkt an CameraHelper übergeben — schneller als auf onAttach-Event warten.
     */
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
        try {
            cameraHelper?.selectDevice(device)
        } catch (e: Exception) {
            AppLog.e(TAG, "selectDevice via Intent fehlgeschlagen", e)
        }
    }

    private fun attachAlreadyConnectedCamera() {
        val helper = cameraHelper ?: return
        if (cameraOpened) return
        try {
            val devices = helper.deviceList
            AppLog.i(TAG, "onResume enumerate — ${devices?.size ?: 0} USB-Device(s) sichtbar")
            devices?.forEachIndexed { i, d ->
                AppLog.i(TAG, "  [$i] ${d.productName} VID=${d.vendorId} PID=${d.productId} class=${d.deviceClass} iface=${d.interfaceCount}")
            }
            val uvcDevice = devices?.firstOrNull { isLikelyUvcCamera(it) }
            if (uvcDevice != null) {
                AppLog.i(TAG, "Starte bereits verbundene UVC-Kamera: ${uvcDevice.productName}")
                helper.selectDevice(uvcDevice)
            } else if (!devices.isNullOrEmpty()) {
                AppLog.i(TAG, "Keine UVC-Heuristik getroffen — probiere erstes Gerät: ${devices[0].productName}")
                helper.selectDevice(devices[0])
            } else {
                AppLog.w(TAG, "Keine USB-Geräte sichtbar. Kamera nicht angesteckt oder von anderer App beansprucht?")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "attachAlreadyConnectedCamera fehlgeschlagen", e)
        }
    }

    private fun isLikelyUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == 239) return true
        if (device.deviceClass == 14) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14) return true
        }
        return false
    }

    private fun initCamera() {
        cameraHelper = CameraHelper().also { helper ->
            helper.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    AppLog.i(TAG, "→ onAttach: ${device?.productName} VID=${device?.vendorId} PID=${device?.productId}")
                    device?.let { helper.selectDevice(it) }
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
                    AppLog.i(TAG, "→ onCameraOpen: ${device?.productName} — Size=${size?.width}x${size?.height} — startPreview()")
                    cameraOpened = true
                    try {
                        helper.startPreview()
                    } catch (e: Exception) {
                        AppLog.e(TAG, "startPreview() Exception", e)
                    }
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
                }

                override fun onCancel(device: UsbDevice?) {
                    AppLog.w(TAG, "→ onCancel (USB-Permission vom User abgelehnt?) ${device?.productName}")
                }
            })

            helper.setFrameCallback(object : IFrameCallback {
                private var logged = false
                override fun onFrame(frame: ByteBuffer) {
                    try {
                        val size = helper.previewSize
                        val w = size?.width ?: PREVIEW_WIDTH
                        val h = size?.height ?: PREVIEW_HEIGHT
                        val bytes = ByteArray(frame.remaining())
                        frame.get(bytes)
                        val jpeg = if (isJpeg(bytes)) bytes else nv21ToJpeg(bytes, w, h)
                        if (!logged) {
                            AppLog.i(TAG, "Erster Frame empfangen: ${bytes.size}B roh → ${jpeg.size}B JPEG @ ${w}x$h")
                            logged = true
                        }
                        mjpegServer.pushFrame(jpeg)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Frame-Verarbeitung fehlgeschlagen", e)
                    }
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)
        }
    }

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
        try { cameraHelper?.closeCamera() } catch (_: Exception) {}
        try { cameraHelper?.release() } catch (_: Exception) {}
        mjpegServer.stop()
    }
}
