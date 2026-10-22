package com.fluxa.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ReadingTheme { System, Light, Dark, Sepia }
data class ReaderSettings(val fontSize: Int = 18, val lineSpacing: Float = 1.8f, val theme: ReadingTheme = ReadingTheme.System) {
    fun bounded() = copy(fontSize = fontSize.coerceIn(14, 30),
        lineSpacing = if (lineSpacing.isFinite()) lineSpacing.coerceIn(1.3f, 2.4f) else 1.8f)
}
/** Only a digest is durable; publisher text and native trees never enter preferences.
 * relative is 0..1 within content, negative in the preceding gap, or >1 in the following
 * gap. Gap offsets are normalized to gap size rather than the wrapping text's height.
 */
data class ContentAnchor(val key: String, val kind: String, val path: String, val occurrence: Int,
    val relative: Float, val top: Int, val height: Int) {
    fun bounded(): ContentAnchor? = takeIf {
        key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } &&
            kind.length in 1..80 && path.length <= 512 && occurrence in 0..20_000 &&
            relative.isFinite() && relative in -1000f..1000f &&
            top in 0..100_000_000 && height in 1..100_000_000
    }
    fun json() = JSONObject().put("key", key).put("kind", kind).put("path", path)
        .put("occurrence", occurrence).put("relative", relative.toDouble()).put("top", top).put("height", height)
    companion object {
        fun fromJson(json: JSONObject?): ContentAnchor? = json?.let {
            ContentAnchor(it.optString("key"), it.optString("kind"), it.optString("path"),
                it.optInt("occurrence", -1), it.optDouble("relative", Double.NaN).toFloat(),
                it.optInt("top", -1), it.optInt("height", -1)).bounded()
        }
    }
}
data class ReadingPosition(val fraction: Float = 0f, val pixels: Int = 0, val range: Int = 0,
    val anchor: ContentAnchor? = null) {
    fun bounded() = ReadingPosition(if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f,
        pixels.coerceIn(0, 100_000_000), range.coerceIn(0, 100_000_000), anchor?.bounded())
    fun offset(newRange: Int): Int = if (newRange == range) pixels.coerceIn(0, newRange.coerceAtLeast(0))
        else (fraction * newRange.coerceAtLeast(0)).toInt()
}

/** Immutable order captured before markRead changes an unread query. Missing neighbors are not skipped. */
data class ReadingSession(val ids: List<String> = emptyList()) {
    /** Prefer the following survivor on a tie, then the preceding one in frozen order. */
    fun nearestSurvivor(id: String, surviving: Set<String>): String? {
        val index = ids.indexOf(id)
        if (index < 0) return null
        for (distance in ids.indices) {
            ids.getOrNull(index + distance)?.takeIf { it in surviving }?.let { return it }
            ids.getOrNull(index - distance)?.takeIf { it in surviving }?.let { return it }
        }
        return null
    }
    fun adjacent(id: String, direction: Int): String? {
        val index = ids.indexOf(id)
        return if (index < 0 || direction !in listOf(-1, 1)) null else ids.getOrNull(index + direction)
    }
}

data class ReaderSnapshot(val settings: ReaderSettings, val positions: Map<String, ReadingPosition>, val session: ReadingSession)

