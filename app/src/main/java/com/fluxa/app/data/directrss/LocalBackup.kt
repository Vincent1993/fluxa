package com.fluxa.app.data.directrss

import androidx.room.withTransaction
import com.fluxa.app.data.local.*
import com.fluxa.app.ui.feedlist.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Deliberately not a database/prefs dump. No account store or transport is reachable here. */
data class BackupPayload(
    val folders: List<DirectFolder>, val sources: List<DirectSubscription>, val articles: List<DirectArticle>,
    val hashes: Map<String, String>, val full: List<DirectFullBody>, val modes: List<DirectReadingMode>,
    val notes: List<DirectReaderNote>, val articlePins: Set<String>, val folderPins: Set<String>,
    val triage: TriageConfig, val reader: ReaderSnapshot, val bodies: Boolean, val images: Boolean,
    val offlinePolicy: LocalOfflinePolicy = LocalOfflinePolicy(), val swipes: SwipeConfig = SwipeConfig()
)
data class BackupPreview internal constructor(val payload: BackupPayload, val bytes: Int, val stamp: String,
    val newSources: Int, val newArticles: Int, val newFolders: Int, val newNotes: Int, val collisions: Int)

/** Only fixed, payload-free validation messages may reach the backup UI. */
class BackupValidationException(message: String) : IllegalArgumentException(message)

internal fun safeBackupDetail(error: Exception): String? = when (error.message) {
    "备份含重复记录", "备份包含多余内容", "文件超过 32 MiB",
    "备份超过 32 MiB，请不包含正文后重试", "正文超过备份上限，请不包含正文后重试",
    "文件夹冲突，请先整理后重新预览", "订阅身份冲突", "订阅网址冲突",
    "文章已变化或身份冲突，请重新导出", "笔记身份冲突", "资料库已变化，请重新选择文件预览" -> error.message
    else -> null
}

