package com.fluxa.app.data.directrss

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

const val SAVED_PAGE_SOURCE = LOCAL_PREFIX + "saved-pages"
data class SharedLinks(val urls: List<String>, val message: String? = null)

/** Text is only a hint. Nothing from SEND is rendered or fetched automatically. */
fun sharedLinks(text: String?, html: String? = null): SharedLinks {
    if ((text?.length ?: 0) > 65536 || (html?.length ?: 0) > 65536) return SharedLinks(emptyList(), "分享内容过长，请粘贴单个网址")
    val candidates = mutableListOf<String>()
    val pattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    pattern.findAll(text.orEmpty()).take(33).forEach { candidates += it.value.trimEnd('.', ',', '。', '，') }
    if (!html.isNullOrBlank()) {
        val doc = Jsoup.parse(html)
        doc.select("a[href]").take(33).forEach { candidates += it.attr("href") }
        pattern.findAll(doc.text()).take(33).forEach { candidates += it.value.trimEnd('.', ',', '。', '，') }
    }
    if (candidates.size > 32) return SharedLinks(emptyList(), "链接过多，请粘贴单个网址")
    val urls = candidates.mapNotNull { it.takeIf { it.length <= 8192 }?.let { value -> safeUrl(value) } }.distinct()
    return SharedLinks(urls, when { urls.isEmpty() -> "未找到安全的 HTTP(S) 网址，请编辑"; urls.size > 1 -> "包含多个链接，请选择或编辑"; else -> null })
}

data class SavedPage(val url: String, val title: String, val body: String)

/** No JS, cookies, paid extraction or bypass. Reusable by the future full-text reader mode. */
object WebPageExtractor {
    fun parse(bytes: ByteArray, finalUrl: String): SavedPage {
        require(bytes.size <= DirectRssClient.MAX_BYTES) { "网页超过大小上限" }
        val url = requireNotNull(safeUrl(finalUrl)) { "无效网页地址" }
        val doc = Jsoup.parse(bytes.inputStream(), null, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().trim().takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        doc.select("script,style,noscript,nav,header,footer,aside,form,iframe,object,embed,button,input,svg,[hidden],[aria-hidden=true]").remove()
        fun score(node: Element): Int {
            val paragraphs = node.select("p").sumOf { it.text().length }
            val links = node.select("a").sumOf { it.text().length }
            return paragraphs - links * 2
        }
        val structured = doc.select("article,main,[role=main]").filter { it.text().length >= 120 }.maxByOrNull { score(it) }
        val container = structured ?: doc.select("section,div,body").filter { it.select("p").size >= 2 }
            .maxByOrNull { score(it) }
        // Prefer an explicitly identified body inside the selected article, not a blanket heading purge.
        // A short/link-heavy marker must not displace the readable generic container.
        val identifiedBody = container?.select(".entry-content,.article-body,[itemprop~=\\barticleBody\\b]" +
            if (structured != null) ",.bodydiv" else "")
            ?.filter { it.text().length >= 120 && score(it) >= 100 }
            ?.maxByOrNull { score(it) }
        val chosen = identifiedBody ?: container
        require(chosen != null && score(chosen) >= 100) { "未找到可读正文；可订阅网站或打开原网页" }
        chosen.select("img[src]").forEach { image ->
            val resolved = safeUrl(image.attr("src"), url)
            if (resolved == null || image.attr("width") == "1" || image.attr("height") == "1") image.remove()
            else image.attr("src", resolved)
        }
        chosen.select("a[href]").forEach { link ->
            val resolved = safeUrl(link.attr("href"), url)
            if (resolved == null) link.removeAttr("href") else link.attr("href", resolved)
        }
        val body = DirectHtml.clean(chosen.html(), url)
        require(DirectHtml.text(body).trim().length >= 100 && body.toByteArray().size <= 400_000) { "正文为空或超过上限；请打开原网页" }
        require(title.isNotBlank()) { "网页没有可用标题；请打开原网页" }
        return SavedPage(url, title.take(4096), body)
    }
}
