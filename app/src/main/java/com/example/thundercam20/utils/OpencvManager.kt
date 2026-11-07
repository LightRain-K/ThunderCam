package com.example.thundercam20.utils

import android.util.Log

/**
 * OpenCV native lib грузим ОДИН раз при старте. Если .so нет в AAR —
 * available=false и весь OpenCV-код молча идёт в Kotlin-фолбэк, без крашей.
 * ВАЖНО: ловить Throwable (UnsatisfiedLinkError — это Error, не Exception).
 */
object OpencvManager {
    private const val TAG = "OpencvManager"
    @Volatile var available = false
        private set

    fun init() {
        if (available) return
        available = try {
            // порядок важен: сначала STL, иначе UnsatisfiedLinkError
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            System.loadLibrary("opencv_java4")
            // пробный вызов — убеждаемся что JNI реально работает
            org.opencv.core.Mat().release()
            Log.i(TAG, "opencv_java4 loaded + working")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "opencv_java4 missing: ${t.javaClass.simpleName}: ${t.message} — Kotlin fallback")
            false
        }
    }

    inline fun <T> guard(fallback: () -> T, block: () -> T): T {
        if (!available) return fallback()
        return try { block() } catch (t: Throwable) { fallback() }
    }
}
