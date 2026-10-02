package com.fluxa.app.data.api

import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.*

interface NewsBlurApi {
    @FormUrlEncoded @POST("api/login")
    suspend fun login(@Field("username") username: String, @Field("password") password: String): Response<NewsBlurResult>
    @GET("reader/feeds")
    suspend fun feeds(@Query("include_favicons") icons: Boolean = false,
        @Query("update_counts") counts: Boolean = false): NewsBlurFeeds
    @GET("reader/feed/{id}")
    suspend fun stories(@Path("id") id: String, @Query("page") page: Int,
        @Query("read_filter") filter: String = "all",
        @Query("include_hidden") hidden: Boolean = true): NewsBlurStories
    @FormUrlEncoded @POST("reader/add_url")
    suspend fun addFeed(@Field("url") url: String): NewsBlurResult
    @FormUrlEncoded @POST("reader/mark_story_hashes_as_read")
    suspend fun markRead(@Field("story_hash") hashes: List<String>): NewsBlurResult
    @FormUrlEncoded @POST("reader/mark_story_hash_as_starred")
    suspend fun star(@Field("story_hash") hash: String): NewsBlurResult
    @FormUrlEncoded @POST("reader/mark_story_hash_as_unstarred")
    suspend fun unstar(@Field("story_hash") hash: String): NewsBlurResult
}

data class NewsBlurResult(val code: Int, val message: String? = null) {
    fun requireSuccess() {
        if (code < 0) throw NewsBlurApiException(message ?: "NewsBlur 操作失败，请重试")
    }
}
class NewsBlurApiException(message: String) : java.io.IOException(message)

data class NewsBlurFeeds(val feeds: Map<String, NewsBlurFeed> = emptyMap(), val code: Int = 0)
data class NewsBlurFeed(
    @Json(name = "feed_title") val title: String,
    @Json(name = "feed_address") val url: String = "",
    val active: Boolean = true
)
data class NewsBlurStories(val stories: List<NewsBlurStory> = emptyList(), val code: Int = 0)
data class NewsBlurStory(
    @Json(name = "story_hash") val hash: String,
    @Json(name = "story_feed_id") val feedId: Long,
    @Json(name = "story_title") val title: String = "",
    @Json(name = "story_content") val content: String = "",
    @Json(name = "story_timestamp") val timestamp: String = "0",
    @Json(name = "read_status") val read: Int = 0,
    val starred: Boolean = false
)
