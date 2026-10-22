package com.fluxa.app

import com.fluxa.app.ui.article.NativeDocumentSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tests exercise the production public-API sink, not a second implementation. */
internal class NativeDocumentSnapshotProbe {
    private val completed = CountDownLatch(1)
    val sink = NativeDocumentSnapshot { completed.countDown() }
    fun awaitCompletion(): Boolean = completed.await(10, TimeUnit.SECONDS)
    fun flatten() = sink.flatten()
}
