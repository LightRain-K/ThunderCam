package com.example.thundercam20.imageprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class SuperResZoomGridTest {

    @Test fun tiny_crop_is_single_tile() {
        assertEquals(1 to 1, SuperResZoom.tileGrid(200, 150))
        assertEquals(1 to 1, SuperResZoom.tileGrid(640, 480))
        assertEquals(1 to 1, SuperResZoom.tileGrid(10, 10))
    }

    @Test fun medium_crop_splits() {
        assertEquals(2 to 1, SuperResZoom.tileGrid(800, 600))
        assertEquals(2 to 2, SuperResZoom.tileGrid(1000, 1000))
    }

    @Test fun large_crop_caps_at_3x3() {
        assertEquals(3 to 3, SuperResZoom.tileGrid(2000, 1500))
        assertEquals(3 to 3, SuperResZoom.tileGrid(4000, 3000))
    }

    @Test fun weak_tier_caps_tiles() {
        // бюджетник: та же широкая сцена, но не больше 4 тайлов
        assertEquals(2 to 2, SuperResZoom.tileGrid(2000, 1500, 4))
        assertEquals(2 to 2, SuperResZoom.tileGrid(4000, 3000, 4))
        // мелочь кап не трогает
        assertEquals(1 to 1, SuperResZoom.tileGrid(200, 150, 4))
    }
}
