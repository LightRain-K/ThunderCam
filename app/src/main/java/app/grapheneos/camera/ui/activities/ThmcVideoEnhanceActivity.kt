package app.grapheneos.camera.ui.activities

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.R
import app.grapheneos.camera.thmc.SharedPrefsStore
import app.grapheneos.camera.thmc.ThmcSettings
import app.grapheneos.camera.thmc.ThmcVideo
import app.grapheneos.camera.thmc.ThmcVideoEnhancer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Видео-улучшение: GPU-транскод с рецептом P0 (тот же, что для фото).
 * Оригинал не трогаем — рядом кладём копию _THMC.
 */
class ThmcVideoEnhanceActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "thmc_video_uri"
        private const val EXTRA_DATE = "thmc_video_date"

        fun open(caller: Activity, item: CapturedItem) {
            val i = android.content.Intent(caller, ThmcVideoEnhanceActivity::class.java).apply {
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_DATE, item.dateString)
            }
            caller.startActivity(i)
        }
    }

    private var srcUri: Uri? = null
    private var dateStr: String = ""
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.let {
            it.setDisplayShowTitleEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.title = getString(R.string.thmc_video_title)
        }
        srcUri = intent.getParcelableExtra(EXTRA_URI)
        dateStr = intent.getStringExtra(EXTRA_DATE) ?: ""
        if (srcUri == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_thmc_video_enhance)
        val status: TextView = findViewById(R.id.video_status)
        val progress: ProgressBar = findViewById(R.id.video_progress)
        val strengthValue: TextView = findViewById(R.id.video_strength_value)
        val strengthBar: com.google.android.material.slider.Slider = findViewById(R.id.video_strength_bar)
        val startBtn: Button = findViewById(R.id.video_start_btn)
        val cancelBtn: Button = findViewById(R.id.video_cancel_btn)
        var strength = 70
        strengthBar.addOnChangeListener { _, v, _ ->
            strength = v.toInt()
            strengthValue.text = "$strength%"
        }
        startBtn.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            cancelled.set(false)
            startBtn.isEnabled = false
            cancelBtn.isEnabled = true
            status.text = getString(R.string.thmc_video_working, 0)
            val uri = srcUri ?: return@setOnClickListener
            Thread {
                runEnhance(uri, strength, status, progress, startBtn, cancelBtn)
            }.start()
        }
        cancelBtn.setOnClickListener {
            cancelled.set(true)
        }
    }

    private fun runEnhance(
        uri: Uri,
        strength: Int,
        status: TextView,
        progress: ProgressBar,
        startBtn: Button,
        cancelBtn: Button,
    ) {
        var ok = false
        var outUri: Uri? = null
        try {
            val thmc = ThmcSettings(SharedPrefsStore(getSharedPreferences("commons", MODE_PRIVATE)))
            val grade = ThmcVideo.gradeFor(
                strength / 100f, thmc.tone, thmc.contrast, thmc.sat, thmc.warmth
            )
            val tmp = File.createTempFile("thmc_video_", ".mp4", cacheDir)
            val done = ThmcVideoEnhancer.enhance(
                this, uri, tmp, grade,
                onProgress = { pct ->
                    runOnUiThread {
                        if (!isFinishing) {
                            progress.progress = pct
                            status.text = getString(R.string.thmc_video_working, pct)
                        }
                    }
                },
                cancelled = cancelled
            )
            if (done && !cancelled.get()) {
                outUri = saveToGallery(tmp, uri)
                tmp.delete()
                ok = outUri != null
            } else {
                tmp.delete()
            }
        } catch (t: Throwable) {
            ok = false
        }
        val finUri = outUri
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            running = false
            startBtn.isEnabled = true
            cancelBtn.isEnabled = false
            if (ok && finUri != null) {
                status.text = getString(R.string.thmc_video_done)
                progress.progress = 100
                try {
                    android.widget.Toast.makeText(this, R.string.thmc_video_done, android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            } else if (!cancelled.get()) {
                status.text = getString(R.string.thmc_video_fail)
                try {
                    android.widget.Toast.makeText(this, R.string.thmc_video_fail, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            } else {
                status.text = getString(R.string.thmc_video_start_hint)
                progress.progress = 0
            }
        }
    }

    /** Копия _THMC рядом: MediaStore Movies/ThunderCam, оригинал цел. */
    private fun saveToGallery(tmp: File, srcUri: Uri): Uri? {
        return try {
            val srcName = try {
                contentResolver.query(srcUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Throwable) { null } ?: "VID_$dateStr.mp4"
            val name = ThmcVideo.outName(srcName)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ThunderCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(coll, cv) ?: return null
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    tmp.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }
                cv.clear()
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } catch (e: Exception) {
                try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                return null
            }
            uri
        } catch (_: Throwable) { null }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }
}
