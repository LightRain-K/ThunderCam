package app.grapheneos.camera.thmc

import app.grapheneos.camera.CamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThmcSettingsTest {

    private fun fresh() = ThmcSettings(MapStore())

    @Test fun defaults_match_spec() {
        val s = fresh()
        assertTrue(s.p0)
        assertEquals(100, s.p0Strength)
        // Стандартный рецепт поднят 60→66/66/68: полевая жалоба «эффект слабоват».
        assertEquals(66, s.tone)
        assertEquals(66, s.contrast)
        assertEquals(68, s.sat)
        assertEquals(50, s.warmth)
        assertEquals(60, s.denoise)
        assertTrue(s.globalRefine)
        assertFalse(s.hdr)
        assertTrue(s.track)
        assertTrue(s.autoScene)
        assertTrue(s.autoEv)
        assertTrue(s.manualEv)
        assertFalse(s.calibrated)
        assertEquals(70, s.srBlend)
        assertFalse(s.srPowerful)
        assertEquals("", s.cloudUrl)
        assertFalse(s.cloudAuto)
        assertTrue(s.nightAuto)
        assertEquals("auto", s.benchTier)
        assertFalse(s.benchDone)
        assertEquals(0f, s.benchScore)
        assertEquals("auto", s.srModel)
        assertEquals("auto", s.photoResolution)
    }

    @Test fun stage_values_are_clamped() {
        val s = fresh()
        s.tone = 200
        assertEquals(100, s.tone)
        s.tone = -5
        assertEquals(0, s.tone)
        s.srBlend = 150
        assertEquals(100, s.srBlend)
        s.srBlend = -1
        assertEquals(0, s.srBlend)
    }

    @Test fun p0strength_getter_never_zero() {
        val s = fresh()
        s.p0Strength = 0
        assertEquals(1, s.p0Strength)
        s.p0Strength = 42
        assertEquals(42, s.p0Strength)
    }

    @Test fun cloud_url_is_trimmed() {
        val s = fresh()
        s.cloudUrl = "  https://example.com/  "
        assertEquals("https://example.com/", s.cloudUrl)
    }

    @Test fun reset_recipe_restores_sliders_only() {
        val s = fresh()
        s.tone = 10
        s.denoise = 0
        s.hdr = true
        s.resetRecipe()
        assertEquals(66, s.tone)
        assertEquals(60, s.denoise)
        assertEquals(100, s.p0Strength)
        assertTrue(s.hdr) // тумблеры не трогает
    }

    @Test fun photo_resolution_normalize() {
        assertEquals("auto", ThmcSettings.normalizePhotoResolution(null))
        assertEquals("auto", ThmcSettings.normalizePhotoResolution("junk"))
        assertEquals("max", ThmcSettings.normalizePhotoResolution(" MAX "))
        assertEquals("4000x3000", ThmcSettings.normalizePhotoResolution(" 4000X3000 "))
    }

    @Test fun parse_wxh() {
        assertEquals(4000 to 3000, ThmcSettings.parseWxH("4000x3000"))
        assertNull(ThmcSettings.parseWxH("max"))
        assertNull(ThmcSettings.parseWxH("auto"))
        assertNull(ThmcSettings.parseWxH("100x100")) // меньше 320x240
        assertNull(ThmcSettings.parseWxH("junk"))
        assertNull(ThmcSettings.parseWxH(null))
    }

    @Test fun keys_match_camconfig_storage() {
        // prefs-ключи — стабильное хранилище: расхождение = потеря настроек юзера.
        // SettingValues.Key — только const, грузится на JVM без Android.
        val k = CamConfig.SettingValues.Key
        assertEquals(k.THMC_P0, ThmcSettings.Keys.P0)
        assertEquals(k.THMC_P0_STRENGTH, ThmcSettings.Keys.P0_STRENGTH)
        assertEquals(k.THMC_HDR, ThmcSettings.Keys.HDR)
        assertEquals(k.THMC_TRACK, ThmcSettings.Keys.TRACK)
        assertEquals(k.THMC_AUTO_SCENE, ThmcSettings.Keys.AUTO_SCENE)
        assertEquals(k.THMC_AUTO_EV, ThmcSettings.Keys.AUTO_EV)
        assertEquals(k.THMC_MANUAL_EV, ThmcSettings.Keys.MANUAL_EV)
        assertEquals(k.THMC_CALIBRATED, ThmcSettings.Keys.CALIBRATED)
        assertEquals(k.THMC_ZOOM, ThmcSettings.Keys.ZOOM)
        assertEquals(k.THMC_SR_POWERFUL, ThmcSettings.Keys.SR_POWERFUL)
        assertEquals(k.THMC_SR_BLEND, ThmcSettings.Keys.SR_BLEND)
        assertEquals(k.THMC_CLOUD_URL, ThmcSettings.Keys.CLOUD_URL)
        assertEquals(k.THMC_CLOUD_AUTO, ThmcSettings.Keys.CLOUD_AUTO)
        assertEquals(k.THMC_TONE, ThmcSettings.Keys.TONE)
        assertEquals(k.THMC_CONTRAST, ThmcSettings.Keys.CONTRAST)
        assertEquals(k.THMC_SAT, ThmcSettings.Keys.SAT)
        assertEquals(k.THMC_WARMTH, ThmcSettings.Keys.WARMTH)
        assertEquals(k.THMC_DENOISE, ThmcSettings.Keys.DENOISE)
        assertEquals(k.THMC_PRESET, ThmcSettings.Keys.PRESET)
        assertEquals(k.THMC_GLOBAL_REFINE, ThmcSettings.Keys.GLOBAL_REFINE)
        assertEquals(k.THMC_NIGHT_AUTO, ThmcSettings.Keys.NIGHT_AUTO)
        assertEquals(k.PHOTO_RESOLUTION, ThmcSettings.Keys.PHOTO_RESOLUTION)
    }
}
