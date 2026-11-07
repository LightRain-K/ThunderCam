package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Порт C:\Users\pc\denoiser\thundercam_pipe_v2.py (лучший по бенчмарку)
 * + локальные куски thundercam_common.py лежат в DenoiseCommon.
 *
 * Правила порта (чтобы развитие Python переносилось построчно):
 * - имена функций/переменных и константы — 1:1 с Python;
 * - порядок стадий тот же; отклонения помечены [DEV];
 * - вход/выход Bitmap ARGB_8888, внутри RGB 8U + float32 Mat (numpy-float32);
 * - Python считает BGR — нам без разницы (Cr/Cb симметричны), работаем в RGB;
 * - владение: функции НЕ забирают входные Mat/Bitmap, отдают новые;
 *   Bitmap-вход ресайклит вызыватель при замене (как старый путь).
 */
object DenoiseV2 {

    data class Sig(val med: Double, val dark: Double)
    data class Denoised(
        val yFlat: Mat, // 8U
        val crO: Mat, // 8U, финальная (возврат + gray-pull)
        val cbO: Mat, // 8U, финальная
        val sm: Double,
        val sd: Double,
    )

    /**
     * Локальная estimate_sigma_two из thundercam_pipe_v2.py
     * (НЕ улучшенная из common — v2 пиннит её ради бит-точности).
     * yF — CV_32F 0..255.
     */
    fun estimateSigmaTwo(yF: Mat): Sig {
        val k = Mat(3, 3, CvType.CV_32FC1)
        k.put(0, 0, floatArrayOf(1f, -2f, 1f, -2f, 4f, -2f, 1f, -2f, 1f))
        val lapS = Mat()
        Imgproc.filter2D(yF, lapS, CvType.CV_32F, k)
        k.release()
        val lap = Mat()
        Core.absdiff(lapS, Scalar(0.0), lap)
        lapS.release()
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(yF, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(yF, gy, CvType.CV_32F, 0, 1, 3)
        val ax = Mat(); Core.absdiff(gx, Scalar(0.0), ax); gx.release()
        val ay = Mat(); Core.absdiff(gy, Scalar(0.0), ay); gy.release()
        val mag = Mat(); Core.add(ax, ay, mag)
        ax.release(); ay.release()
        val flat = Mat()
        Core.compare(mag, Scalar(20.0), flat, Core.CMP_LT)
        mag.release()
        if (Core.countNonZero(flat) == 0) {
            flat.setTo(Scalar(255.0))
        }
        val blur2 = Mat()
        Imgproc.GaussianBlur(yF, blur2, Size(0.0, 0.0), 2.0)
        val hp = Mat(); Core.subtract(yF, blur2, hp); blur2.release()
        val ahp = Mat(); Core.absdiff(hp, Scalar(0.0), ahp); hp.release()

        fun binMask(lo: Double, hi: Double): Mat {
            val ge = Mat(); Core.compare(yF, Scalar(lo), ge, Core.CMP_GE)
            val lt = Mat(); Core.compare(yF, Scalar(hi), lt, Core.CMP_LT)
            val m = Mat(); Core.bitwise_and(ge, lt, m)
            ge.release(); lt.release()
            return m
        }
        val medM = binMask(60.0, 180.0)
        val darkM = Mat(); Core.compare(yF, Scalar(80.0), darkM, Core.CMP_LT)

        fun immerk(mask: Mat): Double =
            1.2533 * Core.mean(lap, mask).`val`[0] / 6.0

        fun madOver(mask: Mat, fallback: Double): Double {
            val med = medianMasked(ahp, mask)
            return if (med == null) fallback * 2.0 else med / 0.6745
        }
        fun robust(bin: Mat): Double {
            val mf = Mat(); Core.bitwise_and(bin, flat, mf)
            val immer = if (Core.countNonZero(mf) > 500) {
                immerk(mf)
            } else if (Core.countNonZero(flat) > 0) {
                immerk(flat)
            } else {
                4.0
            }
            val mad = madOver(bin, immer)
            val s = maxOf(immer, mad / 2.2)
            mf.release()
            return s
        }
        val sMed = robust(medM)
        val sDark = robust(darkM)
        val allOnes = Mat(yF.size(), CvType.CV_8UC1, Scalar(255.0))
        val sAll = robust(allOnes)
        allOnes.release()
        medM.release(); darkM.release()
        lap.release(); ahp.release(); flat.release()
        val sigmaMed = sMed.coerceIn(2.5, 30.0)
        val sigmaDark = maxOf(sDark, sAll, sigmaMed * 1.4).coerceIn(4.0, 30.0)
        return Sig(sigmaMed, sigmaDark)
    }

    /** Медиана значений src (CV_32F) под маской 8U. null если пусто. */
    private fun medianMasked(src: Mat, mask: Mat): Double? {
        val w = src.cols(); val h = src.rows()
        val sv = FloatArray(w * h)
        src.get(0, 0, sv)
        val mv = ByteArray(w * h)
        mask.get(0, 0, mv)
        var n = 0
        for (i in sv.indices) if (mv[i].toInt() != 0) n++
        if (n == 0) return null
        val arr = FloatArray(n)
        var j = 0
        for (i in sv.indices) if (mv[i].toInt() != 0) arr[j++] = sv[i]
        arr.sort()
        return if (n % 2 == 1) arr[n / 2].toDouble()
        else (arr[n / 2 - 1] + arr[n / 2]).toDouble() / 2.0
    }

    /** _soft_edge_mask: мягкая маска кромок 0..1 (CV_32F). yDen — 8U. */
    fun softEdgeMask(yDen: Mat, sigmaMed: Double): Mat {
        val thr = 20.0 + 3.0 * sigmaMed
        val pre = Mat()
        Imgproc.GaussianBlur(yDen, pre, Size(0.0, 0.0), 1.2)
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(pre, gx, CvType.CV_16S, 1, 0, 3)
        Imgproc.Sobel(pre, gy, CvType.CV_16S, 0, 1, 3)
        pre.release()
        val ax = Mat(); Core.convertScaleAbs(gx, ax); gx.release()
        val ay = Mat(); Core.convertScaleAbs(gy, ay); gy.release()
        val mag8 = Mat(); Core.add(ax, ay, mag8)
        ax.release(); ay.release()
        val mag = Mat(); mag8.convertTo(mag, CvType.CV_32FC1); mag8.release()
        val sub = Mat(); Core.subtract(mag, Scalar(thr), mag)
        mag.release()
        Core.multiply(sub, Scalar(1.0 / maxOf(thr, 1e-6)), sub)
        val e = DenoiseCommon.matSmoothstep(sub); sub.release()
        val e8 = Mat(); e.convertTo(e8, CvType.CV_8UC1, 255.0); e.release()
        val dil = Mat()
        Imgproc.dilate(e8, dil, Mat.ones(3, 3, CvType.CV_8UC1))
        e8.release()
        val dilF = Mat(); dil.convertTo(dilF, CvType.CV_32FC1, 1.0 / 255.0); dil.release()
        val res = Mat(); Imgproc.blur(dilF, res, Size(5.0, 5.0)); dilF.release()
        return res
    }

    /** smoothstep((c - m)/d) поэлементно, m — CV_32F. Возвращает новый Mat. */
    private fun sstepInv(m: Mat, c: Double, d: Double): Mat {
        val t = Mat()
        Core.subtract(Mat(m.size(), m.type(), Scalar(c)), m, t)
        Core.multiply(t, Scalar(1.0 / d), t)
        val r = DenoiseCommon.matSmoothstep(t)
        t.release()
        return r
    }

    /** out = a*(1-w) + b*w. Входы не трогаем, вес w — Mat. */
    private fun blendToward(a: Mat, b: Mat, w: Mat): Mat {
        val one = Mat(); Core.subtract(Mat(w.size(), w.type(), Scalar(1.0)), w, one)
        val t1 = Mat(); Core.multiply(a, one, t1); one.release()
        val t2 = Mat(); Core.multiply(b, w, t2)
        val r = Mat(); Core.add(t1, t2, r)
        t1.release(); t2.release()
        return r
    }

    /** dst = dst*(1-w*mix) + src*(w*mix), in-place в dst. */
    private fun blendTowardInPlace(dst: Mat, src: Mat, w: Mat, mix: Double) {
        val wm = Mat(); Core.multiply(w, Scalar(mix), wm)
        val one = Mat(); Core.subtract(Mat(wm.size(), wm.type(), Scalar(1.0)), wm, one)
        val t1 = Mat(); Core.multiply(dst, one, t1); one.release()
        val t2 = Mat(); Core.multiply(src, wm, t2); wm.release()
        Core.add(t1, t2, dst)
        t1.release(); t2.release()
    }

    /**
     * denoise_ycrcb_v2. rgb — 8UC3 (не забираем).
     * Возвращает мытые каналы 8U + сигмы. clean=true — агрессивный дефолт.
     */
    fun denoiseYcrcbV2(
        rgb: Mat,
        clean: Boolean = true,
        sigmaOverride: Sig? = null,
    ): Denoised {
        val ycc = Mat()
        Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val ch = ArrayList<Mat>(3)
        Core.split(ycc, ch)
        ycc.release()
        val y = ch[0]; val cr = ch[1]; val cb = ch[2]
        val yf = Mat(); y.convertTo(yf, CvType.CV_32FC1)

        val (sigmaMed, sigmaDark) = sigmaOverride ?: estimateSigmaTwo(yf)
        val (sWeak, sStrong) = if (clean) {
            (4.0 * sigmaMed).coerceIn(18.0, 50.0) to (9.0 * sigmaDark).coerceIn(35.0, 70.0)
        } else {
            (3.0 * sigmaMed).coerceIn(14.0, 40.0) to (7.0 * sigmaDark).coerceIn(25.0, 60.0)
        }
        val dW = if (sigmaMed <= 10) 5 else 7

        val yWeak = Mat()
        Imgproc.bilateralFilter(y, yWeak, dW, sWeak, 3.0 * dW)
        val yStrong = Mat()
        Imgproc.bilateralFilter(y, yStrong, 7, sStrong, 21.0)
        val med3 = Mat(); Imgproc.medianBlur(y, med3, 3)
        val medB = Mat(); Imgproc.GaussianBlur(med3, medB, Size(5.0, 5.0), 0.0)
        med3.release()
        val yMed = Mat(); medB.convertTo(yMed, CvType.CV_32FC1); medB.release()

        val yWeakF = Mat(); yWeak.convertTo(yWeakF, CvType.CV_32FC1); yWeak.release()
        val yStrongF = Mat(); yStrong.convertTo(yStrongF, CvType.CV_32FC1); yStrong.release()
        // вес сильного по тени (по weak, чтобы не дёргался от шума)
        val shadow = sstepInv(yWeakF, 110.0, 60.0)
        val shadowB = Mat(); Imgproc.blur(shadow, shadowB, Size(7.0, 7.0)); shadow.release()
        val yBase = blendToward(yWeakF, yStrongF, shadowB)
        yWeakF.release(); yStrongF.release(); shadowB.release()
        // в глубокой тени подмешиваем медиану
        val deep = sstepInv(yBase, 60.0, 30.0)
        val deepB = Mat(); Imgproc.blur(deep, deepB, Size(5.0, 5.0)); deep.release()
        blendTowardInPlace(yBase, yMed, deepB, if (clean) 0.7 else 0.5)
        yMed.release(); deepB.release()

        val yBase8 = Mat(); yBase.convertTo(yBase8, CvType.CV_8UC1); yBase.release()
        val edge = softEdgeMask(yBase8, sigmaMed)
        // half-refine
        var half = Mat(); Imgproc.pyrDown(yBase8, half)
        if (half.type() != CvType.CV_8UC1) {
            val t = Mat(); half.convertTo(t, CvType.CV_8UC1); half.release(); half = t
        }
        val halfF = Mat()
        Imgproc.bilateralFilter(half, halfF, 5, minOf(sStrong * 1.2, 45.0), 15.0)
        half.release()
        val eh = Mat()
        Imgproc.resize(edge, eh, halfF.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val hf = Mat(); halfF.convertTo(hf, CvType.CV_32FC1)
        val hff = Mat(); halfF.convertTo(hff, CvType.CV_32FC1)
        halfF.release()
        val flatH = blendToward(hff, hf, eh)
        hff.release(); hf.release(); eh.release()
        val up = Mat()
        Imgproc.resize(flatH, up, Size(yBase8.cols().toDouble(), yBase8.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        flatH.release()
        val yBaseF2 = Mat(); yBase8.convertTo(yBaseF2, CvType.CV_32FC1); yBase8.release()
        val yFlat = Mat()
        run {
            val a = Mat(); Core.multiply(yBaseF2, Scalar(if (clean) 0.35 else 0.5), a)
            val b = Mat(); Core.multiply(up, Scalar(if (clean) 0.65 else 0.5), b)
            Core.add(a, b, yFlat)
            a.release(); b.release()
        }
        yBaseF2.release(); up.release()
        val yFlat8 = Mat(); yFlat.convertTo(yFlat8, CvType.CV_8UC1); yFlat.release()

        val hfull = y.rows(); val wfull = y.cols()
        y.release()
        val smallSz = Size(maxOf(wfull / 6, 16).toDouble(), maxOf(hfull / 6, 16).toDouble())
        val halfSz = Size((wfull / 2).toDouble(), (hfull / 2).toDouble())
        fun cleanChroma(c: Mat): Mat {
            val small = Mat(); Imgproc.resize(c, small, smallSz, 0.0, 0.0, Imgproc.INTER_AREA)
            val med = Mat(); Imgproc.medianBlur(small, med, 5); small.release()
            val sm = Mat(); Imgproc.GaussianBlur(med, sm, Size(7.0, 7.0), 0.0); med.release()
            val cHalf = Mat(); Imgproc.resize(c, cHalf, halfSz, 0.0, 0.0, Imgproc.INTER_AREA)
            val washHalf = Mat(); Imgproc.resize(sm, washHalf, halfSz, 0.0, 0.0, Imgproc.INTER_LINEAR)
            sm.release()
            val bw = if (clean) 0.95 else 0.90
            val a = Mat(); Core.multiply(washHalf, Scalar(bw), a); washHalf.release()
            val b = Mat(); Core.multiply(cHalf, Scalar(1.0 - bw), b); cHalf.release()
            val outH = Mat(); Core.add(a, b, outH); a.release(); b.release()
            val res = Mat()
            Imgproc.resize(outH, res, Size(wfull.toDouble(), hfull.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            outH.release()
            return res
        }
        // исходную хрому держим во float для возврата ниже
        val crOF = Mat(); cr.convertTo(crOF, CvType.CV_32FC1)
        val cbOF = Mat(); cb.convertTo(cbOF, CvType.CV_32FC1)
        var washCr = cleanChroma(cr); cr.release()
        var washCb = cleanChroma(cb); cb.release()
        // в глубокой тени мойки 1/6 мало — вторым проходом гасим пятна
        var deepW = sstepInv(yFlat8, if (clean) 90.0 else 70.0, 40.0)
        val deepWB = Mat(); Imgproc.blur(deepW, deepWB, Size(9.0, 9.0)); deepW.release()
        deepW = deepWB
        if (Core.minMaxLoc(deepW).maxVal > 0.01) {
            val kSize = Size(if (clean) 11.0 else 9.0, if (clean) 11.0 else 9.0)
            val w0 = washCr
            val ws = Mat(); Imgproc.GaussianBlur(w0, ws, kSize, 0.0)
            washCr = blendToward(w0, ws, deepW)
            w0.release(); ws.release()
            val b0 = washCb
            val bs = Mat(); Imgproc.GaussianBlur(b0, bs, kSize, 0.0)
            washCb = blendToward(b0, bs, deepW)
            b0.release(); bs.release()
        }
        deepW.release()
        // мягкий возврат хромы у кромок, максимум 80% (clean 60%)
        val eFull = Mat()
        Imgproc.resize(edge, eFull, Size(wfull.toDouble(), hfull.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        var eT = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(eFull, Scalar(0.15), t); eFull.release()
                Core.multiply(t, Scalar(1.0 / 0.5), t); t
            }
        )
        Core.multiply(eT, Scalar(if (clean) 0.6 else 0.8), eT)
        val eTB = Mat(); Imgproc.blur(eT, eTB, Size(3.0, 3.0)); eT.release()
        eT = eTB
        // в глубокой тени честной хроме делать нечего
        val deepC = sstepInv(yFlat8, 55.0, 25.0)
        val deepCB = Mat(); Imgproc.blur(deepC, deepCB, Size(5.0, 5.0)); deepC.release()
        val oneMd = Mat(); Core.subtract(Mat(deepCB.size(), deepCB.type(), Scalar(1.0)), deepCB, oneMd)
        deepCB.release()
        Core.multiply(eT, oneMd, eT); oneMd.release()
        val washCrF = Mat(); washCr.convertTo(washCrF, CvType.CV_32FC1); washCr.release()
        val washCbF = Mat(); washCb.convertTo(washCbF, CvType.CV_32FC1); washCb.release()
        // cr_o = wash + (orig - wash) * e_t
        fun chromaReturn(wash: Mat, orig: Mat): Mat {
            val d = Mat(); Core.subtract(orig, wash, d)
            val t = Mat(); Core.multiply(d, eT, t); d.release()
            val r = Mat(); Core.add(wash, t, t); t.release()
            return r
        }
        val crO = chromaReturn(washCrF, crOF); washCrF.release(); crOF.release()
        val cbO = chromaReturn(washCbF, cbOF); washCbF.release(); cbOF.release()
        eT.release()
        edge.release()
        // в самом дне цвета нет — тянем к серому вместо пятен
        val gpBase = sstepInv(yFlat8, 45.0, 25.0)
        val grayPull = Mat(); Imgproc.blur(gpBase, grayPull, Size(7.0, 7.0)); gpBase.release()
        val gp = if (clean) 0.65 else 0.5
        fun pullGray(c: Mat): Mat {
            val d = Mat(); Core.subtract(c, Scalar(128.0), d)
            val k = Mat(); Core.multiply(grayPull, Scalar(gp), k)
            val t = Mat(); Core.multiply(d, k, t)
            d.release(); k.release()
            val r = Mat(); Core.subtract(c, t, t); t.release()
            return r
        }
        val crP = pullGray(crO); crO.release()
        val cbP = pullGray(cbO); cbO.release()
        grayPull.release()
        val crO8 = Mat(); crP.convertTo(crO8, CvType.CV_8UC1); crP.release()
        val cbO8 = Mat(); cbP.convertTo(cbO8, CvType.CV_8UC1); cbP.release()
        return Denoised(yFlat8, crO8, cbO8, sigmaMed, sigmaDark)
    }

    /**
     * detail_weights_v2. Все входы CV_32F (yo/ys) и 8U хрома.
     * Возвращает вес возврата w + struct-карту (для tmap shrinkage).
     */
    fun detailWeightsV2(
        yo: Mat, ys: Mat,
        crO: Mat, crS: Mat, cbO: Mat, cbS: Mat,
        t0: Double, t1: Double,
        pthr: Double = 1.65, gf: Double = 0.96,
        clean: Boolean = true,
    ): Pair<Mat, Mat> {
        val h = yo.rows(); val w = yo.cols()
        val diff = Mat(); Core.absdiff(yo, ys, diff)
        val coh3 = Mat(); Imgproc.blur(diff, coh3, Size(3.0, 3.0))
        val coh5 = Mat(); Imgproc.blur(diff, coh5, Size(5.0, 5.0))
        val coh9 = Mat(); Imgproc.blur(diff, coh9, Size(9.0, 9.0))
        var h1 = Mat(); Imgproc.pyrDown(diff, h1)
        var h2 = Mat(); Imgproc.pyrDown(h1, h2); h1.release()
        val h2b = Mat(); Imgproc.blur(h2, h2b, Size(3.0, 3.0)); h2.release()
        val low = Mat()
        Imgproc.resize(h2b, low, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        h2b.release()
        val prat = Mat()
        run {
            val denom = Mat(); Core.add(coh3, Scalar(1.0), denom)
            Core.divide(low, denom, prat)
            denom.release(); low.release()
        }
        val persist = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(prat, Scalar(pthr), t)
                Core.multiply(t, Scalar(1.0 / 0.30), t); t
            }
        )
        val dirc = Mat()
        run {
            val d5 = DenoiseCommon.dirCoh(ys, 5)
            val d9 = DenoiseCommon.dirCoh(ys, 9)
            Core.max(d5, d9, dirc)
            d5.release(); d9.release()
        }
        var g = yo.clone()
        repeat(3) {
            val t = Mat(); Imgproc.pyrDown(g, t); g.release(); g = t
        }
        val ggx = Mat(); Imgproc.Sobel(g, ggx, CvType.CV_32F, 1, 0, 3)
        val ggy = Mat(); Imgproc.Sobel(g, ggy, CvType.CV_32F, 0, 1, 3)
        g.release()
        val gax = Mat(); Core.absdiff(ggx, Scalar(0.0), gax); ggx.release()
        val gay = Mat(); Core.absdiff(ggy, Scalar(0.0), gay); ggy.release()
        val gsum = Mat(); Core.add(gax, gay, gsum)
        gax.release(); gay.release()
        val gsb = Mat(); Imgproc.blur(gsum, gsb, Size(5.0, 5.0)); gsum.release()
        Core.multiply(gsb, Scalar(1.0 / 30.0), gsb)
        val geSmall = Mat(); Core.min(gsb, Scalar(1.0), geSmall); gsb.release()
        val ge = Mat()
        Imgproc.resize(geSmall, ge, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        geSmall.release()

        fun lut(c: Mat, a0: Double, a1: Double): Mat {
            val t = Mat(); Core.subtract(c, Scalar(a0), t)
            Core.multiply(t, Scalar(1.0 / maxOf(a1 - a0, 1e-6)), t)
            val r = DenoiseCommon.matSmoothstep(t); t.release()
            return r
        }
        val w3 = lut(coh3, t0, t1)
        val w5 = lut(coh5, t0 * 0.8, t1 * 0.9)
        val w9 = lut(coh9, t0 * 0.7, t1 * 0.8)
        var amp = Mat(); Core.max(w3, w5, amp); w3.release(); w5.release()
        run {
            val t = Mat(); Core.max(amp, w9, t); amp.release(); amp = t; w9.release()
        }
        val gstr = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(ge, Scalar(0.35), t)
                Core.multiply(t, Scalar(1.0 / 0.3), t); t
            }
        )
        run {
            val t = Mat(); Core.multiply(gstr, dirc, t)
            val t2 = Mat(); Core.max(amp, t, t2)
            amp.release(); t.release(); amp = t2; gstr.release()
        }
        // persist_g = persist * (0.2 + 0.8*ge)
        val kge = Mat(); Core.multiply(ge, Scalar(0.8), kge)
        Core.add(kge, Scalar(0.2), kge)
        val persistG2 = Mat(); Core.multiply(persist, kge, persistG2)
        kge.release()
        val pgate = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(persist, Scalar(0.6), t)
                Core.multiply(t, Scalar(1.0 / 0.2), t); t
            }
        )
        val sup = Mat(); Imgproc.blur(amp, sup, Size(15.0, 15.0))
        val support = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(sup, Scalar(0.1), t)
                Core.multiply(t, Scalar(1.0 / 0.3), t); t
            }
        )
        Core.multiply(support, pgate, support)
        pgate.release()
        var struct = Mat(); Core.max(dirc, persistG2, struct)
        persistG2.release()
        run {
            val t = Mat(); Core.max(struct, support, t)
            struct.release(); struct = t; support.release()
        }
        persist.release()
        val gate = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(struct, Scalar(gf), t)
                Core.multiply(t, Scalar(1.0 / 0.35), t); t
            }
        )
        val dcr = Mat(); Core.absdiff(crO, crS, dcr)
        val dcb = Mat(); Core.absdiff(cbO, cbS, dcb)
        val dcc = Mat(); Core.add(dcr, dcb, dcc)
        dcr.release(); dcb.release()
        val dcrF = Mat(); dcc.convertTo(dcrF, CvType.CV_32FC1); dcc.release()
        val cohC = Mat(); Imgproc.blur(dcrF, cohC, Size(5.0, 5.0)); dcrF.release()
        // ratio = coh3 / (coh_c + 2.0)
        val denom2 = Mat(); Core.add(cohC, Scalar(2.0), denom2); cohC.release()
        val ratio2 = Mat(); Core.divide(coh3, denom2, ratio2)
        denom2.release()
        val pen = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(ratio2, Scalar(0.6), ratio2)
                Core.multiply(ratio2, Scalar(1.0 / 1.4), ratio2); ratio2
            }
        )
        val cBase = Mat()
        Core.multiply(pen, Scalar(0.75), cBase)
        Core.add(cBase, Scalar(0.25), cBase)
        pen.release()
        val cDir = Mat(); Core.multiply(dirc, Scalar(0.9), cDir)
        val colorF = Mat(); Core.max(cBase, cDir, colorF)
        cBase.release(); cDir.release()
        dirc.release(); coh3.release()

        val magY = Mat()
        run {
            val sx = Mat(); Imgproc.Sobel(yo, sx, CvType.CV_32F, 1, 0, 3)
            val sy = Mat(); Imgproc.Sobel(yo, sy, CvType.CV_32F, 0, 1, 3)
            val axx = Mat(); Core.absdiff(sx, Scalar(0.0), axx); sx.release()
            val ayy = Mat(); Core.absdiff(sy, Scalar(0.0), ayy); sy.release()
            Core.add(axx, ayy, magY); axx.release(); ayy.release()
        }
        val proxB = Mat(); Imgproc.blur(magY, proxB, Size(9.0, 9.0)); magY.release()
        Core.multiply(proxB, Scalar(1.0 / 60.0), proxB)
        val prox = Mat(); Core.min(proxB, Scalar(1.0), prox); proxB.release()
        val edgeF = Mat(); Core.multiply(prox, Scalar(0.75), prox)
        Core.add(prox, Scalar(0.25), edgeF); prox.release()

        val wProd = Mat(); Core.multiply(amp, gate, amp)
        amp.release(); gate.release()
        val wProd2 = Mat(); Core.multiply(wProd, colorF, wProd)
        wProd.release(); colorF.release()
        val wProd3 = Mat(); Core.multiply(wProd2, edgeF, wProd2)
        wProd2.release(); edgeF.release()
        val wOut = Mat(); Imgproc.blur(wProd3, wOut, Size(3.0, 3.0)); wProd3.release()
        // shadow_kill в глубокой тени
        val skThr = if (clean) 80.0 else 60.0
        val sk = DenoiseCommon.matSmoothstep(
            run {
                val t = Mat(); Core.subtract(Mat(yo.size(), yo.type(), Scalar(skThr)), yo, t)
                Core.multiply(t, Scalar(1.0 / 30.0), t); t
            }
        )
        val oneMge = Mat(); Core.subtract(Mat(ge.size(), ge.type(), Scalar(1.0)), ge, oneMge)
        Core.multiply(sk, oneMge, sk); oneMge.release()
        val killK = if (clean) 1.0 else 0.9
        val oneMkill = Mat()
        run {
            val t = Mat(); Core.multiply(sk, Scalar(killK), t)
            Core.subtract(Mat(t.size(), t.type(), Scalar(1.0)), t, oneMkill)
            t.release(); sk.release()
        }
        Core.multiply(wOut, oneMkill, wOut); oneMkill.release()
        ge.release()
        // struct нужен вызывателю для tmap — он же gate-основа; gate уже съеден выше,
        // struct жив. diff/coh5/coh9/persist освобождаем:
        diff.release(); coh5.release(); coh9.release()
        return wOut to struct
    }

    /**
     * process_photo_v2 (clean=true — дефолт; clean=false — мягкий).
     * Возвращает люму-возврат + финальную хрому + тег. Сборку YCrCb→Bitmap
     * делает вызыватель (processBitmap). Входной rgb НЕ забираем.
     * global_refine (do_refine && !clean) НЕ вшит: продукт держит свой тумблер
     * globalTextureRefine отдельно ([DEV], поведение то же при вкл. тумблере).
     */
    data class V2Result(val y: Mat, val cr: Mat, val cb: Mat, val tag: String, val sig: Sig)

    fun processPhotoV2(
        rgb: Mat,
        clean: Boolean = true,
        sigmaOverride: Sig? = null,
        k0: Double = 1.5,
        t1Ratio: Double = 1.8,
        pthr: Double = 1.65,
        gf: Double = 0.96,
    ): V2Result {
        val den = denoiseYcrcbV2(rgb, clean, sigmaOverride)
        val (sm, sd) = den.sm to den.sd
        val (t0b, t1b, p2, g2) = if (clean) {
            Quad(maxOf(6.0, k0 * sm), maxOf(6.0, k0 * sm) * t1Ratio, pthr, gf)
        } else {
            Quad(maxOf(4.5, 1.5 * sm), maxOf(4.5, 1.5 * sm) * 2.0, 1.05, 0.65)
        }
        val yccO = Mat(); Imgproc.cvtColor(rgb, yccO, Imgproc.COLOR_RGB2YCrCb)
        val chO = ArrayList<Mat>(3); Core.split(yccO, chO); yccO.release()
        val yo = Mat(); chO[0].convertTo(yo, CvType.CV_32FC1)
        val crO = chO[1]; val cbO = chO[2]
        val ys0 = Mat(); den.yFlat.convertTo(ys0, CvType.CV_32FC1)
        den.yFlat.release()
        val ys = if (clean) {
            // pilot guided: ys = guided_filter(yo, yo, r=4, eps=(2*sm)^2)
            val g = DenoiseCommon.guidedFilter(yo, yo, 4, (2.0 * sm) * (2.0 * sm))
            ys0.release()
            g
        } else ys0
        val crS = den.crO; val cbS = den.cbO
        val (w, struct) = detailWeightsV2(yo, ys, crO, crS, cbO, cbS, t0b, t1b, p2, g2, clean)
        val d = Mat(); Core.subtract(yo, ys, d)
        // tmap = t1*(1-struct) + 0.5
        val oneMs = Mat(); Core.subtract(Mat(struct.size(), struct.type(), Scalar(1.0)), struct, oneMs)
        val tmap = Mat(); Core.multiply(oneMs, Scalar(t1b), oneMs)
        Core.add(oneMs, Scalar(0.5), tmap); oneMs.release()
        // y_shr = ys + sign(d)*max(|d|-tmap, 0) — один проход строками
        val yShr = shrinkReturn(ys, d, tmap)
        tmap.release(); d.release(); struct.release()
        // yr = y_shr*0.8 + (ys + (yo-ys)*w)*0.2 (clean) или ys + d*w
        val yr = F32(yo.rows(), yo.cols())
        runCleanBlend(yo, ys, yShr, w, clean, yr)
        yShr.release(); w.release()
        val yc = DenoiseCommon.crisp(yr, amount = if (clean) 0.15f else 0.35f, thr = 12f)
        yr.release()
        val yOut = Mat(); yc.convertTo(yOut, CvType.CV_8UC1); yc.release()
        yo.release(); ys.release()
        crO.release(); cbO.release()
        // tag как в Python: "+C" если clean
        return V2Result(yOut, crS, cbS, if (clean) "+C" else "", Sig(sm, sd))
    }

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)

    private fun F32(h: Int, w: Int): Mat = Mat(h, w, CvType.CV_32FC1, Scalar(0.0))

    /** yr = y_shr*0.8 + (ys + (yo-ys)*w)*0.2 (clean) или ys + (yo-ys)*w. Пишет в out. */
    private fun runCleanBlend(
        yo: Mat, ys: Mat, yShr: Mat, w: Mat, clean: Boolean, out: Mat,
    ) {
        if (!clean) {
            val d = Mat(); Core.subtract(yo, ys, d)
            val dw = Mat(); Core.multiply(d, w, dw); d.release()
            Core.add(ys, dw, out); dw.release()
            return
        }
        val d = Mat(); Core.subtract(yo, ys, d)
        val dw = Mat(); Core.multiply(d, w, dw); d.release()
        val ysw = Mat(); Core.add(ys, dw, ysw); dw.release()
        val a = Mat(); Core.multiply(yShr, Scalar(0.8), a)
        val b = Mat(); Core.multiply(ysw, Scalar(0.2), ysw)
        Core.add(a, b, out)
        a.release(); b.release(); ysw.release()
    }

    /**
     * Shrinkage-coring: y_shr = ys + sign(d)*max(|d|-tmap, 0).
     * Один проход строками (FloatArray), как Kotlin-фолбэки в кодовой базе.
     */
    private fun shrinkReturn(ys: Mat, d: Mat, tmap: Mat): Mat {
        val w = ys.cols(); val h = ys.rows()
        val out = Mat(h, w, CvType.CV_32FC1)
        val ysR = FloatArray(w); val dR = FloatArray(w)
        val tR = FloatArray(w); val oR = FloatArray(w)
        for (y in 0 until h) {
            ys.get(y, 0, ysR)
            d.get(y, 0, dR)
            tmap.get(y, 0, tR)
            for (x in 0 until w) {
                val dd = dR[x]
                val over = if (dd >= 0) dd - tR[x] else -dd - tR[x]
                oR[x] = if (over > 0) {
                    ysR[x] + (if (dd >= 0) over else -over)
                } else ysR[x]
            }
            out.put(y, 0, oR)
        }
        return out
    }

    /**
     * Точка входа с Bitmap: RGBA → RGB → v2 → сборка YCrCb→RGB→RGBA Bitmap.
     * Возвращает (bitmap, tag). Вход НЕ забираем.
     */
    fun processBitmap(
        src: Bitmap,
        clean: Boolean = true,
        sigmaOverride: Sig? = null,
    ): Pair<Bitmap, String> {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        if (rgba.channels() == 4) {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        } else {
            rgba.copyTo(rgb)
        }
        rgba.release()
        val res = processPhotoV2(rgb, clean, sigmaOverride)
        rgb.release()
        val merged = Mat()
        Core.merge(java.util.Arrays.asList(res.y, res.cr, res.cb), merged)
        res.y.release(); res.cr.release(); res.cb.release()
        val rgbOut = Mat()
        Imgproc.cvtColor(merged, rgbOut, Imgproc.COLOR_YCrCb2RGB)
        merged.release()
        val rgbaOut = Mat()
        Imgproc.cvtColor(rgbOut, rgbaOut, Imgproc.COLOR_RGB2RGBA)
        rgbOut.release()
        val out = Bitmap.createBitmap(rgbaOut.cols(), rgbaOut.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaOut, out)
        rgbaOut.release()
        return out to res.tag
    }
}
