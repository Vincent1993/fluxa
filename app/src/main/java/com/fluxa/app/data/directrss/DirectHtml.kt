package com.fluxa.app.data.directrss

import java.util.Base64

object DirectHtml {
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun decodeAttribute(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);").replace(value) { match ->
        val entity = match.groupValues[1]
        when (entity) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                if (code != null && code in 1..0x10ffff && code !in 0xd800..0xdfff) String(Character.toChars(code)) else ""
            }
        }
    }
    private val tags = Regex("<!--.*?-->|<[^>]*>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val attrs = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val allowed = setOf("p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "table", "thead", "tbody", "tr", "td", "th", "strong", "b", "em", "i", "br", "hr", "pre", "code", "a", "img", "figure", "figcaption")
    fun clean(html: String, base: String): String {
        val stripped = html.replace(Regex("<(script|style|iframe|object|embed|form|svg|math)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        return tags.replace(stripped) { match ->
            val raw = match.value
            val name = Regex("^</?\\s*([a-zA-Z0-9:_-]+)").find(raw)?.groupValues?.get(1)?.substringAfterLast(':')?.lowercase().orEmpty()
            if (name !in allowed) "" else if (raw.startsWith("</")) "</$name>" else {
                val attributes = attrs.findAll(raw).associate { it.groupValues[1].lowercase() to decodeAttribute(it.groupValues.drop(2).firstOrNull { v -> v.isNotEmpty() }.orEmpty()) }
                if (name == "img" && attributes["class"].orEmpty().split(' ').contains("wp-smiley")) escape(attributes["alt"].orEmpty())
                else {
                    val urlName = if (name == "a") "href" else if (name == "img") "src" else ""
                    val candidate = if (name == "img") attributes["srcset"].orEmpty().split(',').map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1] in setOf("768w", "1024w") }?.firstOrNull() ?: attributes[urlName] else attributes[urlName]
                    val url = candidate?.let { safeUrl(it, base) }
                    "<$name" + (if (url != null) " $urlName=\"${escape(url)}\"" else "") +
                        (if (name == "img") " alt=\"${escape(attributes["alt"].orEmpty())}\"" else "") + ">"
                }
            }
        }
    }
    fun text(html: String): String = android.text.Html.fromHtml(html.replace(Regex("<img[^>]*>"), ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
}

data class ImageResult(val html: String, val cached: Int, val total: Int)
class DirectImageCache(
    private val http: okhttp3.OkHttpClient = anonymousClient(),
    private val downloadAllowed: () -> Boolean = { true }
) {
    // One refresh budget shared by all articles; serial requests (concurrency = 1).
    class Budget(var bytes: Int = 0, var requests: Int = 0)
    fun cache(html: String, previous: String, budget: Budget): ImageResult {
        val images = Regex("<img\\b[^>]*>")
        val src = Regex("src=\"([^\"]*)\"")
        val original = Regex("data-original=\"([^\"]*)\"")
        val reused = images.findAll(previous).mapNotNull { tag ->
            val key = original.find(tag.value)?.groupValues?.get(1)
            val data = src.find(tag.value)?.groupValues?.get(1)
            if (key != null && data?.startsWith("data:image/") == true) key to data else null
        }.toMap()
        val session = reused.toMutableMap()
        var cached = 0; var total = 0
        var embeddedChars = 0
        val baseBytes = Regex("src=\"data:image/[^\"]*\"").replace(html, "src=\"\"").toByteArray(Charsets.UTF_8).size
        val result = images.replace(html) { tag ->
            total++
            val source = src.find(tag.value)?.groupValues?.get(1)
            val url = source?.replace("&amp;", "&")?.let { safeUrl(it) }
            var data = source?.takeIf { it.startsWith("data:image/") } ?: source?.let { session[it] }
            if (data != null && embeddedChars + data.length + baseBytes > 1_400_000) data = null
            if (data == null && url != null && downloadAllowed() && budget.requests < 80 && budget.bytes < 24 * 1024 * 1024 && embeddedChars + baseBytes < 1_300_000) {
                try {
                    val allowance = minOf(1024 * 1024, 24 * 1024 * 1024 - budget.bytes,
                        (1_400_000 - embeddedChars - baseBytes) * 3 / 4 - 100)
                    // Charge the full allowance even for failed/oversized transfers.
                    budget.bytes += allowance
                    val bytes = download(http, url, allowance, true) {
                        if (!downloadAllowed() || budget.requests >= 80) throw DirectPayloadException("图片网络规则或请求预算")
                        budget.requests++ // Includes each redirect, not merely each image.
                    }
                    val mime = when {
                        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
                        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                        bytes.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF" -> "image/gif"
                        bytes.size >= 12 && bytes.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                        else -> throw java.io.IOException("图片签名无效")
                    }
                    data = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
                    session[source!!] = data
                } catch (_: java.io.IOException) { /* Keep the remote source and report incomplete caching. */ }
                catch (_: IllegalArgumentException) { /* Invalid redirect: retain the original HTTP image. */ }
            }
            if (data != null && source != null) {
                cached++
                embeddedChars += data.length + 32
                if (source.startsWith("data:image/")) tag.value
                else src.replace(tag.value) { "src=\"$data\" data-original=\"$source\"" }
            } else tag.value
        }
        return ImageResult(result, cached, total)
    }
}
