package com.fluxa.app.data.directrss

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

const val LOCAL_PREFIX = "direct-rss:"
const val IFANR_FEED = "https://www.ifanr.com/feed"
fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun safeUrl(value: String, base: String? = null): String? {
    if (value.isBlank() || Regex("^(?:https?:)?//[^/?#]*@", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())) return null
    val url = if (base == null) value.trim().toHttpUrlOrNull() else base.toHttpUrlOrNull()?.resolve(value.trim())
    return url?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }?.toString()
}
fun isIfanrFeed(url: String): Boolean = url.toHttpUrlOrNull()?.let {
    it.host in setOf("www.ifanr.com", "ifanr.com") && it.encodedPath.trimEnd('/') == "/feed" && it.query == null && it.port == (if (it.isHttps) 443 else 80)
} == true
fun anonymousClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()

// Redirects are validated too; no cookie jar, account interceptors, or credentials.
internal fun redirectUrl(target: String, location: String?): String {
    val next = location?.let { safeUrl(it, target) }
    require(next != null && !(target.startsWith("https:") && next.startsWith("http:"))) { "无效的重定向" }
    return next
}
internal data class HttpPayload(val bytes: ByteArray, val finalUrl: String)
internal fun download(client: OkHttpClient, url: String, limit: Int, image: Boolean = false, beforeRequest: () -> Unit = {}): ByteArray =
    downloadResponse(client, url, limit, image, beforeRequest).bytes
