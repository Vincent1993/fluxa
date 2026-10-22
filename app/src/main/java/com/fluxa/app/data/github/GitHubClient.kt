package com.fluxa.app.data.github

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Nullable API fields, including absent metadata, never become literal "null" in the library.
data class GitHubRepoDto(val id: Long? = null, val full_name: String? = null, val name: String? = null,
    val description: String? = null, val topics: List<String>? = null, val language: String? = null,
    val html_url: String? = null, val stargazers_count: Int? = null)
interface GitHubApi {
    @GET @Headers("Accept: application/vnd.github+json", "X-GitHub-Api-Version: 2026-03-10")
    suspend fun page(@Url url: String): Response<List<GitHubRepoDto>>
}
class GitHubClient(val base: HttpUrl = "https://api.github.com/".toHttpUrl()) {
    // Deliberately separate: no RSS cookies, authentication, logging, or redirects.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val api = Retrofit.Builder().baseUrl(base).client(client)
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
        .build().create(GitHubApi::class.java)
    fun first(username: String): String = base.newBuilder().addPathSegments("users/$username/starred").addQueryParameter("per_page", "100").addQueryParameter("page", "1").build().toString()
    fun validate(raw: String, username: String): String {
        val url = raw.toHttpUrl()
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "分页链接来源无效" }
        val path = url.pathSegments
        require(path.size == 3 && path[2] == "starred" &&
            ((path[0] == "users" && path[1].equals(username, true)) || (path[0] == "user" && path[1].matches(Regex("[1-9][0-9]*"))))) { "分页端点无效" }
        val size = url.queryParameter("per_page")
        require(url.queryParameterNames.all { it in setOf("page", "per_page") } &&
            url.queryParameterValues("page").size == 1 && (url.queryParameter("page")?.toIntOrNull() ?: 0) > 0 &&
            url.queryParameterValues("per_page").size <= 1 &&
            ("per_page" !in url.queryParameterNames || (size?.toIntOrNull() ?: 0) in 1..100)) { "分页参数无效" }
        // Normalize ordering for cycle detection, without changing the server's page size/default.
        return url.newBuilder().query(null).addQueryParameter("page", url.queryParameter("page")!!.toInt().toString())
            .apply { if (size != null) addQueryParameter("per_page", size.toInt().toString()) }.build().toString()
    }
    fun next(link: String?, username: String): String? {
        if (link.isNullOrBlank()) return null
        val next = link.split(',').mapNotNull { part ->
            val target = Regex("^\\s*<([^>]+)>").find(part) ?: error("分页链接格式无效")
            val relation = Regex(";\\s*rel\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE).find(part)
                ?: error("分页链接缺少 rel")
            val relations = relation.groupValues[1].ifEmpty { relation.groupValues[2] }.split(Regex("\\s+"))
            target.groupValues[1].takeIf { "next" in relations }
        }
        require(next.size <= 1) { "重复 next 分页链接" }
        return next.singleOrNull()?.let { validate(it, username) }
    }
    suspend fun page(url: String) = api.page(url)
    fun cooldown(response: Response<*>, now: Long): Long {
        val retry = response.headers()["Retry-After"]
        val retryTime = retry?.toLongOrNull()?.takeIf { it >= 0 && it < Long.MAX_VALUE / 1000 - now / 1000 }?.let { now + it * 1000 }
            ?: runCatching { ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val reset = response.headers()["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000)
        return maxOf(now + 60_000, retryTime ?: 0, reset ?: 0)
    }
}
