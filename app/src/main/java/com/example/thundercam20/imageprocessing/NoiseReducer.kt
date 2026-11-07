package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.nio.ByteBuffer

/**
 * Класс для уменьшения шума в изображении с использованием OpenCV
 */
class NoiseReducer {
    
    /**
     * Уменьшить шум с помощью fastNlMeansDenoisingColored (качественный шумодав)
     * 
     * @param bitmap входное изображение
     * @param h для цветовой составляющей (рекомендуется 3)
     * @param hColor для цветовой составляющей (рекомендуется 3)
     * @param templateWindowSize размер окна шаблона (рекомендуется 7)
     * @param searchWindowSize размер окна поиска (рекомендуется 21)
     * @return изображение без шума
     */
    fun fastNlMeansDenoisingColored(
        bitmap: Bitmap,
        h: Float = 3f,
        hColor: Float = 3f,
        templateWindowSize: Int = 7,
        searchWindowSize: Int = 21
    ): Bitmap {
        try {
            // Конвертировать Bitmap в Mat
            val mat = bitmapToMat(bitmap)
            
            // Применить шумодав
            val denoisedMat = Mat()
            Photo.fastNlMeansDenoisingColored(
                mat,
                denoisedMat,
                h,
                hColor,
                templateWindowSize,
                searchWindowSize
            )
            
            // Конвертировать обратно в Bitmap
            val result = matToBitmap(denoisedMat)
            
            // Освободить память
            mat.release()
            denoisedMat.release()
            
            return result
        } catch (t: Throwable) {
            t.printStackTrace()
            // Если OpenCV не работает, использовать базовую реализацию
            return softKotlin(bitmap)
        }
    }
    
