package com.fluxa.app.data.local

import com.fluxa.app.data.reader.*
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ArticleEntity::class, PendingActionEntity::class, SyncStateEntity::class,
    SubscriptionEntity::class, ReaderMetadata::class, ReaderAnnotation::class, ReaderTag::class, ReaderArticleTag::class], version = 4, exportSchema = true)
abstract class FluxaDatabase : RoomDatabase() {
    abstract fun readerDao(): ReaderDao
    abstract fun articleDao(): ArticleDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun subscriptionDao(): SubscriptionDao
}
