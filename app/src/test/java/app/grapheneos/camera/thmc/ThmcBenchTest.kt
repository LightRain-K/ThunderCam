package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Test

class ThmcBenchTest {

    @Test fun profiles_match_spec() {
        val b = ThmcBench.profileOf(ThmcBench.TIER_BUDGET)
        assertEquals(1792, b.nightSide)
        assertEquals(1792, b.hdrSide)
        assertEquals(5, b.nightMax)
        assertEquals(4, b.srMaxTiles)
        assertEquals(10f, b.zoomCap)
        val m = ThmcBench.profileOf(ThmcBench.TIER_MID)
        assertEquals(2304, m.nightSide)
        assertEquals(2304, m.hdrSide)
        assertEquals(6, m.nightMax)
        assertEquals(20f, m.zoomCap)
        val x = ThmcBench.profileOf(ThmcBench.TIER_BEAST)
        assertEquals(3072, x.nightSide)
        assertEquals(3072, x.hdrSide)
        assertEquals(8, x.nightMax)
        assertEquals(100f, x.zoomCap)
        // мусор → бюджет (безопасный дефолт)
        assertEquals(ThmcBench.TIER_BUDGET, ThmcBench.profileOf("junk").tier)
    }

    @Test fun static_tier_never_beast() {
        assertEquals(ThmcBench.TIER_BUDGET, ThmcBench.staticTier(2f))
        assertEquals(ThmcBench.TIER_MID, ThmcBench.staticTier(8f))
        assertEquals(ThmcBench.TIER_MID, ThmcBench.staticTier(16f))
    }

    @Test fun measured_tier_by_speed_and_ram() {
        assertEquals(ThmcBench.TIER_BUDGET, ThmcBench.measuredTier(300f, 2f)) // мало RAM
        assertEquals(ThmcBench.TIER_BUDGET, ThmcBench.measuredTier(30f, 8f)) // медленно
        assertEquals(ThmcBench.TIER_MID, ThmcBench.measuredTier(100f, 8f))
        assertEquals(ThmcBench.TIER_MID, ThmcBench.measuredTier(300f, 4f)) // быстро, но мало RAM
        assertEquals(ThmcBench.TIER_BEAST, ThmcBench.measuredTier(158f, 12f)) // S25U
        assertEquals(ThmcBench.TIER_BEAST, ThmcBench.measuredTier(300f, 8f))
    }
}
