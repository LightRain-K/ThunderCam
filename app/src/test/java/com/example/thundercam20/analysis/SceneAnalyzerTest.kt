package com.example.thundercam20.analysis

import org.junit.Assert.*
import org.junit.Test

class SceneAnalyzerTest {

    @Test fun warmRatio_all_warm() {
        val px = IntArray(100) { 0xFFDD8833.toInt() } // r=221 g=136 b=51
        assertEquals(1f, SceneAnalyzer.warmRatio(px), 0.001f)
    }

    @Test fun warmRatio_cold_and_grey() {
        val cold = IntArray(50) { 0xFF3366DD.toInt() }
        val grey = IntArray(50) { 0xFF808080.toInt() }
        assertEquals(0f, SceneAnalyzer.warmRatio(cold + grey), 0.001f)
        assertEquals(0f, SceneAnalyzer.warmRatio(IntArray(0)), 0.001f)
    }

    @Test fun warmRatio_half() {
        val px = IntArray(100) { i -> if (i < 50) 0xFFDD8833.toInt() else 0xFF808080.toInt() }
        assertEquals(0.5f, SceneAnalyzer.warmRatio(px), 0.001f)
    }
}
