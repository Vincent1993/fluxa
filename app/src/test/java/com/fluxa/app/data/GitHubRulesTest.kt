package com.fluxa.app.data

import com.fluxa.app.data.github.GitHubRepo
import com.fluxa.app.domain.GitHubRules as Rules
import org.junit.Assert.*
import org.junit.Test

class GitHubRulesTest {
    @Test fun usernameValidationAndTokenBoundaries() {
        assertEquals("vincent1993", Rules.username(" Vincent1993 "))
        listOf("", "-x", "x-", "a--b", "a/b", "a b", "a".repeat(40)).forEach { assertTrue(runCatching { Rules.username(it) }.isFailure) }
        assertFalse("AI" in Rules.tags(GitHubRepo("u", 1, "mail", description = "email daily railway")))
        assertTrue("AI" in Rules.tags(GitHubRepo("u", 1, "llm", description = "AI toolkit")))
        val repo = GitHubRepo("u", 1, "react-android", topics = "self-hosted\nsecurity", language = "Kotlin")
        assertTrue(Rules.tags(repo).containsAll(listOf("前端", "移动开发", "自托管", "安全")))
        assertEquals(emptyList<String>(), Rules.tags(repo.copy(manualTags = "")))
        assertEquals(listOf("我的用途"), Rules.tags(repo.copy(manualTags = "我的用途")))
    }
    @Test fun repositoryLinksOnlyAllowSafeHttpsGitHubRoots() {
        listOf("https://github.com/owner/repo", "https://github.com/owner/repo.name-1_/", "https://github.com:443/owner/repo").forEach {
            assertEquals(it, Rules.repositoryUrl(it))
        }
        listOf("", "https://example.invalid/demo/repo", "http://github.com/owner/repo",
            "javascript:alert(1)", "https://github.com.evil.test/owner/repo", "https://evil.test/github.com/repo",
            "https://user@github.com/owner/repo", "https://github.com:444/owner/repo",
            "https://github.com/owner", "https://github.com/owner/repo/issues",
            "https://github.com/owner/..", "https://github.com/owner/%2e%2e",
            "https://github.com/owner/repo?redirect=evil", "https://github.com/owner/repo#fragment",
            " https://github.com/owner/repo", "https://github.com/owner/repo\\\\evil").forEach {
            assertNull(it, Rules.repositoryUrl(it))
        }
    }
    @Test fun sameIdInAnotherProfileCannotSuppressOwnSimilarity() {
        val source = GitHubRepo("own", 1, "source", topics = "privacy", manualTags = "安全")
        val own = source.copy(id = 2, name = "own-match")
        val foreign = own.copy(profile = "other", name = "foreign-match")
        val expected = Rules.similar(source, listOf(own))
        assertEquals(listOf(own), expected.map { it.repo })
        assertEquals(listOf("共同用途：安全", "共同主题：privacy"), expected.single().reasons)
        assertEquals(expected, Rules.similar(source, listOf(foreign, source, own, own)))
        assertEquals(expected, Rules.similar(source, listOf(own, foreign, source)))
        assertTrue(Rules.similar(source, listOf(foreign, source)).isEmpty())
    }
    @Test fun searchCoversAllFieldsAndEffectiveTags() {
        val repo = GitHubRepo("u", 1, "owner/atlas", "private notes", "knowledge", "Rust", manualTags = "阅读\n工具")
        listOf("atlas", "NOTES", "knowledge", "rust", "阅读").forEach { assertEquals(listOf(repo), Rules.search(listOf(repo), it, null)) }
        assertEquals(listOf(repo), Rules.search(listOf(repo), "", "阅读"))
        assertTrue(Rules.search(listOf(repo), "", "AI").isEmpty())
        assertEquals(listOf(repo.copy(manualTags = "")), Rules.search(listOf(repo.copy(manualTags = "")), "", "未分类"))
    }
    @Test fun deterministicDiversityRecentAvoidanceAndReplacement() {
        val repos = (1L..12L).map { GitHubRepo("u", it, "r$it", language = "Kotlin", stars = 100000, manualTags = listOf("AI", "安全", "前端")[(it % 3).toInt()]) }
        val initial = Rules.choose(repos, emptySet(), emptySet(), "day")
        assertEquals(initial, Rules.choose(repos.reversed(), emptySet(), emptySet(), "day"))
        assertEquals(3, initial.map { id -> repos.first { it.id == id }.manualTags }.distinct().size)
        val next = Rules.choose(repos, initial.toSet(), initial.toSet(), "day/2")
        assertTrue(next.toSet().intersect(initial.toSet()).isEmpty())
        val third = Rules.choose(repos, (initial + next).toSet(), emptySet(), "tomorrow")
        assertTrue(third.toSet().intersect((initial + next).toSet()).isEmpty())
        assertEquals(emptyList<Long>(), Rules.choose(emptyList(), emptySet(), emptySet(), "empty"))
        assertEquals(listOf(1L), Rules.choose(repos.take(1), setOf(1), setOf(1), "one"))
        assertEquals(2, Rules.choose(repos.take(2), emptySet(), emptySet(), "two").size)
    }
    @Test fun similarityUsesOwnLibraryStableReasonsAndNoFabricatedMatch() {
        val source = GitHubRepo("u", 1, "source", "encrypted garden notebooks", "privacy", "Rust", manualTags = "安全")
        val strong = source.copy(id = 2, name = "strong")
        val languageOnly = GitHubRepo("u", 3, "language", language = "Rust", manualTags = "")
        val unrelated = GitHubRepo("u", 4, "unrelated", "weather forecasts", language = "Swift", manualTags = "")
        val otherUser = strong.copy(profile = "other", id = 5)
        val result = Rules.similar(source, listOf(source, languageOnly, unrelated, otherUser, strong, strong))
        assertEquals(listOf(2L, 3L), result.map { it.repo.id })
        assertTrue(result.first().reasons.contains("共同用途：安全"))
        assertTrue(result.first().reasons.contains("共同主题：privacy"))
        assertEquals(listOf("共同语言：Rust"), result.last().reasons)
        assertEquals(result, Rules.similar(source, listOf(strong, unrelated, languageOnly)))
        assertTrue(Rules.similar(source, listOf(source, unrelated, otherUser)).isEmpty())
    }
}
