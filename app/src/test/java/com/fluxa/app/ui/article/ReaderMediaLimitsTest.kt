package com.fluxa.app.ui.article

import org.junit.Assert.*
import org.junit.Test

class ReaderMediaLimitsTest {
    @Test fun viewerAcceptsOnlyBoundedEmbeddedRasterNotRemoteSvgOrArbitraryData() {
        val data = "data:image/png;base64,AQID"
        assertArrayEquals(byteArrayOf(1, 2, 3), ReaderImageResource.bytes(data))
        for (source in listOf("https://example.org/large.png", "file:///private", "data:image/svg+xml;base64,AQID",
            "data:text/html;base64,AQID", "data:image/png;base64,??", "data:image/png;base64," + "A".repeat(1_500_000))) {
            assertNull(ReaderImageResource.bytes(source))
        }
        assertEquals(setOf(data), ReaderImageResource.embedded("<img src='$data'><img src='https://example.org/a'>"))
    }
    @Test fun decodedPixelBudgetSamplesLongImagesAndRejectsDimensionBombs() {
        assertEquals(1, ReaderImageResource.sampleSize(800, 9000))
        assertEquals(2, ReaderImageResource.sampleSize(1000, 30000))
        assertEquals(32, ReaderImageResource.sampleSize(100, 300000))
        assertNull(ReaderImageResource.sampleSize(1000, 33000))
        assertNull(ReaderImageResource.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(ReaderImageResource.sampleSize(0, 300))
    }
    @Test fun horizontalCodePanCannotRearmAsBoundaryPullWithinSameStream() {
        val pull = BoundaryPull(8f, 88f)
        pull.begin(1, 1, true, false, true)
        assertFalse(pull.move(40f, -4f, 1))
        assertFalse(pull.move(40f, -200f, 1))
        assertEquals(0, pull.release(1))
        pull.begin(1, 1, true, false, true)
        pull.move(0f, -100f, 1)
        pull.cancel() // native image/selection/multiple-pointer ownership
        assertEquals(0, pull.release(1))
        pull.begin(1, 1, true, false, true)
        assertTrue(pull.move(0f, -100f, 1))
        assertEquals(1, pull.release(1))
    }
}
