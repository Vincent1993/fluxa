package com.fluxa.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.test.runner.AndroidJUnitRunner

/** Opt-in CI runner: default UiAutomation suppresses real accessibility services.
 * Keep the acquisition mode consistent even for Espresso/platform helper calls.
 */
class ReaderAccessibilityRunner : AndroidJUnitRunner() {
    override fun getUiAutomation(): UiAutomation = getUiAutomation(0)
    override fun getUiAutomation(flags: Int): UiAutomation = checkNotNull(
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))

    override fun onStart() {
        checkNotNull(uiAutomation.serviceInfo)
        val manager = targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val deadline = SystemClock.uptimeMillis() + 10_000
        fun connected() = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == "com.fluxa.app.debug.test" &&
                it.resolveInfo.serviceInfo.name == ReaderAccessibilityService::class.java.name &&
                it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0 }
        while (!connected() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(manager.isEnabled && connected() && !manager.isTouchExplorationEnabled) {
            "Dedicated reader accessibility client must be connected without changing touch gestures"
        }
        Log.i("FluxaTestAccessibility", "Verified real service; UiAutomation preserves services; touch exploration off")
        super.onStart()
    }
}
