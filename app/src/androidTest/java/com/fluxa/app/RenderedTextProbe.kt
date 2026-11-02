package com.fluxa.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.util.Pair
import android.view.ViewStructure
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/** A fresh renderer snapshot through the public Assist API, NOT a TalkBack/AX assertion.
 * Unlike UIAutomator's persistent accessibility tree, this snapshot is tied to one visual
 * commit and one unchanged native host/viewport. It never reads the fixture HTML or runs JS.
 */
internal object RenderedTextProbe {
    data class Frame(val host: WebView, val title: String?, val url: String?, val viewport: Rect,
        val width: Int, val height: Int, val scrollX: Int, val scrollY: Int, val contentHeight: Int)
    data class Text(val text: String, val screenBounds: Rect, val color: Int)
    data class Document(val frame: Frame, val texts: List<Text>) {
        fun exact(value: String): Text? = texts.filter { it.text == value }
            .minByOrNull { it.screenBounds.width().toLong() * it.screenBounds.height() }
        fun visible(value: String): Text? = exact(value)?.takeIf {
            it.screenBounds.width() > 1 && it.screenBounds.height() > 1 &&
                Color.alpha(it.color) > 0 && frame.viewport.contains(it.screenBounds)
        }
    }

    fun frame(web: WebView): Frame {
        val viewport = Rect()
        check(web.isAttachedToWindow && web.isShown && web.hasWindowFocus() &&
            web.getGlobalVisibleRect(viewport) && !viewport.isEmpty &&
            web.progress == 100 && web.contentHeight > 0 && !web.settings.javaScriptEnabled) {
            "Native document is not visually ready: title=${web.title}, progress=${web.progress}"
        }
        return Frame(web, web.title, web.url, viewport, web.width, web.height,
            web.scrollX, web.scrollY, web.contentHeight)
    }

    fun read(onWeb: ((WebView) -> Unit) -> Unit, await: (() -> Boolean) -> Unit): Document {
        lateinit var before: Frame
        var previous: Frame? = null
        var stableSince = SystemClock.uptimeMillis()
        await {
            onWeb { web -> before = frame(web) }
            if (before != previous) stableSince = SystemClock.uptimeMillis()
            previous = before
            SystemClock.uptimeMillis() - stableSince >= 500
        }
        val drawn = AtomicBoolean()
        onWeb { web ->
            before = frame(web)
            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.set(true) }
            })
        }
        await { drawn.get() }
        val sink = RenderedTextSink()
        try {
            onWeb { web ->
                check(frame(web) == before) { "Native host/document/viewport changed before renderer snapshot" }
                web.onProvideVirtualStructure(sink)
            }
            await { sink.complete.get() }
            lateinit var result: Document
            onWeb { web ->
                check(frame(web) == before) { "Stale renderer snapshot: native host/document/viewport changed" }
                result = Document(before, sink.texts())
            }
            return result
        } finally { sink.cancel() }
    }

    /** Complement exact renderer text + complete geometry with pixels from the captured frame.
     * Empty/background-only rectangles cannot prove visible text. Geometry must be screen pixels.
     */
    fun inkPixels(bitmap: Bitmap, text: Text): Int {
        val bounds = text.screenBounds
        check(Rect(0, 0, bitmap.width, bitmap.height).contains(bounds) && !bounds.isEmpty)
        check(Color.alpha(text.color) > 0) { "Transparent text cannot prove visible prose" }
        var ink = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val pixel = bitmap.getPixel(x, y)
            if (kotlin.math.abs(Color.red(pixel) - Color.red(text.color)) <= 24 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(text.color)) <= 24 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(text.color)) <= 24) ink++
        }
        // A solid fill is not text. Require glyph ink as well as non-ink in the same box.
        check(ink >= 12 && ink < bounds.width().toLong() * bounds.height() * .8) {
            "No distinguishable rendered text ink: bounds=$bounds ink=$ink"
        }
        return ink
    }
}

