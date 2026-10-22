package com.fluxa.app.ui.feedlist

import android.content.Context
import com.fluxa.app.domain.model.Article
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
internal fun plainBody(html: String): String = Jsoup.parse(html).also {
    it.select("script,style,noscript,template").remove()
}.text()

data class SourceTriage(val oldestFirst: Boolean = false, val hideRead: Boolean = false)
data class SearchScope(
    val query: String = "", val source: String? = null, val filter: ArticleFilter = ArticleFilter.All,
    val after: Long? = null, val before: Long? = null, val showHidden: Boolean = false, val tag: String = ""
) {
    init { require(query.length <= 256); require(after == null || before == null || after <= before) }
}
data class SavedSearch(val id: String, val name: String, val scope: SearchScope) {
    init { require(name.isNotBlank() && name.length <= 80) }
}
enum class RuleKind(val label: String) { KeepCategory("保留分类"), KeepKeyword("保留关键词"), BlockKeyword("屏蔽关键词"), BlockAuthor("屏蔽作者（完全匹配）") }
data class TriageRule(val id: String, val source: String?, val kind: RuleKind, val value: String, val enabled: Boolean = true) {
    init { require(value.isNotBlank() && value.length <= 256); require(!kind.name.startsWith("Keep") || source != null) }
    fun matches(article: Article, plain: String): Boolean = when (kind) {
        RuleKind.KeepCategory -> if (article.categories.isEmpty())
            normalized(article.title + " " + plain).contains(normalized(value))
            else article.categories.any { normalized(it) == normalized(value) }
        RuleKind.BlockAuthor -> article.author.isNotBlank() && normalized(article.author) == normalized(value)
        else -> normalized(article.title + " " + plain).contains(normalized(value))
    }
}
data class TriageConfig(val sources: Map<String, SourceTriage> = emptyMap(), val saved: List<SavedSearch> = emptyList(),
    val rules: List<TriageRule> = emptyList(), val collapse: Boolean = false,
    val comfortable: Boolean = false, val showSummary: Boolean = true, val showThumbnail: Boolean = true)
data class TriageResult(val articles: List<Article>, val groups: Map<String, List<Article>>, val unread: Int, val starred: Int)

