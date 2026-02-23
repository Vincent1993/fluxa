package com.fluxa.app.di

import com.fluxa.app.data.repository.ArticleRepository
import com.fluxa.app.data.repository.AuthRepository
import com.fluxa.app.data.repository.InoreaderArticleRepository
import com.fluxa.app.data.repository.InoreaderAuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindArticleRepository(impl: InoreaderArticleRepository): ArticleRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: InoreaderAuthRepository): AuthRepository
}
