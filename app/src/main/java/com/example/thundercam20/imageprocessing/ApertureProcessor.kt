package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap

/**
 * Форк-заглушка (GrapheneOS): глубинное боке через TFLite сюда не портировано (Фаза 1 — только P0).
 * В текущем пайплайне класс всё равно не используется: боке идёт через FaceBokeh,
 * aperture по умолчанию 16f (ветка боке пропускается). Заглушка — чтобы донорские файлы
 * компилировались без изменений.
 */
class ApertureProcessor {
    fun restore(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") aperture: Float): Bitmap = bitmap
}
