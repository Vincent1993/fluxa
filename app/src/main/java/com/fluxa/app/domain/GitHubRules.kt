package com.fluxa.app.domain

import com.fluxa.app.data.github.GitHubRepo
import java.util.Locale
import java.net.URI

object GitHubRules {
    val purposes = listOf("AI", "开发工具", "前端", "移动开发", "数据处理", "自动化", "自托管", "学习参考", "安全")
    fun username(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        require(value.length in 1..39 && Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(value)) { "请输入有效 GitHub 用户名（1–39 位字母、数字或单连字符）" }
        return value
    }
    /** Only repository-root HTTPS links; reject credentials, escapes and non-GitHub targets. */
    fun repositoryUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        raw.takeIf {
            uri.scheme.equals("https", true) && uri.host.equals("github.com", true) &&
                uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
                uri.rawQuery == null && uri.rawFragment == null &&
                Regex("/[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*/[A-Za-z0-9_.-]+/?").matches(uri.rawPath.orEmpty()) &&
                uri.rawPath.trimEnd('/').substringAfterLast('/') !in listOf(".", "..")
        }
    }.getOrNull()

    fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+(?:\\+\\+|#)?").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
    fun unpack(text: String): List<String> = text.split('\n').filter { it.isNotBlank() }.distinct()
    fun pack(tags: List<String>): String = tags.map { it.replace('\n', ' ').trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(30).joinToString("\n")
    fun tags(repo: GitHubRepo): List<String> {
        repo.manualTags?.let { return unpack(it) }
        val tokens = words("${repo.name} ${repo.description} ${repo.topics} ${repo.language}")
        val rules = listOf(
            setOf("ai", "llm", "gpt", "pytorch", "tensorflow", "machine", "人工智能"),
            setOf("cli", "ide", "compiler", "debugger", "developer", "git", "sdk"),
            setOf("frontend", "react", "vue", "css", "html", "javascript", "typescript"),
            setOf("android", "ios", "swift", "kotlin", "flutter", "mobile"),
            setOf("data", "sql", "database", "pandas", "analytics", "etl"),
            setOf("automation", "workflow", "bot", "scraper", "ansible"),
            setOf("selfhosted", "docker", "homelab", "hosting"),
            setOf("tutorial", "learning", "learn", "awesome", "examples", "course"),
            setOf("security", "encryption", "vulnerability", "pentest", "cryptography")
        )
        return purposes.filterIndexed { index, _ -> tokens.intersect(rules[index]).isNotEmpty() || (index == 6 && repo.topics.contains("self-hosted", true)) }
    }
    fun search(repos: List<GitHubRepo>, query: String, purpose: String?): List<GitHubRepo> = repos.filter { repo ->
        (purpose == null || if (purpose == "未分类") tags(repo).isEmpty() else purpose in tags(repo)) &&
            listOf(repo.name, repo.description, repo.topics, repo.language, tags(repo).joinToString(" ")).joinToString(" ").contains(query.trim(), true)
    }
    data class Similar(val repo: GitHubRepo, val reasons: List<String>, val score: Int)
    fun similar(source: GitHubRepo, library: List<GitHubRepo>): List<Similar> = library.filter { it.profile == source.profile && it.id != source.id }.distinctBy { it.id }.mapNotNull { other ->
        val commonTags = tags(source).intersect(tags(other).toSet()).sorted()
        val topics = unpack(source.topics).intersect(unpack(other.topics).toSet()).sorted()
        val stop = setOf("the", "a", "an", "for", "and", "to", "of", "in", "with", "is", "on", "your", "from", "this")
        val terms = (words(source.description).intersect(words(other.description)) - stop).filter { it.length > 2 }.sorted().take(4)
        val language = source.language.takeIf { it.isNotBlank() && it.equals(other.language, true) }
        val reasons = buildList {
            if (commonTags.isNotEmpty()) add("共同用途：${commonTags.joinToString("、")}")
            if (topics.isNotEmpty()) add("共同主题：${topics.joinToString("、")}")
            if (terms.isNotEmpty()) add("共同描述词：${terms.joinToString("、")}")
            if (language != null) add("共同语言：$language")
        }
        if (reasons.isEmpty()) null else Similar(other, reasons, commonTags.size * 5 + topics.size * 4 + terms.size * 2 + if (language != null) 1 else 0)
    }.sortedWith(compareByDescending<Similar> { it.score }.thenBy { it.repo.name }.thenBy { it.repo.id }).take(5)

    fun choose(library: List<GitHubRepo>, recent: Set<Long>, current: Set<Long>, seed: String): List<Long> {
        val remaining = library.distinctBy { it.id }.toMutableList()
        val selected = mutableListOf<GitHubRepo>()
        val used = mutableSetOf<String>()
        while (selected.size < 3 && remaining.isNotEmpty()) {
            val next = remaining.minWith(compareBy<GitHubRepo> { if (it.id in current) 1 else 0 }
                .thenBy { if (it.id in recent) 1 else 0 }
                .thenByDescending { tags(it).count { tag -> tag !in used } }
                .thenBy { (seed + ":" + it.id).hashCode().toLong() and 0xffffffffL }.thenBy { it.id })
            selected += next; used += tags(next); remaining.remove(next)
        }
        return selected.map { it.id }
    }
}
