package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import app.grapheneos.camera.thmc.ThmcBitmaps

/**
 * JNI обёртка — один проход P0 в C++ с NEON, 8-10× быстрее Kotlin.
 * Фолбэк на Kotlin если native не загрузился (слабые без NDK).
 */
object NativeProcessor {
    private var loaded = false
    init {
        try {
            System.loadLibrary("processor")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
        }
    }
    fun isAvailable(): Boolean = loaded

    @JvmStatic external fun processPixels(pixels: IntArray, n: Int, toneStrength: Float, contrastFactor: Float, satFactor: Float, warmth: Float, meanL: Float)

    @JvmStatic external fun mergeHdrBitmaps(dark: Bitmap, mid: Bitmap, bright: Bitmap, out: Bitmap): Boolean

    @JvmStatic external fun softDenoise(src: Bitmap, dst: Bitmap, radius: Int, blend: Float): Boolean

    @JvmStatic external fun bitmapToYuv420(bmp: Bitmap, yuv: ByteArray, w: Int, h: Int): Boolean

    @JvmStatic external fun blendBitmaps(stock: Bitmap, p0: Bitmap, dst: Bitmap, s: Float): Boolean

    @JvmStatic external fun mergeWeightedN(frames: Array<Bitmap>, weights: IntArray, ref: Bitmap?, out: Bitmap, rejectThr: Int): Boolean

    /** Настоящий exposure fusion (Mertens, пирамиды). frames 2-4 одного размера, refIdx — опора для анти-гостинга. */
    @JvmStatic external fun mergeMertensN(frames: Array<Bitmap>, refIdx: Int, out: Bitmap): Boolean

    @JvmStatic external fun mergeWienerN(frames: Array<Bitmap>, refIdx: Int, out: Bitmap, noise: Float, rejectThr: Int): Boolean

    @JvmStatic external fun processDirect(src: Bitmap, dst: Bitmap, toneStrength: Float, contrastFactor: Float, satFactor: Float, warmth: Float, meanL: Float): Boolean

    /** Треугольный dither ±1 LSB перед JPEG-энкодом (in-place). false = нет натива. */
    @JvmStatic external fun ditherBitmap(bmp: Bitmap): Boolean

    /**
     * Обёртка dither: любая проблема → false, кадр не тронут.
     * Точка вызова — строго перед compress(JPEG): после дизера никакой
     * обработки быть не должно, иначе шум снова сгладится/переквантуется.
     */
    fun dither(bmp: Bitmap): Boolean {
        if (!loaded) return false
        return try {
            if (bmp.config != Bitmap.Config.ARGB_8888 || !bmp.isMutable) false
            else ditherBitmap(bmp)
        } catch (_: Throwable) { false }
    }

    /**
     * Мягкий шумодав в нативе (box H+V ×3 + подмес). null при любой проблеме.
     */
    fun softDenoiseBitmap(work: Bitmap, radius: Int, blend: Float): Bitmap? {
        if (!loaded) return null
        return try {
            if (work.config != Bitmap.Config.ARGB_8888) return null
            val out = ThmcBitmaps.obtain(work.width, work.height)
                ?: Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
            if (softDenoise(work, out, radius, blend)) out
            else { ThmcBitmaps.release(out); null }
        } catch (t: Throwable) { null }
    }

    /**
     * HDR-мерж в нативе, zero-copy (без IntArray-транзиентов и GC-ада).
     * null при любой проблеме — вызыватель идёт в Kotlin mergeHdrBrackets.
     */
    fun mergeHdr(dark: Bitmap, mid: Bitmap, bright: Bitmap): Bitmap? {
        if (!loaded) return null
        return try {
            if (dark.config != Bitmap.Config.ARGB_8888 || mid.config != Bitmap.Config.ARGB_8888 ||
                bright.config != Bitmap.Config.ARGB_8888) return null
            if (dark.width != mid.width || dark.height != mid.height ||
                bright.width != mid.width || bright.height != mid.height) return null
            val out = ThmcBitmaps.obtain(mid.width, mid.height)
                ?: Bitmap.createBitmap(mid.width, mid.height, Bitmap.Config.ARGB_8888)
            if (mergeHdrBitmaps(dark, mid, bright, out)) out
            else { ThmcBitmaps.release(out); null }
        } catch (t: Throwable) { null }
    }

    /**
     * Взвешенный мерж N кадров в нативе (попиксельное накопление, без FloatArray w*h*3).
     * ref = null → чистое среднее; иначе робастный reject. null при проблеме → Kotlin-фолбэк.
     */
    fun mergeWeightedN(frames: List<Bitmap>, weights: List<Int>, ref: Bitmap?, rejectThr: Int): Bitmap? {
        if (!loaded) return null
        return try {
            if (frames.isEmpty() || frames.size > 8) return null
            if (frames.size == 1) return frames[0]
            val w = frames[0].width; val h = frames[0].height
            for (f in frames) {
                if (f.config != Bitmap.Config.ARGB_8888 || f.width != w || f.height != h) return null
            }
            if (ref != null && (ref.config != Bitmap.Config.ARGB_8888 || ref.width != w || ref.height != h)) return null
            val out = ThmcBitmaps.obtain(w, h)
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = mergeWeightedN(frames.toTypedArray(), weights.toIntArray(), ref, out, rejectThr)
            if (ok) out
            else { ThmcBitmaps.release(out); null }
        } catch (t: Throwable) { null }
    }

