package com.example.thundercam20.utils

import android.util.Log

/**
 * Замеры стадий обработки: PhotoPipeline total=4200ms [decode=300 downscale=80 merge=1800 p0=400 denoise=900 save=120].
 * Ноль аллокаций в горячем пути (один ArrayList на кадр), вызовы обёрнуты в try чтобы
 * никогда не ронять обработку.
 */
class Timing(private val tag: String) {
    private val t0 = System.currentTimeMillis()
    private var last = t0
    private val parts = ArrayList<String>(12)

    fun mark(name: String) {
        try {
            val now = System.currentTimeMillis()
            parts.add("$name=${now - last}")
            last = now
        } catch (_: Throwable) {}
    }

    fun report() {
        try {
            Log.i(tag, "total=${System.currentTimeMillis() - t0}ms [${parts.joinToString(" ")}]")
        } catch (_: Throwable) {}
    }
}
