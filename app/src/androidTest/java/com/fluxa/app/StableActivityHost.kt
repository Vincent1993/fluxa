package com.fluxa.app

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs

/** Native-only pre-injection barrier. Never queries semantics or reinstalls lost content. */
internal fun <A : ComponentActivity> ActivityScenario<A>.awaitStableHost() {
    val args = InstrumentationRegistry.getArguments()
    val expectedOrientation = args.getString("expectedOrientation")?.let {
        when (it) {
            "portrait" -> Configuration.ORIENTATION_PORTRAIT
            "landscape" -> Configuration.ORIENTATION_LANDSCAPE
            else -> error("expectedOrientation must be portrait or landscape: $it")
        }
    }
    val expectedFont = args.getString("expectedFontScale")?.let {
        it.toFloatOrNull()?.takeIf { value -> value.isFinite() && value > 0 }
            ?: error("expectedFontScale must be a positive scale (150 percent = 1.5): $it")
    }
    val expected = "orientation=${expectedOrientation ?: "current"}, fontScale=${expectedFont ?: "current"}"
    val deadline = SystemClock.uptimeMillis() + 10_000
    var previousActivity: A? = null
    var previous: HostSample? = null
    var stableSince = SystemClock.uptimeMillis()
    var diagnostic = "not sampled"
    while (SystemClock.uptimeMillis() < deadline) {
        var stable = false
        // Fetch the current instance on every sample, including after recreation.
        onActivity { activity ->
            val decor = activity.window.decorView
            val config = Configuration(activity.resources.configuration)
            val location = IntArray(2).also { decor.getLocationOnScreen(it) }
            val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            val sample = HostSample(config, activity.lifecycle.currentState,
                decor.hasWindowFocus(), decor.isAttachedToWindow, decor.isLayoutRequested,
                decor.width, decor.height, location.toList(), frame, decor.display?.rotation)
            val ready = sample.lifecycle == Lifecycle.State.RESUMED && sample.focused &&
                sample.attached && !sample.layoutRequested && sample.width > 0 && sample.height > 0 &&
                !frame.isEmpty && !activity.isFinishing && !activity.isDestroyed &&
                (expectedOrientation == null || config.orientation == expectedOrientation) &&
                (expectedFont == null || abs(config.fontScale - expectedFont) < 0.01f)
            val now = SystemClock.uptimeMillis()
            if (!ready || previousActivity !== activity || previous != sample) stableSince = now
            previousActivity = activity
            previous = sample
            diagnostic = "expected=[$expected], actual=[orientation=${config.orientation}, " +
                "fontScale=${config.fontScale}, densityDpi=${config.densityDpi}, " +
                "screenDp=${config.screenWidthDp}x${config.screenHeightDp}], " +
                "identity=${System.identityHashCode(activity)}, lifecycle=${sample.lifecycle}, " +
                "focus=${sample.focused}, attached=${sample.attached}, layoutRequested=${sample.layoutRequested}, " +
                "decor=${sample.width}x${sample.height}@${sample.location}, frame=$frame, rotation=${sample.rotation}"
            stable = ready && now - stableSince >= 750
        }
        if (stable && SystemClock.uptimeMillis() < deadline) {
            Log.i("FluxaStableHost", diagnostic)
            return
        }
        SystemClock.sleep(25)
    }
    Log.e("FluxaStableHost", diagnostic)
    throw AssertionError("Activity host not stable for 750ms within 10000ms: $diagnostic")
}

private data class HostSample(
    val configuration: Configuration,
    val lifecycle: Lifecycle.State,
    val focused: Boolean,
    val attached: Boolean,
    val layoutRequested: Boolean,
    val width: Int,
    val height: Int,
    val location: List<Int>,
    val visibleFrame: Rect,
    val rotation: Int?
)
