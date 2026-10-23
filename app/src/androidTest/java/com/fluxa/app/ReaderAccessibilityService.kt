package com.fluxa.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** A real, read-only accessibility client on the dedicated CI emulator.
 * It neither injects input nor manufactures/focuses nodes. Assertions still inspect
 * WebView's live provider through UiAutomation. No touch exploration is requested.
 */
class ReaderAccessibilityService : AccessibilityService() {
    private val reads = Handler(Looper.getMainLooper())
    private var readQueued = false
    private var lastObservation: String? = null
    private val readWindow = Runnable {
        readQueued = false
        val root = rootInActiveWindow ?: return@Runnable
        var remaining = 256
        var webNodes = 0
        var proseNodes = 0
        fun inspect(node: AccessibilityNodeInfo, inWeb: Boolean) {
            if (remaining-- <= 0) return
            val web = inWeb || node.className?.toString() == "android.webkit.WebView"
            if (web) {
                webNodes++
                if (node.className?.toString() != "android.webkit.WebView" &&
                    !node.text.isNullOrBlank()) proseNodes++
            }
            for (index in 0 until node.childCount) {
                if (remaining <= 0) break
                node.getChild(index)?.let { child ->
                    try { inspect(child, web) }
                    finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
        }
        try {
            if (root.packageName?.toString() != "com.fluxa.app.debug") return@Runnable
            inspect(root, false)
            val observation = "window=${root.windowId} webNodes=$webNodes proseNodes=$proseNodes"
            if (observation != lastObservation) {
                lastObservation = observation
                Log.i("FluxaTestAccessibility", "Live client read $observation")
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    override fun onServiceConnected() {
        Log.i("FluxaTestAccessibility", "Test APK accessibility service connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            Log.i("FluxaTestAccessibility", "Window state event received from ${event.packageName}")
        // Chromium initializes its native accessibility bridge on the first node
        // query. A connected but idle service does not model a reader observing a
        // newly attached/replaced WebView. Consume the real event-driven tree here;
        // UiAutomation still independently checks exact prose and native geometry.
        if (event?.packageName?.toString() == "com.fluxa.app.debug" &&
            event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) && !readQueued) {
            readQueued = true
            reads.post(readWindow)
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        reads.removeCallbacks(readWindow)
        super.onDestroy()
    }
}