/** URL identity only for display; never used to overwrite IDs or propagate flags. */
internal fun displayCanonical(raw: String): String? = runCatching {
    val uri = URI(raw)
    require(uri.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    require(com.fluxa.app.data.directrss.safeUrl(raw.substringBefore('#')) != null)
    val query = uri.rawQuery?.split('&')?.filterNot {
        val key = it.substringBefore('=').lowercase(Locale.ROOT)
        key.startsWith("utm_") || key == "fbclid" || key == "gclid"
    }?.joinToString("&")?.takeIf { it.isNotEmpty() }
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
    "$scheme://${uri.host.lowercase(Locale.ROOT)}${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().ifEmpty { "/" }}${query?.let { "?$it" }.orEmpty()}"
}.getOrNull()

class TriageRuntime internal constructor(val dispatcher: kotlinx.coroutines.CoroutineDispatcher, val clock: Clock) {
    @Inject constructor() : this(kotlinx.coroutines.Dispatchers.Default, Clock.systemUTC())
}

class TriageEngine(private val clock: Clock = Clock.systemUTC()) {
    fun apply(all: List<Article>, scope: SearchScope, config: TriageConfig, checkActive: () -> Unit = {}): TriageResult {
        val now = clock.instant()
        val cutoff = now.minusSeconds(86400)
        val scoped = all.filter { a ->
            checkActive()
            val plain by lazy { plainBody(a.contentHtml) }
            val rules = config.rules.filter { it.enabled && (it.source == null || it.source == a.source) }
            val keeps = rules.filter { it.kind.name.startsWith("Keep") }
            (scope.source == null || a.source == scope.source) &&
                (if (scope.filter == ArticleFilter.Archived) a.archivedAtEpochSeconds != null else a.archivedAtEpochSeconds == null) &&
                (scope.tag.isBlank() || a.tags.any { it.equals(scope.tag, true) }) &&
                (scope.after == null || a.publishedAt.epochSecond >= scope.after) &&
                (scope.before == null || a.publishedAt.epochSecond <= scope.before) &&
                (scope.filter != ArticleFilter.Last24Hours || (!a.publishedAt.isBefore(cutoff) && !a.publishedAt.isAfter(now))) &&
                (scope.query.isBlank() || normalized(listOf(a.title, a.feedName, a.author, a.excerpt, a.tags.joinToString(" "), plain).joinToString(" ")).contains(normalized(scope.query))) &&
                (scope.showHidden || ((!a.isRead || config.sources[a.source]?.hideRead != true) &&
                    (keeps.isEmpty() || keeps.any { it.matches(a, plain) }) &&
                    rules.none { !it.kind.name.startsWith("Keep") && it.matches(a, plain) }))
        }
        val comparator = if (scope.source != null && config.sources[scope.source]?.oldestFirst == true)
            compareBy<Article> { it.publishedAt }.thenBy { it.id }
        else compareByDescending<Article> { it.publishedAt }.thenBy { it.id }
        val filtered = scoped.filter { (scope.filter != ArticleFilter.Unread || !it.isRead) &&
            (scope.filter != ArticleFilter.Starred || it.isStarred) &&
            (scope.filter != ArticleFilter.ReadLater || it.readLater || it.savedAtEpochSeconds != null) &&
            (scope.filter != ArticleFilter.Annotated || it.annotationCount > 0) }.sortedWith(comparator)
        val groups = if (config.collapse) filtered.groupBy { displayCanonical(it.originalUrl) ?: "id:${it.id}" }.values
            .associate { it.first().id to it.toList() } else emptyMap()
        return TriageResult(if (config.collapse) groups.values.map { it.first() } else filtered,
            groups.filterValues { it.size > 1 }, scoped.count { !it.isRead }, scoped.count { it.isStarred })
    }
}

/** Dedicated local preferences: no account data, payloads, or reader state. Atomic single-document writes. */
@Singleton
class TriageSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("rss_triage_v1", Context.MODE_PRIVATE)
    @Synchronized fun load(): TriageConfig = runCatching { decode(JSONObject(prefs.getString("config", "{}")!!)) }.getOrDefault(TriageConfig())
    @Synchronized fun mergeBackup(incoming: TriageConfig) {
        val old = load()
        val merged = if (!prefs.contains("config")) incoming else old.copy(
            sources = incoming.sources + old.sources,
            saved = old.saved + incoming.saved.filter { n -> old.saved.none { it.id == n.id } },
            rules = old.rules + incoming.rules.filter { n -> old.rules.none { it.id == n.id } })
        require(merged.saved.size <= 50 && merged.rules.size <= 100) { "文章已恢复；搜索或规则已满，请整理后重试设置" }
        save(merged)
    }
    @Synchronized fun save(config: TriageConfig) {
        require(config.saved.size <= 50 && config.rules.size <= 100)
        val sources = JSONObject()
        config.sources.forEach { (id, value) -> sources.put(id, JSONObject().put("oldest", value.oldestFirst).put("hide", value.hideRead)) }
        val searches = JSONArray()
        config.saved.forEach { s -> searches.put(JSONObject().put("id", s.id).put("name", s.name).put("query", s.scope.query)
            .put("source", s.scope.source).put("filter", s.scope.filter.name).put("after", s.scope.after)
            .put("before", s.scope.before).put("hidden", s.scope.showHidden).put("tag", s.scope.tag)) }
        val rules = JSONArray()
        config.rules.forEach { r -> rules.put(JSONObject().put("id", r.id).put("source", r.source).put("kind", r.kind.name)
            .put("value", r.value).put("enabled", r.enabled)) }
        check(prefs.edit().putString("config", JSONObject().put("sources", sources).put("saved", searches)
            .put("rules", rules).put("collapse", config.collapse).put("comfortable", config.comfortable)
            .put("summary", config.showSummary).put("thumbnail", config.showThumbnail).toString()).commit())
    }
    private fun decode(o: JSONObject): TriageConfig {
        fun JSONObject.optional(key: String) = if (has(key) && !isNull(key)) getString(key) else null
        val sources = o.optJSONObject("sources") ?: JSONObject()
        val saved = o.optJSONArray("saved") ?: JSONArray()
        val rules = o.optJSONArray("rules") ?: JSONArray()
        return TriageConfig(sources.keys().asSequence().associateWith { id -> sources.getJSONObject(id).let { SourceTriage(it.optBoolean("oldest"), it.optBoolean("hide")) } },
            (0 until minOf(saved.length(), 50)).map { saved.getJSONObject(it).let { s -> SavedSearch(s.getString("id"), s.getString("name"),
                SearchScope(s.getString("query"), s.optional("source"), ArticleFilter.fromPersisted(s.getString("filter")), s.optional("after")?.toLong(), s.optional("before")?.toLong(), s.optBoolean("hidden"), s.optString("tag"))) } },
            (0 until minOf(rules.length(), 100)).map { rules.getJSONObject(it).let { r -> TriageRule(r.getString("id"), r.optional("source"), RuleKind.valueOf(r.getString("kind")), r.getString("value"), r.optBoolean("enabled", true)) } }, o.optBoolean("collapse"), o.optBoolean("comfortable"), o.optBoolean("summary", true), o.optBoolean("thumbnail", true))
    }
}