/** Test-only sink. Chromium's public extras contain UNCLIPPED SCREEN coordinates; never
 * sum setDimens rectangles (the legacy and unified Assist implementations differ there).
 * Source: Chromium 149 AssistDataBuilder / ViewStructureBuilder / AccessibilityNodeInfoBuilder.
 */
private class RenderedTextSink private constructor(
    private val state: State, private val depth: Int
) : ViewStructure() {
    constructor() : this(State(), 0)
    private class State { val complete = AtomicBoolean(); var cancelled = false; var count = 0 }
    val complete get() = state.complete
    private val children = mutableListOf<RenderedTextSink?>()
    private val extras = Bundle()
    private var text: CharSequence? = null
    private var clazz: String? = null
    private var color: Int? = null
    private var html: HtmlInfo? = null
    fun cancel() { state.cancelled = true }
    fun texts(): List<RenderedTextProbe.Text> = buildList {
        fun visit(node: RenderedTextSink) {
            val keys = listOf("Left", "Top", "Right", "Bottom").map { "AccessibilityNodeInfo.unclipped$it" }
            val prose = node.text?.toString()
            if (node.depth > 1 && !prose.isNullOrBlank() && node.color != null &&
                node.clazz != "android.webkit.WebView" && node.clazz?.contains("Image") != true &&
                node.html?.attributes?.none { it.first == "display" && it.second == "none" } != false &&
                keys.all(node.extras::containsKey)) {
                val b = Rect(node.extras.getInt(keys[0]), node.extras.getInt(keys[1]),
                    node.extras.getInt(keys[2]), node.extras.getInt(keys[3]))
                if (b.width() > 1 && b.height() > 1) add(RenderedTextProbe.Text(prose, b, checkNotNull(node.color)))
            }
            node.children.forEach { it?.let(::visit) }
        }
        visit(this@RenderedTextSink)
    }
    override fun setChildCount(num: Int) { children.clear(); addChildCount(num) }
    override fun addChildCount(num: Int): Int = children.size.also {
        check(num >= 0 && state.count + num <= 20_000 && depth < 128) { "Renderer snapshot exceeded limits" }
        state.count += num
        repeat(num) { children.add(null) }
    }
    override fun getChildCount() = children.size
    override fun newChild(index: Int): ViewStructure = RenderedTextSink(state, depth + 1).also { children[index] = it }
    override fun asyncNewChild(index: Int) = newChild(index)
    override fun asyncCommit() { if (depth == 1 && !state.cancelled) state.complete.set(true) }
    override fun setDimens(left: Int, top: Int, scrollX: Int, scrollY: Int, width: Int, height: Int) {}
    override fun setText(value: CharSequence?) { check((value?.length ?: 0) <= 2_000_000); text = value }
    override fun setText(value: CharSequence?, selectionStart: Int, selectionEnd: Int) { setText(value) }
    override fun getText() = text
    override fun getTextSelectionStart() = -1
    override fun getTextSelectionEnd() = -1
    override fun setClassName(className: String?) { clazz = className }
    override fun setContentDescription(contentDescription: CharSequence?) {} // Labels/alt text are not body prose.
    override fun setHtmlInfo(value: HtmlInfo) { html = value }
    override fun newHtmlInfoBuilder(tagName: String): HtmlInfo.Builder = object : HtmlInfo.Builder() {
        private val capturedAttributes = mutableListOf<Pair<String, String>>()
        override fun addAttribute(name: String, value: String): HtmlInfo.Builder = apply {
            if (name == "display") capturedAttributes.add(Pair(name, value))
        }
        override fun build(): HtmlInfo = object : HtmlInfo() {
            override fun getTag() = tagName
            override fun getAttributes() = capturedAttributes.toList()
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
    override fun setTextStyle(size: Float, fgColor: Int, bgColor: Int, style: Int) { color = fgColor }
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
