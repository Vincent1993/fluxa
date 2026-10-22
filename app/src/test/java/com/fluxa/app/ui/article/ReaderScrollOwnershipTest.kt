package com.fluxa.app.ui.article

import org.junit.Assert.*
import org.junit.Test

class ReaderScrollOwnershipTest {
    @Test fun downAndShortOrDiagonalMotionDoNotOwnScroll() {
        val owner = ReaderScrollOwnership(8f)
        owner.begin(1, false)
        assertFalse(owner.accepts(1, 100, 0))
        owner.move(0f, -7f)
        assertFalse(owner.accepts(1, 100, 0))
        owner.move(30f, -30f)
        owner.move(30f, -100f)
        owner.release(0)
        assertFalse(owner.accepts(1, 100, 10))
    }

    @Test fun actualMatchingScrollMayArriveOnlyAfterRelease() {
        val owner = ReaderScrollOwnership(8f)
        owner.begin(1, false)
        owner.move(0f, -100f)
        owner.release(600)
        assertTrue(owner.accepts(1, 30, 650))
        assertTrue(owner.accepts(1, 200, 1500))
        assertFalse(owner.accepts(1, -20, 1500))
        assertFalse(owner.accepts(1, 0, 1500))
        assertFalse(owner.accepts(1, 20, 2601))
        assertFalse(owner.accepts(1, 20, 2700))
    }

    @Test fun reverseIntentRejectsOldFlingAndAcceptsReverseScroll() {
        val owner = ReaderScrollOwnership(8f)
        owner.begin(1, false)
        owner.move(0f, -100f)
        owner.move(0f, -96f)
        assertTrue(owner.accepts(1, 10, 100)) // jitter is not reversal
        owner.move(0f, -90f)
        assertFalse(owner.accepts(1, 10, 110))
        assertTrue(owner.accepts(1, -10, 110))
        owner.release(200)
        assertTrue(owner.accepts(1, -10, 300))
        owner.begin(1, false) // new DOWN cannot inherit the old fling
        assertFalse(owner.accepts(1, -10, 310))
        owner.move(0f, 50f)
        assertTrue(owner.accepts(1, -10, 350))
    }

    @Test fun edgeCancellationClaimGeometryLifecycleAndGenerationInvalidate() {
        val owner = ReaderScrollOwnership(8f)
        owner.begin(1, true)
        owner.move(0f, -100f)
        owner.release(100)
        assertFalse(owner.accepts(1, 20, 200))
        owner.begin(1, false)
        owner.move(0f, -100f)
        owner.release(100)
        owner.clear() // same invalidation used for claim/cancel/pointers/geometry/lifecycle
        owner.move(0f, -200f)
        assertFalse(owner.accepts(1, 20, 200))
        owner.begin(1, false)
        owner.move(0f, -100f)
        owner.release(100)
        assertFalse(owner.accepts(2, 20, 200))
        assertFalse(owner.accepts(1, 20, 200))
    }
}
