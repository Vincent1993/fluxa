package com.fluxa.app.data.repository

import com.fluxa.app.data.api.NewsBlurApi
import com.fluxa.app.data.local.SecureTokenStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsBlurAuthRepository @Inject constructor(
    private val api: NewsBlurApi,
    private val store: SecureTokenStore
) : AuthRepository {
    override suspend fun signIn(username: String, password: String) {
        val account = username.trim()
        require(account.isNotBlank()) { "请输入 NewsBlur 用户名" }
        val bound = store.getNewsBlurAccount()
        check(bound == null || bound.equals(account, ignoreCase = true)) {
            "当前缓存属于已有账号，请使用原 NewsBlur 账号登录"
        }
        val response = api.login(account, password)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val result = response.body() ?: error("登录返回为空")
        check(result.code == 1) { "NewsBlur 登录失败，请检查用户名和密码" }
        val origin = "https://www.newsblur.com/".toHttpUrl()
        val session = response.headers().values("Set-Cookie")
            .mapNotNull { Cookie.parse(origin, it) }
            .firstOrNull { it.name == "newsblur_sessionid" && it.matches(origin) }
            ?: error("登录未返回有效会话，请重试")
        // Password is never stored; retain only the authenticated session.
        store.saveNewsBlurSession(account, "${session.name}=${session.value}", session.expiresAt)
    }
    override suspend fun exchangeCode(code: String) = error("NewsBlur 使用账号登录")
    override suspend fun refreshIfNeeded() = store.getNewsBlurSession()
    override fun hasSession() = store.getNewsBlurSession() != null
    override suspend fun logout() { store.clearNewsBlurSession() }
}
