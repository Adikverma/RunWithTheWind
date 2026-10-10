package com.example.runwiththewind.wear.ui.history

import com.example.runwiththewind.wear.data.ActivityEntity
import com.example.runwiththewind.wear.data.RecStatus
import com.example.runwiththewind.wear.data.SyncState
import com.example.runwiththewind.wear.formatPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class HistoryFormatTest {

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun testFormatElapsedEdgeCases() {
        assertEquals("0s", formatElapsed(0L))
        assertEquals("59s", formatElapsed(59_000L))
        assertEquals("1min", formatElapsed(60_000L))
        assertEquals("59min", formatElapsed(3599_000L))
        assertEquals("1h 00min", formatElapsed(3600_000L))
        assertEquals("2h 24min", formatElapsed(8640_000L))
    }

    @Test
    fun testFormatPaceInvalidValues() {
        assertEquals("--:--", formatPace(0.0))
        assertEquals("--:--", formatPace(-1.0))
        assertEquals("--:--", formatPace(15.1))
        assertEquals("--:--", formatPace(Double.NaN))
        assertEquals("--:--", formatPace(Double.POSITIVE_INFINITY))
    }

    @Test
    fun testToHistoryItemUiFormatting() {
        val e = ActivityEntity(
            id = "a",
            startEpochMs = Instant.parse("2026-06-07T08:30:00Z").toEpochMilli(),
            elapsedMs = 8_640_000,
            distanceM = 7240.0,
            avgPace = 4.25,
            status = RecStatus.FINISHED,
            filePath = "x"
        )
        val ui = e.toHistoryItemUi(ZoneOffset.UTC)
        assertEquals("7 Jun Morning Run", ui.title)
        assertEquals("7.24 km", ui.distanceText)
        assertEquals("4:15 /km", ui.paceText)
        assertEquals("2h 24min", ui.elapsedText)
        assertEquals("Not synced yet", ui.syncText)
    }

    @Test
    fun testTrackRemovedAndSynced() {
        val e1 = ActivityEntity(
            id = "b",
            startEpochMs = Instant.parse("2026-06-07T08:30:00Z").toEpochMilli(),
            trackAvailable = false,
            syncState = SyncState.PENDING,
            status = RecStatus.FINISHED,
            filePath = "x"
        )
        assertEquals("Track removed from watch", e1.toHistoryItemUi(ZoneOffset.UTC).syncText)

        val e2 = ActivityEntity(
            id = "c",
            startEpochMs = Instant.parse("2026-06-07T08:30:00Z").toEpochMilli(),
            syncedAtMs = Instant.parse("2026-06-07T09:00:00Z").toEpochMilli(),
            syncState = SyncState.SYNCED,
            status = RecStatus.FINISHED,
            filePath = "x"
        )
        assertTrue(e2.toHistoryItemUi(ZoneOffset.UTC).syncText.startsWith("Synced "))
    }

    @Test
    fun testTimeBuckets() {
        val night = ActivityEntity(
            id = "d",
            startEpochMs = Instant.parse("2026-06-07T03:00:00Z").toEpochMilli(),
            status = RecStatus.FINISHED,
            filePath = "x"
        ).toHistoryItemUi(ZoneOffset.UTC)
        assertTrue(night.title.contains("Night"))

        val afternoon = ActivityEntity(
            id = "e",
            startEpochMs = Instant.parse("2026-06-07T14:00:00Z").toEpochMilli(),
            status = RecStatus.FINISHED,
            filePath = "x"
        ).toHistoryItemUi(ZoneOffset.UTC)
        assertTrue(afternoon.title.contains("Afternoon"))
    }
}
