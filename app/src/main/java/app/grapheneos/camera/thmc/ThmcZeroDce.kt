package app.grapheneos.camera.thmc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ThunderCam ИИ-свет: Zero-DCE (Apache-2.0, zerodce_512.tflite, 336КБ).
 * Сеть считает на проксе 512×512, карта усиления апскейлится билинейно и умножается
 * на полный кадр — детали сохраняются на 100% (умножение, не апскейл картинки).
 * GPU-делегат первым, фолбэк CPU×4. Ленивая инициализация (не трогаем старт приложения).
 */
object ThmcZeroDce {
    private const val TAG = "ThmcZeroDce"
    private const val MODEL = "zerodce_512.tflite"
    private const val PS = 512

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var ready = false
    @Volatile private var failed = false

    fun isAvailable(): Boolean = ready && !failed

    private val initLock = Any()
    private val inferLock = Any()

    private fun ensure(context: Context): Boolean {
        if (ready) return true
        if (failed) return false
        synchronized(initLock) {
            if (ready) return true
            if (failed) return false
            return try {
            val buf = loadModel(context.applicationContext)
            val opts = Interpreter.Options()
            var gpu = false
            try {
                // No-arg GpuDelegate (как у донора): класс опций в 2.16 вынесен в отдельный
                // артефакт, тянем минимум зависимостей. Поддержка проверена CompatList.
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    opts.addDelegate(GpuDelegate())
                    gpu = true
                } else {
                    opts.setNumThreads(4)
                }
            } catch (_: Throwable) {
                opts.setNumThreads(4)
            }
            interpreter = Interpreter(buf, opts)
            ready = true
            Log.i(TAG, "ready (gpu=$gpu)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "init failed", t)
            failed = true
            false
        }
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        val fd = context.assets.openFd(MODEL)
        val inp = FileInputStream(fd.fileDescriptor)
        val ch = inp.channel
        return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            .order(ByteOrder.nativeOrder())
    }

    /** Полный цикл на битмапе любого размера: прокся → сеть → карта усиления → умножение.
     * Локи разделены: initLock только на инициализацию, inferLock только на interp.run.
     * Тяжёлый попиксельный gain-map идёт БЕЗ лока — параллельные вызовы не сериализуются. */
    fun enhance(context: Context, orig: Bitmap): Bitmap? {
        if (!ensure(context)) return null
        val interp = interpreter ?: return null
        return try {
            val t0 = System.nanoTime()
            val w = orig.width; val h = orig.height
            if (w < 8 || h < 8) return null
            // Прокся 512 (стретч — DCE-Net локальная, терпит)
            val proxy = Bitmap.createScaledBitmap(orig, PS, PS, true)
            val px = IntArray(PS * PS)
            proxy.getPixels(px, 0, PS, 0, 0, PS, PS)
            try { proxy.recycle() } catch (_: Throwable) {}
            val inBuf = ByteBuffer.allocateDirect(PS * PS * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in px) {
                inBuf.putFloat(((p shr 16) and 255) / 255f)
                inBuf.putFloat(((p shr 8) and 255) / 255f)
                inBuf.putFloat((p and 255) / 255f)
            }
            inBuf.rewind()
            val outBuf = ByteBuffer.allocateDirect(PS * PS * 3 * 4).order(ByteOrder.nativeOrder())
            synchronized(inferLock) { interp.run(inBuf, outBuf) }
            outBuf.rewind()
            val enh = FloatArray(PS * PS * 3)
            outBuf.asFloatBuffer().get(enh)
            // Карта усиления → билинейно на полный кадр → умножение (детали целы).
            // Полосами по 512 строк: пик ~2*w*512 int вместо 2*w*h (12Мп: 8МБ вместо 96МБ).
            val res = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "oom on createBitmap ${w}x$h")
                return null
            }
            try {
                val stripH = 512
                var y0s = 0
                while (y0s < h) {
                    val hh = minOf(stripH, h - y0s)
                    val origPx = IntArray(w * hh)
                    orig.getPixels(origPx, 0, w, 0, y0s, w, hh)
                    val outPx = IntArray(w * hh)
                    for (y in 0 until hh) {
                        val gy = y0s + y
                        val fy = gy * (PS - 1).toFloat() / (h - 1).coerceAtLeast(1)
                        val y0 = fy.toInt().coerceIn(0, PS - 2)
                        val wy = fy - y0
                        for (x in 0 until w) {
                            val fx = x * (PS - 1).toFloat() / (w - 1).coerceAtLeast(1)
                            val x0 = fx.toInt().coerceIn(0, PS - 2)
                            val wx = fx - x0
                            val p = origPx[y * w + x]
                            val or_ = ((p shr 16) and 255) / 255f
                            val og = ((p shr 8) and 255) / 255f
                            val ob = (p and 255) / 255f
                            val r = gainAt(enh, px, x0, y0, wx, wy, 0, or_) * or_
                            val g = gainAt(enh, px, x0, y0, wx, wy, 1, og) * og
                            val b = gainAt(enh, px, x0, y0, wx, wy, 2, ob) * ob
                            outPx[y * w + x] = -16777216 or
                                ((r * 255).toInt().coerceIn(0, 255) shl 16) or
                                ((g * 255).toInt().coerceIn(0, 255) shl 8) or
                                (b * 255).toInt().coerceIn(0, 255)
                        }
                    }
                    res.setPixels(outPx, 0, w, 0, y0s, w, hh)
                    y0s += hh
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "oom on gain strips ${w}x$h")
                try { res.recycle() } catch (_: Throwable) {}
                return null
            }
            Log.i(TAG, "enhance ok ${(System.nanoTime() - t0) / 1_000_000}ms ${w}x$h")
            res
        } catch (t: Throwable) {
            Log.w(TAG, "enhance failed", t)
            null
        }
    }

    /** Усиление канала c в точкеproxy с билинейной интерполяцией, клип [0.25, 4]. */
    private fun gainAt(
        enh: FloatArray, px: IntArray, x0: Int, y0: Int, wx: Float, wy: Float, c: Int, origV: Float,
    ): Float {
        fun e(xx: Int, yy: Int) = enh[(yy * PS + xx) * 3 + c].coerceIn(0f, 1f)
        fun o(xx: Int, yy: Int): Float {
            val p = px[yy * PS + xx]
            return when (c) {
                0 -> ((p shr 16) and 255) / 255f
                1 -> ((p shr 8) and 255) / 255f
                else -> (p and 255) / 255f
            }
        }
        val ev = e(x0, y0) * (1 - wx) * (1 - wy) + e(x0 + 1, y0) * wx * (1 - wy) +
            e(x0, y0 + 1) * (1 - wx) * wy + e(x0 + 1, y0 + 1) * wx * wy
        val ov = o(x0, y0) * (1 - wx) * (1 - wy) + o(x0 + 1, y0) * wx * (1 - wy) +
            o(x0, y0 + 1) * (1 - wx) * wy + o(x0 + 1, y0 + 1) * wx * wy
        return (ev / (ov + 1e-3f)).coerceIn(0.25f, 4f)
    }
}