object BackupJson {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_ROWS = 5000
    private fun id(s: String): String { require(s.length in 1..256 && s.none { it.isISOControl() }); return s }
    private fun local(s: String): String { id(s); require(s.startsWith(LOCAL_PREFIX) && !s.endsWith("::fulltext")); return s }
    private fun text(s: String, max: Int): String { require(s.length <= max && '\u0000' !in s); return s }
    private fun url(s: String): String { require(s.length <= 8192 && safeUrl(s) != null); return s }
    private fun hash(s: String): String { require(s.matches(Regex("[a-f0-9]{64}"))); return s }
    private fun JSONObject.s(k: String, max: Int = 4096): String { val v = get(k); require(v is String); return text(v, max) }
    private fun JSONObject.optional(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.n(k: String, min: Long = 0, max: Long = 253402300799): Long {
        val v = get(k); require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble())
        return v.toLong().also { require(it in min..max) }
    }
    private fun <T> JSONArray.rows(cap: Int = MAX_ROWS, block: (JSONObject) -> T): List<T> {
        require(length() <= cap); return (0 until length()).map { block(getJSONObject(it)) }
    }
    private fun JSONArray.strings(): List<String> { require(length() <= MAX_ROWS); return (0 until length()).map { getString(it) } }
    private fun <T> unique(rows: List<T>, key: (T) -> String) { require(rows.map(key).distinct().size == rows.size) { "备份含重复记录" } }
    private fun array(rows: Iterable<JSONObject>) = JSONArray().apply { rows.forEach { put(it) } }
    suspend fun read(stream: InputStream): ByteArray = withContext(Dispatchers.IO) {
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= MAX_BYTES) { "文件超过 32 MiB" }; out.write(buffer, 0, n) }
            out.toByteArray()
        }
    }
    private fun checkedBody(s: String, images: Boolean): String {
        require(s.toByteArray(Charsets.UTF_8).size <= 1_400_000)
        val doc = Jsoup.parseBodyFragment(s)
        require(doc.select("script,style,iframe,object,embed,form,svg,math,video,audio,source,link,meta,base").isEmpty())
        for (e in doc.allElements) for (a in e.attributes()) {
            require(!a.key.startsWith("on", ignoreCase = true) && a.key.lowercase() !in setOf("srcdoc", "srcset"))
            if (a.key.equals("style", ignoreCase = true)) require(!Regex("url\\s*\\(|expression\\s*\\(|@import", RegexOption.IGNORE_CASE).containsMatchIn(a.value))
            if (a.key.lowercase() in listOf("src", "href", "data-original")) {
                val v = a.value
                if (v.startsWith("data:")) {
                    require(images && e.tagName() == "img" && a.key == "src")
                    val match = Regex("data:image/(png|jpeg|gif|webp);base64,([A-Za-z0-9+/=\\r\\n]+)").matchEntire(v)
                    require(match != null && v.length <= 1_398_200)
                    val data = android.util.Base64.decode(match.groupValues[2], android.util.Base64.DEFAULT)
                    require(data.size <= 1024 * 1024)
                    val mime = match.groupValues[1]
                    require(when (mime) {
                        "png" -> data.size >= 8 && data.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
                        "jpeg" -> data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte()
                        "gif" -> data.size >= 6 && data.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")
                        "webp" -> data.size >= 12 && data.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && data.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
                        else -> false
                    })
                } else require(v.isEmpty() || (a.key == "href" && v.startsWith("#")) || safeUrl(v) != null)
            }
        }
        return s
    }
    fun decode(bytes: ByteArray): BackupPayload = try {
        decodeValidated(bytes)
    } catch (e: BackupValidationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw BackupValidationException(safeBackupDetail(e) ?: "备份格式不正确")
    } catch (_: org.json.JSONException) {
        throw BackupValidationException("备份格式不正确")
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw BackupValidationException("备份格式不正确")
    }
    private fun decodeValidated(bytes: ByteArray): BackupPayload {
        require(bytes.size <= MAX_BYTES) { "文件超过 32 MiB" }
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val tokenizer = org.json.JSONTokener(decoded)
        val o = tokenizer.nextValue() as? JSONObject ?: throw BackupValidationException("备份格式不正确")
        require(tokenizer.nextClean() == '\u0000') { "备份包含多余内容" }
        require(o.s("format") == "fluxa-local")
        val version = o.get("version")
        require(version is Number && version.toDouble().isFinite() && version.toDouble() == version.toLong().toDouble())
        if (version.toLong() != 1L) throw BackupValidationException("不支持此备份版本")
        require(o.keys().asSequence().toSet() == setOf("version", "format", "bodies", "images", "folders", "sources", "articles", "full", "modes", "notes", "articlePins", "folderPins", "triage", "reader", "offline", "swipes"))
        val bodies = o.getBoolean("bodies"); val images = o.getBoolean("images"); require(!images || bodies)
        val folders = o.getJSONArray("folders").rows(500) { DirectFolder(id(it.s("id", 256)), it.s("name", 80).also { n -> require(n.isNotBlank()) }) }
        unique(folders) { it.id }; unique(folders) { it.name }
        val folderIds = folders.map { it.id }.toSet()
        val sources = o.getJSONArray("sources").rows(500) {
            DirectSubscription(local(it.s("id", 256)), it.s("title"), url(it.s("url", 8192)), it.getBoolean("daily"),
                alias = it.optional("alias")?.let { a -> text(a, 200) }, folderId = it.optional("folder")?.also { f -> require(f in folderIds) },
                paused = it.getBoolean("paused"), archived = it.getBoolean("archived"))
        }
        unique(sources) { it.id }; unique(sources) { it.url + "|" + it.dailyOnly }
        val sourceIds = sources.map { it.id }.toSet() + SAVED_PAGE_SOURCE
        val hashes = linkedMapOf<String, String>()
        val articles = o.getJSONArray("articles").rows {
            val key = local(it.s("id", 256)); hashes[key] = hash(it.s("hash", 64))
            DirectArticle(key, it.s("guid", 8192).also { g -> require(g.isNotBlank()) }, id(it.s("source", 256)).also { s -> require(s in sourceIds) }, it.s("title"),
                it.n("published"), checkedBody(it.s("body", 1_400_000), images).also { b -> require(bodies || b.isEmpty()) },
                it.s("url", 8192).also { u -> if (u.isNotEmpty()) url(u) }, it.getBoolean("read"), it.getBoolean("star"),
                0, 0, it.s("categories", 16384), it.s("summary", 16384), it.n("minutes", 1, 10000).toInt(),
                it.s("author"), it.getBoolean("later"))
        }
        unique(articles) { it.id }; require(articles.all { it.source != SAVED_PAGE_SOURCE || it.originalUrl.isNotEmpty() })
        val articleIds = articles.map { it.id }.toSet()
        val full = o.getJSONArray("full").rows {
            DirectFullBody(it.s("id", 256).also { a -> require(a in articleIds) }, checkedBody(it.s("body", 1_400_000), images).also { b -> require(bodies || b.isEmpty()) })
        }; unique(full) { it.articleId }
        val modes = o.getJSONArray("modes").rows(501) {
            DirectReadingMode(it.s("source", 256).also { s -> require(s in sourceIds) }, LocalReadingMode.valueOf(it.s("mode", 32)).name)
        }; unique(modes) { it.source }
        val notes = o.getJSONArray("notes").rows {
            DirectReaderNote(id(it.s("id", 256)), it.s("article", 256).also { a -> require(a in articleIds) },
                it.s("mode", 32).also { m -> require(m in listOf("RSS", "FULL_TEXT", "SAVED")) },
                it.s("quote", 2000), hash(it.s("hash", 64)), it.n("occurrence", 0, 1999).toInt(),
                it.n("occurrences", 1, 2000).toInt(), it.s("note", 4000)).also { n ->
                require(n.quote.isNotBlank() && stableHash(n.quote) == n.quoteHash && n.occurrence < n.occurrences)
            }
        }; unique(notes) { it.id }; require(notes.groupingBy { it.articleId }.eachCount().values.all { it <= 100 })
        val articleSources = articles.associate { it.id to it.source }
        require(notes.all { (it.mode == "SAVED") == (articleSources[it.articleId] == SAVED_PAGE_SOURCE) })
        fun pins(key: String, known: Set<String>): Set<String> = o.getJSONArray(key).strings().also { require(it.distinct().size == it.size && it.all { v -> v in known }) }.toSet()
        val t = o.getJSONObject("triage")
        val views = t.getJSONArray("sources").rows(501) { it.s("id", 256).also { s -> require(s in sourceIds) } to SourceTriage(it.getBoolean("oldest"), it.getBoolean("hide")) }
        unique(views) { it.first }
        val saved = t.getJSONArray("saved").rows(50) { s -> SavedSearch(id(s.s("id", 256)), s.s("name", 80), SearchScope(s.s("query", 256),
            s.optional("source")?.also { require(it in sourceIds) }, ArticleFilter.valueOf(s.s("filter", 32)),
            if (s.isNull("after")) null else s.n("after"), if (s.isNull("before")) null else s.n("before"), s.getBoolean("hidden"))) }
        unique(saved) { it.id }
        val rules = t.getJSONArray("rules").rows(100) { r -> TriageRule(id(r.s("id", 256)), r.optional("source")?.also { require(it in sourceIds) },
            RuleKind.valueOf(r.s("kind", 32)), r.s("value", 256), r.getBoolean("enabled")) }; unique(rules) { it.id }
        val triage = TriageConfig(views.toMap(), saved, rules, t.getBoolean("collapse"), t.getBoolean("comfortable"), t.getBoolean("summary"), t.getBoolean("thumbnail"))
        val r = o.getJSONObject("reader")
        val spacing = r.getDouble("spacing").toFloat(); require(spacing.isFinite() && spacing in 1.3f..2.4f)
        val settings = ReaderSettings(r.n("font", 14, 30).toInt(), spacing, ReadingTheme.valueOf(r.s("theme", 32)))
        val knownPositions = articleIds + full.map { it.articleId + "::fulltext" }
        val positions = r.getJSONArray("positions").rows { p ->
            val key = p.s("id", 270).also { require(it in knownPositions) }
            val fraction = p.getDouble("fraction").toFloat(); require(fraction.isFinite() && fraction in 0f..1f)
            val anchor = if (p.isNull("anchor")) null else p.getJSONObject("anchor").let { a ->
                ContentAnchor(hash(a.s("key", 64)), a.s("kind", 80), a.s("path", 512),
                    a.n("occurrence", 0, 20_000).toInt(), a.getDouble("relative").toFloat(),
                    a.n("top", 0, 100_000_000).toInt(), a.n("height", 1, 100_000_000).toInt()).also {
                    require(it.bounded() != null && it.kind in setOf("text", "image", "pre", "table", "blockquote") && it.path.matches(Regex("(/[0-9]{1,5}){1,80}")))
                }
            }
            key to ReadingPosition(fraction, p.n("pixels", 0, 100_000_000).toInt(), p.n("range", 0, 100_000_000).toInt(), anchor)
        }; unique(positions) { it.first }
        val session = r.getJSONArray("session").strings().also { require(it.distinct().size == it.size && it.all { a -> a in articleIds }) }
        val offline = o.getJSONObject("offline")
        val policy = LocalOfflinePolicy(offline.n("capacity", 64, 512).toInt(), offline.getBoolean("images"), offline.getBoolean("wifi"), offline.n("days", 7, 90).toInt())
        fun choice(j: JSONObject) = SwipeChoice(SwipeAction.valueOf(j.s("left", 32)), SwipeAction.valueOf(j.s("right", 32)),
            SwipeAction.valueOf(j.s("deepLeft", 32)), SwipeAction.valueOf(j.s("deepRight", 32)))
        val sw = o.getJSONObject("swipes")
        val sourceSwipes = sw.getJSONArray("sources").rows(501) { j -> j.s("id", 256).also { require(it in sourceIds) } to choice(j) }
        unique(sourceSwipes) { it.first }
        return BackupPayload(folders, sources, articles, hashes, full, modes, notes, pins("articlePins", articleIds), pins("folderPins", folderIds),
            triage, ReaderSnapshot(settings, positions.toMap(), ReadingSession(session)), bodies, images, policy,
            SwipeConfig(choice(sw.getJSONObject("global")), sourceSwipes.toMap()))
    }
    fun encode(p: BackupPayload): ByteArray {
        val t = p.triage; val r = p.reader
        fun choice(c: SwipeChoice) = JSONObject().put("left", c.left.name).put("right", c.right.name).put("deepLeft", c.deepLeft.name).put("deepRight", c.deepRight.name)
        val o = JSONObject().put("format", "fluxa-local").put("version", 1).put("bodies", p.bodies).put("images", p.images)
            .put("folders", array(p.folders.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("sources", array(p.sources.map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("daily", it.dailyOnly)
                .put("alias", it.alias ?: JSONObject.NULL).put("folder", it.folderId ?: JSONObject.NULL).put("paused", it.paused).put("archived", it.archived) }))
            .put("articles", array(p.articles.map { JSONObject().put("id", it.id).put("guid", it.guid).put("source", it.source).put("title", it.title)
                .put("published", it.published).put("body", it.body).put("url", it.originalUrl).put("read", it.isRead).put("star", it.isStarred)
                .put("categories", it.categories).put("summary", it.summary).put("minutes", it.readMinutes).put("author", it.author).put("later", it.readLater).put("hash", p.hashes.getValue(it.id)) }))
            .put("full", array(p.full.map { JSONObject().put("id", it.articleId).put("body", it.body) }))
            .put("modes", array(p.modes.map { JSONObject().put("source", it.source).put("mode", it.mode) }))
            .put("notes", array(p.notes.map { JSONObject().put("id", it.id).put("article", it.articleId).put("mode", it.mode).put("quote", it.quote)
                .put("hash", it.quoteHash).put("occurrence", it.occurrence).put("occurrences", it.occurrences).put("note", it.note) }))
            .put("articlePins", JSONArray(p.articlePins.toList())).put("folderPins", JSONArray(p.folderPins.toList()))
            .put("swipes", JSONObject().put("global", choice(p.swipes.global)).put("sources", array(p.swipes.sources.map { (id, c) -> choice(c).put("id", id) })))
            .put("offline", JSONObject().put("capacity", p.offlinePolicy.capacityMiB).put("images", p.offlinePolicy.imagesEnabled)
                .put("wifi", p.offlinePolicy.imagesWifiOnly).put("days", p.offlinePolicy.retainedReadDays))
            .put("triage", JSONObject().put("sources", array(t.sources.map { (id, v) -> JSONObject().put("id", id).put("oldest", v.oldestFirst).put("hide", v.hideRead) }))
                .put("saved", array(t.saved.map { s -> JSONObject().put("id", s.id).put("name", s.name).put("query", s.scope.query).put("source", s.scope.source ?: JSONObject.NULL)
                    .put("filter", s.scope.filter.name).put("after", s.scope.after ?: JSONObject.NULL).put("before", s.scope.before ?: JSONObject.NULL).put("hidden", s.scope.showHidden) }))
                .put("rules", array(t.rules.map { JSONObject().put("id", it.id).put("source", it.source ?: JSONObject.NULL).put("kind", it.kind.name).put("value", it.value).put("enabled", it.enabled) }))
                .put("collapse", t.collapse).put("comfortable", t.comfortable).put("summary", t.showSummary).put("thumbnail", t.showThumbnail))
            .put("reader", JSONObject().put("font", r.settings.fontSize).put("spacing", r.settings.lineSpacing.toDouble()).put("theme", r.settings.theme.name)
                .put("session", JSONArray(r.session.ids)).put("positions", array(r.positions.map { (id, v) -> JSONObject().put("id", id).put("fraction", v.fraction.toDouble())
                    .put("pixels", v.pixels).put("range", v.range).put("anchor", v.anchor?.json() ?: JSONObject.NULL) })))
        return o.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "备份超过 32 MiB，请不包含正文后重试" }; decode(it) }
    }
}

