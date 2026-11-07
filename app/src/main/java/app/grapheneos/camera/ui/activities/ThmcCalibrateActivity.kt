package app.grapheneos.camera.ui.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.R
import app.grapheneos.camera.thmc.ThmcP0

/**
 * ThunderCam мастер калибровки под свой телефон: цвета сенсоров бюджетников гуляют,
 * слайдеры — для инженеров. Здесь: дневное фото с лицом → 2 раунда выбора из 4
 * (теплее/холоднее × сочнее/спокойнее) → рецепт пишется в слайдеры. 2 тапа, без слов.
 */
class ThmcCalibrateActivity : AppCompatActivity() {

    companion object {
        // CamConfig не parcelable — короткий мост на время мастера (чистим в onDestroy)
        @Volatile var camConfig: CamConfig? = null
        @Volatile var offerShown = false

        fun open(caller: AppCompatActivity, cfg: CamConfig) {
            camConfig = cfg
            caller.startActivity(Intent(caller, ThmcCalibrateActivity::class.java))
        }
    }

    private lateinit var titleView: TextView
    private lateinit var contentBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var actionBtn: Button

    private var step = 0 // 0 выбор фото, 1 раунд1, 2 раунд2, 3 готово
    private var orig512: Bitmap? = null
    private var r1: List<Cell> = emptyList()
    private var r2: List<Cell> = emptyList()
    private var winner12: Cell? = null
    private var finalW = 60
    private var finalS = 60
    private var pickerLaunched = false

