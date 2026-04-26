package de.bmcservice.trefferpoint

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Phase 2 Pipeline: ExoPlayer rendert in SurfaceTexture (OES-Texture),
 * GL-Renderer zieht jeden Frame in einen Off-Screen-FBO 960×540 und liest mit
 * glReadPixels heraus → JPEG → Callback.
 *
 *   ExoPlayer ──setVideoSurface──> Surface(SurfaceTexture)
 *                                      │  (HW-Decoder schreibt OES-Texture-Inhalt)
 *                                      ▼
 *   onFrameAvailable ──requestRender──> onDrawFrame:
 *      1. updateTexImage()                         (SurfaceTexture → OES-Texture)
 *      2. bindFramebuffer(FBO 960×540)
 *      3. drawArrays(QUAD, OES-Sampler)            (Render in FBO)
 *      4. glReadPixels(FBO)                        (RGBA → ByteBuffer)
 *      5. Bitmap.copyPixelsFromBuffer + JPEG
 *      6. onJpegFrame(jpeg)
 *
 * Vorteil gegenüber PixelCopy: Liest exakt den Decoder-Output direkt aus der GL-Pipeline.
 * Kein Hardware-Overlay-Problem — wir lesen aus unserem eigenen FBO, nicht aus dem
 * Compositor. HW-Decoder bleibt aktiv, kein IDR-Restart-Trick mehr nötig.
 */
