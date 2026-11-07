package app.grapheneos.camera.thmc

import android.graphics.Bitmap

/**
 * Учёт пула битмапов: сколько байт держим, кого выкидываем. Чистая логика,
 * тестируется на JVM (без android.graphics).
 *
 * Ключ корзины — байты (w*h*4 для ARGB_8888). Совпадение только точное:
 * для inBitmap-переиспользования и нативных out-буферов нужен ровно тот размер.
 */
class PoolLedger(val maxBytes: Long) {
    private val order = LinkedHashMap<Long, Long>() // key -> bytes, порядок = LRU (старые впереди)
    var currentBytes: Long = 0L
        private set
    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set
    var evictions: Long = 0L
        private set

    fun keyFor(w: Int, h: Int): Long = w.toLong() * h * 4

    /** Забрать корзину (хит) или промах. Чистая. */
    @Synchronized
    fun take(key: Long): Boolean {
        val b = order.remove(key)
        return if (b != null) {
            currentBytes -= b
            hits++
            true
        } else {
            misses++
            false
        }
    }

    /**
     * Положить корзину. Возвращает ключи, которые надо выкинуть (самые старые),
     * чтобы влезть в лимит. Не влезает даже пустым — возвращает свой же ключ
     * (вызыватель тогда просто recycle).
     */
    @Synchronized
    fun add(key: Long, bytes: Long): List<Long> {
        if (bytes > maxBytes) return listOf(key)
        order.remove(key)
        order[key] = bytes
        currentBytes += bytes
        val out = ArrayList<Long>()
        while (currentBytes > maxBytes && order.isNotEmpty()) {
            val oldest = order.keys.iterator().next()
            val ob = order.remove(oldest) ?: 0L
            currentBytes -= ob
            evictions++
            out.add(oldest)
        }
        return out
    }

    @Synchronized
    fun clear() {
        order.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun buckets(): Int = order.size
}
