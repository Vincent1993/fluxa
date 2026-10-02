package com.fluxa.app.di

import com.fluxa.app.data.api.AuthHeaderInterceptor
import com.fluxa.app.data.api.InoreaderApi
import com.fluxa.app.data.api.NewsBlurApi
import com.fluxa.app.data.api.NewsBlurJsonAdapter
import com.fluxa.app.data.api.NewsBlurSessionInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().add(NewsBlurJsonAdapter()).addLast(KotlinJsonAdapterFactory()).build()

    @Provides
    @Singleton
    fun provideNewsBlurApi(session: NewsBlurSessionInterceptor, moshi: Moshi): NewsBlurApi =
        Retrofit.Builder().baseUrl("https://www.newsblur.com/")
            .client(OkHttpClient.Builder().addInterceptor(session)
                .followRedirects(false).followSslRedirects(false).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(NewsBlurApi::class.java)

    @Provides
    @Singleton
    fun provideOkHttp(authHeaderInterceptor: AuthHeaderInterceptor): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(authHeaderInterceptor)
            .addInterceptor(logger)
            .build()
    }

    @Provides
    @Singleton
    fun provideInoreaderApi(okHttpClient: OkHttpClient, moshi: Moshi): InoreaderApi {
        return Retrofit.Builder()
            .baseUrl("https://www.inoreader.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
            .create(InoreaderApi::class.java)
    }
}
