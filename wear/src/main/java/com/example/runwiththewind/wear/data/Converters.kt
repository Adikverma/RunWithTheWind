package com.example.runwiththewind.wear.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromRecStatus(status: RecStatus): String = status.name

    @TypeConverter
    fun toRecStatus(value: String): RecStatus = enumValueOf(value)

    @TypeConverter
    fun fromSyncState(state: SyncState): String = state.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = enumValueOf(value)
}
