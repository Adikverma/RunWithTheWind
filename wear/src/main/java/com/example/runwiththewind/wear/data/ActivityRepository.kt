package com.example.runwiththewind.wear.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ActivityRepository(private val dao: ActivityDao, private val filesDir: File) {
    fun observeHistory(sport: SportType, sinceMs: Long) = dao.observeHistory(sport, sinceMs)

    /** Row first, then file. Never deletes a run that is still recording. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        if (e.status == RecStatus.RECORDING) return@withContext
        dao.delete(listOf(id))
        File(filesDir, e.filePath).delete()
    }
}
