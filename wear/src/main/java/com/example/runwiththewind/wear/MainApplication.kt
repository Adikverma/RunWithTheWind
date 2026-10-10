package com.example.runwiththewind.wear

import android.app.Application
import androidx.room.Room
import com.example.runwiththewind.wear.data.AppDatabase

class MainApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "run_with_the_wind.db").build()
    }
}
