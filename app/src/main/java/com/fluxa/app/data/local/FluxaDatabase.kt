package com.fluxa.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ArticleEntity::class, HighlightEntity::class, TagEntity::class, ArticleTagCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class FluxaDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
}
