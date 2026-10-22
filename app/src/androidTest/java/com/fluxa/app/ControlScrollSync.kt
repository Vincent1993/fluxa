package com.fluxa.app

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard

/** Retry only scrolling after native IME/focus resize, never the eventual click.
 * Keyboard-visible layout tests opt out of dismissal to exercise the real viewport.
 */
internal fun scrollControlIntoView(
    compose: ComposeTestRule,
    scenario: ActivityScenario<MainActivity>,
    node: SemanticsNodeInteraction,
    dismissKeyboard: Boolean = true,
) {
    var window = "not sampled"
    var control = "not sampled"
    var previous: String? = null
    var imeVisible = false
    var windowReady = false
    fun sampleWindow() {
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)
            imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
            windowReady = decor.hasWindowFocus() && !decor.isLayoutRequested && decor.width > 0 && decor.height > 0
            val config = activity.resources.configuration
            // Geometry/configuration only: never dump editable semantics or credentials.
            window = "decor=${decor.width}x${decor.height}, visibleFrame=$frame, " +
                "focus=${decor.hasWindowFocus()}, layoutRequested=${decor.isLayoutRequested}, " +
                "IME=$imeVisible/${insets?.getInsets(WindowInsetsCompat.Type.ime())}, " +
                "softInputMode=${activity.window.attributes.softInputMode}, " +
                "orientation=${config.orientation}, fontScale=${config.fontScale}, API=${android.os.Build.VERSION.SDK_INT}"
        }
    }
    try {
        // Espresso's native hide is a no-op when absent, unlike Back which may navigate.
        if (dismissKeyboard) closeSoftKeyboard()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            sampleWindow()
            if (dismissKeyboard && imeVisible) {
                closeSoftKeyboard()
                previous = null
                return@waitUntil false
            }
            // A prior ScrollTo may have used pre-IME-resize coordinates. Re-evaluate
            // the descendant control against its current scroll ancestors each sample.
            node.performScrollTo()
            compose.waitForIdle()
            sampleWindow()
            val target = node.fetchSemanticsNode()
            val scrolls = generateSequence(target.parent) { it.parent }
                .filter { it.config.contains(SemanticsActions.ScrollBy) }
                .map { "${it.boundsInRoot}/${it.boundsInWindow}" }.toList()
            control = "control=${target.boundsInRoot}/${target.boundsInWindow}, " +
                "role=${target.config.getOrNull(SemanticsProperties.Role)}, scrollBounds=$scrolls"
            val displayed = try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
            val sample = "$window; $control"
            val ready = displayed && windowReady && (!dismissKeyboard || !imeVisible)
            val stable = ready && previous == sample
            previous = sample.takeIf { ready }
            stable
        }
        compose.waitForIdle()
        node.assertIsDisplayed()
    } catch (failure: Throwable) {
        throw AssertionError("Control scroll did not settle: $window; $control", failure)
    }
}
