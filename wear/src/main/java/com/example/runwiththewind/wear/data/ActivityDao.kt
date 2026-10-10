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

    @Query("SELECT * FROM activities WHERE sport = :sport AND status != 'RECORDING' AND startEpochMs >= :since ORDER BY startEpochMs DESC")
    fun observeHistory(sport: SportType, since: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE status = 'RECORDING'")
    suspend fun interrupted(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE syncState = 'PENDING' AND status != 'RECORDING' AND trackAvailable = 1 ORDER BY startEpochMs")
    suspend fun pendingSync(): List<ActivityEntity>

    @Query("UPDATE activities SET syncState = 'SYNCED', syncedAtMs = :now WHERE id = :id")
    suspend fun markSynced(id: String, now: Long)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: String): ActivityEntity?

    @Query("SELECT * FROM activities WHERE status != 'RECORDING' AND trackAvailable = 1 AND startEpochMs < :cutoff")
    suspend fun blobExpired(cutoff: Long): List<ActivityEntity>

    @Query("UPDATE activities SET trackAvailable = 0 WHERE id IN (:ids)")
    suspend fun markTracksRemoved(ids: List<String>)

    @Query("SELECT * FROM activities WHERE status != 'RECORDING' AND startEpochMs < :cutoff")
    suspend fun rowsExpired(cutoff: Long): List<ActivityEntity>

    @Query("SELECT id FROM activities")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM activities WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
