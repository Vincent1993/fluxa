package com.fluxa.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ArticleEntity::class, PendingActionEntity::class, SyncStateEntity::class,
    SubscriptionEntity::class], version = 3, exportSchema = true)
abstract class FluxaDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun subscriptionDao(): SubscriptionDao
}
