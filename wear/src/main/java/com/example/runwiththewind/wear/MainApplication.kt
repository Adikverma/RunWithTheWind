package com.example.runwiththewind.wear

import android.app.Application
import com.example.runwiththewind.wear.data.AppDatabase

class MainApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
