package com.fluxa.app.ui.article

import com.fluxa.app.data.local.ContentAnchor
import com.fluxa.app.data.local.ReadingPosition
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Document coordinates in native pixels, independent of the WebView's outer scroll. */
internal data class NativeBlock(val key: String, val kind: String, val path: String,
    val occurrence: Int, val top: Int, val height: Int)

internal object NativeAnchors {
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // Assist completion does not guarantee usable layout. A one-pixel content rectangle
    // cannot describe this reader's text/image layout (observed on API 35). Reject the
    // whole sample, not just that leaf: removing leaves changes occurrences and gaps.
    private fun usable(blocks: List<NativeBlock>) = blocks.isNotEmpty() &&
        blocks.all { it.top >= 0 && it.height > 1 }

    fun capturePosition(blocks: List<NativeBlock>, y: Int, range: Int,
        previous: ReadingPosition, preferred: ContentAnchor? = null): ReadingPosition {
        val boundedRange = range.coerceAtLeast(0)
        val pixels = y.coerceIn(0, boundedRange)
        val anchor = capture(blocks, pixels, preferred)
            // Never attach an old content offset to a new user position. At the same
            // fallback position, however, an unavailable sample must not erase it.
            ?: previous.anchor?.takeIf {
                !usable(blocks) && it.height > 1 && pixels == previous.offset(boundedRange)
            }
        return ReadingPosition(if (boundedRange > 0) pixels.toFloat() / boundedRange else 0f,
            pixels, boundedRange, anchor)
    }

    fun capture(blocks: List<NativeBlock>, y: Int, preferred: ContentAnchor? = null): ContentAnchor? {
        if (!usable(blocks)) return null
        // Reflow can change which overlapping table cell is shortest without changing the selected content.
        val preferredBlock = preferred?.let { anchor -> blocks.firstOrNull {
            it.key == anchor.key && it.kind == anchor.kind && it.occurrence == anchor.occurrence &&
                y >= it.top && y < it.top + it.height
        } }
        val block = preferredBlock ?: blocks.filter { y >= it.top && y < it.top + it.height }
            .minByOrNull { it.height } ?: blocks.minByOrNull {
                if (y < it.top) it.top - y else y - (it.top + it.height)
            } ?: return null
        val relative = when {
            y < block.top -> (y - block.top).toFloat() / gap(blocks, block, above = true)
            y > block.top + block.height -> 1f + (y - block.top - block.height).toFloat() / gap(blocks, block, above = false)
            else -> (y - block.top).toFloat() / block.height.coerceAtLeast(1)
        }
        return ContentAnchor(block.key, block.kind, block.path, block.occurrence,
            relative, block.top, block.height)
    }

    private fun gap(blocks: List<NativeBlock>, block: NativeBlock, above: Boolean): Int =
        if (above) (block.top - (blocks.asSequence().map { it.top + it.height }
            .filter { it <= block.top }.maxOrNull() ?: 0)).coerceAtLeast(1)
        else ((blocks.asSequence().map { it.top }.filter { it >= block.top + block.height }
            .minOrNull() ?: (block.top + 2 * block.height)) - block.top - block.height).coerceAtLeast(1)

    fun resolve(position: ReadingPosition, blocks: List<NativeBlock>, range: Int): Int {
        if (!usable(blocks)) return position.offset(range)
        val anchor = position.anchor?.takeIf { it.height > 1 }
        val block = anchor?.let { a -> blocks.firstOrNull {
            it.key == a.key && it.kind == a.kind && it.occurrence == a.occurrence
        } }
        return if (anchor != null && block != null) {
            // Avoid float round-off for an exactly unchanged layout, not just an equal range.
            if (range == position.range && block.top == anchor.top && block.height == anchor.height)
                position.pixels.coerceIn(0, range.coerceAtLeast(0))
            else {
                val offset = when {
                    anchor.relative < 0 -> anchor.relative * gap(blocks, block, above = true)
                    anchor.relative > 1 -> block.height + (anchor.relative - 1) * gap(blocks, block, above = false)
                    else -> anchor.relative * block.height
                }
                (block.top + offset).roundToInt().coerceIn(0, range.coerceAtLeast(0))
            }
        } else position.offset(range)
    }
}
