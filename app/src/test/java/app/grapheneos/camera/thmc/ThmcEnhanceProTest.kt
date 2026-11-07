package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThmcEnhanceProTest {

    @Test fun small_photo_gets_2x() {
        assertEquals(800 to 600, ThmcEnhance.proTarget(400, 300))
        assertEquals(2048 to 1536, ThmcEnhance.proTarget(1024, 768))
    }

    @Test fun big_photo_refused() {
        // 12Мп: масштаб ×1.03 — Pro на телефоне бессмысленен, только облако.
        assertNull(ThmcEnhance.proTarget(4000, 3000))
        assertNull(ThmcEnhance.proTarget(2962, 3988))
        assertNull(ThmcEnhance.proTarget(10, 10))
    }
}
