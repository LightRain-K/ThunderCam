package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThmcStabTest {

    @Test fun caps_by_ois() {
        assertEquals(12f, ThmcStab.seriesZoomCap(true))
        assertEquals(6f, ThmcStab.seriesZoomCap(false))
    }

    @Test fun allow_series_boundaries() {
        assertTrue(ThmcStab.allowSeries(1f, null))
        assertTrue(ThmcStab.allowSeries(6f, null)) // без камеры — как без OIS
        assertFalse(ThmcStab.allowSeries(6.1f, null))
        assertFalse(ThmcStab.allowSeries(20f, null))
    }
}
