package com.fluxa.app.data.github

import androidx.room.withTransaction
import com.fluxa.app.domain.GitHubRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class GitHubRepository(val db: GitHubDatabase, private val client: GitHubClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now) {
    val dao = db.dao()
    private val importLock = Mutex()
    suspend fun select(raw: String) {
        val user = GitHubRules.username(raw)
        db.withTransaction { dao.putSettings((dao.settings() ?: GitHubSettings()).copy(active = user)) }
    }
    suspend fun editTags(profile: String, id: Long, tags: List<String>?) = dao.tags(profile, id, tags?.let(GitHubRules::pack))
    suspend fun markSeen(profile: String, id: Long): Unit = db.withTransaction {
        if (dao.repo(profile, id) != null) dao.putHistory(GitHubHistory(profile, today().toString(), id))
    }
    suspend fun daily(profile: String, change: Boolean = false): GitHubDaily? = db.withTransaction {
        val date = today()
        val old = dao.daily(profile, date.toString())
        if (old != null && !change) return@withTransaction old
        val library = dao.repos(profile)
        if (library.isEmpty()) return@withTransaction null
        val batch = (old?.batch ?: 0) + 1
        val ids = GitHubRules.choose(library, dao.recent(profile, date.minusDays(7).toString(), date.toString()).toSet(),
            old?.ids?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty(), "$profile/$date/$batch")
        val result = GitHubDaily(profile, date.toString(), batch, ids.joinToString(","))
        dao.putDaily(result)
        ids.forEach { dao.putHistory(GitHubHistory(profile, date.toString(), it)) }
        result
    }
    suspend fun importStars(raw: String, maxPages: Int = 20) = importLock.withLock {
        val user = GitHubRules.username(raw) // Captured once: UI switching cannot redirect this import.
        var state = dao.profile(user) ?: GitHubProfile(user)
        val settings = dao.settings() ?: GitHubSettings()
        if (settings.cooldownUntil > now()) {
            dao.putProfile(state.copy(status = "共享限流冷却中，请等待后继续")); return@withLock
        }
        if (state.cursor == null) state = state.copy(cursor = client.first(user), visited = "", pages = 0)
        // Durable state stays truthful even if the OS kills the process mid-request.
        dao.putProfile(state.copy(status = "导入未完成；中断后可继续"))
        try {
            repeat(maxPages.coerceIn(1, 20)) {
                val url = client.validate(state.cursor ?: client.first(user), user)
                val visited = state.visited.split('\n').filter { it.isNotEmpty() }.toSet()
                check(url !in visited) { "检测到循环分页；可重新导入" }
                val response = client.page(url)
                if (response.code() == 403 || response.code() == 429) {
                    db.withTransaction {
                        val latest = dao.settings() ?: GitHubSettings()
                        dao.putSettings(latest.copy(cooldownUntil = maxOf(latest.cooldownUntil, client.cooldown(response, now()))))
                        dao.putProfile(state.copy(status = "GitHub 限流（${response.code()}），共享冷却后可继续"))
                    }
                    return@withLock
                }
                check(response.isSuccessful) { if (response.code() == 404) "用户不存在或公开 Stars 不可用（404）" else "GitHub 请求失败（${response.code()}）" }
                val body = response.body() ?: error("GitHub 返回空响应")
                // A malformed page must not advance the cursor or claim completion.
                check(body.all { it.id != null && it.id > 0 }) { "仓库缺少有效 ID，保留当前页重试" }
                val next = client.next(response.headers().values("Link").joinToString(","), user)
                check(next == null || (next !in visited && next != url)) { "检测到重复或循环分页链接" }
                val canonical = (visited + url).map { it.toHttpUrl().pathSegments }.firstOrNull { it[0] == "user" }?.get(1)
                if (next != null && canonical != null) {
                    val path = next.toHttpUrl().pathSegments
                    check(path[0] != "user" || path[1] == canonical) { "规范用户 ID 发生变化，停止分页" }
                }
                val updated = state.copy(cursor = next, visited = (visited + url).joinToString("\n"), pages = state.pages + 1,
                    status = if (next == null) "导入完成 · 增量保留本地收藏" else "已导入 ${state.pages + 1} 页，尚未完成")
                db.withTransaction {
                    body.distinctBy { it.id }.forEach { dto ->
                        val id = dto.id!!
                        // Read inside the transaction: concurrent manual edits cannot be overwritten.
                        val manual = dao.repo(user, id)?.manualTags
                        dao.putRepo(GitHubRepo(user, id, dto.full_name ?: dto.name ?: "仓库 #$id", dto.description.orEmpty(),
                            GitHubRules.pack(dto.topics.orEmpty()), dto.language.orEmpty(), dto.html_url.orEmpty(), dto.stargazers_count ?: 0, manual))
                    }
                    dao.putProfile(updated)
                }
                state = updated
                daily(user)
                if (next == null) return@withLock
            }
            dao.putProfile(state.copy(status = "本轮达到页数上限，尚未完成；点击继续导入"))
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { dao.putProfile(state.copy(status = "导入已中断；可继续")) }
            throw cancel
        } catch (error: Exception) {
            dao.putProfile(state.copy(status = "未完成：${error.message ?: "网络断开"}；保留缓存，可重试"))
        }
    }
    suspend fun restart(profile: String) {
        importLock.withLock { dao.putProfile(GitHubProfile(profile, status = "已重置分页；本地收藏保留")) }
    }
}
