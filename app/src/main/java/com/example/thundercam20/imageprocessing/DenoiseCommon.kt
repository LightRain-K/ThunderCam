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
 * Порт C:\Users\pc\denoiser\thundercam_common.py — общая база.
 * Имена и константы 1:1 с Python, чтобы будущие правки прототипа
 * переносились построчно. Отклонения помечены [DEV].
 */
object DenoiseCommon {

    /** Скалярный smoothstep, чистый (тесты). */
    fun smoothstep01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** smoothstep поэлементно на CV_32F (возвращает новый Mat). */
    fun matSmoothstep(m: Mat): Mat {
        val t = Mat()
        Core.max(m, Scalar(0.0), t)
        Core.min(t, Scalar(1.0), t)
        // t*t*(3-2t)
        val t2 = Mat()
        Core.multiply(t, t, t2)
        val twoT = Mat()
        Core.multiply(t, Scalar(2.0), twoT)
        val three = Mat(t.size(), t.type(), Scalar(3.0))
        Core.subtract(three, twoT, twoT)
        three.release()
        val res = Mat()
        Core.multiply(t2, twoT, res)
        t.release(); t2.release(); twoT.release()
        return res
    }

    /** out = (m - s) / d поэлементно (новый Mat). */
    fun matNorm(m: Mat, s: Double, d: Double): Mat {
        val r = Mat()
        Core.subtract(m, Scalar(s), r)
        Core.multiply(r, Scalar(1.0 / d), r)
        return r
    }

    /** Когерентность направлений (common.dir_coh), grayF — CV_32F. */
    fun dirCoh(grayF: Mat, k: Int): Mat {
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(grayF, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(grayF, gy, CvType.CV_32F, 0, 1, 3)
        val kSize = Size(k.toDouble(), k.toDouble())
        val gx2 = Mat(); Core.multiply(gx, gx, gx2)
        val gy2 = Mat(); Core.multiply(gy, gy, gy2)
        val gxy = Mat(); Core.multiply(gx, gy, gxy)
        gx.release(); gy.release()
        val exx = Mat(); Imgproc.blur(gx2, exx, kSize); gx2.release()
        val eyy = Mat(); Imgproc.blur(gy2, eyy, kSize); gy2.release()
        val exy = Mat(); Imgproc.blur(gxy, exy, kSize); gxy.release()
        val diff = Mat(); Core.subtract(exx, eyy, diff)
        val diff2 = Mat(); Core.multiply(diff, diff, diff2); diff.release()
        val cross = Mat(); Core.multiply(exy, exy, cross)
        val cross4 = Mat(); Core.multiply(cross, Scalar(4.0), cross4); cross.release()
        val num = Mat(); Core.add(diff2, cross4, num); diff2.release(); cross4.release()
        Core.sqrt(num, num)
        val den = Mat(); Core.add(exx, eyy, den)
        Core.add(den, Scalar(1.0), den)
        exx.release(); eyy.release(); exy.release()
        val res = Mat(); Core.divide(num, den, res)
        num.release(); den.release()
        return res
    }

    /** crisp из common (возвращает новый Mat CV_32F). */
    fun crisp(y: Mat, amount: Float = 0.15f, radius: Double = 0.8, thr: Float = 12f): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(y, blur, Size(0.0, 0.0), radius)
        val d = Mat(); Core.subtract(y, blur, d); blur.release()
        val pos = Mat(); Core.subtract(d, Scalar(thr.toDouble()), pos)
        Core.max(pos, Scalar(0.0), pos)
        val neg = Mat(); Core.multiply(d, Scalar(-1.0), neg)
        Core.subtract(neg, Scalar(thr.toDouble()), neg)
        Core.max(neg, Scalar(0.0), neg)
        val pd = Mat(); Core.subtract(pos, neg, pd)
        pos.release(); neg.release()
        val add = Mat(); Core.multiply(pd, Scalar(amount.toDouble()), add); pd.release()
        val sum = Mat(); Core.add(y, add, add); add.release()
        val res = Mat()
        Core.max(sum, Scalar(0.0), res)
        Core.min(res, Scalar(255.0), res)
        sum.release()
        d.release()
        return res
    }

    /**
     * Фильтр Хе (только box-blur). p, I — CV_32F одного размера.
     * [DEV]: Python считает в float64; здесь float32 (разница пренебрежима).
     */
    fun guidedFilter(p: Mat, i: Mat, r: Int = 4, eps: Double = 400.0): Mat {
        val k = Size(r.toDouble(), r.toDouble())
        val mI = Mat(); Imgproc.blur(i, mI, k)
        val mp = Mat(); Imgproc.blur(p, mp, k)
        val aa = guidedA(p, i, mI, mp, eps, k)
        val b = Mat(); Core.multiply(aa, mI, b)
        Core.subtract(mp, b, b)
        mI.release(); mp.release()
        val ba = Mat(); Imgproc.blur(aa, ba, k)
        val bb = Mat(); Imgproc.blur(b, bb, k)
        aa.release(); b.release()
        val baI = Mat(); Core.multiply(ba, i, baI); ba.release()
        val res = Mat(); Core.add(baI, bb, res)
        baI.release(); bb.release()
        return res
    }

    private fun guidedA(p: Mat, i: Mat, mI: Mat, mp: Mat, eps: Double, k: Size): Mat {
        val ip = Mat(); Core.multiply(i, p, ip)
        val mIp = Mat(); Imgproc.blur(ip, mIp, k); ip.release()
        val mImp = Mat(); Core.multiply(mI, mp, mImp)
        val cov = Mat(); Core.subtract(mIp, mImp, cov)
        mIp.release(); mImp.release()
        val ii = Mat(); Core.multiply(i, i, ii)
        val mIi = Mat(); Imgproc.blur(ii, mIi, k); ii.release()
        val mImI = Mat(); Core.multiply(mI, mI, mImI)
        val v = Mat(); Core.subtract(mIi, mImI, v)
        mIi.release(); mImI.release()
        Core.add(v, Scalar(eps), v)
        val a = Mat(); Core.divide(cov, v, a)
        cov.release(); v.release()
        return a
    }

    /** sigma_from_iso. Чистая (тесты). */
    fun sigmaFromIso(iso: Int, a: Double = 1.2): Pair<Double, Double> {
        val sm = (a * kotlin.math.sqrt(maxOf(iso, 1) / 100.0)).coerceIn(2.5, 25.0)
        val sd = (sm * 1.8).coerceIn(4.0, 30.0)
        return sm to sd
    }

    /**
     * Индекс бины медианы равномерной гистограммы (interpolated в половинах
     * бин). Чистая (тесты): медиана |резидуума| для оценка шума — robust к
     * кромкам/текстурам, которые не переносят усреднение hasHighNoise.
     */
    fun medianFromHist(hist: IntArray, total: Int): Double {
        if (total <= 0) return -1.0
        var acc = 0
        val half = total / 2
        for (i in hist.indices) {
            if (acc + hist[i] >= half) return i + 0.5
            acc += hist[i]
        }
        return (hist.size - 1).toDouble() + 0.5
    }
}
