package app.grapheneos.camera.thmc

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera

/**
 * ThunderCam гейт серий (HDR/ночь) по зуму и стабилизации телефона.
 *
 * Физика: сдвиг от дрожи растёт с зумом линейно; на большом зуме он больше
 * окна поиска равнялки — пересечение схлопывается, мерж даёт кашу.
 * OIS сдвигает границу вверх (проверяем по характеристикам камеры, не по модели).
 */
object ThmcStab {
    /** Есть ли оптическая стабилизация у текущей камеры. */
    fun hasOis(camera: Camera?): Boolean {
        return try {
            camera ?: return false
            val modes = Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                ) ?: return false
            modes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        } catch (_: Throwable) { false }
    }

    /** Потолок тотального зума для серий: OIS 12×, иначе 6×. Чистый. */
    fun seriesZoomCap(hasOis: Boolean): Float = if (hasOis) 12f else 6f

    /** Можно ли серию при таком тотальном зуме (оптика × цифра). */
    fun allowSeries(totalZoom: Float, camera: Camera?): Boolean {
        if (totalZoom <= 1.01f) return true
        return try {
            totalZoom <= seriesZoomCap(hasOis(camera))
        } catch (_: Throwable) {
            totalZoom <= 6f
        }
    }
}
