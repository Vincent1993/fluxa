package com.fluxa.app.ui.article

import kotlin.math.abs

/** Pure per-page gate. A successful release latches until this page is replaced. */
internal class BoundaryPull(private val slop: Float, private val threshold: Float) {
    var direction = 0; private set
    var progress = 0f; private set
    private var generation = -1L
    private var active = false
    private var locked = false
    private var fired = false
    private var peak = 0f
    fun begin(token: Long, boundary: Int, longDocument: Boolean, edge: Boolean, stable: Boolean) {
        if (token != generation) { generation = token; fired = false }
        cancel()
        direction = boundary
        active = !fired && boundary != 0 && longDocument && !edge && stable
    }
    fun move(dx: Float, dy: Float, pointers: Int): Boolean {
        if (!active) return false
        if (pointers != 1) { cancel(); return false }
        val distance = dy * -direction
        if (!locked && (abs(dx) > slop || abs(dy) > slop)) {
            if (distance <= slop || abs(dy) < abs(dx) * 1.5f) { cancel(); return false }
            locked = true
        }
        if (locked) {
            if (distance < peak - slop || abs(dx) * 1.5f > abs(dy)) { cancel(); return true }
            peak = maxOf(peak, distance)
            progress = (distance / threshold).coerceIn(0f, 1f)
        }
        return locked
    }
    fun release(token: Long): Int {
        val result = if (active && locked && progress >= 1f && token == generation && !fired) direction else 0
        if (result != 0) fired = true
        cancel()
        return result
    }
    fun cancel() { active = false; locked = false; progress = 0f; peak = 0f; direction = 0 }
}
