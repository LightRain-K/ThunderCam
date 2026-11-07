package app.grapheneos.camera.thmc

import org.junit.Assert.*
import org.junit.Test

class ThmcVideoTest {

    @Test fun outName_appends_thmc() {
        assertEquals("IMG_20240101_120000_THMC.mp4", ThmcVideo.outName("IMG_20240101_120000.jpg"))
        assertEquals("VID_1_THMC.mp4", ThmcVideo.outName("VID_1.mp4"))
        assertEquals("video_THMC.mp4", ThmcVideo.outName(""))
        assertEquals("noext_THMC.mp4", ThmcVideo.outName("noext"))
    }

    @Test fun grade_ranges() {
        val g = ThmcVideo.gradeFor(0.7f, 60, 60, 60, 50)
        assertTrue(g.lift in 0f..0.4f)
        assertTrue(g.pull in 0f..0.1f)
        assertTrue(g.contrast in 1f..1.1f)
        assertTrue(g.sat in 1f..1.5f)
        assertTrue(g.rGain > 1f)
        assertTrue(g.bGain < 1f) // тепло давит синий — так и задумано
        assertTrue(g.bBias < 0f)
        assertEquals(0.7f, g.strength, 0.001f)
    }

    @Test fun grade_zero_is_identity() {
        val g = ThmcVideo.gradeFor(0f, 0, 0, 0, 0)
        assertEquals(0f, g.lift, 0.001f)
        assertEquals(1f, g.contrast, 0.001f)
        assertEquals(1f, g.sat, 0.001f)
        assertEquals(1f, g.rGain, 0.001f)
        assertEquals(0f, g.bBias, 0.001f)
        assertEquals(0f, g.strength, 0.001f)
    }

    @Test fun grade_clamps() {
        val g = ThmcVideo.gradeFor(2f, 500, -5, 500, -5)
        assertEquals(1f, g.strength, 0.001f)
        assertTrue(g.contrast in 1f..1.1f)
    }
}
