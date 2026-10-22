package com.fluxa.app.data.directrss

import org.jsoup.Jsoup

/** Paragraph selection, not arbitrary browser selection. No publisher script or DOM bridge. */
object ReaderQuotes {
    data class Paragraph(val text: String, val hash: String, val occurrence: Int, val occurrences: Int)
    private fun document(html: String): org.jsoup.nodes.Document {
        require(html.length <= 2_000_000) { "正文超出笔记选择上限" }
        return Jsoup.parseBodyFragment(html).also { it.select("script,style,noscript,template").remove() }
    }
    private fun texts(doc: org.jsoup.nodes.Document): List<String> = doc.select("p").take(2000).map { it.text() }
    fun paragraphs(html: String): List<Paragraph> {
        val texts = texts(document(html))
        val counts = texts.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return texts.map { text ->
            val occurrence = seen.getOrDefault(text, 0); seen[text] = occurrence + 1
            Paragraph(text, stableHash(text), occurrence, counts.getValue(text))
        }.filter { it.text.isNotBlank() && it.text.length <= 2000 }
    }
    fun matches(note: DirectReaderNote, p: Paragraph) = note.quote == p.text && note.quoteHash == p.hash &&
        note.occurrence == p.occurrence && note.occurrences == p.occurrences
    fun validate(note: DirectReaderNote) {
        require(note.id.length in 1..100 && note.articleId.length in 1..8192)
        require(note.mode in setOf("RSS", "FULL_TEXT", "SAVED"))
        require(note.quote.isNotBlank() && note.quote.length <= 2000 && note.note.length <= 4000)
        require(note.quoteHash == stableHash(note.quote))
        require(note.occurrences in 1..2000 && note.occurrence in 0 until note.occurrences)
    }
    /** Mark retains existing sanitized children/links/images; note text is never inserted as HTML. */
    fun highlight(html: String, mode: String, notes: List<DirectReaderNote>): String {
        if (notes.none { it.mode == mode }) return html
        val doc = document(html)
        val paragraphs = paragraphs(html)
        val seen = HashMap<String, Int>()
        for (element in doc.select("p").take(2000)) {
            val text = element.text()
            val occurrence = seen.getOrDefault(text, 0); seen[text] = occurrence + 1
            val paragraph = paragraphs.firstOrNull { it.text == text && it.occurrence == occurrence } ?: continue
            if (notes.any { it.mode == mode && matches(it, paragraph) }) {
                val mark = org.jsoup.nodes.Element("mark")
                mark.appendChildren(element.childNodes().toList())
                element.appendChild(mark)
            }
        }
        return doc.body().html()
    }
}
