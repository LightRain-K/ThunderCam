package com.example.thundercam20.imageprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class DenoiseCommonTest {

    @Test fun smoothstep_edges() {
        assertEquals(0f, DenoiseCommon.smoothstep01(-1f))
        assertEquals(0f, DenoiseCommon.smoothstep01(0f))
        assertEquals(0.5f, DenoiseCommon.smoothstep01(0.5f), 1e-6f)
        assertEquals(1f, DenoiseCommon.smoothstep01(1f))
        assertEquals(1f, DenoiseCommon.smoothstep01(2f))
    }

    @Test fun sigma_from_iso_matches_readme() {
        // sm = clip(1.2*sqrt(iso/100), 2.5, 25), sd = clip(sm*1.8, 4, 30)
        val (sm100, sd100) = DenoiseCommon.sigmaFromIso(100)
        assertEquals(2.5, sm100, 1e-9) // клип снизу
        assertEquals(4.5, sd100, 1e-9)
        val (sm1600, sd1600) = DenoiseCommon.sigmaFromIso(1600)
        assertEquals(4.8, sm1600, 1e-9)
        assertEquals(8.64, sd1600, 1e-9)
        val (sm6400, _) = DenoiseCommon.sigmaFromIso(6400)
        assertEquals(9.6, sm6400, 1e-9)
    }

    @Test fun median_from_hist() {
        // 10 в бине 3, 30 в бине 7: медиана 20-го из 40 — бин 7
        val hist = IntArray(10)
        hist[3] = 10
        hist[7] = 30
        assertEquals(7.5, DenoiseCommon.medianFromHist(hist, 40), 1e-9)
        val one = IntArray(8); one[2] = 5
        assertEquals(2.5, DenoiseCommon.medianFromHist(one, 5), 1e-9)
        assertEquals(-1.0, DenoiseCommon.medianFromHist(hist, 0), 1e-9)
    }
}
