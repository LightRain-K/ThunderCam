package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.TimeUnit

/**
 * ZSL-кольцо V1 (soft): не храним кадры, экономим AF-лок.
 * Полное кольцо YUV-кадров — следующим этапом (нужен второй Analysis use-case
 * на 1280px + 14МБ living cap). Сейчас выигрыш без риска:
 * сцена стоит + AF был <3с назад → пропускаем AF 600мс, снимаем сразу.
 * Веб-подтверждение: у Pixel ZSL берет уже снятые кадры, у нас — пропускаем
 * повторный reconverge. Безопасно: любое сомнение → AF как раньше.
 */
object ThmcZsl {
    /** true = можно пропустить AF-лок серии. */
    fun skipAf(): Boolean {
        return try {
            val dt = android.os.SystemClock.elapsedRealtime() - ThmcTrackState.lastSeriesAfTs
            if (dt < 0 || dt > 3000) return false
            if (ThmcTrackState.sceneChanged()) return false
            val med = ThmcTrackState.medianPreviewL()
            val cur = ThmcTrackState.lastPreviewL
            if (med >= 0f && cur >= 0f && kotlin.math.abs(med - cur) > 0.08f) return false
            true
        } catch (_: Throwable) { false }
    }

    fun markAf() {
        try { ThmcTrackState.lastSeriesAfTs = android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) {}
    }
}

/** Быстрый скоринг на RGB_565 (x2 меньше памяти) для QC-гейта серий. */
object ThmcFastScore {
    fun sharp565(bmp: Bitmap): Double {
        return try {
            val c565 = if (bmp.config == Bitmap.Config.RGB_565) bmp
            else bmp.copy(Bitmap.Config.RGB_565, false) ?: return -1.0
            val g = com.example.thundercam20.analysis.FastGray.of(c565, 320)
            if (c565 !== bmp) try { c565.recycle() } catch (_: Throwable) {}
            com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(g)
        } catch (_: Throwable) { -1.0 }
    }

    fun sampleFor(bytes: ByteArray, cap: Int): Int {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= cap) s *= 2
            s
        } catch (_: Throwable) { 1 }
    }
}
