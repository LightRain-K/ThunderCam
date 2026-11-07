package app.grapheneos.camera.thmc

import org.junit.Assert.assertEquals
import org.junit.Test

class ThmcSrModelsTest {

    @Test fun downloaded_beats_bundled() {
        assertEquals("file", ThmcSrModels.resolveSource("x4plus", true, false))
        assertEquals("file", ThmcSrModels.resolveSource("x4plus_w8a8", true, false))
        assertEquals("file", ThmcSrModels.resolveSource("custom", true, true))
    }

    @Test fun missing_download_falls_back() {
        // Файла нет → вшитые по тумблеру, а не null и не падение.
        assertEquals("fast", ThmcSrModels.resolveSource("x4plus", false, false))
        assertEquals("pro", ThmcSrModels.resolveSource("x4plus", false, true))
        assertEquals("fast", ThmcSrModels.resolveSource("custom", false, false))
    }

    @Test fun explicit_and_auto() {
        assertEquals("fast", ThmcSrModels.resolveSource("fast", false, true))
        assertEquals("pro", ThmcSrModels.resolveSource("pro", false, false))
        assertEquals("fast", ThmcSrModels.resolveSource("auto", false, false))
        assertEquals("pro", ThmcSrModels.resolveSource("auto", false, true))
        assertEquals("fast", ThmcSrModels.resolveSource("junk", false, false))
    }
}
