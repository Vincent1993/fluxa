package com.fluxa.app.ui.article

import android.content.Context
import android.webkit.WebView

/** WebView provider metrics use native scroll pixels, including provider rounding. */
internal class ReaderWebView(context: Context) : WebView(context) {
    // The native renderer belongs to this composition/lifecycle owner, not an article.
    // Retire the former controller before binding listeners or queuing a new document.
    private var page: ReaderPage? = null
    internal fun render(current: ReaderPage, html: String, zoom: Int, background: Int = 0xfffafbf8.toInt()) {
        if (disposed || current.isRetired) return
        if (page !== current) {
            page?.release()
            page = current
            current.attach(this)
        }
        current.load(html, zoom, background)
    }
    internal fun owns(current: ReaderPage) = page === current
    internal fun forget(current: ReaderPage) { if (page === current) page = null }
    internal var committedDocument = 0L; private set
    internal var committedAt = 0L; private set
    // Foreground paints after native content within View.draw: hide stale pixels without suppressing
    // Chromium's ordinary drawing pipeline or manufacturing an accessibility tree.
    internal var documentCovered = false; private set
    private var coveredDocument: Long? = null
    private var coverColor = 0xfffafbf8.toInt()
    internal fun coverDocument(current: ReaderPage, document: Long, background: Int) {
        if (disposed || !owns(current) || current.isRetired) return
        coveredDocument = document
        coverColor = background or 0xff000000.toInt()
        documentCovered = true
        foreground = android.graphics.drawable.ColorDrawable(coverColor)
    }
    internal fun coverRetiredDocument(current: ReaderPage) {
        if (!owns(current)) return
        coveredDocument = null // No late callback may uncover a retired document.
        documentCovered = true
        foreground = android.graphics.drawable.ColorDrawable(coverColor)
    }
    internal fun documentCommitted(current: ReaderPage, document: Long): Boolean {
        if (disposed || !owns(current) || current.isRetired || coveredDocument != document) return false
        committedDocument = document
        committedAt = android.os.SystemClock.uptimeMillis()
        coveredDocument = null
        documentCovered = false
        foreground = null
        return true
    }
    private var disposed = false
    internal fun disposeReader() {
        if (disposed) return
        disposed = true
        page?.release()
        cancelPendingDocument()
        finishSelection()
        destroy()
    }

    // Factory/update may precede attachment. Only the latest queued load can start.
    private var pendingDocument: (() -> Unit)? = null
    private val startDocument = Runnable {
        if (isAttachedToWindow) {
            val load = pendingDocument
            pendingDocument = null
            if (load != null) { lifecycleTrace("document-start"); load() }
        }
    }
    internal fun loadAfterAttachment(load: () -> Unit) {
        cancelPendingDocument()
        pendingDocument = load
        if (isAttachedToWindow) post(startDocument)
    }
    internal fun cancelPendingDocument() {
        removeCallbacks(startDocument)
        pendingDocument = null
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleTrace("attached")
        if (pendingDocument != null) post(startDocument)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(startDocument)
        lifecycleTrace("detached")
        super.onDetachedFromWindow()
    }
    private fun lifecycleTrace(event: String) {
        if (com.fluxa.app.BuildConfig.DEBUG) android.util.Log.d("ReaderNativeLifecycle",
            "view=${System.identityHashCode(this)} event=$event attached=$isAttachedToWindow shown=$isShown windowFocus=${hasWindowFocus()} size=${width}x${height}")
    }

    private var selectionMode: android.view.ActionMode? = null
    internal fun finishSelection() {
        selectionMode?.finish()
        selectionMode = null
        selectionActive = false
    }
    var selectionActive = false; private set
    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? {
        val wrapped = object : android.view.ActionMode.Callback2() {
            override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if (callback is android.view.ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean =
                callback.onCreateActionMode(mode, menu).also { selectionActive = it; if (it) selectionMode = mode }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = callback.onPrepareActionMode(mode, menu)
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = callback.onActionItemClicked(mode, item)
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (selectionMode === mode) { selectionMode = null; selectionActive = false }
                callback.onDestroyActionMode(mode)
            }
        }
        return super.startActionMode(wrapped, type)
    }
    val nativeMaxScrollOffset: Int
        get() = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
}
