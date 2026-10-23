package com.fluxa.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Read-only client in the test APK's own process. Instrumentation shares the
 * target app's Kotlin runtime, but this service does not: use platform Java only.
 * No input, focus changes, synthesized nodes, or touch exploration.
 */
public final class ReaderAccessibilityService extends AccessibilityService {
    private final Handler reads = new Handler(Looper.getMainLooper());
    private boolean readQueued;
    private String lastObservation;
    private final Runnable readWindow = this::readCurrentWindow;

    @SuppressWarnings("deprecation")
    private void readCurrentWindow() {
        readQueued = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (!"com.fluxa.app.debug".contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName())) return;
            int[] counts = {256, 0, 0}; // Remaining nodes, WebView nodes, text nodes.
            inspect(root, false, counts);
            String observation = "window=" + root.getWindowId()
                    + " webNodes=" + counts[1] + " proseNodes=" + counts[2];
            if (!observation.equals(lastObservation)) {
                lastObservation = observation;
                Log.i("FluxaTestAccessibility", "Live client read " + observation);
            }
        } finally {
            root.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static void inspect(AccessibilityNodeInfo node, boolean inWeb, int[] counts) {
        if (counts[0]-- <= 0) return;
        boolean host = "android.webkit.WebView".contentEquals(
                node.getClassName() == null ? "" : node.getClassName());
        boolean web = inWeb || host;
        if (web) {
            counts[1]++;
            if (!host && node.getText() != null
                    && !node.getText().toString().trim().isEmpty()) counts[2]++;
        }
        for (int index = 0; index < node.getChildCount() && counts[0] > 0; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                inspect(child, web, counts);
            } finally {
                child.recycle();
            }
        }
    }

    @Override public void onServiceConnected() {
        Log.i("FluxaTestAccessibility", "Test APK accessibility service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i("FluxaTestAccessibility", "Window state event received from " + event.getPackageName());
        }
        // Chromium initializes its native bridge on the first node query. Read
        // real window/content events; UiAutomation independently checks exact
        // prose and geometry. Coalesce events without sleeps or focus changes.
        if ("com.fluxa.app.debug".contentEquals(
                event.getPackageName() == null ? "" : event.getPackageName())
                && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                && !readQueued) {
            readQueued = true;
            reads.post(readWindow);
        }
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        reads.removeCallbacks(readWindow);
        super.onDestroy();
    }
}
