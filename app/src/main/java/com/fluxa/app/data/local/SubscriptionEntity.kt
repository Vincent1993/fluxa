package com.fluxa.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(@PrimaryKey val id: String, val title: String, val url: String)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>
    @Query("SELECT * FROM subscriptions ORDER BY id")
    suspend fun getAll(): List<SubscriptionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SubscriptionEntity>)
    @Query("DELETE FROM subscriptions") suspend fun clearAll()
}
