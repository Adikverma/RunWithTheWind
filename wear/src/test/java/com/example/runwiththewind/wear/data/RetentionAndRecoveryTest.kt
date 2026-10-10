package com.example.runwiththewind.wear.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetentionAndRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testPaceOfEdgeCases() {
        // Zero distance -> 0.0
        assertEquals(0.0, paceOf(60_000L, 0.0), 0.001)
        // Distance below minimum (5.0m) -> 0.0
        assertEquals(0.0, paceOf(60_000L, 4.0), 0.001)
        // Zero moving ms -> 0.0
        assertEquals(0.0, paceOf(0L, 1000.0), 0.001)
        // Valid 4.25 min/km case (4 mins 15 sec per km)
        // movingMs = 255_000 (4.25 mins), distanceM = 1000.0 (1 km) -> pace = 4.25
        assertEquals(4.25, paceOf(255_000L, 1000.0), 0.001)
        // Pace above max cutoff (15.0 min/km) -> 0.0
        assertEquals(0.0, paceOf(1_000_000L, 1000.0), 0.001)
    }

    /*
     * Note on recoverOne / pruneOld unit testing:
     * ActivityRecorder interacts directly with Room's ActivityDao and physical File IO.
     * Because ActivityDao is a Room-generated implementation, mocking it requires Mockito/MockK
     * or an in-memory Room database. Here we test the standalone pure logic function `paceOf`.
     */
}
