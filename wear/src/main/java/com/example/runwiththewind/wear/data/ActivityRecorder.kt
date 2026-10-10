package com.example.runwiththewind.wear.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream

class ActivityRecorder(
    private val filesDir: File,
    private val dao: ActivityDao,
    private val scope: CoroutineScope
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var queue: Channel<Sample>? = null
    private var writerJob: Job? = null
    private var entity: ActivityEntity? = null

    fun begin(startMs: Long) {
        val id = UUID.randomUUID().toString()
        val rel = "activities/$id.jsonl"
        val file = File(filesDir, rel).apply { parentFile?.mkdirs() }
        val q = Channel<Sample>(Channel.UNLIMITED).also { queue = it }
        entity = ActivityEntity(id = id, startEpochMs = startMs, status = RecStatus.RECORDING, filePath = rel)

        writerJob = scope.launch(Dispatchers.IO) {
            dao.insert(entity!!)
            file.bufferedWriter().use { out ->
                out.appendLine(json.encodeToString(Header(id = id, start = startMs)))
                var lastFlush = System.nanoTime()
                for (s in q) {
                    out.appendLine(json.encodeToString(s))
                    if (System.nanoTime() - lastFlush > 5_000_000_000) {
                        out.flush()
                        lastFlush = System.nanoTime()
                    }
                }
            }
        }
    }

    fun add(s: Sample) {
        queue?.trySend(s)
    }

    suspend fun finish(summary: ActivityEntity.() -> ActivityEntity) {
        queue?.close()
        writerJob?.join()
        val e = entity ?: return
        withContext(Dispatchers.IO) {
            val raw = File(filesDir, e.filePath)
            val gzPath = e.filePath + ".gz"
            val gz = File(filesDir, gzPath).apply { parentFile?.mkdirs() }
            if (raw.exists()) {
                raw.inputStream().use { input ->
                    GZIPOutputStream(gz.outputStream().buffered()).use { output ->
                        input.copyTo(output)
                    }
                }
                raw.delete()
            }
            val updated = e.summary().copy(
                status = RecStatus.FINISHED,
                filePath = gzPath
            )
            dao.update(updated)
        }
        queue = null
        entity = null
        writerJob = null
    }

    suspend fun recoverInterrupted() {
        withContext(Dispatchers.IO) {
            val interruptedList = dao.interrupted()
            for (interruptedEntity in interruptedList) {
                val raw = File(filesDir, interruptedEntity.filePath)
                var lastTimestamp = interruptedEntity.startEpochMs
                var lastDist = 0.0
                var lastHr: Int? = null
                var hrSum = 0.0
                var hrCount = 0

                if (raw.exists()) {
                    try {
                        raw.forEachLine { line ->
                            if (!line.startsWith("{")) return@forEachLine
                            runCatching {
                                val sample = json.decodeFromString<Sample>(line)
                                lastTimestamp = sample.t
                                sample.dist?.let { lastDist = it }
                                sample.hr?.let {
                                    lastHr = it
                                    hrSum += it
                                    hrCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ActivityRecorder", "Error reading interrupted file", e)
                    }

                    val elapsed = (lastTimestamp - interruptedEntity.startEpochMs).coerceAtLeast(0L)
                    val avgHr = if (hrCount > 0) hrSum / hrCount else null
                    val gzPath = interruptedEntity.filePath + ".gz"
                    val gz = File(filesDir, gzPath).apply { parentFile?.mkdirs() }

                    raw.inputStream().use { input ->
                        GZIPOutputStream(gz.outputStream().buffered()).use { output ->
                            input.copyTo(output)
                        }
                    }
                    raw.delete()

                    val recoveredEntity = interruptedEntity.copy(
                        endEpochMs = lastTimestamp,
                        movingMs = elapsed,
                        elapsedMs = elapsed,
                        distanceM = lastDist,
                        avgHr = lastHr?.toDouble() ?: interruptedEntity.avgHr,
                        status = RecStatus.RECOVERED,
                        filePath = gzPath
                    )
                    dao.update(recoveredEntity)
                } else {
                    val recoveredEntity = interruptedEntity.copy(
                        endEpochMs = interruptedEntity.startEpochMs,
                        status = RecStatus.RECOVERED
                    )
                    dao.update(recoveredEntity)
                }
            }
        }
    }

    suspend fun pruneOld(retentionDays: Int = 7, maxDaysCap: Int = 30) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cutoffSynced = now - (retentionDays * 24 * 60 * 60 * 1000L)
            val cutoffHardCap = now - (maxDaysCap * 24 * 60 * 60 * 1000L)

            val expiredList = dao.expired(cutoffSynced)
            val idsToDelete = mutableListOf<String>()
            for (activity in expiredList) {
                if (activity.syncState == SyncState.SYNCED || activity.startEpochMs < cutoffHardCap) {
                    idsToDelete.add(activity.id)
                    File(filesDir, activity.filePath).delete()
                }
            }
            if (idsToDelete.isNotEmpty()) {
                dao.delete(idsToDelete)
            }
        }
    }
}