@Singleton
class LocalBackup @Inject constructor(private val db: DirectRssDatabase, private val reader: ReaderPreferences,
    private val offline: LocalOfflineSettings, private val triage: TriageSettings, private val swipes: SwipeSettings? = null) {
    private suspend fun snapshot(bodies: Boolean, images: Boolean): BackupPayload {
        require(!images || bodies)
        val dao = db.dao(); val ids = dao.backupArticleIds().toSet(); require(ids.size <= 5000)
        val fullIds = dao.backupFullIds(); require(fullIds.all { it in ids })
        val sources = dao.subscriptions(); val folders = dao.folders()
        val sourceIds = sources.map { it.id }.toSet() + SAVED_PAGE_SOURCE
        var retainedBytes = 0L
        fun body(value: String): String {
            require(value.toByteArray(Charsets.UTF_8).size <= 1_400_000)
            val result = if (!bodies) "" else if (images) value else withoutEmbeddedImages(value)
            retainedBytes += result.toByteArray(Charsets.UTF_8).size
            require(retainedBytes <= BackupJson.MAX_BYTES) { "正文超过备份上限，请不包含正文后重试" }
            return result
        }
        val hashes = linkedMapOf<String, String>()
        val articles = ids.map { id -> requireNotNull(dao.article(id)).let { row ->
            hashes[id] = stableHash(row.body); row.copy(body = body(row.body))
        } }
        val full = fullIds.map { id -> if (bodies) requireNotNull(dao.fullBody(id)).let { it.copy(body = body(it.body)) } else DirectFullBody(id, "") }
        val config = triage.load().let { it.copy(sources = it.sources.filterKeys { s -> s in sourceIds },
            saved = it.saved.filter { s -> s.scope.source == null || s.scope.source in sourceIds },
            rules = it.rules.filter { r -> r.source == null || r.source in sourceIds }) }
        return BackupPayload(folders, sources, articles, hashes, full, dao.allModes(), dao.allNotes(),
            ids.filter { offline.articlePinned(it) }.toSet(), folders.map { it.id }.filter { offline.folderPinned(it) }.toSet(), config,
            reader.snapshot(ids + full.map { it.articleId + "::fulltext" }, ids), bodies, images, offline.policy(),
            (swipes?.load() ?: SwipeConfig()).let { it.copy(sources = it.sources.filterKeys { source -> source in sourceIds }) })
    }
    suspend fun export(bodies: Boolean = false, images: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        reader.ready.firstReady()
        BackupJson.encode(db.withTransaction { snapshot(bodies, images) })
    }
    private suspend fun stamp(): String {
        val d = db.dao()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) { digest.update(value.toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        add(d.folders()); add(d.subscriptions()); add(d.allModes()); add(d.allNotes())
        d.backupArticleIds().forEach { id -> add(d.article(id)); add(d.revision(id)) }
        d.backupFullIds().forEach { add(d.fullBody(it)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun plan(p: BackupPayload, bytes: Int): BackupPreview {
        val d = db.dao(); val existingSources = d.subscriptions().associateBy { it.id }; val existing = d.backupArticleIds().toSet()
        p.folders.forEach { f -> require(d.folder(f.id)?.let { it.name == f.name } != false && d.folderNamed(f.name)?.let { it.id == f.id } != false) { "文件夹冲突，请先整理后重新预览" } }
        p.sources.forEach { s ->
            require(existingSources[s.id]?.let { it.url == s.url && it.dailyOnly == s.dailyOnly } != false) { "订阅身份冲突" }
            require(existingSources.values.none { it.id != s.id && it.url == s.url && it.dailyOnly == s.dailyOnly }) { "订阅网址冲突" }
        }
        p.articles.forEach { a -> d.article(a.id)?.let { old ->
            val sameBody = stableHash(old.body) == p.hashes[a.id] || (old.body == a.body &&
                (p.bodies || (old.title == a.title && old.summary == a.summary)))
            require(old.source == a.source && old.guid == a.guid && old.originalUrl == a.originalUrl && sameBody) { "文章已变化或身份冲突，请重新导出" }
        } }
        p.notes.forEach { n -> d.note(n.id)?.let { require(it.articleId == n.articleId) { "笔记身份冲突" } } }
        val noteCounts = (d.allNotes() + p.notes.filter { d.note(it.id) == null }).groupingBy { it.articleId }.eachCount()
        require(noteCounts.values.sum() <= 5000 && noteCounts.values.all { it <= 100 })
        return BackupPreview(p, bytes, stamp(), p.sources.count { it.id !in existingSources }, p.articles.count { it.id !in existing },
            p.folders.count { d.folder(it.id) == null }, p.notes.count { d.note(it.id) == null }, p.articles.count { it.id in existing })
    }
    suspend fun preview(bytes: ByteArray): BackupPreview = withContext(Dispatchers.IO) {
        val payload = BackupJson.decode(bytes) // full validation BEFORE transaction, with no writes
        db.withTransaction { plan(payload, bytes.size) }
    }
    /** One local DB transaction; never deletes or replaces existing rows. Caller separately retries preferences. */
    suspend fun restore(preview: BackupPreview) = withContext(Dispatchers.IO) {
        db.withTransaction {
            check(stamp() == preview.stamp) { "资料库已变化，请重新选择文件预览" }
            val p = preview.payload; plan(p, preview.bytes); val d = db.dao()
            p.folders.forEach { if (d.folder(it.id) == null) d.putFolder(it) }
            p.sources.forEach { if (d.subscription(it.id) == null) d.putSubscription(it) }
            val newIds = p.articles.filter { d.article(it.id) == null }.map { it.id }.toSet()
            p.articles.filter { it.id in newIds }.forEach { d.putArticle(it); d.putRevision(DirectRevision(it.id, java.util.UUID.randomUUID().toString())) }
            if (p.bodies) p.full.filter { it.articleId in newIds }.forEach { d.putFullBody(it) }
            p.modes.forEach { if (d.mode(it.source) == null) d.putMode(it) }
            p.notes.forEach { if (d.note(it.id) == null) d.putNote(it) }
        }
    }
    suspend fun restorePreferences(p: BackupPayload) = withContext(Dispatchers.IO) {
        // Typed, bounded, merge-only. No notification opt-ins, background policy or authentication imported.
        offline.mergeBackupPins(p.articlePins, p.folderPins, p.offlinePolicy)
        triage.mergeBackup(p.triage)
        swipes?.mergeBackup(p.swipes)
        val ids = db.dao().backupArticleIds().toSet()
        val knownPositions = ids + db.dao().backupFullIds().filter { it in ids }.map { "$it::fulltext" }
        reader.mergeSnapshot(p.reader.copy(positions = p.reader.positions.filterKeys { it in knownPositions },
            session = ReadingSession(p.reader.session.ids.filter { it in ids })))
    }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<Boolean>.firstReady() = first { it }
