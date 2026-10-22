package com.fluxa.app.ui

import com.fluxa.app.data.local.ReadingPosition
import com.fluxa.app.ui.article.NativeAnchors
import com.fluxa.app.ui.article.NativeBlock
import com.fluxa.app.ui.article.NativeDocumentSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeAnchorGeometryRegressionTest {
    private val paragraph = NativeBlock(NativeAnchors.digest("paragraph"), "text", "/0/0", 0, 476, 45)
    private val next = NativeBlock(NativeAnchors.digest("next"), "text", "/0/1", 0, 1223, 57)
    private val good = listOf(paragraph, next)
    private val saved = ReadingPosition(618f / 74132, 618, 74132, NativeAnchors.capture(good, 618))

    @Test fun completedAssistWithUniformOnePixelLeavesUsesFallbackWithoutErasingAnchor() {
        var completed = false
        val sink = NativeDocumentSnapshot { completed = true }
        sink.setDimens(0, 0, 0, 618, 400, 800)
        sink.setChildCount(1)
        val document = sink.asyncNewChild(0)
        document.setDimens(0, 0, 0, 0, 400, 74932)
        document.setChildCount(2)
        listOf("paragraph", "next").forEachIndexed { index, text ->
            document.newChild(index).apply {
                setText(text)
                setDimens(0, index * 700, 0, 0, 400, 1)
            }
        }
        document.asyncCommit()
        assertTrue(completed)
        val clipped = sink.blocks()
        assertEquals(listOf(1, 1), clipped.map { it.height })
        assertNull(NativeAnchors.capture(clipped, 618))
        assertEquals(618, NativeAnchors.resolve(saved, clipped, 74132))
        val recaptured = NativeAnchors.capturePosition(clipped, 618, 74132, saved)
        assertEquals(saved, recaptured)
        assertEquals(618, NativeAnchors.resolve(recaptured, good, 74132))
        assertEquals(saved.offset(90000), NativeAnchors.resolve(saved, clipped, 90000))
        val resized = NativeAnchors.capturePosition(clipped, saved.offset(90000), 90000, saved)
        assertEquals(saved.anchor, resized.anchor)
    }

    @Test fun oneClippedLeafInvalidatesGapGeometryRatherThanSelectingAnotherIdentity() {
        val partial = listOf(paragraph, next.copy(top = 700, height = 1))
        assertNull(NativeAnchors.capture(partial, 618))
        assertEquals(618, NativeAnchors.resolve(saved, partial, 74132))
        // Even a non-degenerate matching leaf is unsafe when its adjacent gap is clipped.
        assertEquals(saved, NativeAnchors.capturePosition(partial, 618, 74132, saved))
        val moved = NativeAnchors.capturePosition(partial, 1000, 74132, saved)
        assertNull(moved.anchor)
        assertEquals(1000, moved.pixels)
        assertEquals(saved, NativeAnchors.capturePosition(emptyList(), 618, 74132, saved))
    }

    @Test fun alreadyPersistedDegenerateAnchorCannotDriveRestoreOrBeRetained() {
        val bad = saved.copy(anchor = saved.anchor!!.copy(top = 0, height = 1))
        assertEquals(618, NativeAnchors.resolve(bad, good, 74132))
        assertNull(NativeAnchors.capturePosition(emptyList(), 618, 74132, bad).anchor)
        assertEquals(0, NativeAnchors.resolve(saved, emptyList(), 0))
    }

    @Test fun validGeometryStillUsesContentRelativeReflowAndExactLayoutGuard() {
        assertEquals(618, NativeAnchors.resolve(saved, good, 74132))
        val shifted = good.map { it.copy(top = it.top + 80) }
        assertEquals(698, NativeAnchors.resolve(saved, shifted, 74132))
        for (kind in listOf("text", "image", "blockquote", "pre", "table")) {
            val block = paragraph.copy(kind = kind, top = 100, height = 200)
            val anchor = NativeAnchors.capture(listOf(block), 150)!!
            val position = ReadingPosition(.15f, 150, 1000, anchor)
            assertEquals(150, NativeAnchors.resolve(position, listOf(block), 1000))
            assertEquals(500, NativeAnchors.resolve(position,
                listOf(block.copy(top = 400, height = 400)), 2000))
        }
    }
}
