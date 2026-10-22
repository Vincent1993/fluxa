package com.fluxa.app.ui.article

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.LocaleList
import android.util.Pair
import android.view.ViewStructure
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue

/** Public assist API sink. asyncCommit on the document child completes the entire tree.
 * No script execution, accessibility service, reflection, or retained WebView reference.
 */
internal class NativeDocumentSnapshot private constructor(
    private val state: State, private val depth: Int, private val document: Boolean
) : ViewStructure() {
    constructor(onComplete: (NativeDocumentSnapshot) -> Unit) : this(State(onComplete), 0, false) {
        state.root = this
    }
    private class State(var callback: ((NativeDocumentSnapshot) -> Unit)?) {
        var root: NativeDocumentSnapshot? = null
        var nodes = 0
        var slots = 0
        var remainingText = 2_000_000
    }
    data class Entry(val text: String?, val bounds: Rect, val className: String?,
        val description: String?, val html: HtmlInfo?, val path: String)
    private val children = mutableListOf<NativeDocumentSnapshot?>()
    private val extras = Bundle()
    private val dimensions = Rect()
    private var sx = 0
    private var sy = 0
    private var capturedText: CharSequence? = null
    private var start = -1
    private var end = -1
    private var clazz: String? = null
    private var description: String? = null
    private var html: HtmlInfo? = null
    fun cancel() { state.callback = null }

    fun flatten(): List<Entry> = buildList {
        fun visit(node: NativeDocumentSnapshot, x: Int, y: Int, path: String) {
            val rect = Rect(node.dimensions).apply { offset(x, y) }
            if (node.depth > 0) add(Entry(node.capturedText?.toString(), rect, node.clazz, node.description, node.html, path))
            node.children.forEachIndexed { index, child -> child?.let {
                // The outer View's position and scroll belong to the host, not the document.
                visit(it, if (node.depth == 0) 0 else rect.left - node.sx,
                    if (node.depth == 0) 0 else rect.top - node.sy, "$path/$index")
            } }
        }
        visit(this@NativeDocumentSnapshot, 0, 0, "")
    }

    fun blocks(): List<NativeBlock> {
        val entries = flatten()
        val meaningful = entries.filter { entry ->
            entry.bounds.height() > 0 && entry.bounds.width() > 0 && entry.bounds.top >= 0 &&
                (!entry.text.isNullOrBlank() || !entry.description.isNullOrBlank() ||
                    entry.html?.tag in listOf("img", "pre", "table", "blockquote") ||
                    entry.className?.contains("Image", ignoreCase = true) == true)
        }
        // Containers repeat descendant prose. Keep only deepest meaningful content nodes.
        val parents = HashSet<String>()
        meaningful.forEach { entry ->
            var path = entry.path
            while (path.contains('/')) {
                path = path.substringBeforeLast('/')
                parents.add(path)
            }
        }
        val byPath = entries.associateBy { it.path }
        fun kind(entry: Entry): String {
            var node: Entry? = entry
            while (node != null) {
                if (node.html?.tag == "img" || node.className?.contains("Image", true) == true) return "image"
                node.html?.tag?.takeIf { it in listOf("pre", "table", "blockquote") }?.let { return it }
                node = byPath[node.path.substringBeforeLast('/', "")]
            }
            return "text"
        }
        val occurrences = mutableMapOf<String, Int>()
        return meaningful.filter { it.path !in parents }.map { entry ->
            val kind = kind(entry)
            val content = entry.text?.takeIf { it.isNotBlank() } ?: entry.description?.takeIf { it.isNotBlank() }
            val key = NativeAnchors.digest(content?.trim()?.replace(Regex("\\s+"), " ")
                ?: "$kind:${entry.html?.tag}:${entry.path}")
            val occurrence = occurrences.getOrDefault("$kind:$key", 0)
            occurrences["$kind:$key"] = occurrence + 1
            NativeBlock(key, kind, entry.path, occurrence, entry.bounds.top, entry.bounds.height())
        }
    }
    override fun setChildCount(num: Int) { children.clear(); addChildCount(num) }
    override fun addChildCount(num: Int): Int = children.size.also {
        val count = if (depth > 128) 0 else num.coerceIn(0, (20_000 - state.slots).coerceAtLeast(0))
        state.slots += count
        repeat(count) { children.add(null) }
    }
    override fun getChildCount() = children.size
    private fun child(index: Int, async: Boolean): ViewStructure {
        val child = NativeDocumentSnapshot(state, depth + 1, async && depth == 0)
        if (depth < 128 && state.nodes < 20_000 && index in children.indices) {
            state.nodes++; children[index] = child
        }
        return child
    }
    override fun newChild(index: Int) = child(index, false)
    override fun asyncNewChild(index: Int) = child(index, true)
    override fun asyncCommit() {
        if (document) {
            val callback = state.callback
            state.callback = null
            state.root?.let { callback?.invoke(it) }
        }
    }
    override fun setDimens(left: Int, top: Int, scrollX: Int, scrollY: Int, width: Int, height: Int) {
        dimensions.set(left.coerceIn(-100_000_000, 100_000_000), top.coerceIn(-100_000_000, 100_000_000),
            left.coerceIn(-100_000_000, 100_000_000) + width.coerceIn(0, 100_000_000),
            top.coerceIn(-100_000_000, 100_000_000) + height.coerceIn(0, 100_000_000))
        sx = scrollX; sy = scrollY
    }
    private fun boundedText(text: CharSequence?): String? {
        val value = text?.take(minOf(65_536, state.remainingText))?.toString()
        state.remainingText -= value?.length ?: 0
        return value
    }
    override fun setText(text: CharSequence?) { capturedText = boundedText(text) }
    override fun setText(text: CharSequence?, selectionStart: Int, selectionEnd: Int) {
        setText(text); start = selectionStart; end = selectionEnd
    }
    override fun getText() = capturedText
    override fun getTextSelectionStart() = start
    override fun getTextSelectionEnd() = end
    override fun setClassName(className: String?) { clazz = className?.take(80) }
    override fun setContentDescription(contentDescription: CharSequence?) { description = boundedText(contentDescription) }
    override fun setHtmlInfo(htmlInfo: HtmlInfo) { html = htmlInfo }
    override fun newHtmlInfoBuilder(tagName: String): HtmlInfo.Builder = object : HtmlInfo.Builder() {
        override fun addAttribute(name: String, value: String): HtmlInfo.Builder = this
        override fun build(): HtmlInfo = object : HtmlInfo() {
            override fun getTag() = tagName.take(80)
            override fun getAttributes(): List<Pair<String, String>> = emptyList()
        }
    }
    override fun getExtras() = extras
    override fun hasExtras() = !extras.isEmpty
    override fun setId(id: Int, packageName: String?, typeName: String?, entryName: String?) {}
    override fun setTransformation(matrix: Matrix?) {}
    override fun setElevation(elevation: Float) {}
    override fun setAlpha(alpha: Float) {}
    override fun setVisibility(visibility: Int) {}
    override fun setEnabled(state: Boolean) {}
    override fun setClickable(state: Boolean) {}
    override fun setLongClickable(state: Boolean) {}
    override fun setContextClickable(state: Boolean) {}
    override fun setFocusable(state: Boolean) {}
    override fun setFocused(state: Boolean) {}
    override fun setAccessibilityFocused(state: Boolean) {}
    override fun setCheckable(state: Boolean) {}
    override fun setChecked(state: Boolean) {}
    override fun setSelected(state: Boolean) {}
    override fun setActivated(state: Boolean) {}
    override fun setOpaque(opaque: Boolean) {}
    override fun setTextStyle(size: Float, fgColor: Int, bgColor: Int, style: Int) {}
    override fun setTextLines(charOffsets: IntArray?, baselines: IntArray?) {}
    override fun setHint(hint: CharSequence?) {}
    override fun getHint(): CharSequence? = null
    override fun getAutofillId(): AutofillId? = null
    override fun setAutofillId(id: AutofillId) {}
    override fun setAutofillId(parentId: AutofillId, virtualId: Int) {}
    override fun setAutofillType(type: Int) {}
    override fun setAutofillHints(hints: Array<out String>?) {}
    override fun setAutofillValue(value: AutofillValue?) {}
    override fun setAutofillOptions(options: Array<out CharSequence>?) {}
    override fun setInputType(inputType: Int) {}
    override fun setDataIsSensitive(sensitive: Boolean) {}
    override fun setWebDomain(domain: String?) {}
    override fun setLocaleList(localeList: LocaleList?) {}
}
