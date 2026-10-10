package com.example.runwiththewind.wear.ui.history

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.example.runwiththewind.wear.R
import com.example.runwiththewind.wear.data.ActivityEntity
import com.example.runwiththewind.wear.data.SportType
import com.example.runwiththewind.wear.data.SyncState
import com.example.runwiththewind.wear.formatDistance
import com.example.runwiththewind.wear.formatPace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class HistoryItemUi(
    val id: String,
    val sport: SportType,
    val title: String,          // "7 Jun Morning Run"
    val distanceText: String,   // "7.24 km"
    val paceText: String,       // "4:15 /km"
    val elapsedText: String,    // "2h 24min"
    val syncText: String,       // "Not synced yet" | "Synced 9 Jun, 14:20" | "Track removed from watch"
    val isSynced: Boolean,
    val trackAvailable: Boolean
)

@DrawableRes
fun SportType.iconRes(): Int = when (this) {
    SportType.RUN -> R.drawable.ic_run
    else -> R.drawable.ic_run   // TODO: per-sport icons when those sports are implemented
}

fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours >= 1 -> {
            val minStr = if (minutes < 10) "0$minutes" else "$minutes"
            "${hours}h ${minStr}min"
        }
        minutes >= 1 -> "${minutes}min"
        else -> "${secs}s"
    }
}

private fun dateFormatter() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private fun syncDateFormatter() = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

fun ActivityEntity.toHistoryItemUi(zoneId: ZoneId = ZoneId.systemDefault()): HistoryItemUi {
    val zdt = Instant.ofEpochMilli(startEpochMs).atZone(zoneId)
    val dayMonthStr = zdt.format(dateFormatter())
    val hour = zdt.hour
    val bucket = when (hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    val generatedTitle = "$dayMonthStr $bucket ${sport.displayName}"
    val displayTitle = title ?: generatedTitle

    val distStr = "${formatDistance(distanceM)} km"
    val rawPace = formatPace(avgPace)
    val paceStr = if (rawPace == "--:--") "--:-- /km" else "$rawPace /km"
    val elapsedStr = formatElapsed(elapsedMs)

    val syncStr = when {
        !trackAvailable && syncState != SyncState.SYNCED -> "Track removed from watch"
        syncedAtMs != null -> {
            val syncDt = Instant.ofEpochMilli(syncedAtMs).atZone(zoneId)
            val syncTimeStr = syncDt.format(syncDateFormatter())
            "Synced $syncTimeStr"
        }
        else -> "Not synced yet"
    }

    return HistoryItemUi(
        id = id,
        sport = sport,
        title = displayTitle,
        distanceText = distStr,
        paceText = paceStr,
        elapsedText = elapsedStr,
        syncText = syncStr,
        isSynced = syncState == SyncState.SYNCED,
        trackAvailable = trackAvailable
    )
}