private fun downloadResponse(client: OkHttpClient, url: String, limit: Int, image: Boolean = false, beforeRequest: () -> Unit = {}): HttpPayload {
    var target = requireNotNull(safeUrl(url)) { "无效的 HTTP(S) 地址" }
    repeat(6) {
        beforeRequest()
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                target = redirectUrl(target, response.header("Location"))
            } else {
                if (!response.isSuccessful) throw DirectHttpException(response.code)
                val body = response.body ?: throw DirectPayloadException("空响应")
                if (image && body.contentType()?.let { it.type == "image" && it.subtype in setOf("jpeg", "png", "gif", "webp") } != true) throw DirectPayloadException("不支持的图片格式")
                if (body.contentLength() > limit) throw DirectPayloadException("响应超过大小上限")
                val out = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (out.size() + n > limit) throw DirectPayloadException("响应超过大小上限")
                        out.write(buffer, 0, n)
                    }
                }
                return HttpPayload(out.toByteArray(), target)
            }
        }
    }
    throw DirectPayloadException("重定向过多")
}
data class RssItem(val key: String, val title: String, val published: Long, val body: String, val url: String, val categories: List<String>, val author: String = "")
data class RssFeed(val title: String, val items: List<RssItem>)
data class FeedCandidate(val url: String, val title: String)
data class FeedDiscovery(val candidates: List<FeedCandidate>, val directFeed: RssFeed? = null)
class DirectRssClient(
    private val http: OkHttpClient = anonymousClient(),
    private val readFeed: ((String) -> ByteArray)? = null
) {
    fun extractPage(url: String): SavedPage {
        val response = readFeed?.let { HttpPayload(it(url), requireNotNull(safeUrl(url))) }
            ?: downloadResponse(http, url, MAX_BYTES)
        return WebPageExtractor.parse(response.bytes, response.finalUrl)
    }
    fun fetch(url: String): RssFeed {
        readFeed?.let { return parse(it(url), url) }
        val response = downloadResponse(http, url, MAX_BYTES)
        return parse(response.bytes, response.finalUrl)
    }
    fun discover(url: String): FeedDiscovery {
        val response = readFeed?.let { HttpPayload(it(url), requireNotNull(safeUrl(url))) }
            ?: downloadResponse(http, url, MAX_BYTES)
        require(response.bytes.size <= MAX_BYTES) { "响应超过大小上限" }
        // Decode the XML sniff without losing UTF-16 feeds; strict XML parsing still uses original bytes.
        val bytes = response.bytes
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val text = bytes.toString(charset).removePrefix("\uFEFF")
        val probe = org.jsoup.Jsoup.parse(text, response.finalUrl, org.jsoup.parser.Parser.xmlParser())
        val rootName = probe.children().firstOrNull()?.normalName()?.substringAfter(':')
        if (rootName in setOf("rss", "feed")) {
            val feed = parse(response.bytes, response.finalUrl)
            return FeedDiscovery(listOf(FeedCandidate(response.finalUrl, feed.title)), feed)
        }
        require(rootName in setOf("html", "head", "body", "link", "meta", "title")) { "响应不是 RSS、Atom 或 HTML 页面" }
        val html = org.jsoup.Jsoup.parse(text, response.finalUrl)
        val candidates = html.select("link[href]").asSequence().mapNotNull { link ->
            val alternate = link.attr("rel").split(Regex("\\s+")).any { it.equals("alternate", true) }
            val type = link.attr("type").substringBefore(';').trim().lowercase()
            if (!alternate || type !in setOf("application/rss+xml", "application/atom+xml")) return@mapNotNull null
            val resolved = safeUrl(link.attr("href"), response.finalUrl) ?: return@mapNotNull null
            if (response.finalUrl.startsWith("https:") && resolved.startsWith("http:")) return@mapNotNull null
            FeedCandidate(resolved, link.attr("title").trim().take(200).ifBlank { resolved })
        }.distinctBy { it.url }.take(12).toList()
        require(candidates.isNotEmpty()) { "页面中未找到 RSS 或 Atom 链接" }
        return FeedDiscovery(candidates)
    }
    companion object { const val MAX_BYTES = 5 * 1024 * 1024; const val MAX_ITEMS = 500 }
    fun parse(bytes: ByteArray, base: String): RssFeed {
        require(bytes.size <= MAX_BYTES) { "响应超过大小上限" }
        // Reject declarations before invoking any XML implementation (including UTF-16/32).
        val probe = bytes.toString(Charsets.UTF_8).replace("\u0000", "")
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(probe)) { "不允许 XML DTD 或实体声明" }
        val root = secureDocument(bytes)
        val atom = root.local() == "feed"
        require(atom || root.local() == "rss") { "响应不是 RSS 或 Atom" }
        val container = if (atom) root else root.children().firstOrNull { it.local() == "channel" } ?: error("RSS 缺少 channel")
        val entries = container.children().filter { it.local() == if (atom) "entry" else "item" }
        require(entries.isNotEmpty()) { "Feed 为空" }
        require(entries.size <= MAX_ITEMS) { "Feed 条目超过上限" }
        val items = entries.map { entry ->
            val title = entry.text("title").trim().ifBlank { "（无标题）" }
            val link = entry.children().firstOrNull { it.local() == "link" && (!atom || it.getAttribute("rel") in listOf("", "alternate")) }
            val rawLink = if (atom) link?.getAttribute("href").orEmpty() else link?.textContent.orEmpty()
            val entryBase = elementBase(entry, base)
            val url = safeUrl(rawLink, link?.let { elementBase(it, base) } ?: entryBase).orEmpty()
            val content = entry.children().firstOrNull { it.local() == if (atom) "content" else "encoded" }
                ?: entry.children().firstOrNull { it.local() == if (atom) "summary" else "description" }
            val body = if (atom && content != null && content.getAttribute("type") in listOf("xhtml", "application/xhtml+xml")) content.children().joinToString("") { serialize(it) }
                else if (atom && content != null && content.getAttribute("type") !in listOf("html", "text/html", "xhtml", "application/xhtml+xml")) DirectHtml.escape(content.textContent)
                else content?.textContent.orEmpty()
            val date = entry.text(if (atom) "published" else "pubDate").ifBlank { entry.text("updated") }
            val categories = entry.children().filter { it.local() == "category" }.map { if (atom) it.getAttribute("term").trim() else it.textContent.trim() }
            val authorNode = entry.children().firstOrNull { it.local() == "creator" }
                ?: entry.children().firstOrNull { it.local() == "author" }
                ?: if (atom) container.children().firstOrNull { it.local() == "author" } else null
            val author = (if (atom && authorNode?.local() == "author") authorNode?.text("name") else authorNode?.textContent).orEmpty().trim().take(512)
            val key = entry.text(if (atom) "id" else "guid").trim().ifBlank { canonicalArticleKey(url) }.ifBlank { "$title|$date" }
            require(body.toByteArray().size <= 400_000) { "单篇正文超过缓存上限" }
            require(listOf(key, title, url, categories.joinToString()).all { it.length <= 16_384 }) { "条目元数据超过上限" }
            val cleaned = DirectHtml.clean(body, content?.let { elementBase(it, base, url.ifBlank { entryBase }) } ?: url.ifBlank { entryBase })
            require(cleaned.toByteArray().size <= 400_000) { "单篇正文超过缓存上限" }
            RssItem(key, title, parseDate(date), cleaned, url, categories.filter { it.isNotBlank() }.take(64).map { it.take(256) }, author)
        }.distinctBy { it.key }
        return RssFeed(container.text("title").take(4096).ifBlank { base }, items)
    }
}
private fun canonicalArticleKey(value: String): String {
    val url = value.toHttpUrlOrNull() ?: return ""
    val builder = url.newBuilder()
    url.queryParameterNames.filter { it.startsWith("utm_", true) || it.lowercase() in setOf("fbclid", "gclid") }
        .forEach { builder.removeAllQueryParameters(it) }
    return builder.build().toString()
}
fun isDaily(item: RssItem): Boolean = if (item.categories.isNotEmpty()) "早报" in item.categories else Regex("^早报(?:[｜|丨:：/\\s\\p{Z}]|$)").containsMatchIn(item.title.trim())
private fun Element.local() = localName ?: tagName.substringAfter(':')
private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
private fun Element.text(name: String) = children().firstOrNull { it.local() == name }?.textContent.orEmpty()
private fun serialize(node: Node): String = java.io.StringWriter().also { out -> TransformerFactory.newInstance().newTransformer().apply { setOutputProperty("omit-xml-declaration", "yes") }.transform(DOMSource(node), StreamResult(out)) }.toString()
private fun parseDate(value: String): Long = runCatching { Instant.parse(value.trim()).epochSecond }.getOrElse {
    runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrElse {
        runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).apply { isLenient = false }.parse(value.trim())!!.time / 1000 }.getOrDefault(0)
    }
}
