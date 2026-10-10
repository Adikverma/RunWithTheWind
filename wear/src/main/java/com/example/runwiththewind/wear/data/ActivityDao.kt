package com.example.runwiththewind.wear.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Insert suspend fun insert(a: ActivityEntity)
    @Update suspend fun update(a: ActivityEntity)

    @Query("SELECT * FROM activities WHERE status != 'RECORDING' ORDER BY startEpochMs DESC")
    fun observeHistory(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE status = 'RECORDING'")
    suspend fun interrupted(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE syncState = 'PENDING' AND status != 'RECORDING' ORDER BY startEpochMs")
    suspend fun pendingSync(): List<ActivityEntity>

    @Query("UPDATE activities SET syncState = 'SYNCED', syncedAtMs = :now WHERE id = :id")
    suspend fun markSynced(id: String, now: Long)

    @Query("""
        SELECT * FROM activities
        WHERE status != 'RECORDING'
          AND ((syncState = 'SYNCED' AND startEpochMs < :syncedCutoff) OR startEpochMs < :hardCutoff)
    """)
    suspend fun prunable(syncedCutoff: Long, hardCutoff: Long): List<ActivityEntity>

    @Query("DELETE FROM activities WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
