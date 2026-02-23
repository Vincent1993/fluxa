package com.fluxa.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ArticleEntity::class], version = 1, exportSchema = false)
abstract class FluxaDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
}
