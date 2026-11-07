package app.grapheneos.camera.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.ITEM_TYPE_IMAGE
import app.grapheneos.camera.R
import app.grapheneos.camera.thmc.ThmcEnhance
import app.grapheneos.camera.thmc.ThmcP0
import com.google.android.material.slider.Slider

/**
 * ThunderCam Enhance — отдельный экран обработки фото (задел под будущие инструменты:
 * сюда встанут апскейл, фильтры, текст/объекты).
 * Сейчас: шумодав + ИИ-свет (Zero-DCE) с живым превью, сравнение удержанием,
 * сохранение перезаписью оригинала (по умолчанию) или копией рядом.
 */
class ThmcEnhanceActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "thmc_enhance_uri"
        private const val EXTRA_DATE = "thmc_enhance_date"

        fun open(caller: InAppGallery, item: CapturedItem) {
            val i = android.content.Intent(caller, ThmcEnhanceActivity::class.java).apply {
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_DATE, item.dateString)
            }
            caller.startActivity(i)
        }

        /** Вход с видоискателя (снакбар после кадра): тот же intent, галерейные статики не трогаем. */
        fun open(caller: android.app.Activity, item: CapturedItem) {
            val i = android.content.Intent(caller, ThmcEnhanceActivity::class.java).apply {
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_DATE, item.dateString)
            }
            caller.startActivity(i)
        }
    }

    private lateinit var previewView: ImageView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var compareHint: TextView
    private lateinit var toolStrip: LinearLayout
    private lateinit var btnAi: Button
    private lateinit var btnDen: Button
    private lateinit var optionsPanel: LinearLayout
    private lateinit var aiStrengthBlock: LinearLayout
    private lateinit var aiStrengthBar: Slider
    private lateinit var aiStrengthValue: TextView
    private lateinit var strengthValue: TextView
    private lateinit var strengthBar: Slider
    private lateinit var strengthRow: LinearLayout
    private lateinit var overwriteRow: LinearLayout
    private lateinit var overwriteSwitch: android.widget.Switch
    private lateinit var saveBtn: Button
    private lateinit var proBtn: Button
    private lateinit var proStatus: TextView
    private lateinit var proProgress: ProgressBar
    private lateinit var saveRow: LinearLayout

    private var targetUri: Uri? = null
    private var targetDate: String = ""
    private var origBytes: ByteArray? = null

    private var origSmall: Bitmap? = null
    private var aiFull: Bitmap? = null
    private var denAi: Bitmap? = null
    private var denOrig: Bitmap? = null
    private var shown: Bitmap? = null
    private var curStrength = 85
    private var aiOn = false
    private var aiStrength = 25
    private var denVer = 0
    /** Активный инструмент: 0 — ничего (оригинал), 1 — ИИ-свет, 2 — шумодав. */
    private var activeTool = 0

    private fun enhancePrefs() =
        getSharedPreferences("thmc_enhance", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            curStrength = enhancePrefs().getInt("strength", 85).coerceIn(0, 100)
            aiStrength = enhancePrefs().getInt("ai_strength", 25).coerceIn(0, 100)
        } catch (_: Throwable) {}
        supportActionBar?.let {
            it.setDisplayShowTitleEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.title = getString(R.string.thmc_enhance_title)
        }

        targetUri = intent.getParcelableExtra(EXTRA_URI)
        targetDate = intent.getStringExtra(EXTRA_DATE) ?: ""
        val uri = targetUri
        if (uri == null) {
            finish()
            return
        }

        // Раскладка — в activity_thmc_enhance.xml (была кодом в onCreate). Здесь только привязка + стиль.
        setContentView(R.layout.activity_thmc_enhance)
        progressBox = findViewById(R.id.progress_box)
        progressBar = findViewById(R.id.progress_bar)
        previewView = findViewById(R.id.preview_view)
        compareHint = findViewById(R.id.compare_hint)
        optionsPanel = findViewById(R.id.options_panel)
        aiStrengthBlock = findViewById(R.id.ai_strength_block)
        aiStrengthValue = findViewById(R.id.ai_strength_value)
        aiStrengthBar = findViewById(R.id.ai_strength_bar)
        strengthRow = findViewById(R.id.strength_row)
        strengthValue = findViewById(R.id.strength_value)
        strengthBar = findViewById(R.id.strength_bar)
        toolStrip = findViewById(R.id.tool_strip)
        btnAi = findViewById(R.id.btn_ai)
        btnDen = findViewById(R.id.btn_den)
        overwriteRow = findViewById(R.id.overwrite_row)
        overwriteSwitch = findViewById(R.id.overwrite_switch)
        saveRow = findViewById(R.id.save_row)
        saveBtn = findViewById(R.id.save_btn)
        proBtn = findViewById(R.id.pro_btn)
        proStatus = findViewById(R.id.pro_status)
        proProgress = findViewById(R.id.pro_progress)
        aiStrengthBar.value = aiStrength.toFloat()
        aiStrengthValue.text = "$aiStrength%"
        strengthBar.value = curStrength.toFloat()
        strengthValue.text = "$curStrength%"
        overwriteSwitch.isChecked = try { enhancePrefs().getBoolean("overwrite", false) } catch (_: Throwable) { false }
        refreshToolTabs()

        val density = resources.displayMetrics.density
        // Минималистичные слайдеры: тонкая полоска + точка (монохром под чёрный фон)
        fun styleSlider(s: Slider) {
            s.trackHeight = (3 * density).toInt()
            s.thumbRadius = (7 * density).toInt()
            s.haloRadius = 0
            val grey = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
            val white = android.content.res.ColorStateList.valueOf(-1)
            s.trackInactiveTintList = grey
            s.trackActiveTintList = white
            s.thumbTintList = white
            s.tickInactiveTintList = grey
            s.tickActiveTintList = white
        }

        // (корень, превью, тулбар и панели — из activity_thmc_enhance.xml)


        // Лоадер с подписью (иначе открытие выглядит зависшим)
        // (прогресс и подпись загрузки — из XML)

        // (превью и хинт сравнения — из XML)


        // Панель инструментов как в нормальных редакторах: превью сверху,
        // внизу табы (ничего не применяется само), опции — над табами.
        // (тулбар с табами — из XML)


        // (опции и блоки слайдеров — из XML)

        // (слайдер ИИ-силы — из XML)
        styleSlider(aiStrengthBar)


        // (блок шумодава — из XML)

        // (слайдер шумодава — из XML)
        styleSlider(strengthBar)

        // (перезапись оригинала — из XML)


        // (кнопки сохранения и Pro — из XML)

        // (статус/прогресс Pro и сборка — из XML через setContentView выше)

        strengthBar.addOnChangeListener { _, v, _ ->
            curStrength = v.toInt()
            strengthValue.text = "${v.toInt()}%"
            showBlended()
        }
        strengthBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                try { enhancePrefs().edit().putInt("strength", curStrength).apply() } catch (_: Throwable) {}
            }
        })
        aiStrengthBar.addOnChangeListener { _, v, _ ->
            aiStrength = v.toInt()
            aiStrengthValue.text = "${v.toInt()}%"
            showBlended()
        }
        aiStrengthBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                try { enhancePrefs().edit().putInt("ai_strength", aiStrength).apply() } catch (_: Throwable) {}
                recomputeDen()
            }
        })
        btnAi.setOnClickListener { selectTool(if (activeTool == 1) 0 else 1) }
        btnDen.setOnClickListener { selectTool(if (activeTool == 2) 0 else 2) }
        overwriteSwitch.setOnCheckedChangeListener { _, checked ->
            try { enhancePrefs().edit().putBoolean("overwrite", checked).apply() } catch (_: Throwable) {}
        }
        previewView.setOnTouchListener { _, e ->
            val o = origSmall
            if (o == null) false
            else when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { previewView.setImageBitmap(o); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { showBlended(); true }
                else -> false
            }
        }
        saveBtn.setOnClickListener { save() }
        proBtn.setOnClickListener { proSave() }

        // Загрузка фото в фоне
        Thread {
            val bytes = ThmcEnhance.readBytes(this, uri)
            val o = bytes?.let { ThmcEnhance.decodePreview(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bytes == null || o == null) {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                origBytes = bytes
                origSmall = o
                // Ничего не применяем само: открываемся на оригинале, инструменты — по табам.
                activeTool = 0
                aiOn = false
                refreshToolTabs()
                recompute()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        try { origSmall?.recycle() } catch (_: Throwable) {}
        recycleOwned()
        try { shown?.recycle() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun recycleOwned() {
        try {
            val a = aiFull
            if (a != null && a !== origSmall) try { a.recycle() } catch (_: Exception) {}
        } catch (_: Throwable) {}
        try { denAi?.recycle() } catch (_: Throwable) {}
        try { denOrig?.recycle() } catch (_: Throwable) {}
        aiFull = null
        denAi = null
        denOrig = null
    }

    /** Табы-инструменты: выбранный — с ✓ и ярко, остальные приглушены, но не «мертвы». */
    private fun refreshToolTabs() {
        try {
            val aiLabel = getString(R.string.thmc_enhance_tool_ai)
            val denLabel = getString(R.string.thmc_enhance_strength)
            btnAi.text = if (activeTool == 1) "✓ $aiLabel" else aiLabel
            btnDen.text = if (activeTool == 2) "✓ $denLabel" else denLabel
            btnAi.alpha = if (activeTool == 1) 1f else 0.7f
            btnDen.alpha = if (activeTool == 2) 1f else 0.7f
        } catch (_: Throwable) {}
    }

    /** Выбор таба инструмента (повторный тап — снять, показать оригинал). */
    private fun selectTool(t: Int) {
        activeTool = if (activeTool == t) 0 else t
        refreshToolTabs()
        val wantAi = activeTool == 1
        aiStrengthBlock.visibility = if (wantAi) View.VISIBLE else View.GONE
        strengthRow.visibility = if (activeTool == 2) View.VISIBLE else View.GONE
        if (wantAi == aiOn) {
            showBlended()
            return
        }
        aiOn = wantAi
        recompute()
    }

    // Превью: два живых бленда (ИИ-сила, затем шумодав) — без пересчёта, ~100мс.
    // Без инструмента — честный оригинал.
    private fun showBlended() {
        val o = origSmall ?: return
        if (activeTool == 0) {
            try {
                val old = shown
                shown = null
                previewView.setImageBitmap(o)
                try { old?.recycle() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            return
        }
        try {
            var stage: Bitmap? = null
            if (aiOn) {
                val a = aiFull ?: return
                val c1 = a.copy(Bitmap.Config.ARGB_8888, false) ?: return
                val bl = ThmcP0.blendWithStock(o, c1, aiStrength / 100f)
                if (bl !== c1) try { c1.recycle() } catch (_: Throwable) {}
                stage = bl ?: return
            }
            val dn = if (aiOn) denAi else denOrig
            if (dn == null) {
                try { stage?.recycle() } catch (_: Throwable) {}
                return
            }
            val c2 = dn.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                try { stage?.recycle() } catch (_: Throwable) {}
                return
            }
            val res = ThmcP0.blendWithStock(stage ?: o, c2, curStrength / 100f)
            if (res !== c2) try { c2.recycle() } catch (_: Throwable) {}
            if (res == null) {
                try { stage?.recycle() } catch (_: Throwable) {}
                return
            }
            try { stage?.recycle() } catch (_: Throwable) {}
            val old = shown
            shown = res
            previewView.setImageBitmap(res)
            try { old?.recycle() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun showWork(work: Boolean) {
        progressBox.visibility = if (work) View.VISIBLE else View.GONE
        previewView.visibility = if (work) View.GONE else View.VISIBLE
        compareHint.visibility = if (work) View.GONE else View.VISIBLE
        optionsPanel.visibility = if (work) View.GONE else View.VISIBLE
        toolStrip.visibility = if (work) View.GONE else View.VISIBLE
        overwriteRow.visibility = if (work) View.GONE else View.VISIBLE
        saveRow.visibility = if (work) View.GONE else View.VISIBLE
        saveBtn.isEnabled = !work
        proBtn.isEnabled = !work
        if (!work) {
            // Панели опций — только у активного таба.
            aiStrengthBlock.visibility = if (aiOn) View.VISIBLE else View.GONE
            strengthRow.visibility = if (activeTool == 2) View.VISIBLE else View.GONE
        }
    }

    // Пересчёт тяжёлой базы (ИИ + оба шумодава). Слайдеры потом блендят живьём, без пересчёта.
    private fun recompute() {
        val o = origSmall ?: return
        denVer++
        showWork(true)
        val wantAi = aiOn
        Thread {
            var ai: Bitmap? = null
            var dAi: Bitmap? = null
            var dOrig: Bitmap? = null
            try {
                if (wantAi) {
                    ai = ThmcEnhance.aiLight(this, o)
                    dAi = ai?.let { ThmcEnhance.denoiseMax(it) }
                }
                dOrig = ThmcEnhance.denoiseMax(o)
            } catch (_: Throwable) {}
            val fAi = ai; val fDAi = dAi; val fDOrig = dOrig
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if ((wantAi && fDAi == null) || fDOrig == null) {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                recycleOwned()
                aiFull = fAi
                denAi = fDAi
                denOrig = fDOrig
                showWork(false)
                showBlended()
            }
        }.start()
    }

    // Шумодав превью пересчитываем от текущей ИИ-смеси при отпускании слайдера
    private fun recomputeDen() {
        val o = origSmall ?: return
        if (!aiOn) return
        val a = aiFull ?: return
        val s = aiStrength
        denVer++
        val myVer = denVer
        Thread {
            var stage: Bitmap? = null
            var den: Bitmap? = null
            try {
                val c = a.copy(Bitmap.Config.ARGB_8888, false) ?: return@Thread
                val bl = ThmcP0.blendWithStock(o, c, s / 100f)
                if (bl !== c) try { c.recycle() } catch (_: Throwable) {}
                stage = bl ?: c
                den = ThmcEnhance.denoiseMax(stage!!)
            } catch (_: Throwable) {}
            val fDen = den
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (myVer != denVer) {
                    try { fDen?.recycle() } catch (_: Throwable) {}
                    return@runOnUiThread
                }
                if (fDen == null) return@runOnUiThread
                try { denAi?.recycle() } catch (_: Throwable) {}
                denAi = fDen
                showBlended()
            }
        }.start()
    }

    private fun save() {
        saveBtn.isEnabled = false
        showWork(true)
        val bytes = origBytes
        val uri = targetUri
        if (bytes == null || uri == null) {
            finish()
            return
        }
        val s = curStrength
        val wantAi = aiOn
        val wantAiS = aiStrength
        val wantOverwrite = overwriteSwitch.isChecked
        Thread {
            val out = ThmcEnhance.enhanceFullChain(
                this, bytes, 95,
                if (wantAi) wantAiS / 100f else null, s / 100f
            )
            if (out == null) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    showWork(false)
                }
                return@Thread
            }
            if (wantOverwrite && ThmcEnhance.overwrite(this, uri, out)) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_overwritten, android.widget.Toast.LENGTH_SHORT).show()
                    InAppGallery.pendingFocusItem = CapturedItem(ITEM_TYPE_IMAGE, targetDate, uri)
                    InAppGallery.requestReload = true
                    finish()
                }
                return@Thread
            }
            val saved = ThmcEnhance.saveCopy(this, out)
            runOnUiThread {
                if (saved == null) {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    showWork(false)
                } else {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_saved, android.widget.Toast.LENGTH_SHORT).show()
                    InAppGallery.pendingFocusItem = CapturedItem(ITEM_TYPE_IMAGE, saved.second, saved.first)
                    InAppGallery.requestReload = true
                    finish()
                }
            }
        }.start()
    }

    /** Мощный ИИ-апскейл: облако x4plus (если настроено) иначе Pro на телефоне. Всегда копия _SR. */
    private fun proSave() {
        saveBtn.isEnabled = false
        proBtn.isEnabled = false
        showWork(true)
        proStatus.visibility = View.VISIBLE
        proProgress.visibility = View.VISIBLE
        proProgress.progress = 0
        val bytes = origBytes
        if (bytes == null) {
            finish()
            return
        }
        val backendName = try {
            val b = ThmcEnhance.proBackend(this)
            getString(
                if (b == ThmcEnhance.BACKEND_CLOUD) R.string.thmc_enhance_pro_cloud
                else R.string.thmc_enhance_pro_device
            )
        } catch (_: Throwable) { "Pro" }
        Thread {
            val res = ThmcEnhance.upscalePro(this, bytes, 93) { p ->
                runOnUiThread {
                    try {
                        proProgress.progress = p
                        proStatus.text = getString(R.string.thmc_enhance_pro_work, backendName, p)
                    } catch (_: Throwable) {}
                }
            }
            if (res == null) {
                runOnUiThread {
                    if (ThmcEnhance.lastProFail == "big") {
                        android.widget.Toast.makeText(this, R.string.thmc_enhance_pro_toobig, android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    proStatus.visibility = View.GONE
                    proProgress.visibility = View.GONE
                    showWork(false)
                }
                return@Thread
            }
            val saved = ThmcEnhance.saveCopy(this, res.bytes, "_SR")
            runOnUiThread {
                if (saved == null) {
                    android.widget.Toast.makeText(this, R.string.thmc_enhance_failed, android.widget.Toast.LENGTH_SHORT).show()
                    proStatus.visibility = View.GONE
                    proProgress.visibility = View.GONE
                    showWork(false)
                } else {
                    android.widget.Toast.makeText(
                        this,
                        getString(
                            R.string.thmc_enhance_pro_done,
                            backendName,
                            (res.ms / 1000).toInt(),
                            res.bytes.size / 1024
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    InAppGallery.pendingFocusItem = CapturedItem(ITEM_TYPE_IMAGE, saved.second, saved.first)
                    InAppGallery.requestReload = true
                    finish()
                }
            }
        }.start()
    }
}
