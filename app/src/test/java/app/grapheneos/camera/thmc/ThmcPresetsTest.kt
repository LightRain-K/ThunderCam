package app.grapheneos.camera.thmc

import com.example.thundercam20.analysis.SceneAnalyzer.Scene
import org.junit.Assert.*
import org.junit.Test

class ThmcPresetsTest {

    @Test fun byId_fallback_to_standard() {
        assertEquals(ThmcPresets.ID_STANDARD, ThmcPresets.byId(null).id)
        assertEquals(ThmcPresets.ID_STANDARD, ThmcPresets.byId("junk").id)
        assertEquals(ThmcPresets.ID_FOOD, ThmcPresets.byId(ThmcPresets.ID_FOOD).id)
    }

    @Test fun matchId_exact_or_custom() {
        val f = ThmcPresets.byId(ThmcPresets.ID_FOOD)
        assertEquals(ThmcPresets.ID_FOOD, ThmcPresets.matchId(f.tone, f.contrast, f.sat, f.warmth, f.denoise))
        assertEquals(ThmcPresets.ID_CUSTOM, ThmcPresets.matchId(61, 60, 60, 50, 60))
    }

    @Test fun all_values_in_slider_range() {
        for (p in ThmcPresets.ALL) {
            assertTrue(p.id, p.tone in 0..100)
            assertTrue(p.id, p.contrast in 0..100)
            assertTrue(p.id, p.sat in 0..100)
            assertTrue(p.id, p.warmth in 0..100)
            assertTrue(p.id, p.denoise in 0..100)
        }
    }

    @Test fun night_preset_max_denoise_restrained_sat() {
        val n = ThmcPresets.byId(ThmcPresets.ID_NIGHT)
        assertEquals(100, n.denoise)
        assertTrue(n.sat <= 45)
    }

    @Test fun document_preset_calm_and_punchy() {
        val d = ThmcPresets.byId(ThmcPresets.ID_DOCUMENT)
        assertTrue(d.sat <= 15)
        assertTrue(d.contrast >= 80)
        assertTrue(d.denoise <= 20)
    }

    @Test fun scenePreset_mapping() {
        assertEquals(ThmcPresets.ID_NIGHT, ThmcPresets.scenePreset(Scene.NIGHT))
        assertEquals(ThmcPresets.ID_NIGHT, ThmcPresets.scenePreset(Scene.DARK_INDOOR))
        assertEquals(ThmcPresets.ID_PORTRAIT, ThmcPresets.scenePreset(Scene.PORTRAIT))
        assertEquals(ThmcPresets.ID_DOCUMENT, ThmcPresets.scenePreset(Scene.TEXT))
        assertEquals(ThmcPresets.ID_FOOD, ThmcPresets.scenePreset(Scene.FOOD))
        assertEquals(ThmcPresets.ID_LANDSCAPE, ThmcPresets.scenePreset(Scene.LANDSCAPE))
        assertEquals(ThmcPresets.ID_LANDSCAPE, ThmcPresets.scenePreset(Scene.DAY_OUTDOOR))
        assertNull(ThmcPresets.scenePreset(Scene.DAY_INDOOR))
        // все id из маппинга существуют
        for (id in listOf(ThmcPresets.ID_NIGHT, ThmcPresets.ID_PORTRAIT, ThmcPresets.ID_DOCUMENT, ThmcPresets.ID_FOOD, ThmcPresets.ID_LANDSCAPE)) {
            assertEquals(id, ThmcPresets.byId(id).id)
        }
    }

    @Test fun toRestoreConfig_ranges() {
        for (p in ThmcPresets.ALL) {
            val c = ThmcPresets.restoreConfigOf(p)
            assertTrue(p.id, c.toneStrength in 0f..1f)
            assertTrue(p.id, c.contrastFactor in 0.9f..1.6f)
            assertTrue(p.id, c.saturationFactor in 0.9f..1.6f)
            assertTrue(p.id, c.warmth in 0f..1f)
        }
        val night = ThmcPresets.restoreConfigOf(ThmcPresets.byId(ThmcPresets.ID_NIGHT))
        assertEquals(2, night.denoiseMode)
        val doc = ThmcPresets.restoreConfigOf(ThmcPresets.byId(ThmcPresets.ID_DOCUMENT))
        assertTrue(doc.contrastFactor > 1.05f)
        assertTrue(doc.saturationFactor < 1.1f)
    }

    @Test fun applyTo_writes_sliders_and_id() {
        val s = ThmcSettings(MapStore())
        ThmcPresets.applyTo(s, ThmcPresets.ID_PORTRAIT)
        val p = ThmcPresets.byId(ThmcPresets.ID_PORTRAIT)
        assertEquals(p.tone, s.tone)
        assertEquals(p.sat, s.sat)
        assertEquals(p.denoise, s.denoise)
        assertEquals(ThmcPresets.ID_PORTRAIT, s.preset)
        assertEquals(ThmcPresets.ID_PORTRAIT, ThmcPresets.matchId(s.tone, s.contrast, s.sat, s.warmth, s.denoise))
    }
}
