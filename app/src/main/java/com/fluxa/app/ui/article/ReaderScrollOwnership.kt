package com.fluxa.app.ui.article

import kotlin.math.abs

/** Attribution only: never scrolls the view or changes restore/capture coordinates. */
internal class ReaderScrollOwnership(private val slop: Float) {
    private var generation = -1L
    private var eligible = false
    private var direction = 0
    private var lastY = 0f
    private var intentY = 0f
    private var releasedAt: Long? = null

    fun begin(token: Long, excluded: Boolean) {
        clear()
        generation = token
        eligible = !excluded
    }

    // Coordinates are displacement from DOWN. Pointer-down alone owns no scroll.
    fun move(dx: Float, dy: Float) {
        if (!eligible) return
        if (direction == 0) {
            if (maxOf(abs(dx), abs(dy)) <= slop) return
            if (abs(dy) < abs(dx) * 1.5f) { clear(); return }
            direction = if (dy < 0) 1 else -1
            intentY = dy
        } else {
            if (abs(dx) * 1.5f > abs(dy)) { clear(); return }
            if ((dy - lastY) * direction > 0) {
                // Accumulate a real reversal rather than flipping on pointer jitter.
                if ((dy - intentY) * direction > slop) {
                    direction = -direction
                    intentY = dy
                }
            } else intentY = dy
        }
        lastY = dy
    }

    fun release(now: Long) {
        if (eligible && direction != 0) releasedAt = now else clear()
    }

    fun accepts(token: Long, delta: Int, now: Long): Boolean {
        if (token != generation || releasedAt?.let { now - it > 2000 } == true) clear()
        return eligible && direction != 0 && delta * direction > 0
    }

    fun clear() {
        eligible = false
        direction = 0
        lastY = 0f
        intentY = 0f
        releasedAt = null
        generation = -1L
    }
}
