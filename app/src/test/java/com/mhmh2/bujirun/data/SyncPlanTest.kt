package com.mhmh2.bujirun.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    @Test fun firstSyncUploadsEverything() {
        val p = SyncPlan.of(mapOf("w:1" to "a", "c:2" to "b"), emptyMap(), emptySet())
        assertEquals(setOf("w:1", "c:2"), p.upload)
        assertTrue(p.download.isEmpty() && p.deleteRemote.isEmpty() && p.deleteLocal.isEmpty())
    }

    @Test fun aReinstalledAppGetsEverythingBack() {
        val p = SyncPlan.of(emptyMap(), mapOf("w:1" to "a", "steps" to "s"), emptySet())
        assertEquals(setOf("w:1", "steps"), p.download)
        assertTrue(p.upload.isEmpty() && p.deleteRemote.isEmpty() && p.deleteLocal.isEmpty())
    }

    @Test fun inStepNothingHappens() {
        val both = mapOf("w:1" to "a", "c:2" to "b")
        assertTrue(SyncPlan.of(both, both, both.keys).isEmpty)
    }

    @Test fun aChangedItemIsUploadedAgain() {
        val p = SyncPlan.of(mapOf("c:2" to "renamed"), mapOf("c:2" to "b"), setOf("c:2"))
        assertEquals(setOf("c:2"), p.upload)
        assertTrue(p.download.isEmpty())
    }

    @Test fun deletedHereIsDeletedThereAndNotDownloadedBack() {
        val p = SyncPlan.of(mapOf("w:1" to "a"), mapOf("w:1" to "a", "w:9" to "z"), setOf("w:1", "w:9"))
        assertEquals(setOf("w:9"), p.deleteRemote)
        assertTrue(p.download.isEmpty() && p.upload.isEmpty())
    }

    @Test fun deletedOnAnotherPhoneIsDeletedHere() {
        val p = SyncPlan.of(mapOf("w:1" to "a", "w:9" to "z"), mapOf("w:1" to "a"), setOf("w:1", "w:9"))
        assertEquals(setOf("w:9"), p.deleteLocal)
        assertTrue(p.upload.isEmpty())
    }

    @Test fun aServerThatLostEverythingGetsItBackRatherThanWipingThePhone() {
        val mine = (1..10).associate { "w:$it" to "h$it" }
        val p = SyncPlan.of(mine, emptyMap(), mine.keys)
        assertTrue(p.deleteLocal.isEmpty())
        assertEquals(mine.keys, p.upload)
    }

    @Test fun newOnBothSidesMeet() {
        val p = SyncPlan.of(mapOf("w:1" to "a", "w:5" to "e"), mapOf("w:1" to "a", "w:7" to "g"), setOf("w:1"))
        assertEquals(setOf("w:5"), p.upload)
        assertEquals(setOf("w:7"), p.download)
    }
}
