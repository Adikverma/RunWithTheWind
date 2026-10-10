package com.example.runwiththewind.wear.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}
