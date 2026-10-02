package com.fluxa.app.data.api

import com.fluxa.app.data.api.model.StreamContentsResponse
import com.fluxa.app.data.api.model.TokenResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Path
import com.fluxa.app.data.api.model.SubscriptionResponse
import com.fluxa.app.data.api.model.AddSubscriptionResponse

interface InoreaderApi {
    @FormUrlEncoded
    @POST("oauth2/token")
    suspend fun exchangeToken(
        @Field("grant_type") grantType: String,
        @Field("code") code: String? = null,
        @Field("refresh_token") refreshToken: String? = null,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("redirect_uri") redirectUri: String
    ): TokenResponse

    @GET("reader/api/0/stream/contents/{streamId}")
    suspend fun getReadingStream(
        @Path("streamId") streamId: String = "user/-/state/com.google/reading-list",
        @Query("n") count: Int,
        @Query("c") continuation: String? = null
    ): StreamContentsResponse

    @FormUrlEncoded
    @POST("reader/api/0/edit-tag")
    suspend fun editTag(
        @Field("i") itemId: String,
        @Field("a") addTag: String? = null,
        @Field("r") removeTag: String? = null
    )

    @GET("reader/api/0/subscription/list")
    suspend fun getSubscriptions(): SubscriptionResponse

    @FormUrlEncoded
    @POST("reader/api/0/subscription/quickadd")
    suspend fun addSubscription(@Field("quickadd") feedId: String): AddSubscriptionResponse
}
