package com.fluxa.app.di

import android.content.Context
import androidx.room.Room
import com.fluxa.app.data.local.ArticleDao
import com.fluxa.app.data.local.FluxaDatabase
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
        return Room.databaseBuilder(context, FluxaDatabase::class.java, "fluxa.db").build()
    }

    @Provides
    fun provideArticleDao(db: FluxaDatabase): ArticleDao = db.articleDao()
}
