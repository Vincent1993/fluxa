package com.fluxa.app.ui

import com.fluxa.app.ui.article.TranslationCallbackGate
import com.fluxa.app.ui.article.TranslationProgress
import org.junit.Assert.*
import org.junit.Test

/** Tests coordination, not platform engine availability or invented translation success. */
class TranslationProgressTest {
    @Test fun canceledAndPreviousLanguageCallbacksCannotWriteNewSession() {
        val p = TranslationProgress(); val old = p.begin()
        assertTrue(p.append(old, "owned completed part"))
        p.cancel()
        assertFalse(p.append(old, "late")); assertFalse(p.finish(old))
        val current = p.begin()
        assertEquals("", p.output)
        assertFalse(p.append(old, "old language"))
        assertTrue(p.append(current, "new owned part"))
        assertTrue(p.finish(current)); assertFalse(p.append(current, "duplicate"))
        assertEquals("new owned part", p.output)
    }
    @Test fun partialFailureOrBackgroundCancelRetainsOnlyCompletedPartsAndBoundsOutput() {
        val p = TranslationProgress(); val ticket = p.begin()
        assertTrue(p.append(ticket, "completed"))
        try { p.append(ticket, "x".repeat(16001)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("completed", p.output)
        p.cancel(); assertFalse(p.append(ticket, "background callback")); assertEquals("completed", p.output)
        val fresh = p.begin(); repeat(50) { assertTrue(p.append(fresh, "x".repeat(16000))) }
        try { p.append(fresh, "x"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun callbackAndCancellationAreSingleWinner() {
        val gate = TranslationCallbackGate(); assertTrue(gate.claim()); assertFalse(gate.claim())
        val canceled = TranslationCallbackGate(); canceled.cancel(); assertFalse(canceled.claim())
        val concurrent = TranslationCallbackGate()
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..8).map { Thread { if (concurrent.claim()) wins.incrementAndGet() }.apply { start() } }
        threads.forEach { it.join() }; assertEquals(1, wins.get())
    }
}
