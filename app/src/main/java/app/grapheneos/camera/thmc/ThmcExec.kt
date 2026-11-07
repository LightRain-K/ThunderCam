package app.grapheneos.camera.thmc

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Instant-camera executor'ы: разделяем захват / обработку / сохранение,
 * чтобы кадры не ждали мерж. Синглтон, ленивая инициализация.
 */
object ThmcExec {
    val capture: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
    }
    val process: ThreadPoolExecutor by lazy {
        val n = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
        ThreadPoolExecutor(n, n, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
    }
    val save: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
    }
    // Общий декод-пул для серий (не создавать на серию)
    val decode: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, LinkedBlockingQueue())
    }
}
