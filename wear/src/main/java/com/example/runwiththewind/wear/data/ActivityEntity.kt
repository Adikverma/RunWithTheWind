package com.example.runwiththewind.wear.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RecStatus { RECORDING, FINISHED, RECOVERED }
enum class SyncState { PENDING, SYNCED }

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String,              // UUID, same id used on phone
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val avgPace: Double = 0.0,
    val avgHr: Double? = null,
    val elevationGainM: Double = 0.0,
    val status: RecStatus,
    val syncState: SyncState = SyncState.PENDING,
    val syncedAtMs: Long? = null,
    val filePath: String,                    // relative to filesDir
    val schemaVersion: Int = 1
)
