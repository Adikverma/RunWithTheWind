package com.example.runwiththewind.wear.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal const val ACTIVITY_DIR = "activities"
private const val TAG = "ActivityRecorder"
private const val FLUSH_INTERVAL_NS = 5_000_000_000L
private const val MIN_PACE_DISTANCE_M = 5.0
private const val MAX_PACE_MIN_PER_KM = 15.0
private const val SYNCED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
private const val UNSYNCED_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000   // policy: confirm

private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

fun paceOf(movingMs: Long, distanceM: Double): Double {
    if (distanceM < MIN_PACE_DISTANCE_M || movingMs <= 0) return 0.0
    val pace = (movingMs / 60_000.0) / (distanceM / 1000.0)
    return if (pace <= MAX_PACE_MIN_PER_KM) pace else 0.0
}

class ActivityRecorder(
    private val filesDir: File,
    private val dao: ActivityDao
) {
    private val dir = File(filesDir, ACTIVITY_DIR).apply { mkdirs() }

    /** Call once per run. The row is inserted before any bytes are written. */
    suspend fun start(startMs: Long, scope: CoroutineScope): RecordingSession =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val entity = ActivityEntity(
                id = id,
                startEpochMs = startMs,
                status = RecStatus.RECORDING,
                filePath = "$ACTIVITY_DIR/$id.jsonl"
            )
            dao.insert(entity)
            RecordingSession(id, startMs, entity, dir, dao, scope)
        }

    /** Finish runs left RECORDING by a crash or kill. Pass the live session id to skip it. */
    suspend fun recoverInterrupted(activeId: String?) = withContext(NonCancellable + Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        for (e in dao.interrupted()) {
            if (e.id == activeId) continue
            try {
                recoverOne(e)
            } catch (ex: IOException) {
                Log.e(TAG, "recovery failed for one run; will retry next launch", ex)
            }
        }
    }

    private suspend fun recoverOne(e: ActivityEntity) {
        val raw = File(dir, "${e.id}.jsonl")
        val gz = File(dir, "${e.id}.jsonl.gz")
        val stats = when {
            raw.exists() -> raw.inputStream().use { scan(it) }
            gz.exists() -> GZIPInputStream(gz.inputStream()).use { scan(it) }
            else -> Stats.EMPTY
        }
        if (raw.exists()) gzipAtomic(raw, gz)          // file is durable before the DB changes
        val end = stats.lastT ?: e.startEpochMs
        dao.update(e.copy(
            status = RecStatus.RECOVERED,
            endEpochMs = end,
            movingMs = stats.movingMs,
            elapsedMs = (end - e.startEpochMs).coerceAtLeast(0L),
            distanceM = stats.distanceM,
            avgPace = paceOf(stats.movingMs, stats.distanceM),
            avgHr = stats.avgHr,
            elevationGainM = stats.elevationGainM,
            filePath = "$ACTIVITY_DIR/${e.id}.jsonl.gz"
        ))
        raw.delete()                                    // only after the DB row is correct
    }

    /** Deletes old runs. DB rows go first; a failed file delete leaves an orphan, not a dangling row. */
    suspend fun pruneOld(now: Long = System.currentTimeMillis()) =
        withContext(NonCancellable + Dispatchers.IO) {
            val victims = dao.prunable(
                syncedCutoff = now - SYNCED_RETENTION_MS,
                hardCutoff = now - UNSYNCED_MAX_AGE_MS
            )
            if (victims.isEmpty()) return@withContext
            dao.delete(victims.map { it.id })
            victims.forEach { File(filesDir, it.filePath).delete() }
        }

    private data class Stats(
        val lastT: Long?,
        val distanceM: Double,
        val movingMs: Long,
        val elevationGainM: Double,
        val avgHr: Double?
    ) {
        companion object {
            val EMPTY = Stats(null, 0.0, 0L, 0.0, null)
        }
    }

    private fun scan(input: InputStream): Stats {
        var lastT: Long? = null
        var dist = 0.0
        var moving = 0L
        var elev = 0.0
        var hrSum = 0.0
        var hrCount = 0
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                // The header line fails to decode as Sample (no `t`), so it is skipped.
                val s = runCatching { json.decodeFromString<Sample>(line) }.getOrNull() ?: return@forEach
                lastT = s.t
                s.dist?.let { dist = it }
                s.mv?.let { moving = it }
                s.eg?.let { elev = it }
                s.hr?.let { hrSum += it; hrCount++ }
            }
        }
        return Stats(lastT, dist, moving, elev, if (hrCount > 0) hrSum / hrCount else null)
    }
}

class RecordingSession internal constructor(
    val id: String,
    private val startMs: Long,
    private val entity: ActivityEntity,
    private val dir: File,
    private val dao: ActivityDao,
    scope: CoroutineScope
) {
    private val raw = File(dir, "$id.jsonl")
    private val gz = File(dir, "$id.jsonl.gz")
    private val channel = Channel<Sample>(Channel.UNLIMITED)

    private val writer: Job = scope.launch(Dispatchers.IO) {
        try {
            raw.bufferedWriter().use { out ->
                out.appendLine(json.encodeToString(Header(id = id, start = startMs)))
                var lastFlush = System.nanoTime()
                for (s in channel) {
                    out.appendLine(json.encodeToString(s))
                    val now = System.nanoTime()
                    if (now - lastFlush > FLUSH_INTERVAL_NS) {
                        out.flush()
                        lastFlush = now
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "writer failed", e)
        }
    }

    /** Non-blocking. Silently drops samples after the session is closed. */
    fun add(sample: Sample) {
        channel.trySend(sample)
    }

    /** Close the stream, gzip atomically, write the summary. Not cancellable mid-way. */
    suspend fun finish(summary: ActivityEntity.() -> ActivityEntity) =
        withContext(NonCancellable + Dispatchers.IO) {
            closeWriter()
            gzipAtomic(raw, gz)
            dao.update(entity.summary().copy(
                status = RecStatus.FINISHED,
                filePath = "$ACTIVITY_DIR/$id.jsonl.gz"
            ))
            raw.delete()
        }

    suspend fun discard() = withContext(NonCancellable + Dispatchers.IO) {
        closeWriter()
        raw.delete()
        gz.delete()
        dao.delete(listOf(id))
    }

    private suspend fun closeWriter() {
        if (!channel.isClosedForSend) channel.close()
        writer.join()
    }
}

private fun gzipAtomic(src: File, dst: File) {
    val tmp = File(dst.parentFile, "${dst.name}.tmp")
    src.inputStream().use { input ->
        GZIPOutputStream(tmp.outputStream().buffered()).use { input.copyTo(it) }
    }
    if (!tmp.renameTo(dst)) throw IOException("rename failed: ${dst.name}")
}
