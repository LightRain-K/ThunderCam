package app.grapheneos.camera.thmc

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.grapheneos.camera.ui.activities.MainActivity

/**
 * ThunderCam трекинг: ведёт точку фокуса за объектом (донорский FocusTracker, чистый Kotlin).
 * Анализатор висит в фото-режиме всегда, но дешёвый: без активного трекинга — мгновенный возврат.
 * Y-плоскость упаковываем построчно (rowStride может быть с паддингом).
 * AF дотягивается троттлингом в MainActivity.onTrackPoint (не чаще 0.8с, при уходе >25px).
 */
/**
 * Состояние трекинга ВНЕ активити: переживает ребинды и пересоздания экрана.
 * Баг был тут: анализатор, забинденный прошлой сессией, читал флаг/трекер протухшего
 * инстанса MainActivity (f=0 при 90 кадрах), а кольцо рисовал живой инстанс.
 */
object ThmcTrackState {
    val tracker = com.example.thundercam20.camera.FocusTracker()
    @Volatile var tracking = false
    @Volatile var lastAf = 0L
    @Volatile var lastPt: android.graphics.PointF? = null
    // Последний средний Y превью (0..1, -1 = нет данных). Пишет анализатор ~2fps,
    // читает авто-EV перед кадром. Переживает ребинды (object, не активити).
    @Volatile var lastPreviewL = -1f
    // Доля ярких пикселей превью (>0.9): точечный свет для авто-EV (лампа не видна
    // по среднему, видна по клиппингу). Пишет анализатор, читает biasSteps.
    @Volatile var lastBrightFrac = 0f
    // Последний успешный AF-лок серии (для soft-ZSL: сцена стоит — пропускаем 600мс).
    @Volatile var lastSeriesAfTs = 0L
    // Последний применённый авто-пресет сцены (для бейджа режима + диагностики).
    // Пусто = авто-сцена выкл или сцена нейтральная (ручной рецепт).
    @Volatile var lastScenePreset: String = ""
    @Volatile var lastSceneTs: Long = 0L
    // Последний применённый рецепт P0 (сцена + стадии + измеренная σ). Серийные
    // пути (ночь/HDR) пишут его в EXIF ImageDescription — «Подробности» галереи
    // без логов. Пусто = P0 не отработал (сток/сбой).
    @Volatile var lastRecipe: String = ""

    /** Запрос замера: произведение (выдержка×ISO), при котором идёт превью. 0 = нет запроса. */
    @Volatile var meterProduct: Long = 0L
    /** Ответ анализатора: средняя яркость кадра при meterProduct. -1 = ещё нет. */
    @Volatile var meterL: Float = -1f

    // История превью-яркости для детектора смены сцены (~2fps, окно ~5с).
    // Петля калибруется прошлым кадром: после резкой смены сцены первый кадр
    // обязан снимать HAL (у него живой замер), а не manual по протухшей кале.
    private val previewHist = ArrayDeque<Pair<Long, Float>>()

    // ZSL-кольцо V2: последние N превью-кадров (Y 160px + резкость + L + ts).
    // Без нового surface — пишет существующий ThmcTrackAnalyzer (~1.6fps).
    // Использование: пропуск AF (уже), адаптивное число кадров по тряске
    // (как Night Sight motion metering), мгновенная миниатюра.
    // Память: 8 x (160*120 + overhead) < 200КБ.
    private data class RingF(val ts: Long, val meanL: Float, val sharp: Double, val w: Int, val h: Int, val y: ByteArray)
    private val ring = ArrayDeque<RingF>()
    private const val RING_CAP = 8

