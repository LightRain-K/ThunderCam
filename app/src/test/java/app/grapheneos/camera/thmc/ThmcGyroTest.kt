package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Test

class ThmcGyroTest {

    @Test fun constant_rotation_integrates() {
        // 0.1 рад/с по x полсекунды → 0.05 рад
        val samples = (0..10).map { i -> Triple(i * 50_000_000L, 0.1f, 0.0f) }
        val (ax, ay) = ThmcGyro.integrateRotation(samples, 0L, 500_000_000L)
        assertEquals(0.05, ax, 1e-6)
        assertEquals(0.0, ay, 1e-9)
    }

    @Test fun rotation_clamped_to_window() {
        val samples = (0..20).map { i -> Triple(i * 50_000_000L, 0.2f, 0.0f) }
        // окно 0.2с внутри секундного буфера → 0.04 рад
        val (ax, _) = ThmcGyro.integrateRotation(samples, 200_000_000L, 400_000_000L)
        assertEquals(0.04, ax, 1e-6)
    }

    @Test fun degenerate_input_is_zero() {
        val (ax, ay) = ThmcGyro.integrateRotation(emptyList(), 0L, 100L)
        assertEquals(0.0, ax, 0.0)
        assertEquals(0.0, ay, 0.0)
        val one = listOf(Triple(0L, 1f, 1f))
        val (bx, _) = ThmcGyro.integrateRotation(one, 0L, 100L)
        assertEquals(0.0, bx, 0.0)
    }
}
