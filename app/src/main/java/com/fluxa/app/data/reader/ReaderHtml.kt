package com.fluxa.app.data.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

object ReaderHtml {
    fun text(html: String): String = Jsoup.parse(html).apply { select("script,style,noscript").remove() }.text()
    fun wordCount(text: String): Int = Regex("[\\p{IsHan}]|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+").findAll(text).count()
    fun render(html: String, url: String, highlights: List<ReaderAnnotation>): String {
        val base = url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "https://www.newsblur.com/"
        val safe = Safelist.relaxed().addProtocols("img", "src", "data").removeProtocols("a", "href", "ftp", "mailto")
        val doc = Jsoup.parseBodyFragment(Jsoup.clean(html, base, safe), base)
        for (annotation in highlights.filter { it.kind == "highlight" && it.quote.isNotBlank() }) {
            val nodes = mutableListOf<TextNode>()
            fun collect(element: Element) {
                for (node in element.childNodes()) when (node) {
                    is TextNode -> nodes += node
                    is Element -> if (node.tagName() != "mark") collect(node)
                }
            }
            collect(doc.body())
            val text = nodes.joinToString("") { it.wholeText }
            val matches = mutableListOf<IntRange>()
            var next = text.indexOf(annotation.quote)
            while (next >= 0) {
                matches += next until next + annotation.quote.length
                next = text.indexOf(annotation.quote, next + annotation.quote.length)
            }
            val color = annotation.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#f1d28a"
            var position = 0
            for (node in nodes) {
                val value = node.wholeText
                val start = position
                position += value.length
                val segments = matches.mapNotNull { match ->
                    val from = maxOf(start, match.first) - start
                    val to = minOf(position, match.last + 1) - start
                    if (to > from) from until to else null
                }
                if (segments.isEmpty()) continue
                var copied = 0
                for (segment in segments) {
                    node.before(TextNode(value.substring(copied, segment.first)))
                    node.before(Element("mark").attr("style", "background-color:$color;color:#25231e")
                        .text(value.substring(segment.first, segment.last + 1)))
                    copied = segment.last + 1
                }
                node.before(TextNode(value.substring(copied)))
                node.remove()
            }
        }
        return doc.body().html()
    }
}
