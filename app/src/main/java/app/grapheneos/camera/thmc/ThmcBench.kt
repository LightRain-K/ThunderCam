package app.grapheneos.camera.thmc

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.thundercam20.analysis.FastGray

/**
 * ThunderCam бенчмарк: вместо гадания по модели телефона меряем реальные
 * merge-МПикс/с + память, раскладываем в тир, от тира — все тяжёлые параметры
 * (сторона ночного стека, число кадров, кап SR-тайлов).
 *
 * Замер одноразовый (~3с, фон), результат в prefs. Пороги — грубые, подкрутим
 * по полевым данным с трёх тел (S25U / Pova 5 / A17).
 */
object ThmcBench {
    private const val TAG = "ThmcBench"

    const val TIER_AUTO = "auto"
    const val TIER_BUDGET = "budget"
    const val TIER_MID = "mid"
    const val TIER_BEAST = "beast"

    data class Profile(
        val tier: String,
        /** Рабочая сторона ночного стека. */
        val nightSide: Int,
        /** Рабочая сторона HDR (брекетинг яркий — можно выше, чем ночь). */
        val hdrSide: Int,
        /** Потолок кадров ночи. */
        val nightMax: Int,
        /** Потолок SR-тайлов. */
        val srMaxTiles: Int,
        /** Потолок тотального зума (оптика × цифра): как сток устройства класса. */
        val zoomCap: Float,
    )

    fun profileOf(tier: String): Profile = when (tier) {
        // Нативному мержу FloatArray-аккумуляторы больше не нужны (попиксельно, пик O(N)),
        // поэтому зверю можно дать 3072 без 280МБ транзиента. Бюджетник — 1792, середина — 2304.
        TIER_BEAST -> Profile(TIER_BEAST, 3072, 3072, 8, 9, 100f)
        TIER_MID -> Profile(TIER_MID, 2304, 2304, 6, 9, 20f)
        else -> Profile(TIER_BUDGET, 1792, 1792, 5, 4, 10f)
    }

    /** Статический фолбэк без замера (только RAM; зверя без замера не даём). Чистый. */
    fun staticTier(memGb: Float): String =
        if (memGb < 4f) TIER_BUDGET else TIER_MID

    /**
     * Замерённый тир по скорости мержа + памяти. Чистый.
     * Полевые данные (merge МП/с): A17 ~20-30, Tecno Pova 5 ~40, S25U ~150.
     * Зверь требует и скорость, и RAM (8 кадров на 2560 — ~280МБ транзиента).
     */
    fun measuredTier(mergeMpPerSec: Float, memGb: Float): String = when {
        memGb < 3f -> TIER_BUDGET
        mergeMpPerSec < 50f -> TIER_BUDGET
        mergeMpPerSec < 120f || memGb < 6f -> TIER_MID
        else -> TIER_BEAST
    }

    /** Эффективный профиль: замеренный, иначе статический по RAM. */
    fun effectiveProfile(context: Context, settings: ThmcSettings): Profile {
        return try {
            val stored = settings.benchTier
            if (settings.benchDone && stored != TIER_AUTO) return profileOf(stored)
            profileOf(staticTier(totalMemGb(context)))
        } catch (_: Throwable) {
            profileOf(TIER_MID)
        }
    }

    fun totalMemGb(context: Context): Float {
        return try {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / (1024f * 1024f * 1024f)
        } catch (_: Throwable) { 4f }
    }

    data class BenchResult(val tier: String, val mergeMpPerSec: Float, val memGb: Float)

    /**
     * Синхронный замер (~3с). Только с фонового потока.
     * OOM на пробном аллокаторе = сразу budget (куче тесно).
     */
    fun run(context: Context): BenchResult {
        val memGb = totalMemGb(context)
        try {
            // Проба кучи: 2048×1536 Bitmap. Не влез — бюджетник без обсуждений.
            try {
                val probe = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
                probe.recycle()
            } catch (e: OutOfMemoryError) {
                Log.i(TAG, "oom probe → budget")
                return BenchResult(TIER_BUDGET, 0f, memGb)
            }
            // Синтетика 1536×1152: градиент + детерминированный шум (merge без вырождений).
            val w = 1536; val h = 1152
            val frames = (0 until 4).map { k -> synthetic(w, h, k) }
            try {
                // Прогрев + 2 итерации мержа 4 кадров.
                ThmcMerge.mergeWeighted(frames, listOf(2, 2, 2, 2)).recycle()
                val t0 = System.nanoTime()
                repeat(2) { ThmcMerge.mergeWeighted(frames, listOf(2, 2, 2, 2)).recycle() }
                val sec = (System.nanoTime() - t0) / 1_000_000_000.0
                val mpS = if (sec > 0) ((w * h * 4 * 2) / 1_000_000.0 / sec).toFloat() else 0f
                // Gray-скоринг для контроля (десятикратный прогон, в лог).
                val t1 = System.nanoTime()
                repeat(10) { FastGray.meanAbsLaplacian(FastGray.of(frames[0], 320)) }
                Log.i(TAG, "merge ${"%.0f".format(mpS)} MP/s, gray ${(System.nanoTime() - t1) / 1_000_000}ms/10, mem ${"%.1f".format(memGb)}GB")
                return BenchResult(measuredTier(mpS, memGb), mpS, memGb)
            } finally {
                for (b in frames) try { b.recycle() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bench failed → mid", t)
            return BenchResult(TIER_MID, 0f, memGb)
        }
    }

    private fun synthetic(w: Int, h: Int, seed: Int): Bitmap {
        val px = IntArray(w * h)
        var s = seed * 7919 + 13
        for (y in 0 until h) {
            for (x in 0 until w) {
                s = (s * 1103515245 + 12345) and 0x7fffffff
                val g = ((x * 255 / w + y * 255 / h) / 2 + (s % 64) - 32).coerceIn(0, 255)
                px[y * w + x] = -16777216 or (g shl 16) or (g shl 8) or g
            }
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** Флаг «оффер уже показывали» (пересоздание активити не должно спамить). */
    @Volatile var offerShown = false
}

/**
 * Общий запуск замера из любой активити: диалог «замеряю» → фон → сохранение → результат.
 */
fun runBenchFlow(activity: androidx.appcompat.app.AppCompatActivity, settings: ThmcSettings) {
    val running = try {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(app.grapheneos.camera.R.string.thmc_bench_running_title)
            .setMessage(app.grapheneos.camera.R.string.thmc_bench_running_text)
            .setCancelable(false)
            .show()
    } catch (_: Throwable) { null }
    Thread {
        val res = ThmcBench.run(activity)
        try {
            settings.benchTier = res.tier
            settings.benchScore = res.mergeMpPerSec
            settings.benchDone = true
        } catch (_: Throwable) {}
        activity.runOnUiThread {
            try { running?.dismiss() } catch (_: Throwable) {}
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                val prof = ThmcBench.profileOf(res.tier)
                val tierName = activity.getString(
                    when (res.tier) {
                        ThmcBench.TIER_BEAST -> app.grapheneos.camera.R.string.thmc_bench_tier_beast
                        ThmcBench.TIER_MID -> app.grapheneos.camera.R.string.thmc_bench_tier_mid
                        else -> app.grapheneos.camera.R.string.thmc_bench_tier_budget
                    }
                )
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(app.grapheneos.camera.R.string.thmc_bench_done_title, tierName))
                    .setMessage(
                        activity.getString(
                            app.grapheneos.camera.R.string.thmc_bench_done_text,
                            res.mergeMpPerSec.toInt(), prof.nightSide, prof.nightMax
                        )
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (_: Throwable) {}
        }
    }.start()
}