    /**
     * Настоящий Мертенс: веса контраст/насыщенность/экспонированность + пирамиды Лапласа.
     * null при проблеме/OOM/неподходящих кадрах — вызыватель идёт в mergeWeighted.
     */
    fun mergeMertens(frames: List<Bitmap>, refIdx: Int): Bitmap? {
        if (!loaded) return null
        return try {
            if (frames.size < 2 || frames.size > 4) return null
            val w = frames[0].width; val h = frames[0].height
            if (w < 64 || h < 64) return null
            for (f in frames) {
                if (f.config != Bitmap.Config.ARGB_8888 || f.width != w || f.height != h) return null
            }
            val tM = System.nanoTime()
            val out = ThmcBitmaps.obtain(w, h)
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = mergeMertensN(frames.toTypedArray(), refIdx.coerceIn(0, frames.size - 1), out)
            try {
                android.util.Log.i("ThmcP0", "mergeMertens ${(System.nanoTime() - tM) / 1_000_000}ms ${w}x$h n=${frames.size} ok=$ok")
            } catch (_: Throwable) {}
            if (ok) out
            else { ThmcBitmaps.release(out); null }
        } catch (t: Throwable) { null }
    }

    /**
     * Винер-мерж для fixed-burst (HDR+ style): тайл 16x16, вес var/(var+k*noise^2).
     * noise 2..30 (ISO-догадка: день 4-6, ночь 10-14). null -> mergeWeighted.
     */
    fun mergeWiener(frames: List<Bitmap>, refIdx: Int, noise: Float, rejectThr: Int = 28): Bitmap? {
        if (!loaded) return null
        return try {
            if (frames.size < 2 || frames.size > 8) return null
            val w = frames[0].width; val h = frames[0].height
            if (w < 64 || h < 64) return null
            for (f in frames) {
                if (f.config != Bitmap.Config.ARGB_8888 || f.width != w || f.height != h) return null
            }
            val tM = System.nanoTime()
            val out = ThmcBitmaps.obtain(w, h)
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = mergeWienerN(frames.toTypedArray(), refIdx.coerceIn(0, frames.size - 1), out, noise, rejectThr)
            try {
                android.util.Log.i("ThmcP0", "mergeWiener ${(System.nanoTime() - tM) / 1_000_000}ms ${w}x$h n=${frames.size} ok=$ok")
            } catch (_: Throwable) {}
            if (ok) out
            else { ThmcBitmaps.release(out); null }
        } catch (t: Throwable) { null }
    }

    /**
     * Быстрый путь: getPixels → native → setPixels, один проход вместо 4.
     * meanL 0-1 уже адаптивен. config ДОЛЖЕН быть уже адаптирован вызывателем
     * (ImageProcessor делает adapt один раз после микса) — тут adapt НЕ делаем.
     * Zero-copy first: processDirect без IntArray-транзиента; фолбэк — старый путь.
     */
    fun processBitmap(bitmap: Bitmap, config: RestoreConfig, meanL: Float): Bitmap {
        if (loaded) {
            try {
                if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    val out = ThmcBitmaps.obtain(bitmap.width, bitmap.height)
                        ?: Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    if (processDirect(bitmap, out, config.toneStrength, config.contrastFactor, config.saturationFactor, config.warmth, meanL)) {
                        return out
                    }
                    ThmcBitmaps.release(out)
                }
            } catch (e: OutOfMemoryError) {
                return bitmap
            } catch (_: Throwable) {}
        }
        if (!loaded) return bitmap // fallback
        val w = bitmap.width; val h = bitmap.height; val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        processPixels(pixels, n, config.toneStrength, config.contrastFactor, config.saturationFactor, config.warmth, meanL)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Бленд stock→p0 без больших IntArray: сначала натив in-place,
     * иначе чанками по полосам (пик ~2*w*256 int). Возвращает dst (== p0 если мутабелен).
     * null при проблеме — вызыватель оставляет p0 как есть.
     */
    fun blend(stock: Bitmap, p0: Bitmap, s: Float): Bitmap? {
        return try {
            if (stock.width != p0.width || stock.height != p0.height) return null
            if (s >= 0.999f) return p0
            if (s <= 0.001f) return stock
            val dst = if (p0.isMutable) p0
            else p0.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            if (loaded) {
                try {
                    if (blendBitmaps(stock, p0, dst, s)) return dst
                } catch (_: Throwable) {}
            }
            // Kotlin-фолбэк чанками (не двумя полными IntArray сразу)
            try {
                val w = dst.width; val h = dst.height
                val stripH = 256
                var y = 0
                while (y < h) {
                    val hh = minOf(stripH, h - y)
                    val a = IntArray(w * hh)
                    val b = IntArray(w * hh)
                    stock.getPixels(a, 0, w, 0, y, w, hh)
                    dst.getPixels(b, 0, w, 0, y, w, hh)
                    for (i in b.indices) {
                        val p1 = a[i]; val p2 = b[i]
                        val r1 = (p1 shr 16) and 255; val g1 = (p1 shr 8) and 255; val b1 = p1 and 255
                        val r = (r1 + (((p2 shr 16) and 255) - r1) * s).toInt().coerceIn(0, 255)
                        val g = (g1 + (((p2 shr 8) and 255) - g1) * s).toInt().coerceIn(0, 255)
                        val bl = (b1 + ((p2 and 255) - b1) * s).toInt().coerceIn(0, 255)
                        b[i] = (p2 and -0x1000000) or (r shl 16) or (g shl 8) or bl
                    }
                    dst.setPixels(b, 0, w, 0, y, w, hh)
                    y += hh
                }
                dst
            } catch (e: OutOfMemoryError) {
                null
            }
        } catch (_: Throwable) { null }
    }
}