    private data class Cell(val bmp: Bitmap, val caption: String, val w: Int, val s: Int)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            if (orig512 == null) showPick()
            return@registerForActivityResult
        }
        Thread {
            val bmp = decode512(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp == null) {
                    Toast.makeText(this, R.string.thmc_enhance_failed, Toast.LENGTH_SHORT).show()
                    showPick()
                    return@runOnUiThread
                }
                try { orig512?.recycle() } catch (_: Throwable) {}
                orig512 = bmp
                step = 1
                renderRound(25, 20, null)
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (camConfig == null) {
            finish()
            return
        }
        supportActionBar?.let {
            it.setDisplayShowTitleEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.title = getString(R.string.thmc_cal_title)
        }
        // Каркас — activity_thmc_calibrate.xml; сетка вариантов строится кодом в contentBox.
        setContentView(R.layout.activity_thmc_calibrate)
        titleView = findViewById(R.id.cal_title)
        contentBox = findViewById(R.id.cal_content)
        progressBar = findViewById(R.id.cal_progress)
        actionBtn = findViewById(R.id.cal_action)

        showPick()
    }

    override fun onSupportNavigateUp(): Boolean {
        stepBack()
        return true
    }

    override fun onBackPressed() {
        if (step > 0) stepBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        try { orig512?.recycle() } catch (_: Throwable) {}
        clearCells(r1)
        clearCells(r2)
        camConfig = null
        super.onDestroy()
    }

    private fun clearCells(cells: List<Cell>) {
        for (c in cells) try { c.bmp.recycle() } catch (_: Throwable) {}
    }

    private fun stepBack() {
        if (step == 3) {
            step = 2
            showGrid(r2, getString(R.string.thmc_cal_step2))
        } else if (step == 2) {
            step = 1
            showGrid(r1, getString(R.string.thmc_cal_step1))
        } else {
            finish()
        }
    }

    private fun showPick() {
        step = 0
        titleView.text = getString(R.string.thmc_cal_pick_hint)
        progressBar.visibility = View.GONE
        contentBox.removeAllViews()
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.image_quality)
            alpha = 0.4f
        }
        contentBox.addView(iv)
        actionBtn.text = getString(R.string.thmc_cal_pick)
        actionBtn.setOnClickListener { pickImage.launch("image/*") }
        if (!pickerLaunched) {
            pickerLaunched = true
            try { pickImage.launch("image/*") } catch (_: Throwable) {}
        }
    }

    private fun decode512(uri: android.net.Uri): Bitmap? {
        return try {
            val cr = contentResolver
            cr.openInputStream(uri)?.use { inp ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(inp, null, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = s
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                cr.openInputStream(uri)?.use { inp2 ->
                    BitmapFactory.decodeStream(inp2, null, opts)
                }
            }
        } catch (_: Throwable) { null }
    }

    /** Раунд вариантов: сетка вокруг (cw,cs) с разбросом (spreadW, spreadS). null = от текущих слайдеров. */
    private fun renderRound(spreadW: Int, spreadS: Int, center: Pair<Int, Int>?) {
        val cfg = camConfig ?: run { finish(); return }
        val o = orig512 ?: run { showPick(); return }
        val cw = center?.first ?: cfg.thmcWarmth
        val cs = center?.second ?: cfg.thmcSat
        titleView.text = getString(if (step == 1) R.string.thmc_cal_step1 else R.string.thmc_cal_step2)
        progressBar.visibility = View.VISIBLE
        contentBox.removeAllViews()
        actionBtn.text = getString(R.string.thmc_cal_other)
        actionBtn.setOnClickListener { showPick() }
        Thread {
            val variants = listOf(
                Pair((cw - spreadW).coerceIn(0, 100), (cs - spreadS).coerceIn(0, 100)),
                Pair((cw - spreadW).coerceIn(0, 100), (cs + spreadS).coerceIn(0, 100)),
                Pair((cw + spreadW).coerceIn(0, 100), (cs - spreadS).coerceIn(0, 100)),
                Pair((cw + spreadW).coerceIn(0, 100), (cs + spreadS).coerceIn(0, 100)),
            )
            val cells = mutableListOf<Cell>()
            try {
                val ip = com.example.thundercam20.imageprocessing.ImageProcessor()
                for ((w, s) in variants) {
                    val params = ThmcP0.Params(
                        tone = cfg.thmcTone, contrast = cfg.thmcContrast, sat = cfg.thmcSat,
                        warmth = w, denoise = cfg.thmcDenoise,
                    )
                    // Тепло/насыщенность — вариантные, остальное из слайдеров; сат вариантовый:
                    val p2 = params.copy(sat = s)
                    val res = ip.process(o, p2.toRestoreConfig(), null, null)
                    val cap = (if (w >= cw) getString(R.string.thmc_cal_warmer) else getString(R.string.thmc_cal_cooler)) +
                        " • " + (if (s >= cs) getString(R.string.thmc_cal_richer) else getString(R.string.thmc_cal_calmer))
                    cells.add(Cell(res.bitmap, cap, w, s))
                }
            } catch (_: Throwable) {}
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (cells.size < 4) {
                    Toast.makeText(this, R.string.thmc_enhance_failed, Toast.LENGTH_SHORT).show()
                    showPick()
                    return@runOnUiThread
                }
                progressBar.visibility = View.GONE
                if (step == 1) {
                    clearCells(r1)
                    r1 = cells
                } else {
                    clearCells(r2)
                    r2 = cells
                }
                showGrid(cells, titleView.text.toString())
            }
        }.start()
    }

    private fun showGrid(cells: List<Cell>, title: String) {
        titleView.text = title
        progressBar.visibility = View.GONE
        contentBox.removeAllViews()
        for (row in 0..1) {
            val rowBox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (col in 0..1) {
                val cell = cells[row * 2 + col]
                val cellBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setPadding(4, 4, 4, 4)
                }
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(cell.bmp)
                    isClickable = true
                    isFocusable = true
                }
                val cap = TextView(this).apply {
                    text = cell.caption
                    gravity = Gravity.CENTER
                    textSize = 13f
                }
                cellBox.addView(iv)
                cellBox.addView(cap)
                cellBox.setOnClickListener { choose(cell) }
                iv.setOnClickListener { choose(cell) }
                rowBox.addView(cellBox)
            }
            contentBox.addView(rowBox)
        }
        actionBtn.text = getString(R.string.thmc_cal_other)
        actionBtn.setOnClickListener { showPick() }
    }

    private fun choose(cell: Cell) {
        if (step == 1) {
            winner12 = cell
            step = 2
            renderRound(12, 10, Pair(cell.w, cell.s))
        } else {
            finalW = cell.w
            finalS = cell.s
            step = 3
            showDone(cell)
        }
    }

    private fun showDone(cell: Cell) {
        val cfg = camConfig ?: run { finish(); return }
        cfg.thmcWarmth = cell.w
        cfg.thmcSat = cell.s
        cfg.thmcCalibrated = true
        titleView.text = getString(R.string.thmc_cal_done)
        progressBar.visibility = View.GONE
        contentBox.removeAllViews()
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(cell.bmp)
        }
        contentBox.addView(iv)
        actionBtn.text = getString(R.string.ok)
        actionBtn.setOnClickListener { finish() }
        Toast.makeText(this, R.string.thmc_cal_saved, Toast.LENGTH_SHORT).show()
    }
}