    fun pushRing(meanL: Float, sharp: Double, w: Int, h: Int, y: ByteArray) {
        try {
            synchronized(ring) {
                // даунскейл до 160 по длинной для кольца (дешево, для motion metering хватает)
                val srcW = w; val srcH = h
                val longSide = maxOf(srcW, srcH)
                val s = if (longSide > 160) 160f / longSide else 1f
                val dw = maxOf(1, (srcW * s).toInt()); val dh = maxOf(1, (srcH * s).toInt())
                val small: ByteArray = if (s >= 1f) y.copyOf(minOf(y.size, dw * dh)) else {
                    val out = ByteArray(dw * dh)
                    val stepX = srcW.toFloat() / dw; val stepY = srcH.toFloat() / dh
                    for (dy in 0 until dh) for (dx in 0 until dw) {
                        val sx = (dx * stepX).toInt().coerceIn(0, srcW - 1)
                        val sy = (dy * stepY).toInt().coerceIn(0, srcH - 1)
                        val si = sy * srcW + sx
                        out[dy * dw + dx] = if (si < y.size) y[si] else 0
                    }
                    out
                }
                ring.addLast(RingF(System.currentTimeMillis(), meanL, sharp, dw, dh, small))
                while (ring.size > RING_CAP) ring.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /** Тряска за окно кольца: разброс резкости + яркости. >0.5 = руки ходят. */
    fun ringShake(): Float {
        return try {
            synchronized(ring) {
                if (ring.size < 3) return 0f
                var mnS = Double.MAX_VALUE; var mxS = -Double.MAX_VALUE
                var mnL = Float.MAX_VALUE; var mxL = -Float.MAX_VALUE
                for (f in ring) {
                    if (f.sharp < mnS) mnS = f.sharp
                    if (f.sharp > mxS) mxS = f.sharp
                    if (f.meanL < mnL) mnL = f.meanL
                    if (f.meanL > mxL) mxL = f.meanL
                }
                val sSpread = if (mxS > 0) ((mxS - mnS) / mxS).toFloat() else 0f
                (sSpread * 0.7f + (mxL - mnL) * 0.3f).coerceIn(0f, 1f)
            }
        } catch (_: Throwable) { 0f }
    }

    /** Адаптивное число кадров как у Night Sight: тряска → меньше кадров, иначе больше. */
    fun adaptiveFrames(base: Int, minF: Int = 2, maxF: Int = 8): Int {
        val shake = ringShake()
        return when {
            shake > 0.5f -> minF
            shake > 0.25f -> (base - 1).coerceAtLeast(minF)
            else -> base.coerceIn(minF, maxF)
        }
    }

    fun pushPreviewL(v: Float) {
        try {
            val now = System.currentTimeMillis()
            synchronized(previewHist) {
                previewHist.addLast(now to v)
                while (previewHist.size > 12) previewHist.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /** Разброс яркости превью за окно (макс-мин). <0 = мало сэмплов. */
    fun sceneSpread(windowMs: Long = 3000L): Float {
        return try {
            val now = System.currentTimeMillis()
            synchronized(previewHist) {
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                var n = 0
                for ((ts, v) in previewHist) {
                    if (now - ts > windowMs) continue
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                    n++
                }
                if (n >= 4) mx - mn else -1f
            }
        } catch (_: Throwable) { -1f }
    }

    /** Медиана яркости превью за окно. -1 = мало сэмплов. Чистая логика. */
    fun medianPreviewL(windowMs: Long = 3000L): Float {
        return try {
            val now = System.currentTimeMillis()
            val vs = synchronized(previewHist) {
                previewHist.filter { now - it.first <= windowMs }.map { it.second }
            }
            if (vs.size < 4) -1f
            else vs.sorted().let { it[it.size / 2] }
        } catch (_: Throwable) { -1f }
    }

    /** Резкий скачок яркости превью за последние ~3с = сцена сменилась. */
    fun sceneChanged(windowMs: Long = 3000L, thr: Float = 0.12f): Boolean {
        val s = sceneSpread(windowMs)
        return s >= 0f && s > thr
    }
    // Текущая (живая) активити для кольца/AF — ставит doTrackPress, фолбэк на bound
    @Volatile var uiRef: java.lang.ref.WeakReference<MainActivity>? = null
    fun ui(fallback: MainActivity): MainActivity = try {
        uiRef?.get() ?: fallback
    } catch (_: Throwable) { fallback }
}

class ThmcTrackAnalyzer(private val mActivity: MainActivity) : ImageAnalysis.Analyzer {
    private var lastTs = 0L
    private var lastSceneTs = 0L

    companion object {
        // Диагностика: сколько кадров вообще дошло до анализатора (0 = стрим не забиндился)
        @Volatile var framesTotal = 0L
        // Имя последнего исключения упаковки (пусто = упаковка ок, виноват трекер)
        @Volatile var lastError = ""
    }

    override fun analyze(image: ImageProxy) {
        try {
            framesTotal++
            // Свой замер экспозиции: каждый кадр, без троттлинга (ждёт захват).
            try {
                if (ThmcTrackState.meterProduct > 0L) {
                    val m = frameMean(image)
                    if (m != null) {
                        ThmcTrackState.meterL = m
                    }
                }
            } catch (_: Throwable) {}
            // Дешёвая сцена превью (~2fps): средний Y по каждому 16-му пикселю.
            // Работает всегда, независимо от трекинга.
            try {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSceneTs > 600) {
                    lastSceneTs = nowMs
                    val label = sceneLabel(image)
                    // ZSL-кольцо: каждый сэмпл сцены кладем в кольцо (motion metering).
                    try {
                        val w = image.width; val h = image.height
                        if (w >= 64 && h >= 64) {
                            packY(image, w, h)?.let { yFull ->
                                val longSide = maxOf(w, h)
                                val s = if (longSide > 160) 160f / longSide else 1f
                                val dw = maxOf(1, (w * s).toInt()); val dh = maxOf(1, (h * s).toInt())
                                val fy = FloatArray(dw * dh)
                                for (dy in 0 until dh) for (dx in 0 until dw) {
                                    val sx = (dx / s).toInt().coerceIn(0, w - 1)
                                    val sy = (dy / s).toInt().coerceIn(0, h - 1)
                                    val si = sy * w + sx
                                    fy[dy * dw + dx] = (yFull[si].toInt() and 0xFF).toFloat()
                                }
                                val sharp = com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(
                                    com.example.thundercam20.analysis.FastGray.Gray(dw, dh, fy)
                                )
                                val mL = ThmcTrackState.lastPreviewL
                                if (mL >= 0f) ThmcTrackState.pushRing(mL, sharp, w, h, yFull)
                            }
                        }
                    } catch (_: Throwable) {}
                    if (label != null) {
                        try {
                            val act = ThmcTrackState.ui(mActivity)
                            act.runOnUiThread { act.setSceneBadge(label) }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
            if (!ThmcTrackState.tracking) return
            val tracker = ThmcTrackState.tracker
            if (!tracker.isTracking()) return
            val now = System.nanoTime()
            if (now - lastTs < 66_000_000L) return // ~15fps: ведению нужно чаще, SAD дешёвый
            lastTs = now
            val w = image.width; val h = image.height
            if (w < 64 || h < 64) return
            // Упаковка Y с защитой от коротких/страйдовых буферов (BufferUnderflow ронял всё молча)
            val y: ByteArray = try {
                packY(image, w, h) ?: return
            } catch (e: Throwable) {
                lastError = "${e.javaClass.simpleName}"
                return
            }
            val rot = image.imageInfo.rotationDegrees
            val act = ThmcTrackState.ui(mActivity)
            val pt = tracker.onYFrame(y, w, h, rot) ?: run {
                // Потеряли — гасим UI трекинга
                try {
                    act.runOnUiThread { act.stopTrackUI() }
                } catch (_: Throwable) {}
                return
            }
            try {
                act.runOnUiThread { act.onTrackPoint(pt) }
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}"
            Log.w("ThmcTrack", "analyze", t)
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Средний Y кадра быстрой выборкой (каждый 16-й байт). null = кадр битый.
     * Используется и сценой (~2fps), и своим замером экспозиции (каждый кадр).
     * Заодно считает долю ярких (>230): клиппинг светов для авто-EV.
     */
    private fun frameMean(image: ImageProxy): Float? {
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            if (rowStride <= 0) return null
            buf.rewind()
            val avail = buf.remaining()
            if (avail <= 0) return null
            val take = minOf(avail, rowStride * image.height)
            if (take <= 0) return null
            val row = ByteArray(take)
            buf.get(row, 0, take)
            var sum = 0L; var cnt = 0; var bright = 0
            var i = 0
            while (i < take) {
                val v = row[i].toInt() and 0xFF
                sum += v; cnt++
                if (v > 230) bright++
                i += 16
            }
            if (cnt == 0) return null
            try { ThmcTrackState.lastBrightFrac = bright.toFloat() / cnt } catch (_: Throwable) {}
            sum.toFloat() / cnt / 255f
        } catch (_: Throwable) { null }
    }

    private fun labelFor(meanL: Float): String = when {
        meanL < 0.18f -> "НОЧЬ"
        meanL < 0.34f -> "ТЕМНО"
        meanL < 0.55f -> "ДЕНЬ"
        else -> "ЯРКО"
    }

    /**
     * Грубая сцена только по яркости превью (цвета в Y-плоскости нет).
     * Пороги согласованы с SceneAnalyzer: 0.28 ночь / 0.34 тёмное.
     * null = кадр битый, бейдж не трогаем.
     */
    private fun sceneLabel(image: ImageProxy): String? {
        return try {
            val meanL = frameMean(image) ?: return null
            try {
                ThmcTrackState.lastPreviewL = meanL
                ThmcTrackState.pushPreviewL(meanL)
            } catch (_: Throwable) {}
            labelFor(meanL)
        } catch (_: Throwable) { null }
    }

    /**
     * Упаковка Y-плоскости в плотный массив. Строго по remaining() — никаких чтений
     * мимо ёмкости (BufferUnderflow): короткая строка добивается нулями, короткий
     * кадр — null (пропуск кадра, не смерть трекинга).
     */
    private fun packY(image: ImageProxy, w: Int, h: Int): ByteArray? {
        val plane = image.planes[0]
        val pixStride = plane.pixelStride
        val buf = plane.buffer
        val rowStride = plane.rowStride
        if (rowStride <= 0) return null
        val y = ByteArray(w * h)
        buf.rewind()
        var dst = 0
        for (r in 0 until h) {
            val avail = buf.remaining()
            if (avail <= 0) return null
            val take = minOf(rowStride, avail)
            if (take < w && pixStride == 1) {
                // Короткая строка: забираем что есть, остальное нули
                val tmp = ByteArray(take)
                buf.get(tmp)
                System.arraycopy(tmp, 0, y, dst, minOf(take, w))
                dst += w
                continue
            }
            val row = ByteArray(take)
            buf.get(row)
            if (pixStride == 1) {
                System.arraycopy(row, 0, y, dst, minOf(w, take))
                dst += w
            } else {
                var si = 0
                for (c in 0 until w) {
                    y[dst++] = if (si < take) row[si] else 0
                    si += pixStride
                }
            }
        }
        return y
    }
}
