package com.example.runwiththewind.wear.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecStatus { RECORDING, FINISHED, RECOVERED }
enum class SyncState { PENDING, SYNCED }

@Entity(
    tableName = "activities",
    indices = [Index("startEpochMs"), Index("sport")]
)
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
    val schemaVersion: Int = 1,
    @ColumnInfo(defaultValue = "'RUN'") val sport: SportType = SportType.RUN,
    val title: String? = null,
    @ColumnInfo(defaultValue = "1") val trackAvailable: Boolean = true,
    val metaJson: String? = null
)
