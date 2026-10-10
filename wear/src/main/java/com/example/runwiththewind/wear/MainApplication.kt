package com.example.runwiththewind.wear

import android.app.Application
import androidx.room.Room
import com.example.runwiththewind.wear.data.ActivityRepository
import com.example.runwiththewind.wear.data.AppDatabase
import com.example.runwiththewind.wear.data.MIGRATION_1_2

class MainApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "run_with_the_wind.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    val repository: ActivityRepository by lazy {
        ActivityRepository(database.activityDao(), filesDir)
    }
}
