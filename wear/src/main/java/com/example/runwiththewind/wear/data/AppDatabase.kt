package com.example.runwiththewind.wear.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activities ADD COLUMN sport TEXT NOT NULL DEFAULT 'RUN'")
        db.execSQL("ALTER TABLE activities ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE activities ADD COLUMN trackAvailable INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE activities ADD COLUMN metaJson TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activities_startEpochMs ON activities(startEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activities_sport ON activities(sport)")
    }
}

@Database(entities = [ActivityEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}
