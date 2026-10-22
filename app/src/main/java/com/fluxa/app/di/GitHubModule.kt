package com.fluxa.app.di

import android.content.Context
import androidx.room.Room
import com.fluxa.app.data.github.*
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Internal construction access for actual-Activity device verification; no fixture data or actions.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GitHubAccess {
    fun database(): GitHubDatabase
    fun repository(): GitHubRepository
}

@Module
@InstallIn(SingletonComponent::class)
object GitHubModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): GitHubDatabase =
        Room.databaseBuilder(context, GitHubDatabase::class.java, "github-library.db").build()
    @Provides @Singleton fun client(): GitHubClient = GitHubClient()
    @Provides @Singleton fun repository(db: GitHubDatabase, client: GitHubClient): GitHubRepository = GitHubRepository(db, client)
}
