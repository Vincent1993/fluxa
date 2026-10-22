package com.fluxa.app.ui.article

/** Coordination only, not an engine or a claimed translation. All mutations on reader main thread. */
internal class TranslationProgress {
    private var generation = 0L
    private var active = false
    private val parts = ArrayList<String>()
    private var characters = 0
    val output: String get() = parts.joinToString("\n\n")
    fun begin(): Long { generation++; active = true; parts.clear(); characters = 0; return generation }
    fun accepts(ticket: Long) = active && ticket == generation
    fun append(ticket: Long, part: String): Boolean {
        if (!accepts(ticket)) return false
        require(part.length <= 16_000 && characters + part.length <= 800_000) { "翻译结果超出上限" }
        parts += part; characters += part.length; return true
    }
    fun finish(ticket: Long): Boolean {
        if (!accepts(ticket)) return false
        active = false; return true
    }
    fun cancel() { generation++; active = false }
}

/** Provider callbacks may race cancellation or invoke duplicate results. Only one may resume. */
internal class TranslationCallbackGate {
    private val settled = java.util.concurrent.atomic.AtomicBoolean(false)
    fun claim() = settled.compareAndSet(false, true)
    fun cancel() { settled.set(true) }
}
