package app.grapheneos.camera.thmc

import android.graphics.Bitmap

/**
 * Единая точка работы с временными битмапами вместо россыпи ручных recycle().
 *
 * Правила:
 * - транзиент отработал → release() (в пул, а не в GC);
 * - чужое (превью/сохранённое/показанное) → не трогаем вообще;
 * - recycleQuietly — только для финального добивания, когда пул отказал.
 */
object ThmcBitmaps {
    /** Тихо добить битмапы (null/recycled-safe). Пул предпочтительнее. */
    fun recycleQuietly(vararg bs: Bitmap?) {
        for (b in bs) {
            try { if (b != null && !b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
    }

    /** Вернуть временный битмап в оборот. false = пришлось добить. */
    fun release(b: Bitmap?): Boolean {
        if (b == null || b.isRecycled) return true
        if (BitmapPool.release(b)) return true
        recycleQuietly(b)
        return false
    }

    /** Взять буфер w×h или null (вызыватель создаёт сам). */
    fun obtain(w: Int, h: Int): Bitmap? = try {
        BitmapPool.obtain(w, h)
    } catch (_: Throwable) { null }
}
