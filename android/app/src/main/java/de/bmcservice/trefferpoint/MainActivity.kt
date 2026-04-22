package de.bmcservice.trefferpoint

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.utils.bus.BusKey
import com.jiangdg.ausbc.utils.bus.EventBus
import com.jiangdg.usb.USBMonitor
import java.io.ByteArrayOutputStream

/**
 * TrefferPoint Android App
 *
 * Architektur:
 *   USB-C Kamera (UVC) → AUSBC Library → JPEG-Frames
 *                                          ↓
 *   WebView ← http://127.0.0.1:8888/video ← MjpegServer (NanoHTTPD)
 *
 * Die index.html aus assets/trefferpoint/ wird im WebView geladen.
 * TrefferPoint verbindet sich im Stream-Modus mit localhost:8888 — genau so
 * wie vorher mit USB Dual Cam, nur eben lokal und ohne dritte App.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrefferPoint"
        private const val MJPEG_PORT = 8888
    }

    private lateinit var webView: WebView
    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: CameraUVC? = null
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

        // TrefferPoint HTML aus Assets laden
        webView.loadUrl("file:///android_asset/trefferpoint/index.html")

        // MJPEG-Server starten (localhost:8888)
        try {
            mjpegServer.start()
            Log.i(TAG, "MJPEG server gestartet auf Port $MJPEG_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG server konnte nicht starten", e)
        }

        // USB-Kamera initialisieren
        initCamera()
    }

    private fun initCamera() {
        cameraClient = MultiCameraClient(this, object : MultiCameraClient.IUsbConnectListener {
            override fun onAttachDev(device: android.hardware.usb.UsbDevice?) {
                Log.i(TAG, "USB Device attached: ${device?.productName}")
                device?.let {
                    cameraClient?.requestPermission(it)
                }
            }

            override fun onDetachDec(device: android.hardware.usb.UsbDevice?) {
                Log.i(TAG, "USB Device detached: ${device?.productName}")
                currentCamera?.closeCamera()
                currentCamera = null
            }

            override fun onConnectDev(
                device: android.hardware.usb.UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                device ?: return
                ctrlBlock ?: return
                Log.i(TAG, "USB Device connected: ${device.productName}")
                openCamera(device, ctrlBlock)
            }

            override fun onDisConnectDec(
                device: android.hardware.usb.UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                Log.i(TAG, "USB Device disconnected")
                currentCamera?.closeCamera()
                currentCamera = null
            }

            override fun onCancelDev(device: android.hardware.usb.UsbDevice?) {
                Log.i(TAG, "USB permission denied")
            }
        })
        cameraClient?.register()
    }

    private fun openCamera(
        device: android.hardware.usb.UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock
    ) {
        val camera = CameraUVC(this, device)
        currentCamera = camera

        val request = CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .create()

        camera.openCamera(null, request)
        camera.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?
            ) {
                Log.i(TAG, "Camera state: $code — $msg")
                if (code == ICameraStateCallBack.State.OPENED) {
                    startFrameCapture(camera)
                }
            }
        })
    }

    private fun startFrameCapture(camera: CameraUVC) {
        camera.addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(
                data: ByteArray?,
                width: Int,
                height: Int,
                format: IPreviewDataCallBack.DataFormat
            ) {
                data ?: return
                // NV21 → JPEG → in MJPEG-Server pushen
                val jpeg = nv21ToJpeg(data, width, height)
                mjpegServer.pushFrame(jpeg)
            }
        })
    }

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuv = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, width, height, null
        )
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
        return out.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentCamera?.closeCamera()
        cameraClient?.unRegister()
        cameraClient?.destroy()
        mjpegServer.stop()
    }
}