/** Application-lived IO writer; scroll callbacks only update memory and enqueue a conflated signal. */
@Singleton
class ReaderPreferences internal constructor(context: Context, private val scope: CoroutineScope) {
    @Inject constructor(@ApplicationContext context: Context) : this(context,
        CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val prefs by lazy { context.getSharedPreferences("reader_private", Context.MODE_PRIVATE) }
    private val lock = Any()
    private val positions = mutableMapOf<String, ReadingPosition>()
    private var settingsPresent = false
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val _settings = MutableStateFlow(ReaderSettings())
    val settings = _settings.asStateFlow()
    private val _session = MutableStateFlow(ReadingSession())
    val session = _session.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    init {
        scope.launch {
            runCatching {
                val json = JSONObject(prefs.getString("state", "{}") ?: "{}")
                settingsPresent = prefs.contains("state")
                _settings.value = ReaderSettings(json.optInt("font", 18), json.optDouble("spacing", 1.8).toFloat(),
                    ReadingTheme.entries.firstOrNull { it.name == json.optString("theme") } ?: ReadingTheme.System).bounded()
                val ids = json.optJSONArray("session") ?: JSONArray()
                _session.value = ReadingSession((0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }.distinct())
                val stored = json.optJSONObject("positions") ?: JSONObject()
                synchronized(lock) {
                    stored.keys().forEach { id -> stored.optJSONObject(id)?.let {
                        positions[id] = ReadingPosition(it.optDouble("fraction", 0.0).toFloat(), it.optInt("pixels"), it.optInt("range"),
                            ContentAnchor.fromJson(it.optJSONObject("anchor"))).bounded()
                    } }
                }
            }
            _ready.value = true
            for (signal in changes) {
                delay(300) // batch a burst of scrolling, but never postpone indefinitely
                if (!synchronized(lock) { persistLocked() }) {
                    delay(1000)
                    changes.trySend(Unit) // retain memory state and retry transient storage failures
                }
            }
        }
    }
    private fun persistLocked(): Boolean = runCatching {
        val settings = _settings.value
        val json = JSONObject().put("font", settings.fontSize).put("spacing", settings.lineSpacing.toDouble())
            .put("theme", settings.theme.name).put("session", JSONArray(_session.value.ids))
            .put("positions", JSONObject().apply { positions.forEach { (id, p) ->
                put(id, JSONObject().put("fraction", p.fraction.toDouble()).put("pixels", p.pixels).put("range", p.range)
                    .apply { p.anchor?.let { put("anchor", it.json()) } })
            } })
        prefs.edit().putString("state", json.toString()).commit()
    }.getOrDefault(false)
    suspend fun snapshot(knownPositions: Set<String>, knownArticles: Set<String>): ReaderSnapshot {
        ready.first { it }
        return synchronized(lock) { ReaderSnapshot(_settings.value, positions.filterKeys { it in knownPositions }.toMap(),
            ReadingSession(_session.value.ids.filter { it in knownArticles }.distinct().take(5000))) }
    }
    /** Merge only absent positions/session; existing settings always win. IO and memory share the writer lock. */
    suspend fun mergeSnapshot(value: ReaderSnapshot) = withContext(Dispatchers.IO) {
        ready.first { it }
        synchronized(lock) {
            require(value.positions.size <= 5000 && value.session.ids.size <= 5000)
            value.positions.forEach { (id, p) -> require(p == p.bounded()); positions.putIfAbsent(id, p) }
            if (!settingsPresent) { _settings.value = value.settings.bounded(); settingsPresent = true }
            if (_session.value.ids.isEmpty()) _session.value = value.session
            if (!persistLocked()) { changes.trySend(Unit); error("文章已恢复；阅读位置保存失败，请重试设置") }
        }
    }
    fun update(settings: ReaderSettings) { synchronized(lock) {
        if (_ready.value) { settingsPresent = true; _settings.value = settings.bounded(); changes.trySend(Unit) }
    } }
    suspend fun awaitAndFreeze(ids: List<String>, selected: String): Boolean {
        val snapshot = ids.toList() // copy before suspending; the live unread query can change
        ready.first { it }
        return freeze(snapshot, selected)
    }
    fun freeze(ids: List<String>, selected: String): Boolean {
        if (!_ready.value || selected !in ids) return false
        synchronized(lock) { _session.value = ReadingSession(ids.distinct().take(5000)); changes.trySend(Unit) }; return true
    }
    fun position(id: String): ReadingPosition = synchronized(lock) { positions[id] ?: ReadingPosition() }
    fun savePosition(id: String, position: ReadingPosition) {
        if (!_ready.value) return
        synchronized(lock) { positions[id] = position.bounded() }; changes.trySend(Unit)
    }
}

/** A render callback may restore/write only its own page generation. */
class PageRestoreGuard {
    private var generation = 0L
    private var article = ""
    private var restoring = true
    fun begin(id: String): Long { article = id; restoring = true; return ++generation }
    fun accepts(id: String, token: Long) = article == id && generation == token
    fun rendered(id: String, token: Long): Boolean {
        if (!accepts(id, token)) return false
        restoring = false; return true
    }
    fun canWrite(id: String, token: Long) = accepts(id, token) && !restoring
}
