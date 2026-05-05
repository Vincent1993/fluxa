package com.fluxa.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fluxa.app.data.local.ArticleDao
import com.fluxa.app.data.local.FluxaDatabase
import com.fluxa.app.data.local.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FluxaDatabase {
        return Room.databaseBuilder(context, FluxaDatabase::class.java, "fluxa.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideArticleDao(db: FluxaDatabase): ArticleDao = db.articleDao()

    @Provides
    fun provideSyncStateDao(db: FluxaDatabase): SyncStateDao = db.syncStateDao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `sync_state` (
                    `source` TEXT NOT NULL,
                    `cursor` TEXT,
                    `lastSyncAtEpochMillis` INTEGER NOT NULL,
                    `syncToken` TEXT,
                    PRIMARY KEY(`source`)
                )
            """.trimIndent())
        }
    }
}
