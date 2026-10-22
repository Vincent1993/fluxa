package com.fluxa.app.data.directrss

import org.w3c.dom.Element
import java.io.InputStream

/** Same identity as manual subscription; ifanr daily and full-site are intentionally distinct. */
fun subscriptionIdentity(raw: String, daily: Boolean = false): String {
    val url = requireNotNull(safeUrl(raw)) { "无效的 HTTP(S) 地址" }
    require(!daily || isIfanrFeed(url)) { "早报专选仅支持爱范儿官方源" }
    return LOCAL_PREFIX + stableHash(if (isIfanrFeed(url)) IFANR_FEED else url) + if (daily) ":daily" else ":all"
}

data class OpmlRecord(val name: String, val url: String, val folder: String?, val daily: Boolean = false,
    val error: String? = null) {
    val identity: String? get() = if (error == null) subscriptionIdentity(url, daily) else null
}

object DirectOpml {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val NAMESPACE = "urn:fluxa:opml:1"
    fun read(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            require(out.size() + size <= MAX_BYTES) { "OPML 超过 2 MiB" }
            out.write(buffer, 0, size)
        }
        return out.toByteArray()
    }
    fun parse(bytes: ByteArray): List<OpmlRecord> {
        require(bytes.size <= MAX_BYTES) { "OPML 超过 2 MiB" }
        val root = secureDocument(bytes)
        require(root.tagName == "opml" && root.getAttribute("version") in setOf("1.0", "1.1", "2.0")) { "需要 OPML 1.0、1.1 或 2.0" }
        val bodies = root.elements().filter { it.tagName == "body" }
        require(bodies.size == 1) { "OPML 需要一个 body" }
        val records = mutableListOf<OpmlRecord>()
        val seen = mutableSetOf<String>()
        var outlines = 0
        fun visit(parent: Element, path: List<String>) {
            parent.elements().forEach { node ->
                require(node.tagName == "outline") { "body 只能包含 outline" }
                require(++outlines <= 2000 && path.size <= 16) { "OPML 目录结构超过上限" }
                val name = node.getAttribute("text").ifBlank { node.getAttribute("title") }.trim()
                require(name.length <= 4096 && name.none { it.isISOControl() }) { "OPML 名称过长或包含控制字符" }
                val raw = node.getAttribute("xmlUrl")
                val type = node.getAttribute("type").lowercase()
                if (raw.isNotBlank() || type == "rss") {
                    require(records.size < 500) { "OPML 最多 500 个订阅" }
                    val url = raw.takeIf { it.length <= 8192 }?.let { safeUrl(it) }
                    val daily = node.getAttributeNS(NAMESPACE, "dailyOnly") == "true"
                    val folder = path.joinToString(" / ").takeIf { it.isNotEmpty() }
                    val error = when {
                        url == null -> "无效的 HTTP(S) 地址（不接受凭据或片段）"
                        daily && !isIfanrFeed(url) -> "早报标记仅适用于爱范儿"
                        else -> null
                    }
                    val identity = if (error == null) subscriptionIdentity(url!!, daily) else null
                    val duplicate = identity != null && !seen.add(identity)
                    records += OpmlRecord(name.ifBlank { url ?: "无效订阅" }, (url ?: raw).take(8192), folder, daily,
                        error ?: if (duplicate) "文件内重复订阅" else null)
                    visit(node, path)
                } else {
                    // link/include outlines are metadata only: never follow their URLs.
                    if (type !in setOf("include", "link")) {
                        val next = if (name.isBlank()) path else path + name
                        require(next.joinToString(" / ").length <= 80) { "展开后的文件夹路径最多 80 字符" }
                        visit(node, next)
                    }
                }
            }
        }
        visit(bodies.single(), emptyList())
        return records
    }
    fun export(feeds: List<DirectSubscription>, folders: List<DirectFolder>): ByteArray {
        val active = feeds.filterNot { it.archived }
        require(active.size <= 500) { "最多导出 500 个订阅" }
        fun escaped(value: String) = DirectHtml.escape(value).replace("\"", "&quot;").replace("'", "&apos;")
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\" xmlns:fluxa=\"$NAMESPACE\"><head><title>Fluxa subscriptions</title></head><body>")
            active.groupBy { feed -> folders.firstOrNull { it.id == feed.folderId }?.name }.forEach { (folder, rows) ->
                if (folder != null) append("<outline text=\"${escaped(folder)}\">")
                rows.forEach { feed ->
                    append("<outline type=\"rss\" text=\"${escaped(feed.alias ?: feed.title)}\" xmlUrl=\"${escaped(feed.url)}\"")
                    if (feed.dailyOnly) append(" fluxa:dailyOnly=\"true\"")
                    append("/>")
                }
                if (folder != null) append("</outline>")
            }
            append("</body></opml>")
        }.toByteArray(Charsets.UTF_8)
        require(xml.size <= MAX_BYTES) { "导出超过 2 MiB" }
        // Reject out-of-contract legacy metadata before opening a SAF output stream.
        // Every successful export must be accepted by our own bounded importer.
        require(parse(xml).all { it.error == null }) { "来源元数据超出 OPML 导出范围" }
        return xml
    }
}
private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
