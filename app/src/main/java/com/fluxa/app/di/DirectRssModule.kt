package com.fluxa.app.di

import android.content.Context
import androidx.room.Room
import com.fluxa.app.data.directrss.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DirectRssModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): DirectRssDatabase =
        Room.databaseBuilder(context, DirectRssDatabase::class.java, "direct-rss.db").addMigrations(DirectRssDatabase.MIGRATION_1_2, DirectRssDatabase.MIGRATION_2_3, DirectRssDatabase.MIGRATION_3_4, DirectRssDatabase.MIGRATION_4_5, DirectRssDatabase.MIGRATION_5_6).build()
    @Provides @Singleton fun repository(db: DirectRssDatabase, settings: LocalOfflineSettings, notifications: LocalFeedNotifications): DirectRssRepository =
        DirectRssRepository(db, images = DirectImageCache(downloadAllowed = settings::canDownloadImages), offline = settings, notifications = notifications)
}

/** Read-only access to app-owned dependencies for explicitly opted-in device verification. */
@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface DirectRssAccess {
    fun directRepository(): DirectRssRepository
    fun directDatabase(): DirectRssDatabase
    fun newsBlurDatabase(): com.fluxa.app.data.local.FluxaDatabase
    fun authRepository(): com.fluxa.app.data.repository.AuthRepository
}
