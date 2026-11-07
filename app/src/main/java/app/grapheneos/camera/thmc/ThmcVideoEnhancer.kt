package app.grapheneos.camera.thmc

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Видео-улучшение GPU-транскодом: декодер → GL-шейдер (P0-лайт: тон/контраст/
 * насыщенность/тепло из рецепта фото) → энкодер → MP4. Аудио — passthrough
 * без перекодирования отдельным проходом.
 *
 * Почему не CPU-покадрово: P0-цепочка на CPU это ~1-2с на кадр 12Мп —
 * 3-минутный ролик шёл бы часами. Шейдер делает то же на GPU в реалтайме.
 * Точность сознательно упрощена (контраст вокруг 0.5, а не адаптивной средней):
 * для видео стабильность важнее попиксельной точности.
 *
 * Возвращает true при успехе. Любая проблема — false, вызыватель чистит хвост.
 * Только с фонового потока (блокирует на IO/кодеках).
 */
object ThmcVideoEnhancer {
    private const val TAG = "ThmcVideo"
    private const val MIME_AVC = "video/avc"
    private const val TIMEOUT_US = 10_000L

    fun enhance(
        ctx: Context,
        srcUri: Uri,
        dstFile: File,
        grade: ThmcVideo.Grade,
        onProgress: (Int) -> Unit,
        cancelled: AtomicBoolean,
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglCore? = null
        var outSurface: WindowSurface? = null
        var codecOut: CodecOutputSurface? = null
        var ok = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(ctx, srcUri, null)
            var videoIdx = -1
            var audioIdx = -1
            var vFormat: MediaFormat? = null
            var aFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoIdx < 0) {
                    videoIdx = i
                    vFormat = f
                } else if (mime.startsWith("audio/") && audioIdx < 0) {
                    audioIdx = i
                    aFormat = f
                }
            }
            if (videoIdx < 0 || vFormat == null) {
                Log.w(TAG, "no video track")
                return false
            }
            val w = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val h = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
            if (w < 16 || h < 16 || w > 8192 || h > 8192) {
                Log.w(TAG, "bad size ${w}x$h")
                return false
            }
            val durationUs = try {
                vFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Throwable) { 0L }
            val frameRate = try {
                vFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (_: Throwable) { 30 }
            val inBitrate = try {
                vFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } catch (_: Throwable) { 0 }
            val bitrate = maxOf(inBitrate, (w * h * 30 * 0.15).toInt())

            // Декодер → GL-текстура. Порядок важен: GL-объекты (шейдер, текстура)
            // создавать только при текущем EGL-контексте, иначе glGenTextures/
            // glCreateProgram работают без контекста и молча дают 0 → черный кадр/креш.
            egl = EglCore()

            // Энкодер + его входная поверхность как EGL-окно — первой, делаем текущей.
            val eFormat = MediaFormat.createVideoFormat(MIME_AVC, w, h)
            eFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            eFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(15, 120))
            eFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            eFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            encoder = MediaCodec.createEncoderByType(MIME_AVC)
            encoder.configure(eFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encInput = encoder.createInputSurface()
            outSurface = WindowSurface(egl, encInput)
            outSurface.makeCurrent()
            val render = P0TextureRender(grade)
            render.viewportW = w
            render.viewportH = h
            codecOut = CodecOutputSurface(w, h)
            decoder = MediaCodec.createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(vFormat, codecOut.surface, null, 0)
            decoder.start()
            encoder.start()

            muxer = MediaMuxer(dstFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val retr = MediaMetadataRetriever()
                try {
                    retr.setDataSource(ctx, srcUri)
                    val rot = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    muxer.setOrientationHint(rot)
                } finally {
                    try { retr.release() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            extractor.selectTrack(videoIdx)
            val bufInfo = MediaCodec.BufferInfo()
            var videoTrackOut = -1
            var audioTrackOut = -1
            var muxerStarted = false
            var decoderEos = false
            var encoderEos = false
            var lastPct = -1

            fun drainEncoder(end: Boolean) {
                while (true) {
                    val st = encoder!!.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    if (st == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) break
                    } else if (st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw RuntimeException("format changed twice")
                        videoTrackOut = muxer!!.addTrack(encoder.outputFormat)
                        // Аудио-трек добавляем ДО muxer.start(): addTrack после start
                        // кидает IllegalStateException и все аудио молча пропадало.
                        if (audioIdx >= 0 && aFormat != null) {
                            try {
                                audioTrackOut = muxer!!.addTrack(aFormat)
                            } catch (t: Throwable) {
                                Log.w(TAG, "audio track rejected, video-only", t)
                                audioTrackOut = -1
                            }
                        }
                        muxer.start()
                        muxerStarted = true
                    } else if (st >= 0) {
                        val enc = encoder.getOutputBuffer(st)
                        if (bufInfo.size > 0 && muxerStarted) {
                            enc!!.position(bufInfo.offset)
                            enc.limit(bufInfo.offset + bufInfo.size)
                            muxer!!.writeSampleData(videoTrackOut, enc, bufInfo)
                        }
                        encoder.releaseOutputBuffer(st, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderEos = true
                            break
                        }
                    }
                }
            }

            while (!encoderEos && !cancelled.get()) {
                // Вход декодера.
                if (!decoderEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                // Выход декодера → GL → энкодер.
                var outIdx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                while (outIdx >= 0 && !cancelled.get()) {
                    val doRender = bufInfo.size > 0
                    decoder.releaseOutputBuffer(outIdx, doRender)
                    if (doRender) {
                        codecOut.awaitNewImage()
                        codecOut.drawImage(render)
                        outSurface.setPresentationTime(bufInfo.presentationTimeUs * 1000)
                        outSurface.swapBuffers()
                        if (durationUs > 0) {
                            val pct = ((bufInfo.presentationTimeUs * 100 / durationUs).toInt()).coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                try { onProgress(pct) } catch (_: Throwable) {}
                            }
                        }
                    }
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderEos = true
                        encoder.signalEndOfInputStream()
                    }
                    drainEncoder(false)
                    if (encoderEos) break
                    outIdx = decoder.dequeueOutputBuffer(bufInfo, 0)
                }
                drainEncoder(false)
            }

            // Добить энкодер до EOS (если не отмена).
            if (!cancelled.get()) {
                var guard = 0
                while (!encoderEos && guard++ < 600) drainEncoder(true)
            }
            try { onProgress(100) } catch (_: Throwable) {}

            // Аудио отдельным проходом: трек уже добавлен до muxer.start(),
            // здесь только льем сэмплы (раньше addTrack после start кидал и резал звук).
            if (!cancelled.get() && audioIdx >= 0 && audioTrackOut >= 0 && muxerStarted) {
                try {
                    val ex2 = MediaExtractor()
                    try {
                        ex2.setDataSource(ctx, srcUri, null)
                        ex2.selectTrack(audioIdx)
                        val ab = ByteBuffer.allocate(256 * 1024)
                        val ai = MediaCodec.BufferInfo()
                        while (!cancelled.get()) {
                            ai.offset = 0
                            ai.size = ex2.readSampleData(ab, 0)
                            if (ai.size < 0) break
                            ai.presentationTimeUs = ex2.sampleTime
                            ai.flags = ex2.sampleFlags
                            ab.position(0)
                            ab.limit(ai.size)
                            muxer.writeSampleData(audioTrackOut, ab, ai)
                            ex2.advance()
                        }
                    } finally {
                        try { ex2.release() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    // Аудио не влезло (старый мультиплексор) — видео уже цело, идём без звука.
                    Log.w(TAG, "audio passthrough skipped", t)
                }
            }

            ok = !cancelled.get() && muxerStarted
        } catch (t: Throwable) {
            Log.w(TAG, "enhance failed", t)
            ok = false
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            try {
                if (muxer != null) {
                    try { muxer.stop() } catch (_: Throwable) {}
                    try { muxer.release() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            try { outSurface?.release() } catch (_: Throwable) {}
            try { codecOut?.release() } catch (_: Throwable) {}
            try { egl?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
        if (!ok) {
            try { dstFile.delete() } catch (_: Throwable) {}
        }
        return ok
    }

    // ---------- EGL/GL-инфраструктура (компактный Grafika-стиль) ----------

    private class EglCore {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: EGLContext
        val config: EGLConfig

        init {
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] <= 0) {
                throw RuntimeException("eglChooseConfig failed")
            }
            config = configs[0]!!
            val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        }

        fun release() {
            try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
            try { EGL14.eglDestroyContext(display, context) } catch (_: Throwable) {}
            try { EGL14.eglTerminate(display) } catch (_: Throwable) {}
        }
    }

    private class WindowSurface(private val egl: EglCore, surface: Any) {
        private val eglSurface: EGLSurface = EGL14.eglCreateWindowSurface(
            egl.display, egl.config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )

        init {
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            makeCurrent()
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(egl.display, eglSurface, eglSurface, egl.context)
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(egl.display, eglSurface)

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(egl.display, eglSurface, nsecs)
        }

        fun release() {
            try { EGL14.eglDestroySurface(egl.display, eglSurface) } catch (_: Throwable) {}
        }
    }

    /** Выход декодера: SurfaceTexture + ожидание кадра. Рендер делает вызыватель. */
    private class CodecOutputSurface(width: Int, height: Int) {
        private val textureId: Int
        val surfaceTexture: SurfaceTexture
        val surface: Surface
        val texId: Int get() = textureId
        private val lock = Object()
        @Volatile private var frameAvailable = false

        init {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(width, height)
            surfaceTexture.setOnFrameAvailableListener({ frameAvailable = true; synchronized(lock) { lock.notifyAll() } }, android.os.Handler(android.os.Looper.getMainLooper()))
            surface = Surface(surfaceTexture)
        }

        fun awaitNewImage() {
            synchronized(lock) {
                var guard = 0
                while (!frameAvailable && guard++ < 100) {
                    try { lock.wait(100) } catch (_: InterruptedException) { break }
                }
                if (!frameAvailable) throw RuntimeException("frame wait timeout")
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun drawImage(render: P0TextureRender) {
            render.drawFrame(textureId, surfaceTexture)
        }

        fun release() {
            try { surface.release() } catch (_: Throwable) {}
            try { surfaceTexture.release() } catch (_: Throwable) {}
        }
    }

    private object GLES11Ext {
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }

    /** P0-лайт шейдер: тот же тон/контраст/насыщенность/тепло, что нативный P0. */
    private class P0TextureRender(private val g: ThmcVideo.Grade) {
        private val program: Int
        private val aPosition: Int
        private val aTextureCoord: Int
        private val uMVPMatrix: Int
        private val uSTMatrix: Int
        private val uTexture: Int
        private val uLift: Int
        private val uPull: Int
        private val uContrast: Int
        private val uSat: Int
        private val uR: Int
        private val uG: Int
        private val uB: Int
        private val uBias: Int
        private val uStrength: Int
        private val quad: java.nio.FloatBuffer

        private val vertexShader = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uLift;
            uniform float uPull;
            uniform float uContrast;
            uniform float uSat;
            uniform float uR;
            uniform float uG;
            uniform float uB;
            uniform float uBias;
            uniform float uStrength;
            void main() {
              vec3 orig = texture2D(sTexture, vTextureCoord).rgb;
              float lum = dot(orig, vec3(0.299, 0.587, 0.114));
              float gain = 1.0;
              if (lum > 0.002) {
                float x = lum;
                float shW = (1.0 - x) * (1.0 - x);
                float hiW = x * x;
                float xNew = x + uLift * shW - uPull * hiW * (1.0 - x);
                gain = clamp((xNew * 1.0) / lum, 0.75, 2.2);
              }
              vec3 c = orig * gain;
              c = (c - 0.5) * uContrast + 0.5;
              float gray = dot(c, vec3(0.299, 0.587, 0.114));
              c = mix(vec3(gray), c, uSat);
              c *= vec3(uR, uG, uB);
              c.b += uBias;
              c = clamp(mix(orig, c, uStrength), 0.0, 1.0);
              gl_FragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        init {
            program = createProgram(vertexShader, fragmentShader)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrix = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uTexture = GLES20.glGetUniformLocation(program, "sTexture")
            uLift = GLES20.glGetUniformLocation(program, "uLift")
            uPull = GLES20.glGetUniformLocation(program, "uPull")
            uContrast = GLES20.glGetUniformLocation(program, "uContrast")
            uSat = GLES20.glGetUniformLocation(program, "uSat")
            uR = GLES20.glGetUniformLocation(program, "uR")
            uG = GLES20.glGetUniformLocation(program, "uG")
            uB = GLES20.glGetUniformLocation(program, "uB")
            uBias = GLES20.glGetUniformLocation(program, "uBias")
            uStrength = GLES20.glGetUniformLocation(program, "uStrength")
            val verts = floatArrayOf(
                -1f, -1f, 0f, 0f, 0f,
                1f, -1f, 0f, 1f, 0f,
                -1f, 1f, 0f, 0f, 1f,
                1f, 1f, 0f, 1f, 1f
            )
            quad = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(verts).position(0)
        }

        fun drawFrame(texId: Int, st: SurfaceTexture) {
            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, viewportW, viewportH)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, quad)
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(3)
            GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 20, quad)
            GLES20.glEnableVertexAttribArray(aTextureCoord)
            val mvp = FloatArray(16)
            android.opengl.Matrix.setIdentityM(mvp, 0)
            GLES20.glUniformMatrix4fv(uMVPMatrix, 1, false, mvp, 0)
            val stMat = FloatArray(16)
            st.getTransformMatrix(stMat)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, stMat, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniform1f(uLift, g.lift)
            GLES20.glUniform1f(uPull, g.pull)
            GLES20.glUniform1f(uContrast, g.contrast)
            GLES20.glUniform1f(uSat, g.sat)
            GLES20.glUniform1f(uR, g.rGain)
            GLES20.glUniform1f(uG, g.gGain)
            GLES20.glUniform1f(uB, g.bGain)
            GLES20.glUniform1f(uBias, g.bBias)
            GLES20.glUniform1f(uStrength, g.strength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTextureCoord)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        @Volatile var viewportW: Int = 0
        @Volatile var viewportH: Int = 0

        private fun loadShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("ThmcVideo", "shader: " + GLES20.glGetShaderInfoLog(sh))
                GLES20.glDeleteShader(sh)
                throw RuntimeException("shader compile failed")
            }
            return sh
        }

        private fun createProgram(vs: String, fs: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                Log.e("ThmcVideo", "link: " + GLES20.glGetProgramInfoLog(p))
                throw RuntimeException("program link failed")
            }
            return p
        }
    }
}
