package app.grapheneos.camera.thmc

import org.junit.Assert.*
import org.junit.Test

class ThmcRawProbeTest {

    @Test fun pickBest_largest_fitting() {
        val sizes = listOf(4000 to 3000, 2000 to 1500, 8000 to 6000)
        assertEquals(4000 to 3000, ThmcRawProbe.pickBest(sizes, 6000))
    }

    @Test fun pickBest_caps_huge_sensor() {
        // 200Мп S25U в стек не тянем — крупнейший влезающий, а не крупнейший вообще
        val sizes = listOf(16000 to 12000, 4000 to 3000)
        assertEquals(4000 to 3000, ThmcRawProbe.pickBest(sizes, 6000))
    }

    @Test fun pickBest_mid_cap() {
        val sizes = listOf(4000 to 3000, 6000 to 4500)
        assertEquals(4000 to 3000, ThmcRawProbe.pickBest(sizes, 4000))
    }

    @Test fun pickBest_nothing_fits_or_empty() {
        assertNull(ThmcRawProbe.pickBest(listOf(8000 to 6000), 4000))
        assertNull(ThmcRawProbe.pickBest(emptyList(), 6000))
        assertNull(ThmcRawProbe.pickBest(listOf(4000 to 3000), 0))
    }

    @Test fun maxSideForTier_budget_zero() {
        // Бюджетнику RAW не предлагаем даже при флаге HAL.
        // MID 4128 — S25U бин 4080x3060 проходит впритык.
        assertEquals(0, ThmcRawProbe.maxSideForTier(ThmcBench.TIER_BUDGET))
        assertEquals(4128, ThmcRawProbe.maxSideForTier(ThmcBench.TIER_MID))
        assertEquals(6000, ThmcRawProbe.maxSideForTier(ThmcBench.TIER_BEAST))
    }
}
