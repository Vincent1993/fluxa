package com.fluxa.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** A real, read-only accessibility client on the dedicated CI emulator.
 * It neither injects input nor manufactures/focuses nodes. Assertions still inspect
 * WebView's live provider through UiAutomation. No touch exploration is requested.
 */
class ReaderAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i("FluxaTestAccessibility", "Test APK accessibility service connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            Log.i("FluxaTestAccessibility", "Window state event received from ${event.packageName}")
    }
    override fun onInterrupt() = Unit
}
