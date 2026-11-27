package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Декод кадров серии «на лету»: как только кадр пришёл с HAL, он сразу уходит
 * в decode-пул, и расшифровывается ПОКА снимается следующий. Раньше серия
 * сначала снималась целиком, потом декодировалась — эти 0.3–0.8с (3–6 кадров
 * по 100–150мс) были чистыми потерями «простоя CPU во время затвора».
 *
 * Порядок и соответствие исходным индексам сохраняются: результат — те же
 * (bmps, times, srcIdx), что отдавал ImageSaverShared.decodeParallel.
 * Рабочее разрешение (sample) определяется по первому кадру — как раньше.
 */
class ThmcBurst(workSide: Int) {
    data class Decoded(
        val bmps: MutableList<Bitmap>,
        val times: MutableList<Long>,
        val srcIdx: MutableList<Int>,
        val sample: Int,
    )

    private val workSide = workSide.coerceAtLeast(64)
    private val futs = ArrayList<Future<Bitmap?>>()
    private val tms = ArrayList<Long>()
    private val idxs = ArrayList<Int>()
    private var sample = 1
    private var ew = 0
    private var eh = 0
    private var sized = false

    /** Вызывать сразу после получения JPEG, не дожидаясь следующего кадра. */
    fun submit(srcIdx: Int, bytes: ByteArray, tNs: Long) {
        if (!sized) {
            sized = true
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= workSide) s *= 2
                sample = s
                if (o.outWidth > 0 && o.outHeight > 0) {
                    ew = maxOf(1, (o.outWidth + s - 1) / s)
                    eh = maxOf(1, (o.outHeight + s - 1) / s)
                }
            } catch (_: Throwable) { sample = 1 }
        }
        idxs.add(srcIdx); tms.add(tNs)
        val s = sample; val w = ew; val h = eh
        futs.add(ThmcExec.decode.submit<Bitmap?> { ImageSaverShared.decodeOne(bytes, s, w, h) })
    }

    /** Уже готовый (декодированный) кадр серии, не забирая владение. null = ещё считается. */
    fun peek(index: Int): Bitmap? {
        return try {
            val f = futs.getOrNull(index) ?: return null
            if (!f.isDone) null else f.get(0, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
    }

    /** Дождаться всех декодов (обычно к этому моменту почти всё готово). */
    fun awaitAll(): Decoded {
        val bmps = ArrayList<Bitmap>(futs.size)
        val times = ArrayList<Long>(futs.size)
        val src = ArrayList<Int>(futs.size)
        for (i in futs.indices) {
            try {
                val b = futs[i].get(12, TimeUnit.SECONDS)
                if (b != null) { bmps.add(b); times.add(tms[i]); src.add(idxs[i]) }
            } catch (_: Throwable) {}
        }
        return Decoded(bmps, times, src, sample)
    }
}
