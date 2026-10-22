package com.fluxa.app.ui

import com.fluxa.app.ui.components.CachedCoverLimits
import org.junit.Assert.*
import org.junit.Test

class CachedCoverLimitsTest {
    @Test fun malformedRemoteAndNonRasterSourcesAreSkipped() {
        val html = """
            <img src="https://example.org/photo.png">
            <img src="data:image/svg+xml;base64,AAAA">
            <img src="data:image/png;base64,!!!!">
            <img data-original="data:image/png;base64,AQID" src="https://example.org/other">
            <img src='data:image/png;base64,AQID'>
        """.trimIndent()
        val candidates = CachedCoverLimits.embeddedBytes(html).toList()
        assertEquals(1, candidates.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), candidates.single())
    }

    @Test fun oversizedPayloadAndDocumentAreRejectedBeforeDecoding() {
        val hugePayload = "A".repeat(((CachedCoverLimits.MAX_BYTES + 2) / 3) * 4 + 4)
        assertFalse(CachedCoverLimits.embeddedBytes("<img src=\"data:image/png;base64,$hugePayload\">").any())
        val hugeDocument = " ".repeat(CachedCoverLimits.MAX_HTML_CHARS) + "<img src='data:image/png;base64,AQID'>"
        assertFalse(CachedCoverLimits.embeddedBytes(hugeDocument).any())
    }

    @Test fun dimensionsRejectBombsAndSamplingBoundsBothAxes() {
        listOf(0 to 100, -1 to -1, Int.MAX_VALUE to Int.MAX_VALUE, 16385 to 1, 8000 to 8000).forEach { (w, h) ->
            assertNull(CachedCoverLimits.sampleSize(w, h))
        }
        assertEquals(1, CachedCoverLimits.sampleSize(640, 480))
        assertEquals(4, CachedCoverLimits.sampleSize(4000, 3000))
        assertEquals(16, CachedCoverLimits.sampleSize(1, 16384))
    }
}
