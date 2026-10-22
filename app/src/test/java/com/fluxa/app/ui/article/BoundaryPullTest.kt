package com.fluxa.app.ui.article

import org.junit.Assert.*
import org.junit.Test

class BoundaryPullTest {
    private fun gate() = BoundaryPull(8f, 88f)
    @Test fun thresholdAndOnlyOneReleasePerPage() {
        val gate = gate()
        gate.begin(1, 1, true, false, true)
        assertTrue(gate.move(0f, -87f, 1))
        assertEquals(0, gate.release(1))
        gate.begin(1, 1, true, false, true)
        gate.move(0f, -88f, 1)
        assertEquals(1, gate.release(1))
        repeat(3) {
            gate.begin(1, 1, true, false, true); gate.move(0f, -150f, 1)
            assertEquals(0, gate.release(1))
        }
        gate.begin(2, -1, true, false, true); gate.move(0f, 90f, 1)
        assertEquals(-1, gate.release(2))
    }
    @Test fun shortEdgeUnstableAndNonBoundaryNeverTurn() {
        for (mode in 0..3) {
            val gate = gate()
            gate.begin(1, if (mode == 0) 0 else 1, mode != 1, mode == 2, mode != 3)
            gate.move(0f, -200f, 1)
            assertEquals(0, gate.release(1))
        }
    }
    @Test fun diagonalPinchReversalCancelAndStaleGenerationAbort() {
        for (mode in 0..4) {
            val gate = gate()
            gate.begin(1, 1, true, false, true)
            when (mode) {
                0 -> gate.move(100f, -100f, 1)
                1 -> gate.move(0f, -100f, 2)
                2 -> { gate.move(0f, -120f, 1); gate.move(0f, -100f, 1) }
                3 -> { gate.move(0f, -120f, 1); gate.cancel() }
                4 -> gate.move(0f, -120f, 1)
            }
            assertEquals(0, gate.release(if (mode == 4) 2 else 1))
        }
    }
    @Test fun claimedDiagonalAndPointerCancellationRequireAFreshStream() {
        val gate = gate()
        gate.begin(1, 1, true, false, true)
        assertTrue(gate.move(0f, -100f, 1))
        gate.move(90f, -120f, 1)
        assertEquals(0, gate.release(1))
        gate.begin(1, 1, true, false, true)
        assertTrue(gate.move(0f, -100f, 1))
        gate.move(0f, -120f, 2)
        assertEquals(0, gate.release(1))
        gate.begin(1, 1, true, false, true)
        gate.move(0f, -100f, 1)
        assertEquals(1, gate.release(1))
    }
    @Test fun newGenerationCancelsOldProgress() {
        val gate = gate()
        gate.begin(1, 1, true, false, true); gate.move(0f, -120f, 1)
        gate.begin(2, 1, true, false, true)
        assertEquals(0, gate.release(2))
    }
}