class RtspGlRenderer(
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onStatusLog: (String) -> Unit,
    private val captureWidth: Int = 960,
    private val captureHeight: Int = 540
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "RtspGlRenderer"
        private const val JPEG_QUALITY = 85
    }

    @Volatile var surface: Surface? = null; private set
    @Volatile var surfaceTexture: SurfaceTexture? = null; private set
    @Volatile var lastFrameJpeg: ByteArray? = null; private set
    @Volatile var frameCount: Long = 0; private set
    @Volatile var ready: Boolean = false; private set
    @Volatile var debugColorMode: Boolean = true
    @Volatile private var frameAvailable: Boolean = false

    private var glView: GLSurfaceView? = null

    private var program = 0
    private var oesTexId = 0
    private var fboId = 0
    private var fboTexId = 0

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uStMatrixLoc = 0
    private var uTextureLoc = 0

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var pixelBuffer: ByteBuffer? = null
    private var displayWidth = captureWidth
    private var displayHeight = captureHeight

    // JPEG-Encoder läuft auf separatem Thread, damit der GL-Thread nicht blockiert.
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    @Volatile private var encodePending = false
    private var debugColorLogged = false

    // -1..1 Quad — beim Render in OpenGL ist Y nach oben positiv,
    // bei JPEG/Bitmap ist Y nach unten. SurfaceTexture transform-matrix
    // korrigiert das normalerweise. Wir vertrauen auf st.getTransformMatrix().
    private val vertexCoords = floatArrayOf(
        -1f, -1f, 0f, 1f,
         1f, -1f, 0f, 1f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 0f, 1f
    )
    private val texCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private val vsSrc = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val fragShaderNormalSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val fragShaderDebugSrc = """
        precision mediump float;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = vec4(vTexCoord.x, vTexCoord.y, 0.5, 1.0);
        }
    """.trimIndent()

    fun attachToView(view: GLSurfaceView) {
        glView = view
    }

    fun isVideoSurfaceReady(): Boolean =
        ready && surfaceTexture != null && (surface?.isValid == true)

    fun shutdown() {
        try { encoderThread?.quitSafely() } catch (_: Exception) {}
        encoderThread = null
        encoderHandler = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        ready = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Vertex/TexCoord-Buffer befüllen
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertexCoords); position(0)
            }
        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texCoords); position(0)
            }

        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        if (!debugColorMode && !extensions.contains("GL_OES_EGL_image_external")) {
            AppLog.e(TAG, "GL_OES_EGL_image_external fehlt im GL-Context")
            onStatusLog("GL-Setup fehlgeschlagen: OES-Extension fehlt")
            ready = false
            return
        }

        // Shader-Programm
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fragmentShaderSrc = if (debugColorMode) fragShaderDebugSrc else fragShaderNormalSrc
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            AppLog.e(TAG, "Shader-Link fehlgeschlagen: ${GLES20.glGetProgramInfoLog(program)}")
            return
        }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        // OES-Texture für SurfaceTexture-Input vom Decoder
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // FBO + Color-Texture für Off-Screen-Capture (immer 960×540)
        val fbo = IntArray(1); GLES20.glGenFramebuffers(1, fbo, 0); fboId = fbo[0]
        val ftex = IntArray(1); GLES20.glGenTextures(1, ftex, 0); fboTexId = ftex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            captureWidth, captureHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTexId, 0)
        val fboStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (fboStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            AppLog.e(TAG, "FBO incomplete: status=$fboStatus")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // SurfaceTexture + Surface für ExoPlayer
        val st = SurfaceTexture(oesTexId)
        st.setDefaultBufferSize(captureWidth, captureHeight)
        st.setOnFrameAvailableListener(this, Handler(Looper.getMainLooper()))
        surfaceTexture = st
        surface = Surface(st)
        frameAvailable = false

        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(stMatrix, 0)

        pixelBuffer = ByteBuffer.allocateDirect(captureWidth * captureHeight * 4)
            .order(ByteOrder.nativeOrder())

        // JPEG-Encoder-Thread
        encoderThread = HandlerThread("RtspGlEncoder").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        ready = true
        debugColorLogged = false
        onStatusLog("GL-Setup OK: oesTex=$oesTexId fbo=$fboId surface=${surface != null}")
        AppLog.i(TAG, "GL-Setup OK: oesTex=$oesTexId fbo=$fboId fboTex=$fboTexId surface=${surface != null} ${captureWidth}x${captureHeight}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        AppLog.i(TAG, "onSurfaceChanged: display=${width}x${height}")
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
        glView?.requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (!ready) return
        if (!frameAvailable) {
            GLES20.glViewport(0, 0, displayWidth, displayHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        frameAvailable = false
        if (debugColorMode && !debugColorLogged) {
            debugColorLogged = true
            AppLog.i(TAG, "DEBUG-COLOR aktiv — Test-Pattern wird gerendert (kein OES-Sampler)")
        }

        // Neuen Frame in OES-Texture laden
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (e: Exception) {
            return
        }

        // ─── 1. Render in FBO 960×540 ─────────────────────────────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, captureWidth, captureHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad()

        // glReadPixels: synchroner Read aus FBO. ~3-5ms auf Adreno 6xx für 960×540×4.
        val pb = pixelBuffer ?: run {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }
        pb.position(0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, captureWidth, captureHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pb)
        val readError = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (readError != GLES20.GL_NO_ERROR) {
            AppLog.w(TAG, "glReadPixels Fehler: 0x${readError.toString(16)}")
            return
        }

        // ─── 2. JPEG-Encoding off-Thread (drop frame falls Encoder noch beschäftigt) ─
        if (!encodePending) {
            encodePending = true
            // Snapshot des ByteBuffer (defensive Kopie, weil pb in onDrawFrame wiederverwendet wird)
            pb.position(0)
            val copy = ByteArray(captureWidth * captureHeight * 4)
            pb.get(copy)
            encoderHandler?.post {
                try {
                    val bmp = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(copy))
                    val out = ByteArrayOutputStream(captureWidth * captureHeight / 4)
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    val jpeg = out.toByteArray()
                    bmp.recycle()
                    lastFrameJpeg = jpeg
                    frameCount++
                    if (frameCount == 1L || frameCount % 30L == 0L) {
                        AppLog.i(TAG, "GL-Frame #$frameCount: ${jpeg.size}B JPEG (FBO+glReadPixels)")
                    }
                    onJpegFrame(jpeg)
                } catch (e: Exception) {
                    AppLog.w(TAG, "JPEG-Encode Fehler: ${e.message}")
                } finally {
                    encodePending = false
                }
            }
        }

        // ─── 3. Optional: Display rendern (Debug) ────────────────────────────────
        // WebView überdeckt GLSurfaceView mit opakem Hintergrund — Display-Render nicht
        // sichtbar. Aber GLSurfaceView swapt eh einen Frame, also entweder schwarz lassen
        // oder denselben Frame zeichnen. Schwarz reicht.
        GLES20.glViewport(0, 0, displayWidth, displayHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun drawQuad() {
        GLES20.glUseProgram(program)

        // OES-Texture binden
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uTextureLoc, 0)

        // Vertex-Attribute
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 4, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        texBuffer.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        // Matrizen
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            AppLog.e(TAG, "Shader-Compile Fehler ($type): ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }
}
