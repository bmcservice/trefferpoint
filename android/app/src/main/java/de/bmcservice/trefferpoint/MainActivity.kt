package de.bmcservice.trefferpoint

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCParam

/**
 * TrefferPoint Android App — Schritt 2 (UVC-Kamera-Anbindung)
 *
 *   USB-C Kamera (UVC) → CameraHelper (shiyinghan/UVCAndroid)
 *        → setFrameCallback → NV21/YUYV Frames
 *        → JPEG-Konvertierung
 *        → MjpegServer (127.0.0.1:8888)
 *        → WebView → TrefferPoint Stream-Modus
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrefferPoint"
        private const val MJPEG_PORT = 8888
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private lateinit var webView: WebView
    private val mjpegServer = MjpegServer(MJPEG_PORT)

    private var cameraHelper: ICameraHelper? = null
    private var cameraOpened = false

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

        try {
            mjpegServer.start()
            Log.i(TAG, "MJPEG server bereit auf Port $MJPEG_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG server konnte nicht starten", e)
        }

        initCamera()
    }

    private fun initCamera() {
        cameraHelper = CameraHelper().also { helper ->
            helper.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    Log.i(TAG, "USB attach: ${device?.productName}  VID=${device?.vendorId} PID=${device?.productId}")
                    // Automatisch öffnen sobald Kamera angesteckt wird
                    device?.let { helper.selectDevice(it) }
                }

                override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                    Log.i(TAG, "Device open: ${device?.productName}")
                    val param = UVCParam()
                    helper.openCamera(param)
                }

                override fun onCameraOpen(device: UsbDevice?) {
                    Log.i(TAG, "Camera open: ${device?.productName}")
                    cameraOpened = true
                    // Preview starten → Frame-Callback liefert NV21
                    helper.startPreview()
                }

                override fun onCameraClose(device: UsbDevice?) {
                    Log.i(TAG, "Camera close")
                    cameraOpened = false
                }

                override fun onDeviceClose(device: UsbDevice?) {
                    Log.i(TAG, "Device close")
                }

                override fun onDetach(device: UsbDevice?) {
                    Log.i(TAG, "USB detach")
                    cameraOpened = false
                }

                override fun onCancel(device: UsbDevice?) {
                    Log.i(TAG, "USB permission cancel")
                }
            })

            // Frame-Callback: pushen als JPEG in MJPEG-Server
            helper.addFrameCallback({ frame ->
                // frame ist ByteBuffer mit NV21-Daten (je nach UVCParam Format)
                // Für MJPEG direkt: falls Format MJPEG, schon JPEG — sonst konvertieren
                try {
                    val bytes = ByteArray(frame.remaining())
                    frame.get(bytes)
                    // Falls bereits JPEG (MJPEG-Format) → direkt pushen
                    // Sonst NV21 → JPEG konvertieren
                    val jpeg = if (isJpeg(bytes)) bytes else nv21ToJpeg(bytes, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    mjpegServer.pushFrame(jpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame-Verarbeitung fehlgeschlagen", e)
                }
            }, com.serenegiant.usb.IFrameCallback.PIXEL_FORMAT_NV21)
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
