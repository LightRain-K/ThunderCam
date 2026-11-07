package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.util.Log

/**
 * Пул битмапов вместо ручного recycle(): горячие транзиенты серий
 * (мерж/out-буферы натива, декод) крутятся по кругу, а не дергают GC.
 *
 * Правила, чтобы не было мин из прошлого:
 * - в пул только живые мутабельные ARGB_8888 (inBitmap-совместимые);
 * - размер строго точный, иначе декодер/натив падает — несовпадение = мимо пула;
 * - лимит 64МБ с LRU-выс plumbing: лишнее добивается через recycle;
 * - битмап, ушедший наружу (превью/сохранение/показ), в пул НЕ кладём.
 */
object BitmapPool {
    private const val TAG = "ThmcPool"
    const val CAP_BYTES = 64L * 1024 * 1024

    private val ledger = PoolLedger(CAP_BYTES)
    private val bufs = HashMap<Long, ArrayDeque<Bitmap>>()

    /** Взять буфер ровно w×h ARGB_8888 или null. Потокобезопасно. */
    @Synchronized
    fun obtain(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = ledger.keyFor(w, h)
        if (key > CAP_BYTES) return null
        val q = bufs[key] ?: return null
        while (q.isNotEmpty()) {
            val b = q.removeFirst()
            ledger.take(key)
            if (b != null && !b.isRecycled && b.width == w && b.height == h &&
                b.config == Bitmap.Config.ARGB_8888 && b.isMutable
            ) {
                // Чистить не надо: декодер и натив перезаписывают целиком.
                return b
            }
            // Битый/протухший — уронить молча, попробовать следующий.
            try { if (b != null && !b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Вернуть временный битмап в пул. false = не влез/неформат (вызыватель recycle).
     * Чужие (превью/сохранённые/показанные) сюда не класть.
     */
    @Synchronized
    fun release(b: Bitmap?): Boolean {
        if (b == null || b.isRecycled) return true
        if (b.config != Bitmap.Config.ARGB_8888 || !b.isMutable) return false
        val key = ledger.keyFor(b.width, b.height)
        var selfEvicted = false
        for (ev in ledger.add(key, key)) {
            if (ev == key) selfEvicted = true
            val q = bufs.remove(ev)
            if (q != null) for (x in q) {
                try { if (!x.isRecycled) x.recycle() } catch (_: Throwable) {}
            }
        }
        // Собственная корзина вытеснена (не влез даже пустым) — битмап в утиль.
        if (selfEvicted) {
            try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
            return false
        }
        val q = bufs.getOrPut(key) { ArrayDeque() }
        q.addLast(b)
        return true
    }

    @Synchronized
    fun clear() {
        for ((_, q) in bufs) for (b in q) {
            try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
        bufs.clear()
        ledger.clear()
    }

    fun stats(): String = try {
        "pool buckets=${ledger.buckets()} bytes=${ledger.currentBytes / 1024}KB hits=${ledger.hits} miss=${ledger.misses} ev=${ledger.evictions}"
    } catch (_: Throwable) { "pool ?" }
}