    /**
     * Умный раздельный шумодав YCrCb для бюджетников:
     * 1. Хроматический шум (Cr/Cb — цветные пятна в сумерках/ночью) давится сильно на даунскейле ×4
     *    за миллисекунды — вообще без потери чёткости границ.
     * 2. Яркостный шум (Y) чистится одноканальным bilateral с малым окном d=5 (в 3 раза быстрее RGB
     *    и сохраняет микротекстуру травы/кирпичей/волос).
     */
    /**
     * Умный раздельный шумодав YCrCb: сила/окно/итерации — от измеренного σ шума
     * (Immerkaer), а не от фиксированных констант. Батч 1.
     */
    fun denoiseYCrCb(
        bitmap: Bitmap,
        chromaBlend: Float = 0.80f
    ): Pair<Bitmap, Double> {
        if (!com.example.thundercam20.utils.OpencvManager.available) {
            return Pair(softKotlin(bitmap, radius = 2, blend = 0.35f), 12.0)
        }
        return try {
            val mat = bitmapToMat(bitmap)
            val rgb = Mat()
            Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
            mat.release()

            val ycrcb = Mat()
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            rgb.release()

            val ch = java.util.ArrayList<Mat>(3)
            org.opencv.core.Core.split(ycrcb, ch)
            ycrcb.release()

            val yMat = ch[0]
            val crMat = ch[1]
            val cbMat = ch[2]

            // Карта сильных люма-кромок ПО ЧИСТОМУ Y (до bilateral!): тонкие штрихи
            // и текст bilateral уже подъедает, маска по мытому их не видит.
            // Здесь ни рябь не давим, ни хрому не моем — иначе серая обводка.
            val eGx = Mat(); Imgproc.Sobel(yMat, eGx, CvType.CV_16SC1, 1, 0, 3)
            val eGy = Mat(); Imgproc.Sobel(yMat, eGy, CvType.CV_16SC1, 0, 1, 3)
            val eAx = Mat(); org.opencv.core.Core.convertScaleAbs(eGx, eAx); eGx.release()
            val eAy = Mat(); org.opencv.core.Core.convertScaleAbs(eGy, eAy); eGy.release()
            val eMag = Mat(); org.opencv.core.Core.add(eAx, eAy, eMag); eAx.release(); eAy.release()
            val eBin = Mat()
            Imgproc.threshold(eMag, eBin, 24.0, 255.0, Imgproc.THRESH_BINARY); eMag.release()
            val eK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(3.0, 3.0))
            val eDil = Mat()
            Imgproc.dilate(eBin, eDil, eK, org.opencv.core.Point(-1.0, -1.0), 2)
            eBin.release(); eK.release()
            val edgeM8 = Mat(); Imgproc.blur(eDil, edgeM8, org.opencv.core.Size(3.0, 3.0)); eDil.release()

            // Оценка шума σ (Immerkaer): среднее |Laplacian| — все силы пляшут от неё,
            // а не от фиксированных констант.
            val yF = Mat(); yMat.convertTo(yF, CvType.CV_32FC1)
            val lapK = Mat(3, 3, CvType.CV_32FC1)
            lapK.put(0, 0, floatArrayOf(1f, -2f, 1f, -2f, 4f, -2f, 1f, -2f, 1f))
            val lap = Mat(); Imgproc.filter2D(yF, lap, -1, lapK); yF.release(); lapK.release()
            val absL = Mat(); org.opencv.core.Core.convertScaleAbs(lap, absL); lap.release()
            val sigma = (1.2533 * org.opencv.core.Core.mean(absL).`val`[0] / 6.0).coerceIn(2.0, 30.0)
            absL.release()
            // Адаптив: sigmaColor = 3.0σ (стенд: на ночном надо ~60), d=7 при σ>10.
            // Вторая итерация (1b на ½) — растущей сигмой 1.4s.
            val sCol = (3.0 * sigma).coerceIn(12.0, 55.0)
            val dd = if (sigma > 10.0) 7 else 5
            val sSp = 3.0 * dd
            val sCol2 = minOf(sCol * 1.4, 45.0)
            try { android.util.Log.i("ThmcP0", "denoise sigma=${"%.1f".format(sigma)} sCol=${"%.0f".format(sCol)} d=$dd") } catch (_: Throwable) {}
            // 1. Яркостный канал Y: одноканальный bilateral (окно и сила — от σ).
            var yFilt = Mat()
            Imgproc.bilateralFilter(yMat, yFilt, dd, sCol, sSp)
            yMat.release()
            // (карта кромок уже посчитана выше по чистому yMat — тонкие штрихи целы)
            // 1b. Низкочастотная рябь («волны» крупнее окна d=5): bilateral на ½
            // разрешении = эффективное окно ~10px, подмес 40% — волны гаснут.
            // Только в плоском: у кромок оставляем резкое (иначе ореол).
            try {
                val halfY = Mat(); Imgproc.pyrDown(yFilt, halfY)
                val halfYf = Mat()
                Imgproc.bilateralFilter(halfY, halfYf, 5, sCol2, 15.0)
                val eHalf = Mat()
                Imgproc.resize(edgeM8, eHalf, halfYf.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                val eHf = Mat(); eHalf.convertTo(eHf, CvType.CV_32FC1, 1.0 / 255.0); eHalf.release()
                val hY = Mat(); halfY.convertTo(hY, CvType.CV_32FC1); halfY.release()
                val hYf = Mat(); halfYf.convertTo(hYf, CvType.CV_32FC1); halfYf.release()
                val invE = Mat(); org.opencv.core.Core.multiply(eHf, org.opencv.core.Scalar(-1.0), invE)
                org.opencv.core.Core.add(invE, org.opencv.core.Scalar(1.0), invE)
                val keep = Mat(); org.opencv.core.Core.multiply(hY, eHf, keep); eHf.release()
                val flat = Mat(); org.opencv.core.Core.multiply(hYf, invE, flat); invE.release()
                val flatH = Mat(); org.opencv.core.Core.add(keep, flat, flatH)
                keep.release(); flat.release(); hY.release(); hYf.release()
                val flatH8 = Mat(); flatH.convertTo(flatH8, CvType.CV_8UC1); flatH.release()
                val upY = Mat()
                Imgproc.resize(flatH8, upY, yFilt.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); flatH8.release()
                val flatY = Mat()
                org.opencv.core.Core.addWeighted(yFilt, 0.6, upY, 0.4, 0.0, flatY)
                upY.release(); yFilt.release()
                yFilt = flatY
            } catch (_: Throwable) { }

            // 2. Хроматические каналы Cr, Cb: давим цветовые пятна на 1/4 разрешения
            val fullSize = org.opencv.core.Size(crMat.cols().toDouble(), crMat.rows().toDouble())
            val smallSize = org.opencv.core.Size(
                (crMat.cols() / 4).coerceAtLeast(16).toDouble(),
                (crMat.rows() / 4).coerceAtLeast(16).toDouble()
            )
            // Мойка хромы в половинном (равномерно): wash*b + orig*(1-b).
            // Кромки правит тайт-бленд ниже — честной full-res хромой в узкой полосе.
            val halfSize = org.opencv.core.Size(fullSize.width / 2.0, fullSize.height / 2.0)
            fun cleanChroma(c: Mat): Mat {
                val cDown = Mat()
                Imgproc.resize(c, cDown, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
                val blurred = Mat()
                Imgproc.medianBlur(cDown, blurred, 5)
                val smClean = Mat()
                Imgproc.GaussianBlur(blurred, smClean, org.opencv.core.Size(5.0, 5.0), 0.0)
                blurred.release(); cDown.release()
                val cHalf = Mat()
                Imgproc.resize(c, cHalf, halfSize, 0.0, 0.0, Imgproc.INTER_AREA)
                val washHalf = Mat()
                Imgproc.resize(smClean, washHalf, halfSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
                smClean.release()
                val b = chromaBlend.coerceIn(0f, 1f).toDouble()
                val wF = Mat(); washHalf.convertTo(wF, CvType.CV_32FC1); washHalf.release()
                val cF = Mat(); cHalf.convertTo(cF, CvType.CV_32FC1); cHalf.release()
                val p1 = Mat(); org.opencv.core.Core.multiply(wF, org.opencv.core.Scalar(b), p1); wF.release()
                val p2 = Mat(); org.opencv.core.Core.multiply(cF, org.opencv.core.Scalar(1.0 - b), p2); cF.release()
                val outH = Mat(); org.opencv.core.Core.add(p1, p2, outH); p1.release(); p2.release()
                val outH8 = Mat(); outH.convertTo(outH8, CvType.CV_8UC1); outH.release()
                val out = Mat()
                Imgproc.resize(outH8, out, fullSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
                outH8.release()
                return out // washFull; c жив — тайт-бленд ниже
            }
            val crWash = cleanChroma(crMat)
            val cbWash = cleanChroma(cbMat)
            // Тайт-бленд: внутри узкой полосы у кромок — честная full-res хрома
            // (тонкий провод/текст без цветной обводки), снаружи — мытая.
            // Маска уже, чем раньше (порог 0.30 + блюр 3): только сама кромка.
            // Полосами по 512 строк — память константна.
            val eFull8 = Mat()
            Imgproc.resize(edgeM8, eFull8, fullSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
            edgeM8.release()
            val eFull = Mat(); eFull8.convertTo(eFull, CvType.CV_32FC1, 1.0 / 255.0); eFull8.release()
            Imgproc.threshold(eFull, eFull, 0.30, 1.0, Imgproc.THRESH_BINARY)
            Imgproc.blur(eFull, eFull, org.opencv.core.Size(3.0, 3.0))
            fun tightBlend(cOrig: Mat, wash: Mat) {
                var y0 = 0
                val bh0 = 512
                while (y0 < wash.rows()) {
                    val bh = minOf(bh0, wash.rows() - y0)
                    val cR = cOrig.submat(y0, y0 + bh, 0, wash.cols())
                    val wR = wash.submat(y0, y0 + bh, 0, wash.cols())
                    val eR = eFull.submat(y0, y0 + bh, 0, wash.cols())
                    val cF = Mat(); cR.convertTo(cF, CvType.CV_32FC1)
                    val wF = Mat(); wR.convertTo(wF, CvType.CV_32FC1)
                    val d = Mat(); org.opencv.core.Core.subtract(cF, wF, d); cF.release()
                    val m = Mat(); org.opencv.core.Core.multiply(d, eR, m); d.release()
                    val o = Mat(); org.opencv.core.Core.add(wF, m, o); wF.release(); m.release()
                    val o8 = Mat(); o.convertTo(o8, CvType.CV_8UC1); o.release()
                    o8.copyTo(wR); o8.release()
                    cR.release(); wR.release(); eR.release()
                    y0 += bh
                }
            }
            tightBlend(crMat, crWash); crMat.release()
            tightBlend(cbMat, cbWash); cbMat.release()
            eFull.release()
            val crClean = crWash
            val cbClean = cbWash

            val merged = Mat()
            org.opencv.core.Core.merge(listOf(yFilt, crClean, cbClean), merged)
            yFilt.release(); crClean.release(); cbClean.release()

            val rgbOut = Mat()
            Imgproc.cvtColor(merged, rgbOut, Imgproc.COLOR_YCrCb2RGB)
            merged.release()

            val rgbaOut = Mat()
            Imgproc.cvtColor(rgbOut, rgbaOut, Imgproc.COLOR_RGB2RGBA)
            rgbOut.release()

            val result = matToBitmap(rgbaOut)
            rgbaOut.release()
            Pair(result, sigma)
        } catch (t: Throwable) {
            Pair(softKotlin(bitmap, radius = 2, blend = 0.35f), 12.0)
        }
    }

    /**
     * Уменьшить шум с помощью bilateralFilter (сохранение краев)
     * 
     * @param bitmap входное изображение
     * @param diameter диаметр пиксельного окна (рекомендуется 9)
     * @param sigmaColor фильтр по цветовым различиям (рекомендуется 75)
     * @param sigmaSpace фильтр по пространственным различиям (рекомендуется 75)
     * @return изображение без шума
     */
    fun bilateralFilter(
        bitmap: Bitmap,
        diameter: Int = 9,
        sigmaColor: Float = 75f,
        sigmaSpace: Float = 75f
    ): Bitmap {
        try {
            // Конвертировать Bitmap в Mat
            val mat = bitmapToMat(bitmap)
            
            // Применить bilateral filter
            val filteredMat = Mat()
            Imgproc.bilateralFilter(mat, filteredMat, diameter, sigmaColor.toDouble(), sigmaSpace.toDouble())
            
            // Конвертировать обратно в Bitmap
            val result = matToBitmap(filteredMat)
            
            // Освободить память
            mat.release()
            filteredMat.release()
            
            return result
        } catch (t: Throwable) {
            t.printStackTrace()
            // Если OpenCV не работает, использовать базовую реализацию
            return softKotlin(bitmap)
        }
    }
    
    /**
     * Комбинированный шумодав: сначала fastNlMeansDenoisingColored, затем bilateralFilter
     */
    fun applyCombinedNoiseReduction(bitmap: Bitmap): Bitmap {
        // Сначала быстрый шумодав
        var result = fastNlMeansDenoisingColored(bitmap)
        
        // Затем сглаживание с сохранением краев
        result = bilateralFilter(result)
        
        return result
    }
    
    /**
     * Быстрый мягкий шумодав без OpenCV: 3-проходный сепарабельный бокс-блюр (O(n),
     * доли секунды на 12МП) + подмес 40% к оригиналу — детали целы, зерно давлено.
     * Используется как фолбэк везде, где раньше был минутный медианный цикл.
     */
    fun softKotlin(bitmap: Bitmap, radius: Int = 2, blend: Float = 0.4f): Bitmap {
        return try {
            // работаем на ≤2048 (память под контролем), потом апскейл — для шумодава ок
            val longSide = maxOf(bitmap.width, bitmap.height)
            val work = if (longSide > 2048) {
                val s = 2048f / longSide
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
            } else bitmap
            // натив first (zero-copy) — Kotlin фолбэк
            val t0 = System.currentTimeMillis()
            val nativeOut = try { NativeProcessor.softDenoiseBitmap(work, radius, blend) } catch (_: Throwable) { null }
            var small: Bitmap? = null
            if (nativeOut != null) {
                try { android.util.Log.i("SoftDenoise", "native ${System.currentTimeMillis() - t0}ms ${work.width}x${work.height}") } catch (_: Throwable) {}
                small = nativeOut
            } else {
                small = kotlinSoft(work, radius, blend)
                try { android.util.Log.i("SoftDenoise", "kotlin ${System.currentTimeMillis() - t0}ms") } catch (_: Throwable) {}
            }
            var res = small
            if (work !== bitmap) {
                val up = Bitmap.createScaledBitmap(res, bitmap.width, bitmap.height, true)
                res.recycle(); work.recycle()
                res = up
            }
            res
        } catch (_: Throwable) { bitmap }
    }

    /**
     * Хрома-шумодав: давит цветные крапинки, не трогая детали.
     * Яркость оригинала, цветность сглаженного поля (/8 + билинейный ап).
     * Вызывать ДО tone/saturation. Порт из thundercam20.
     */
    fun chromaSmooth(bitmap: Bitmap, strength: Float = 0.7f): Bitmap {
        return try {
            val w = bitmap.width; val h = bitmap.height
            if (w < 16 || h < 16) return bitmap
            val k = strength.coerceIn(0f, 1f)
            if (k <= 0.01f) return bitmap
            val sw = (w / 8).coerceAtLeast(1); val sh = (h / 8).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val glow = Bitmap.createScaledBitmap(small, w, h, true)
            try { small.recycle() } catch (_: Exception) {}
            val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val band = 384
            val srcPx = IntArray(w * band)
            val gloPx = IntArray(w * band)
            val outPx = IntArray(w * band)
            var y = 0
            while (y < h) {
                val bh = minOf(band, h - y)
                bitmap.getPixels(srcPx, 0, w, 0, y, w, bh)
                glow.getPixels(gloPx, 0, w, 0, y, w, bh)
                for (i in 0 until w * bh) {
                    val p = srcPx[i]; val q = gloPx[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    val gr = (q shr 16) and 0xFF; val gg = (q shr 8) and 0xFF; val gb = q and 0xFF
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    val gl = 0.299f * gr + 0.587f * gg + 0.114f * gb
                    val nr = (l + (gr - gl) * k + (r - l) * (1 - k)).toInt().coerceIn(0, 255)
                    val ng = (l + (gg - gl) * k + (g - l) * (1 - k)).toInt().coerceIn(0, 255)
                    val nb = (l + (gb - gl) * k + (b - l) * (1 - k)).toInt().coerceIn(0, 255)
                    outPx[i] = -0x1000000 or (nr shl 16) or (ng shl 8) or nb
                }
                res.setPixels(outPx, 0, w, 0, y, w, bh)
                y += bh
            }
            try { glow.recycle() } catch (_: Exception) {}
            res
        } catch (_: Throwable) { bitmap }
    }

    /**
     * Возврат деталей v2 (когерентный, только по яркости):
     * проблема v1 — вес по амплитуде |orig-smooth| не отличает зерно от текстуры
     * (у одиночного шумового пикселя и у края фактуры одинаковый diff ~12-15).
     * v2 отличает их по связности: зерно — изолированный всплеск (соседи ~0),
     * текстура — пятно/линия (соседи тоже высокие). Маска строится по среднему
     * diff в окне 3x3: зерно 15/9≈1.7 давится, текстура 10-12 остаётся.
     * Возврат только в люму (delta яркости поверх smooth): хрома остаётся
     * давленой — цветное зерно не возвращается вместе с деталью.
     * Полосами + halo 1px — память константна.
     * Возвращает НОВЫЙ битмап, входы не трогает.
     */
    fun detailBlend(orig: Bitmap, smooth: Bitmap, t0: Float, t1: Float): Bitmap {
        if (com.example.thundercam20.utils.OpencvManager.available) {
            try { return detailBlendCv(orig, smooth, t0, t1) } catch (_: Throwable) {}
        }
        return try {
            val w = orig.width; val h = orig.height
            if (smooth.width != w || smooth.height != h) return smooth
            if (t1 <= t0) return smooth
            val inv = 1f / (t1 - t0)
            val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val band = 256
            var y0 = 0
            while (y0 < h) {
                val y1 = minOf(y0 + band, h)
                val extTop = if (y0 > 0) y0 - 1 else 0
                val extBot = if (y1 < h) y1 + 1 else h
                val extH = extBot - extTop
                val srcPx = IntArray(w * extH)
                val smoPx = IntArray(w * extH)
                orig.getPixels(srcPx, 0, w, 0, extTop, w, extH)
                smooth.getPixels(smoPx, 0, w, 0, extTop, w, extH)
                // diff по люме для расширенной полосы
                val diff = FloatArray(w * extH)
                for (i in 0 until w * extH) {
                    val p = srcPx[i]; val q = smoPx[i]
                    val lo = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                    val ls = 0.299f * ((q shr 16) and 0xFF) + 0.587f * ((q shr 8) and 0xFF) + 0.114f * (q and 0xFF)
                    diff[i] = kotlin.math.abs(lo - ls)
                }
                val outPx = IntArray(w * (y1 - y0))
                for (y in y0 until y1) {
                    val ey = y - extTop
                    val eyM = if (ey > 0) ey - 1 else 0
                    val eyP = if (ey < extH - 1) ey + 1 else extH - 1
                    for (x in 0 until w) {
                        val xm = if (x > 0) x - 1 else 0
                        val xp = if (x < w - 1) x + 1 else w - 1
                        // Средний diff 3x3 — мера связности структуры
                        val r0 = eyM * w; val r1 = ey * w; val r2 = eyP * w
                        val m = (diff[r0 + xm] + diff[r0 + x] + diff[r0 + xp] +
                                diff[r1 + xm] + diff[r1 + x] + diff[r1 + xp] +
                                diff[r2 + xm] + diff[r2 + x] + diff[r2 + xp]) * 0.1111111f
                        var wgt = (m - t0) * inv
                        wgt = wgt.coerceIn(0f, 1f)
                        wgt = wgt * wgt * (3f - 2f * wgt)
                        val idx = ey * w + x
                        val p = srcPx[idx]; val q = smoPx[idx]
                        val lo = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                        val ls = 0.299f * ((q shr 16) and 0xFF) + 0.587f * ((q shr 8) and 0xFF) + 0.114f * (q and 0xFF)
                        val delta = (lo - ls) * wgt
                        val r = (((q shr 16) and 0xFF) + delta).toInt().coerceIn(0, 255)
                        val g = (((q shr 8) and 0xFF) + delta).toInt().coerceIn(0, 255)
                        val b = ((q and 0xFF) + delta).toInt().coerceIn(0, 255)
                        outPx[(y - y0) * w + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    }
                }
                res.setPixels(outPx, 0, w, 0, y0, w, y1 - y0)
                y0 = y1
            }
            res
        } catch (_: Throwable) { smooth }
    }

    /**
     * Нативный путь detailBlend v6: три шкалы + глобальная карта уверенности.
     * Амплитуда amp = max(w3, w5, w9): структура хоть на одной шкале
     * (3 — мелочь/линии, 5 — пятна, 9 — крупные фактуры).
     * Свидетельство struct = max(dir, persist):
     * - dir = когерентность градиента 5x5 (линия/кромка ~0.8+, шум ~0.2);
     * - persist = стойкость diff при даунскейле ×2: зерно гаснет вдвое (P≈0.5),
     *   связная фактура живёт (P≈0.9). Это и ловит «песок»: в зерновом поле
     *   среднее высоко на ВСЕХ шкалах, но стойкости нет — гейт закрыт.
     * Итог: w = amp * smoothstep((struct-0.25)/0.55). Хрома всегда от smooth.
     * Миллисекунды на 12Мп.
     */
    private fun detailBlendCv(orig: Bitmap, smooth: Bitmap, t0: Float, t1: Float): Bitmap {
        val oMat = Mat(); org.opencv.android.Utils.bitmapToMat(orig, oMat)
        val sMat = Mat(); org.opencv.android.Utils.bitmapToMat(smooth, sMat)
        val oRgb = Mat(); Imgproc.cvtColor(oMat, oRgb, Imgproc.COLOR_RGBA2RGB); oMat.release()
        val sRgb = Mat(); Imgproc.cvtColor(sMat, sRgb, Imgproc.COLOR_RGBA2RGB); sMat.release()
        val oYcc = Mat(); Imgproc.cvtColor(oRgb, oYcc, Imgproc.COLOR_RGB2YCrCb); oRgb.release()
        val sYcc = Mat(); Imgproc.cvtColor(sRgb, sYcc, Imgproc.COLOR_RGB2YCrCb); sRgb.release()
        val oCh = java.util.ArrayList<Mat>(3); org.opencv.core.Core.split(oYcc, oCh); oYcc.release()
        val sCh = java.util.ArrayList<Mat>(3); org.opencv.core.Core.split(sYcc, sCh); sYcc.release()
        val yO = oCh[0]; val yS = sCh[0]
        val diff = Mat(); org.opencv.core.Core.absdiff(yO, yS, diff)
        val coh3 = Mat(); Imgproc.blur(diff, coh3, org.opencv.core.Size(3.0, 3.0))
        val coh5 = Mat(); Imgproc.blur(diff, coh5, org.opencv.core.Size(5.0, 5.0))
        val coh9 = Mat(); Imgproc.blur(diff, coh9, org.opencv.core.Size(9.0, 9.0))
        // Стойкость между масштабами («текстура vs шум»): даунскейл ×4 усредняет —
        // сенсорное зерно коррелировано на 2-3px и переживает ×2, на ×4 гаснет
        // вдвое (P≈0.5); связная фактура коррелирована шире и живёт (P≈0.9).
        val h1 = Mat(); Imgproc.pyrDown(diff, h1); diff.release()
        val h2 = Mat(); Imgproc.pyrDown(h1, h2); h1.release()
        val halfB = Mat(); Imgproc.blur(h2, halfB, org.opencv.core.Size(3.0, 3.0)); h2.release()
        val cohLow = Mat()
        Imgproc.resize(halfB, cohLow, coh3.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); halfB.release()
        // Глобальная карта уверенности: чистый оригинал yO ×8 вниз (три pyrDown) —
        // зерно и кластеры 3-6px там мертвы, живы только настоящие края/фактура.
        // Собель + норма + апскейл. Это «глобалка», по ней сверяем мелочь.
        val gd1 = Mat(); Imgproc.pyrDown(yO, gd1)
        val gd2 = Mat(); Imgproc.pyrDown(gd1, gd2); gd1.release()
        val gd3 = Mat(); Imgproc.pyrDown(gd2, gd3); gd2.release()
        val gd3b = Mat(); Imgproc.blur(gd3, gd3b, org.opencv.core.Size(3.0, 3.0)); gd3.release()
        val ggx = Mat(); Imgproc.Sobel(gd3b, ggx, CvType.CV_32FC1, 1, 0, 3)
        val ggy = Mat(); Imgproc.Sobel(gd3b, ggy, CvType.CV_32FC1, 0, 1, 3)
        gd3b.release()
        val gax = Mat(); org.opencv.core.Core.convertScaleAbs(ggx, gax)
        val gay = Mat(); org.opencv.core.Core.convertScaleAbs(ggy, gay)
        ggx.release(); ggy.release()
        val gmag = Mat(); org.opencv.core.Core.add(gax, gay, gmag); gax.release(); gay.release()
        val gmagB = Mat(); Imgproc.blur(gmag, gmagB, org.opencv.core.Size(5.0, 5.0)); gmag.release()
        val gSmall = Mat(); gmagB.convertTo(gSmall, CvType.CV_32FC1, 1.0 / 30.0); gmagB.release()
        Imgproc.threshold(gSmall, gSmall, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        val globalE = Mat()
        Imgproc.resize(gSmall, globalE, coh3.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); gSmall.release()
        // Сигнатуры направленности: структурный тензор градиентов Y_orig в окнах
        // 5x5 и 9x9 — «проверка с соседними клетками»: короткая чёрточка coherent
        // лишь локально, настоящая линия/фактура держит направление и дальше.
        // C = sqrt((Exx-Eyy)^2 + 4*Exy^2) / (Exx+Eyy+1): 0 шум/плоское, 1 линия.
        // dir = max(dir5, dir9). Плюс близость к глобальным краям: вдали от любых
        // кромок (плоское) возврат давится — там ни фактуры, ни смысла её искать.
        val gx = Mat(); Imgproc.Sobel(yO, gx, CvType.CV_32FC1, 1, 0, 3)
        val gy = Mat(); Imgproc.Sobel(yO, gy, CvType.CV_32FC1, 0, 1, 3)
        val gxx = Mat(); org.opencv.core.Core.multiply(gx, gx, gxx)
        val gyy = Mat(); org.opencv.core.Core.multiply(gy, gy, gyy)
        val gxy = Mat(); org.opencv.core.Core.multiply(gx, gy, gxy)
        // Карта близости к краям: |Gx|+|Gy| -> blur 9x9. Успеть до release gx/gy.
        val ax = Mat(); org.opencv.core.Core.convertScaleAbs(gx, ax)
        val ay = Mat(); org.opencv.core.Core.convertScaleAbs(gy, ay)
        val mag8 = Mat(); org.opencv.core.Core.add(ax, ay, mag8); ax.release(); ay.release()
        val prox8 = Mat(); Imgproc.blur(mag8, prox8, org.opencv.core.Size(9.0, 9.0)); mag8.release()
        val proxF = Mat(); prox8.convertTo(proxF, CvType.CV_32FC1, 1.0 / 60.0); prox8.release()
        Imgproc.threshold(proxF, proxF, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        gx.release(); gy.release()
        // Тензор в двух окнах из тех же квадратов (всё ДО release).
        val eX5 = Mat(); Imgproc.blur(gxx, eX5, org.opencv.core.Size(5.0, 5.0))
        val eY5 = Mat(); Imgproc.blur(gyy, eY5, org.opencv.core.Size(5.0, 5.0))
        val eXY5 = Mat(); Imgproc.blur(gxy, eXY5, org.opencv.core.Size(5.0, 5.0))
        val eX9 = Mat(); Imgproc.blur(gxx, eX9, org.opencv.core.Size(9.0, 9.0))
        val eY9 = Mat(); Imgproc.blur(gyy, eY9, org.opencv.core.Size(9.0, 9.0))
        val eXY9 = Mat(); Imgproc.blur(gxy, eXY9, org.opencv.core.Size(9.0, 9.0))
        gxx.release(); gyy.release(); gxy.release()
        fun dirNum(exx: Mat, eyy: Mat, exy: Mat): Mat {
            val dm = Mat(); org.opencv.core.Core.subtract(exx, eyy, dm)
            val ds = Mat(); org.opencv.core.Core.multiply(dm, dm, ds); dm.release()
            val xs = Mat(); org.opencv.core.Core.multiply(exy, exy, xs)
            org.opencv.core.Core.multiply(xs, org.opencv.core.Scalar(4.0), xs)
            val ns = Mat(); org.opencv.core.Core.add(ds, xs, ns); ds.release(); xs.release()
            val nm = Mat(); org.opencv.core.Core.sqrt(ns, nm); ns.release()
            return nm
        }
        val num5 = dirNum(eX5, eY5, eXY5)
        val num9 = dirNum(eX9, eY9, eXY9)
        val den5 = Mat(); org.opencv.core.Core.add(eX5, eY5, den5); eX5.release(); eY5.release()
        org.opencv.core.Core.add(den5, org.opencv.core.Scalar(1.0), den5)
        val den9 = Mat(); org.opencv.core.Core.add(eX9, eY9, den9); eX9.release(); eY9.release()
        org.opencv.core.Core.add(den9, org.opencv.core.Scalar(1.0), den9)
        eXY5.release(); eXY9.release()
        val dir5 = Mat(); org.opencv.core.Core.divide(num5, den5, dir5); num5.release(); den5.release()
        val dir9 = Mat(); org.opencv.core.Core.divide(num9, den9, dir9); num9.release(); den9.release()
        val dirCoh = Mat(); org.opencv.core.Core.max(dir5, dir9, dirCoh); dir5.release(); dir9.release()
        // Нормированная стойкость persist = smoothstep((P-0.5)/0.35), P = cohLow/(coh3+1).
        val c3f = Mat(); coh3.convertTo(c3f, CvType.CV_32FC1)
        val clf = Mat(); cohLow.convertTo(clf, CvType.CV_32FC1); cohLow.release()
        org.opencv.core.Core.add(c3f, org.opencv.core.Scalar(1.0), c3f)
        val pRat = Mat(); org.opencv.core.Core.divide(clf, c3f, pRat); clf.release(); c3f.release()
        val pN = Mat(); org.opencv.core.Core.subtract(pRat, org.opencv.core.Scalar(0.65), pN); pRat.release()
        org.opencv.core.Core.multiply(pN, org.opencv.core.Scalar(1.0 / 0.30), pN)
        Imgproc.threshold(pN, pN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(pN, pN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val pSq = Mat(); org.opencv.core.Core.multiply(pN, pN, pSq)
        val pLin = Mat(); org.opencv.core.Core.multiply(pN, org.opencv.core.Scalar(-2.0), pLin)
        org.opencv.core.Core.add(pLin, org.opencv.core.Scalar(3.0), pLin)
        val persist = Mat(); org.opencv.core.Core.multiply(pSq, pLin, persist)
        pSq.release(); pLin.release(); pN.release()
        // support открывается только стойкостью: persist>=0.6, иначе зерно
        // само себя подтверждает через blur(amp,15).
        val pgN = Mat(); org.opencv.core.Core.subtract(persist, org.opencv.core.Scalar(0.6), pgN)
        org.opencv.core.Core.multiply(pgN, org.opencv.core.Scalar(1.0 / 0.2), pgN)
        Imgproc.threshold(pgN, pgN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(pgN, pgN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val pgS = Mat(); org.opencv.core.Core.multiply(pgN, pgN, pgS)
        val pgL = Mat(); org.opencv.core.Core.multiply(pgN, org.opencv.core.Scalar(-2.0), pgL)
        org.opencv.core.Core.add(pgL, org.opencv.core.Scalar(3.0), pgL)
        val pgate = Mat(); org.opencv.core.Core.multiply(pgS, pgL, pgate)
        pgS.release(); pgL.release(); pgN.release()
        // Цвет в разделении: настоящая фактура — люминантная (dY >> dC),
        // цветовое пятно — хроминантное (dC >> dY). Отношение R = cohLum/(cohChr+2):
        // R>2 — фактура, R<0.6 — пятно, возврат давится. Хрома при этом всё равно
        // не возвращается (только люма-дельта) — цветной шум не воскресает.
        val dCr = Mat(); org.opencv.core.Core.absdiff(oCh[1], sCh[1], dCr)
        val dCb = Mat(); org.opencv.core.Core.absdiff(oCh[2], sCh[2], dCb)
        val chromaD = Mat(); org.opencv.core.Core.add(dCr, dCb, chromaD); dCr.release(); dCb.release()
        val cohC = Mat(); Imgproc.blur(chromaD, cohC, org.opencv.core.Size(5.0, 5.0)); chromaD.release()
        val lumF = Mat(); coh3.convertTo(lumF, CvType.CV_32FC1)
        val chrF = Mat(); cohC.convertTo(chrF, CvType.CV_32FC1); cohC.release()
        org.opencv.core.Core.add(chrF, org.opencv.core.Scalar(2.0), chrF)
        val ratio = Mat(); org.opencv.core.Core.divide(lumF, chrF, ratio); lumF.release(); chrF.release()
        val rN = Mat(); org.opencv.core.Core.subtract(ratio, org.opencv.core.Scalar(0.6), rN); ratio.release()
        org.opencv.core.Core.multiply(rN, org.opencv.core.Scalar(1.0 / 1.4), rN)
        Imgproc.threshold(rN, rN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(rN, rN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val rSq = Mat(); org.opencv.core.Core.multiply(rN, rN, rSq)
        val rLin = Mat(); org.opencv.core.Core.multiply(rN, org.opencv.core.Scalar(-2.0), rLin)
        org.opencv.core.Core.add(rLin, org.opencv.core.Scalar(3.0), rLin)
        val penC = Mat(); org.opencv.core.Core.multiply(rSq, rLin, penC)
        rSq.release(); rLin.release(); rN.release()
        val colorF = Mat(); org.opencv.core.Core.multiply(penC, org.opencv.core.Scalar(0.75), colorF)
        org.opencv.core.Core.add(colorF, org.opencv.core.Scalar(0.25), colorF); penC.release()
        // Вето не режет организованные кромки: у цветного края (наклейка, текст)
        // есть и хроматический скачок, и направление — это не пятно, возврат нужен.
        // Без этого цветные края мылятся, а серые остаются резкими.
        val dirForC = Mat(); org.opencv.core.Core.multiply(dirCoh, org.opencv.core.Scalar(0.9), dirForC)
        val colorF2 = Mat(); org.opencv.core.Core.max(colorF, dirForC, colorF2)
        colorF.release(); dirForC.release()
        // Веса: w3 по (t0,t1), w5 по ужатым порогам (зерно на 5x5 разбавлено ~2.5x).
        fun lutFor(a0: Float, a1: Float): Mat {
            val l = Mat(1, 256, CvType.CV_8UC1)
            val lb = ByteArray(256)
            for (i in 0..255) {
                var wgt = ((i - a0) / (a1 - a0)).coerceIn(0f, 1f)
                wgt = wgt * wgt * (3f - 2f * wgt)
                lb[i] = (wgt * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            l.put(0, 0, lb)
            return l
        }
        val lut3 = lutFor(t0, t1)
        val lut5 = lutFor(t0 * 0.45f, t1 * 0.5f)
        val lut9 = lutFor(t0 * 0.25f, t1 * 0.3f)
        val w3b = Mat(); org.opencv.core.Core.LUT(coh3, lut3, w3b); coh3.release(); lut3.release()
        val w5b = Mat(); org.opencv.core.Core.LUT(coh5, lut5, w5b); coh5.release(); lut5.release()
        val w9b = Mat(); org.opencv.core.Core.LUT(coh9, lut9, w9b); coh9.release(); lut9.release()
        val w3F = Mat(); w3b.convertTo(w3F, CvType.CV_32FC1, 1.0 / 255.0); w3b.release()
        val w5F = Mat(); w5b.convertTo(w5F, CvType.CV_32FC1, 1.0 / 255.0); w5b.release()
        val w9F = Mat(); w9b.convertTo(w9F, CvType.CV_32FC1, 1.0 / 255.0); w9b.release()
        // Амплитуда: структура хоть на одной шкале. Свидетельство: направление
        // ИЛИ стойкость между масштабами. Гейт: smoothstep((S-0.25)/0.55).
        val amp35 = Mat(); org.opencv.core.Core.max(w3F, w5F, amp35); w3F.release(); w5F.release()
        val amp0 = Mat(); org.opencv.core.Core.max(amp35, w9F, amp0); amp35.release(); w9F.release()
        // Якорь: глобально подтверждённый край + локальное направление =
        // почти полный возврат (края резкие и аккуратные, а не мыльные).
        val gStrN = Mat(); org.opencv.core.Core.subtract(globalE, org.opencv.core.Scalar(0.35), gStrN)
        org.opencv.core.Core.multiply(gStrN, org.opencv.core.Scalar(1.0 / 0.3), gStrN)
        Imgproc.threshold(gStrN, gStrN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(gStrN, gStrN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val gStrS = Mat(); org.opencv.core.Core.multiply(gStrN, gStrN, gStrS)
        val gStrL = Mat(); org.opencv.core.Core.multiply(gStrN, org.opencv.core.Scalar(-2.0), gStrL)
        org.opencv.core.Core.add(gStrL, org.opencv.core.Scalar(3.0), gStrL)
        val gStr = Mat(); org.opencv.core.Core.multiply(gStrS, gStrL, gStr)
        gStrS.release(); gStrL.release(); gStrN.release()
        val anchor = Mat(); org.opencv.core.Core.multiply(gStr, dirCoh, anchor); gStr.release()
        val amp = Mat(); org.opencv.core.Core.max(amp0, anchor, amp); amp0.release(); anchor.release()
        // persist требует опоры на глобалку: кластеры 3-6px стойкие на ×2,
        // но невидимы на ×8 — без глобалки гаснут, фактура проходит.
        val gW = Mat(); org.opencv.core.Core.multiply(globalE, org.opencv.core.Scalar(0.8), gW)
        org.opencv.core.Core.add(gW, org.opencv.core.Scalar(0.2), gW); globalE.release()
        // Опора региона — только по стойкости (см. pgate выше).
        val persistG = Mat(); org.opencv.core.Core.multiply(persist, gW, persistG)
        persist.release(); gW.release()
        // Опора региона: равномерная фактура держится соседями (blur amp 15),
        // одиночный кластер — нет. Линии спасаются направлением, а не опорой.
        val sup15 = Mat(); Imgproc.blur(amp, sup15, org.opencv.core.Size(15.0, 15.0))
        val supN = Mat(); org.opencv.core.Core.subtract(sup15, org.opencv.core.Scalar(0.1), supN); sup15.release()
        org.opencv.core.Core.multiply(supN, org.opencv.core.Scalar(1.0 / 0.3), supN)
        Imgproc.threshold(supN, supN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(supN, supN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val supS = Mat(); org.opencv.core.Core.multiply(supN, supN, supS)
        val supL = Mat(); org.opencv.core.Core.multiply(supN, org.opencv.core.Scalar(-2.0), supL)
        org.opencv.core.Core.add(supL, org.opencv.core.Scalar(3.0), supL)
        val support = Mat(); org.opencv.core.Core.multiply(supS, supL, support)
        supS.release(); supL.release(); supN.release()
        val tmpS = Mat(); org.opencv.core.Core.max(dirCoh, persistG, tmpS)
        dirCoh.release(); persistG.release()
        val supportG = Mat(); org.opencv.core.Core.multiply(support, pgate, supportG)
        support.release(); pgate.release()
        val struct = Mat(); org.opencv.core.Core.max(tmpS, supportG, struct); tmpS.release(); supportG.release()
        val gN = Mat(); org.opencv.core.Core.subtract(struct, org.opencv.core.Scalar(0.40), gN); struct.release()
        org.opencv.core.Core.multiply(gN, org.opencv.core.Scalar(1.0 / 0.35), gN)
        Imgproc.threshold(gN, gN, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(gN, gN, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val gSq = Mat(); org.opencv.core.Core.multiply(gN, gN, gSq)
        val gLin = Mat(); org.opencv.core.Core.multiply(gN, org.opencv.core.Scalar(-2.0), gLin)
        org.opencv.core.Core.add(gLin, org.opencv.core.Scalar(3.0), gLin)
        val gate = Mat(); org.opencv.core.Core.multiply(gSq, gLin, gate)
        gSq.release(); gLin.release(); gN.release()
        val wAg = Mat(); org.opencv.core.Core.multiply(amp, gate, wAg); amp.release(); gate.release()
        val wAgC = Mat(); org.opencv.core.Core.multiply(wAg, colorF2, wAgC); wAg.release(); colorF2.release()
        val edgeF = Mat(); org.opencv.core.Core.multiply(proxF, org.opencv.core.Scalar(0.75), edgeF)
        org.opencv.core.Core.add(edgeF, org.opencv.core.Scalar(0.25), edgeF); proxF.release()
        val wAgE = Mat(); org.opencv.core.Core.multiply(wAgC, edgeF, wAgE); wAgC.release(); edgeF.release()
        // Сгладить карту весов 3x3 — переходы возврата без пятен (против акварели).
        val wF = Mat()
        Imgproc.blur(wAgE, wF, org.opencv.core.Size(3.0, 3.0)); wAgE.release()
        val yOf = Mat(); yO.convertTo(yOf, CvType.CV_32FC1); yO.release()
        val ySf = Mat(); yS.convertTo(ySf, CvType.CV_32FC1); yS.release()
        val dSign = Mat(); org.opencv.core.Core.subtract(yOf, ySf, dSign); yOf.release()
        val weighted = Mat(); org.opencv.core.Core.multiply(dSign, wF, weighted); dSign.release(); wF.release()
        val yOutF = Mat(); org.opencv.core.Core.add(ySf, weighted, yOutF); ySf.release(); weighted.release()
        val yOut = Mat(); yOutF.convertTo(yOut, CvType.CV_8UC1); yOutF.release()
        yOut.copyTo(sCh[0]); yOut.release()
        oCh.forEach { it.release() }
        val merged = Mat(); org.opencv.core.Core.merge(sCh, merged)
        sCh.forEach { it.release() }
        val rgbOut = Mat(); Imgproc.cvtColor(merged, rgbOut, Imgproc.COLOR_YCrCb2RGB); merged.release()
        val rgbaOut = Mat(); Imgproc.cvtColor(rgbOut, rgbaOut, Imgproc.COLOR_RGB2RGBA); rgbOut.release()
        val result = matToBitmap(rgbaOut); rgbaOut.release()
        return result
    }

    /**
     * Глобальная доводка текстуры (отдельная отключаемая стадия, v2):
     * 1. Глобалка: вход шумодава ×8 → Собель → Otsu-края → dilate в пятна →
     *    connectedComponents = регионы-кандидаты (шум на ×8 мёртв).
     * 2. Локальная проверка каждого региона: средний |orig-res| в регионе выше
     *    порога (иначе это кластер шума — пропускаем).
     * 3. Якорь тона региона = средний цвет res в регионе («приводить к одному»).
     * Доводка живёт ТОЛЬКО у краёв (полоса вокруг глобальных границ): там ровняем
     * грань и тон, вдали — без изменений (иначе полкартинки в сером дыму).
     * 5. Текстура к единому виду: gain = sqrt(Areg/Aloc) в [0.5,1] давит всплески
     *    (песок) к уровню региона, тело не трогает, буста вверх нет.
     * out = res + corr, затем деталь × gain. ВЧ-структура сохраняется.
     * origFull — вход шумодава (структура сцены); даунскейл ×8 делается внутри.
     * Возвращает НОВЫЙ битмап либо тот же res, если нечего делать.
     */
    fun globalTextureRefine(origFull: Bitmap, res: Bitmap, t0: Float): Bitmap {
        val w = res.width; val h = res.height
        if (origFull.width < 64 || origFull.height < 64 || w < 64 || h < 64) return res
        return try {
            val sw = (origFull.width / 8).coerceAtLeast(16)
            val sh = (origFull.height / 8).coerceAtLeast(16)
            // --- Глобалка: даунскейл ×8 входа шумодава (структура сцены, шум мёртв) ---
            val gMat = Mat(); org.opencv.android.Utils.bitmapToMat(origFull, gMat)
            val gFull = Mat(); Imgproc.cvtColor(gMat, gFull, Imgproc.COLOR_RGBA2GRAY); gMat.release()
            val g = Mat()
            Imgproc.resize(gFull, g, org.opencv.core.Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            gFull.release()
            val gb = Mat(); Imgproc.blur(g, gb, org.opencv.core.Size(5.0, 5.0)); g.release()
            val ex = Mat(); Imgproc.Sobel(gb, ex, CvType.CV_32FC1, 1, 0, 3)
            val ey = Mat(); Imgproc.Sobel(gb, ey, CvType.CV_32FC1, 0, 1, 3)
            val ax = Mat(); org.opencv.core.Core.convertScaleAbs(ex, ax); ex.release()
            val ay = Mat(); org.opencv.core.Core.convertScaleAbs(ey, ay); ey.release()
            val mag = Mat(); org.opencv.core.Core.add(ax, ay, mag); ax.release(); ay.release()
            val tmpE = Mat()
            val otsu = Imgproc.threshold(mag, tmpE, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            tmpE.release()
            // Чувствительность: порог 45% Otsu — слабые, но связные края тоже становятся
            // регионами. Мусор отсекается дальше связностью (компоненты) и проверкой региона.
            val edge = Mat()
            Imgproc.threshold(mag, edge, otsu * 0.45, 255.0, Imgproc.THRESH_BINARY)
            mag.release()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
            val blobs = Mat()
            Imgproc.dilate(edge, blobs, kernel, org.opencv.core.Point(-1.0, -1.0), 3)
            // Полоса применения: шире ядра — чистим окрестность кромки, не саму линию.
            val bandB0 = Mat()
            Imgproc.dilate(edge, bandB0, kernel, org.opencv.core.Point(-1.0, -1.0), 3)
            // Ядро кромки (±16px): сама линия не трогается ни тоном, ни gain.
            // Узкое ядро не держало расползание билинейного апскейла карт.
            val coreD = Mat()
            Imgproc.dilate(edge, coreD, kernel, org.opencv.core.Point(-1.0, -1.0), 1)
            val coreArr = ByteArray(sw * sh); coreD.get(0, 0, coreArr); coreD.release()
            edge.release(); kernel.release()
            val bandB = Mat(); Imgproc.blur(bandB0, bandB, org.opencv.core.Size(5.0, 5.0)); bandB0.release()
            val labels = Mat()
            val nLab = Imgproc.connectedComponents(blobs, labels)
            blobs.release()
            if (nLab <= 1 || nLab > 500) {
                labels.release(); gb.release()
                return res
            }
            // --- Малый res + локальные свидетельства для проверки ---
            val rMat = Mat(); org.opencv.android.Utils.bitmapToMat(res, rMat)
            val rSmall = Mat()
            Imgproc.resize(rMat, rSmall, gb.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            rMat.release()
            val rGray = Mat(); Imgproc.cvtColor(rSmall, rGray, Imgproc.COLOR_RGBA2GRAY)
            val diffS = Mat(); org.opencv.core.Core.absdiff(gb, rGray, diffS); gb.release()
            // Локальная амплитуда детали: |res − blur(res)|, сглаженная — для карты усиления.
            val grayLow = Mat(); Imgproc.blur(rGray, grayLow, org.opencv.core.Size(5.0, 5.0))
            val dAbs = Mat(); org.opencv.core.Core.absdiff(rGray, grayLow, dAbs); grayLow.release(); rGray.release()
            val ampLoc = Mat(); Imgproc.blur(dAbs, ampLoc, org.opencv.core.Size(5.0, 5.0)); dAbs.release()
            val evS = Mat(); Imgproc.blur(diffS, evS, org.opencv.core.Size(3.0, 3.0)); diffS.release()
            val labArr = IntArray(sw * sh); labels.get(0, 0, labArr); labels.release()
            val evArr = ByteArray(sw * sh); evS.get(0, 0, evArr); evS.release()
            val ampArr = ByteArray(sw * sh); ampLoc.get(0, 0, ampArr); ampLoc.release()
            val bandArr = ByteArray(sw * sh); bandB.get(0, 0, bandArr); bandB.release()
            // Низкие частоты res: якоря и коррекция считаются по ним (ВЧ-текстура не участвует).
            val resLow = Mat(); Imgproc.blur(rSmall, resLow, org.opencv.core.Size(15.0, 15.0)); rSmall.release()
            val resLowBmp = matToBitmap(resLow); resLow.release()
            val resPx = IntArray(sw * sh)
            resLowBmp.getPixels(resPx, 0, sw, 0, 0, sw, sh)
            // --- Аккумуляторы по регионам ---
            val cnt = IntArray(nLab)
            val sumR = LongArray(nLab); val sumG = LongArray(nLab); val sumB = LongArray(nLab)
            val sumEv = LongArray(nLab)
            val sumAmp = LongArray(nLab)
            for (i in labArr.indices) {
                val l = labArr[i]
                if (l <= 0 || l >= nLab) continue
                cnt[l]++
                val p = resPx[i]
                sumR[l] += ((p shr 16) and 0xFF).toLong()
                sumG[l] += ((p shr 8) and 0xFF).toLong()
                sumB[l] += (p and 0xFF).toLong()
                sumEv[l] += (evArr[i].toInt() and 0xFF).toLong()
                sumAmp[l] += (ampArr[i].toInt() and 0xFF).toLong()
            }
            val evThr = maxOf(3.5f, t0 * 1.3f)
            val ok = BooleanArray(nLab)
            var okCount = 0
            for (l in 1 until nLab) {
                if (cnt[l] >= 50 && sumEv[l].toFloat() / cnt[l] > evThr) { ok[l] = true; okCount++ }
            }
            if (okCount == 0) { try { resLowBmp.recycle() } catch (_: Exception) {}; return res }
            val meanR = IntArray(nLab); val meanG = IntArray(nLab); val meanB = IntArray(nLab)
            for (l in 1 until nLab) {
                if (!ok[l]) continue
                meanR[l] = (sumR[l] / cnt[l]).toInt().coerceIn(0, 255)
                meanG[l] = (sumG[l] / cnt[l]).toInt().coerceIn(0, 255)
                meanB[l] = (sumB[l] / cnt[l]).toInt().coerceIn(0, 255)
            }
            // --- Типичная амплитуда региона → карта давки всплесков (только вниз!) ---
            // gain = sqrt(Areg/(Aloc+1)) в [0.5, 1.0]: всплеск (песок) давится к уровню
            // региона, тело фактуры (gain=1) не трогается. Буста вверх нет — шум не качаем.
            val regAmp = FloatArray(nLab)
            for (l in 1 until nLab) {
                if (ok[l] && cnt[l] > 0) regAmp[l] = sumAmp[l].toFloat() / cnt[l]
            }
            val gainPx = IntArray(sw * sh)
            for (i in labArr.indices) {
                val l = labArr[i]
                val bf = (bandArr[i].toInt() and 0xFF)
                if (l > 0 && l < nLab && ok[l] && bf > 0 && (coreArr[i].toInt() and 0xFF) == 0) {
                    val al = (ampArr[i].toInt() and 0xFF).toFloat()
                    val gg = kotlin.math.sqrt(regAmp[l] / (al + 1f)).coerceIn(0.4f, 1.0f)
                    val v = (gg * 128f + 0.5f).toInt().coerceIn(51, 128)
                    // К 1.0 по мере удаления от края: вдали деталь не трогаем.
                    val m = (128 - (128 - v) * bf / 255).coerceIn(51, 128)
                    gainPx[i] = -0x1000000 or (m shl 16) or (m shl 8) or m
                } else {
                    gainPx[i] = -0x1000000 or (128 shl 16) or (128 shl 8) or 128
                }
            }
            // --- Коррекция тона: corr = 0.5*(якорь − низкий), растушёвка 7x7 ---
            // Переход ~30px в полном разрешении — ступень невидима.
            val corrPx = IntArray(sw * sh)
            for (i in labArr.indices) {
                val l = labArr[i]
                val bf = (bandArr[i].toInt() and 0xFF)
                if (l > 0 && l < nLab && ok[l] && bf > 0 && (coreArr[i].toInt() and 0xFF) == 0) {
                    val p = resPx[i]
                    corrPx[i] = -0x1000000 or
                        ((((meanR[l] - ((p shr 16) and 0xFF)) * bf / 510 + 128).coerceIn(0, 255)) shl 16) or
                        ((((meanG[l] - ((p shr 8) and 0xFF)) * bf / 510 + 128).coerceIn(0, 255)) shl 8) or
                        (((meanB[l] - (p and 0xFF)) * bf / 510 + 128).coerceIn(0, 255))
                } else {
                    corrPx[i] = -0x1000000 or (128 shl 16) or (128 shl 8) or 128
                }
            }
            val corrSmall0 = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            corrSmall0.setPixels(corrPx, 0, sw, 0, 0, sw, sh)
            val corrMat0 = Mat(); org.opencv.android.Utils.bitmapToMat(corrSmall0, corrMat0)
            try { corrSmall0.recycle() } catch (_: Exception) {}
            val corrMat = Mat()
            Imgproc.blur(corrMat0, corrMat, org.opencv.core.Size(11.0, 11.0)); corrMat0.release()
            val corrSmall = matToBitmap(corrMat); corrMat.release()
            val corrFull = Bitmap.createScaledBitmap(corrSmall, w, h, true)
            try { corrSmall.recycle() } catch (_: Exception) {}
            val gainSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            gainSmall.setPixels(gainPx, 0, sw, 0, 0, sw, sh)
            val gainFull = Bitmap.createScaledBitmap(gainSmall, w, h, true)
            try { gainSmall.recycle() } catch (_: Exception) {}
            val lowFull = Bitmap.createScaledBitmap(resLowBmp, w, h, true)
            try { resLowBmp.recycle() } catch (_: Exception) {}
            // Проход 1 (тон): out = res + (corr-128).
            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val band = 256
            val bRes = IntArray(w * band); val bCor = IntArray(w * band); val bOut = IntArray(w * band)
            var y0 = 0
            while (y0 < h) {
                val bh = minOf(band, h - y0)
                res.getPixels(bRes, 0, w, 0, y0, w, bh)
                corrFull.getPixels(bCor, 0, w, 0, y0, w, bh)
                for (i in 0 until w * bh) {
                    val c = bCor[i]
                    val dr = ((c shr 16) and 0xFF) - 128
                    val dg = ((c shr 8) and 0xFF) - 128
                    val db = (c and 0xFF) - 128
                    if (dr == 0 && dg == 0 && db == 0) { bOut[i] = bRes[i]; continue }
                    val p = bRes[i]
                    bOut[i] = -0x1000000 or
                        ((((p shr 16) and 0xFF) + dr).coerceIn(0, 255) shl 16) or
                        ((((p shr 8) and 0xFF) + dg).coerceIn(0, 255) shl 8) or
                        (((p and 0xFF) + db).coerceIn(0, 255))
                }
                outBmp.setPixels(bOut, 0, w, 0, y0, w, bh)
                y0 += bh
            }
            try { corrFull.recycle() } catch (_: Exception) {}
            // Проход 2 (текстура, in-place): out += (res-low)*(g-128)/128.
            // gain<=1 всегда: всплески давятся, тело фактуры и тон целы.
            val bRw = IntArray(w * band); val bRs = IntArray(w * band)
            val bLw = IntArray(w * band); val bGn = IntArray(w * band)
            var y2 = 0
            while (y2 < h) {
                val bh = minOf(band, h - y2)
                outBmp.getPixels(bRw, 0, w, 0, y2, w, bh)
                res.getPixels(bRs, 0, w, 0, y2, w, bh)
                lowFull.getPixels(bLw, 0, w, 0, y2, w, bh)
                gainFull.getPixels(bGn, 0, w, 0, y2, w, bh)
                for (i in 0 until w * bh) {
                    val gd = ((bGn[i] shr 16) and 0xFF) - 128
                    if (gd == 0) continue
                    val p = bRs[i]; val q = bLw[i]
                    val dr = (((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) * gd / 128
                    val dg = (((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) * gd / 128
                    val db = ((p and 0xFF) - (q and 0xFF)) * gd / 128
                    if (dr == 0 && dg == 0 && db == 0) continue
                    val o = bRw[i]
                    bRw[i] = -0x1000000 or
                        ((((o shr 16) and 0xFF) + dr).coerceIn(0, 255) shl 16) or
                        ((((o shr 8) and 0xFF) + dg).coerceIn(0, 255) shl 8) or
                        (((o and 0xFF) + db).coerceIn(0, 255))
                }
                outBmp.setPixels(bRw, 0, w, 0, y2, w, bh)
                y2 += bh
            }
            try { lowFull.recycle() } catch (_: Exception) {}
            try { gainFull.recycle() } catch (_: Exception) {}
            outBmp
        } catch (_: Throwable) { res }
    }

    private fun kotlinSoft(work: Bitmap, radius: Int, blend: Float): Bitmap {
        val w = work.width; val h = work.height
        val orig = IntArray(w * h)
        work.getPixels(orig, 0, w, 0, 0, w, h)
        var a = orig.copyOf(); var b = IntArray(w * h)
        repeat(3) {
            boxBlurH(a, b, w, h, radius)
            boxBlurV(b, a, w, h, radius)
        }
        val out = IntArray(w * h)
        for (i in orig.indices) {
            val p = orig[i]; val q = a[i]
            val r = (((p shr 16) and 0xFF) * (1 - blend) + ((q shr 16) and 0xFF) * blend).toInt()
            val g = (((p shr 8) and 0xFF) * (1 - blend) + ((q shr 8) and 0xFF) * blend).toInt()
            val bl = ((p and 0xFF) * (1 - blend) + (q and 0xFF) * blend).toInt()
            out[i] = -0x1000000 or (r shl 16) or (g shl 8) or bl
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (y in -r..r) {
                val px = src[y.coerceIn(0, h - 1) * w + x]
                sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = -0x1000000 or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val yOut = (y - r).coerceIn(0, h - 1); val yIn = (y + r + 1).coerceIn(0, h - 1)
                val po = src[yOut * w + x]; val pi = src[yIn * w + x]
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0
            val row = y * w
            for (x in -r..r) {
                val px = src[row + x.coerceIn(0, w - 1)]
                sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = -0x1000000 or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val xOut = (x - r).coerceIn(0, w - 1); val xIn = (x + r + 1).coerceIn(0, w - 1)
                val po = src[row + xOut]; val pi = src[row + xIn]
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }

    /**
     * Конвертировать Bitmap в Mat (для OpenCV)
     */
    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(bitmap, mat)
            // Convert RGBA -> BGR if needed (Photo expects 8UC3)
            if (mat.channels() == 4) {
                val tmp = Mat()
                Imgproc.cvtColor(mat, tmp, Imgproc.COLOR_RGBA2RGB)
                mat.release()
                return tmp
            }
        } catch (t: Throwable) {
            // fallback: manual
            val fallback = Mat(bitmap.height, bitmap.width, CvType.CV_8UC3)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bytes = ByteArray(bitmap.width * bitmap.height * 3)
            for (i in pixels.indices) {
                bytes[i * 3] = Color.blue(pixels[i]).toByte()
                bytes[i * 3 + 1] = Color.green(pixels[i]).toByte()
                bytes[i * 3 + 2] = Color.red(pixels[i]).toByte()
            }
            fallback.put(0, 0, bytes)
            return fallback
        }
        return mat
    }

    /**
     * Конвертировать Mat в Bitmap (для Android)
     */
    private fun matToBitmap(mat: Mat): Bitmap {
        var rgba = mat
        var tmp: Mat? = null
        if (mat.channels() == 3) {
            tmp = Mat()
            Imgproc.cvtColor(mat, tmp, Imgproc.COLOR_RGB2RGBA)
            rgba = tmp
        }
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        try {
            org.opencv.android.Utils.matToBitmap(rgba, bitmap)
        } catch (t: Throwable) {
            val pixels = IntArray(rgba.cols() * rgba.rows())
            // very rough fallback
            bitmap.setPixels(pixels, 0, rgba.cols(), 0, 0, rgba.cols(), rgba.rows())
        }
        tmp?.release()
        return bitmap
    }
}
