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
    private lateinit var mjpegServer: MjpegServer

    private var cameraHelper: ICameraHelper? = null
    private var cameraOpened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // MJPEG-Server ZUERST starten — die WebView wird davon HTML UND Stream laden
        mjpegServer = MjpegServer(MJPEG_PORT, applicationContext)
        try {
            mjpegServer.start()
            Log.i(TAG, "MJPEG server bereit auf Port $MJPEG_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG server konnte nicht starten", e)
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
        // Alles von http://127.0.0.1:8888 → gleiches Origin wie /video → kein CORS-Block
        webView.loadUrl("http://127.0.0.1:$MJPEG_PORT/")

        UVCUtils.init(this)
        initCamera()
    }

    override fun onResume() {
        super.onResume()
        // Falls Kamera schon angesteckt war bevor die App gestartet wurde,
        // proaktiv erkennen und öffnen (Attach-Event ging ggf. an anderen Handler).
        attachAlreadyConnectedCamera()
    }

    private fun attachAlreadyConnectedCamera() {
        val helper = cameraHelper ?: return
        if (cameraOpened) return
        try {
            val devices = helper.deviceList
            Log.i(TAG, "USB-Geräte beim App-Start gefunden: ${devices?.size ?: 0}")
            devices?.forEach { d ->
                Log.i(TAG, "  - ${d.productName} VID=${d.vendorId} PID=${d.productId} class=${d.deviceClass}")
            }
            val uvcDevice = devices?.firstOrNull { isLikelyUvcCamera(it) }
            if (uvcDevice != null) {
                Log.i(TAG, "Starte bereits verbundene Kamera: ${uvcDevice.productName}")
                helper.selectDevice(uvcDevice)
            } else if (!devices.isNullOrEmpty()) {
                // Fallback: erstes Gerät probieren
                Log.i(TAG, "Keine eindeutige UVC — probiere erstes Gerät")
                helper.selectDevice(devices[0])
            }
        } catch (e: Exception) {
            Log.e(TAG, "attachAlreadyConnectedCamera fehlgeschlagen", e)
        }
    }

    /** Heuristik für UVC-Kameras (Video Interface Class). */
    private fun isLikelyUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == 239) return true // Miscellaneous mit IAD (typisch für UVC)
        if (device.deviceClass == 14) return true  // Direkt Video Class
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14) return true // Video-Interface
        }
        return false
    }

    private fun initCamera() {
        cameraHelper = CameraHelper().also { helper ->
            helper.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    Log.i(TAG, "USB attach: ${device?.productName} VID=${device?.vendorId} PID=${device?.productId}")
                    device?.let { helper.selectDevice(it) }
                }

                override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                    Log.i(TAG, "Device open: ${device?.productName} (first=$isFirstOpen)")
                    helper.openCamera(UVCParam())
                }

                override fun onCameraOpen(device: UsbDevice?) {
                    Log.i(TAG, "Camera open: ${device?.productName} — startPreview()")
                    cameraOpened = true
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
                    Log.i(TAG, "USB permission cancel: ${device?.productName}")
                }
            })

            helper.setFrameCallback(object : IFrameCallback {
                override fun onFrame(frame: ByteBuffer) {
                    try {
                        val size = helper.previewSize
                        val w = size?.width ?: PREVIEW_WIDTH
                        val h = size?.height ?: PREVIEW_HEIGHT
                        val bytes = ByteArray(frame.remaining())
                        frame.get(bytes)
                        val jpeg = if (isJpeg(bytes)) bytes else nv21ToJpeg(bytes, w, h)
                        mjpegServer.pushFrame(jpeg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame-Verarbeitung fehlgeschlagen", e)
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
