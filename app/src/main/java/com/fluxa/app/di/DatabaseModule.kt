package com.fluxa.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fluxa.app.data.local.ArticleDao
import com.fluxa.app.data.local.FluxaDatabase
import com.fluxa.app.data.local.PendingActionDao
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
    fun providePendingActionDao(db: FluxaDatabase): PendingActionDao = db.pendingActionDao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_actions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    articleId TEXT NOT NULL,
                    actionType TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
