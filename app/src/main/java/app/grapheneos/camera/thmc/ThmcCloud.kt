package app.grapheneos.camera.thmc

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ThunderCam облако: апскейл на VPS (Real-ESRGAN x4plus, полное разрешение).
 * Асинхронный протокол:
 *   POST /enhance → {"job_id": "..."}
 *   GET  /status/{job_id} → {"status": "queued|processing|done|error", "elapsed": N}
 *   GET  /result/{job_id} → enhanced JPEG bytes
 * Прогресс оценивается по времени (сервер не стримит точный прогресс).
 */
object ThmcCloud {
    private const val TAG = "ThmcCloud"

    /** Перезапись файла по uri (MediaStore). false = нет доступа → вызыватель делает копию рядом. */
    fun overwrite(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: return false
            true
        } catch (_: Throwable) { false }
    }

    /** Проверка доступности сервера. Пусто = выкл (false, без сети). */
    fun health(baseUrl: String): Boolean {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return false
        if (base.startsWith("http://")) {
            Log.w(TAG, "plain http cloud url — фото уйдёт без TLS, включено явно пользователем")
        }
        return try {
            val url = URL("$base/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Throwable) { false }
    }

    /**
     * Отправка фото на сервер для апскейла (асинхронно с прогрессом).
     * @param onProgress вызывается с 0..100 (оценка по времени)
     * @return enhanced JPEG bytes или null при ошибке
     */
    fun enhance(
        context: Context,
        baseUrl: String,
        imageBytes: ByteArray,
        onProgress: (Int) -> Unit,
    ): ByteArray? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            Log.w(TAG, "cloud url empty — opt-in не включён")
            return null
        }
        if (base.startsWith("http://")) {
            Log.w(TAG, "plain http cloud url — фото уйдёт без TLS")
        }
        var conn: HttpURLConnection? = null
        return try {
            // Step 1: Upload image, get job_id (retry up to 3 times)
            var jobId = ""
            for (uploadRetry in 0..2) {
                try {
                    val url = URL("$base/enhance")
                    conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 30000
                    conn.readTimeout = 120000
                    val boundary = "----ThunderCamBoundary${System.currentTimeMillis()}"
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                    val os = conn.outputStream
                    val dos = DataOutputStream(os)
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n")
                    dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                    dos.write(imageBytes)
                    dos.writeBytes("\r\n--$boundary--\r\n")
                    dos.flush()
                    dos.close()

                    onProgress(5)

                    if (conn.responseCode != 200) {
                        Log.w(TAG, "upload returned ${conn.responseCode}")
                        conn.disconnect()
                        conn = null
                        try { Thread.sleep(2000) } catch (_: Throwable) {}
                        continue
                    }

                    val response = conn.inputStream.readBytes()
                    conn.disconnect()
                    conn = null

                    val json = JSONObject(String(response))
                    jobId = json.optString("job_id", "")
                    if (jobId.isBlank()) {
                        Log.w(TAG, "no job_id in response: ${String(response)}")
                        try { Thread.sleep(2000) } catch (_: Throwable) {}
                        continue
                    }
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "upload retry $uploadRetry failed", t)
                    try { conn?.disconnect() } catch (_: Throwable) {}
                    conn = null
                    try { Thread.sleep(2000) } catch (_: Throwable) {}
                }
            }

            if (jobId.isBlank()) {
                Log.w(TAG, "upload failed after retries")
                return null
            }

            onProgress(10)

            // Step 2: Poll status until done (max 15 minutes)
            val startTime = System.currentTimeMillis()
            val maxWait = 900000L // 15 min
            var elapsed = 0L
            var pollFailures = 0
            while (System.currentTimeMillis() - startTime < maxWait) {
                Thread.sleep(3000) // poll every 3s
                elapsed = System.currentTimeMillis() - startTime

                // Estimate progress: assume ~5min for full processing
                val estProgress = (10 + (elapsed / 1000.0 / 300.0 * 80).toInt()).coerceIn(10, 90)
                onProgress(estProgress)

                // Retry status poll up to 3 times
                var statusOk = false
                var status = ""
                var statusJson: JSONObject? = null
                for (retry in 0..2) {
                    try {
                        val statusUrl = URL("$base/status/$jobId")
                        val statusConn = statusUrl.openConnection() as HttpURLConnection
                        statusConn.connectTimeout = 15000
                        statusConn.readTimeout = 30000
                        statusConn.requestMethod = "GET"

                        if (statusConn.responseCode == 200) {
                            statusJson = JSONObject(String(statusConn.inputStream.readBytes()))
                            status = statusJson.optString("status", "")
                            statusConn.disconnect()
                            statusOk = true
                            break
                        } else {
                            statusConn.disconnect()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "status poll retry $retry failed", t)
                        try { Thread.sleep(1000) } catch (_: Throwable) {}
                    }
                }

                if (!statusOk) {
                    pollFailures++
                    if (pollFailures >= 5) {
                        Log.w(TAG, "too many poll failures, giving up")
                        return null
                    }
                    continue
                }

                if (status == "done") {
                    onProgress(95)
                    // Step 3: Download result with retry
                    for (retry in 0..2) {
                        try {
                            val resultUrl = URL("$base/result/$jobId")
                            val resultConn = resultUrl.openConnection() as HttpURLConnection
                            resultConn.connectTimeout = 15000
                            resultConn.readTimeout = 120000
                            resultConn.requestMethod = "GET"

                            if (resultConn.responseCode == 200) {
                                val resultBytes = resultConn.inputStream.readBytes()
                                resultConn.disconnect()
                                onProgress(100)
                                Log.i(TAG, "cloud enhance ok: ${resultBytes.size / 1024}KB")
                                return resultBytes
                            } else {
                                resultConn.disconnect()
                                Log.w(TAG, "result download failed: ${resultConn.responseCode}")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "result download retry $retry failed", t)
                            try { Thread.sleep(2000) } catch (_: Throwable) {}
                        }
                    }
                    return null
                } else if (status == "error") {
                    Log.w(TAG, "server error: ${statusJson?.optString("error")}")
                    return null
                }
                // else: queued or processing, continue polling
            }

            Log.w(TAG, "timeout waiting for cloud result")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "cloud enhance failed", t)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
