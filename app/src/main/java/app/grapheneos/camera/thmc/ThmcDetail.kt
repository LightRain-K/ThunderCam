package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log

/**
 * Перенос настоящих микродеталей с полного разрешения на финал серии (ночь/HDR).
 *
 * Зачем: серия считается на рабочем разрешении (1792–3072), потом Lanczos тянет
 * до 4000×3000. Всё, что мельче ~размера рабочего кадра, — интерполяция: кадр
 * выходит «чистый, но мыльный», особенно ветки/трава/текст на контрасте.
 * Здесь высокочастотная часть (band ~1–5 px) опорного кадра в 12 Мп добавляется
 * в финал. Зерно одиночного кадра НЕ возвращается: amplitude-gate по σ + знаковое
 * согласие с уже усреднённой деталью финала (гостинг/движение отбрасываются).
 *
 * Всегда fail-safe: любая проблема/OOM → false, кадр остаётся как был.
 */
object ThmcDetail {
    private const val TAG = "ThmcDetail"

    /**
     * @param fullBaseJpeg JPEG опорного кадра (полное разрешение).
     * @param dst         финал серии (mutable, ARGB_8888), размер = габариты исходника.
     * @param crop        кроп этого кадра в координатах рабочего разрешения (из алайна).
     *                    null — кадр не кропался (одиночный путь).
     * @param workW/workH габариты рабочего кадра (до кропа): для масштаба кропа.
     * @param noise       ожидаемый шум ISO (ThmcMerge.noiseForIso): порог амплитуды.
     * @param amount      сила переноса 0..1 (0.85–0.9 = вернуть реальную деталь).
     */
    fun inject(
        fullBaseJpeg: ByteArray?,
        dst: Bitmap,
        crop: Rect?,
        workW: Int,
        workH: Int,
        noise: Float,
        amount: Float = 0.9f,
    ): Boolean {
        if (fullBaseJpeg == null) return false
        if (dst.width < 64 || dst.height < 64 || !dst.isMutable) return false
        if (!com.example.thundercam20.imageprocessing.NativeProcessor.isAvailable()) return false
        val t0 = System.nanoTime()
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = BitmapFactory.decodeByteArray(fullBaseJpeg, 0, fullBaseJpeg.size, opts) ?: return false
            val bw = decoded.width; val bh = decoded.height
            if (bw < 64 || bh < 64) return false
            var base: Bitmap = decoded
            if (crop != null && workW >= 16 && workH >= 16) {
                val fx = bw.toFloat() / workW.toFloat()
                val fy = bh.toFloat() / workH.toFloat()
                val x = Math.round(crop.left * fx).coerceIn(0, bw - 2)
                val y = Math.round(crop.top * fy).coerceIn(0, bh - 2)
                val cw = Math.round(crop.width() * fx).coerceIn(2, bw - x)
                val ch = Math.round(crop.height() * fy).coerceIn(2, bh - y)
                if (cw >= 32 && ch >= 32 && !(x == 0 && y == 0 && cw == bw && ch == bh)) {
                    // createBitmap(source, ...) — view на те же пиксели, без 48 МБ копии.
                    cropped = Bitmap.createBitmap(decoded, x, y, cw, ch)
                    base = cropped
                }
            }
            val sx = base.width.toFloat() / dst.width.toFloat()
            val sy = base.height.toFloat() / dst.height.toFloat()
            // Порог амплитуды: ниже — это зерно одиночного кадра, не деталь.
            val thr = (noise * 0.85f).coerceIn(4.5f, 16f)
            val ok = com.example.thundercam20.imageprocessing.NativeProcessor.detailTransfer(
                base, dst, scaleX = sx, scaleY = sy, amount = amount, thr = thr
            )
            if (ok) {
                val ms = (System.nanoTime() - t0) / 1_000_000
                try {
                    ThmcTrackState.lastRecipe = (ThmcTrackState.lastRecipe + " +dt${ms}ms").trim()
                } catch (_: Throwable) {}
                try { Log.i(TAG, "detail ok ${ms}ms base=${bw}x$bh dst=${dst.width}x${dst.height} thr=${"%.1f".format(thr)}") } catch (_: Throwable) {}
            }
            ok
        } catch (e: OutOfMemoryError) {
            try { Log.w(TAG, "detail oom, skip") } catch (_: Throwable) {}
            false
        } catch (t: Throwable) {
            try { Log.w(TAG, "detail skip", t) } catch (_: Throwable) {}
            false
        } finally {
            // cropped — view на decoded: в пул его отдавать нельзя (не конфигурируется),
            // только снять ссылку. Сам decoded — обычный временный битмап.
            if (cropped != null && cropped !== decoded) {
                try { if (!cropped.isRecycled) cropped.recycle() } catch (_: Throwable) {}
            }
            ThmcBitmaps.release(decoded)
        }
    }
}
