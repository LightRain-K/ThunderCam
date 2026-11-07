package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThmcNightTest {

    @Test fun dark_preview_is_night() {
        assertTrue(ThmcNight.isNight(0.10f))
        assertTrue(ThmcNight.isNight(0.34f))
        // Сумерки (0.35–0.48) теперь ВДВОРЬ ночи: единственный инструмент,
        // реально давящий шум в этом свете — стек (√N), а не эвристики P0.
        assertTrue(ThmcNight.isNight(0.36f))
        assertTrue(ThmcNight.isNight(0.47f))
        assertFalse(ThmcNight.isNight(0.50f))
        assertFalse(ThmcNight.isNight(0.60f))
        assertFalse(ThmcNight.isNight(-1f)) // неизвестно — не ночь
        assertFalse(ThmcNight.isNight(0.0f))
    }

    @Test fun darker_scene_means_more_frames_capped_at_6() {
        assertEquals(6, ThmcNight.planNight(0.05f).frames)
        assertEquals(5, ThmcNight.planNight(0.15f).frames)
        assertEquals(4, ThmcNight.planNight(0.30f).frames)
        // Сумеречный ти́р: 3 кадра — √3 шума без долгой стойки.
        assertEquals(3, ThmcNight.planNight(0.40f).frames)
    }

    @Test fun median_of_window() {
        repeat(5) { ThmcTrackState.pushPreviewL(0.8f) }
        assertEquals(0.8f, ThmcTrackState.medianPreviewL(), 0.001f)
    }

    @Test fun night_gating_pure_branches() {
        assertFalse(ThmcNight.shouldRunNight(0.60f)) // не ночь
        assertFalse(ThmcNight.shouldRunNight(-1f)) // неизвестно
        assertTrue(ThmcNight.shouldRunNight(0.05f)) // глубокая тьма — сразу
    }
}
