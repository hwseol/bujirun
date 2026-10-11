package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PassiveTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val min = 60_000L
    private fun at(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 10, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun counterDeltaSurvivesARestart() {
        assertEquals(0, StepLedger.delta(null, 5000))
        assertEquals(300, StepLedger.delta(5000, 5300))
        assertEquals(120, StepLedger.delta(5300, 120)) // rebooted: counting from 0 again
    }

    @Test fun stepsAcrossMidnightAreShared() {
        val split = StepLedger.split(at(10, 22), at(11, 2), 4000, zone)
        assertEquals(mapOf(LocalDate.of(2026, 10, 10) to 2000L, LocalDate.of(2026, 10, 11) to 2000L), split)
        assertEquals(mapOf(LocalDate.of(2026, 10, 11) to 500L), StepLedger.split(at(11, 8), at(11, 9), 500, zone))
    }

    @Test fun aLongWalkWithAStopIsOneRecord() {
        val b = BoutTracker()
        val t0 = at(11, 7)
        assertNull(b.enter(FootKind.WALK, t0, 1000))
        b.exit(FootKind.WALK, t0 + 10 * min, 2000)
        assertNull(b.enter(FootKind.WALK, t0 + 12 * min, 2000)) // a red light
        b.exit(FootKind.WALK, t0 + 20 * min, 2900)
        assertNull(b.check(t0 + 22 * min, 2900)) // might carry on yet
        val bout = b.check(t0 + 24 * min, 2900)
        assertNotNull(bout)
        bout!!
        assertEquals(FootKind.WALK, bout.kind)
        assertEquals(18 * min, bout.movingMs)
        assertEquals(1900, bout.steps)
        assertEquals(t0 + 20 * min, bout.endMs)
        assertEquals(1330.0, bout.distanceM, 1.0)
    }

    @Test fun shortTripsAreNotKept() {
        val b = BoutTracker()
        b.enter(FootKind.WALK, at(11, 9), 0)
        b.exit(FootKind.WALK, at(11, 9, 6), 600)
        assertNull(b.check(at(11, 10), 600))
        // ...but five minutes of running is a run.
        b.enter(FootKind.RUN, at(11, 18), 600)
        b.exit(FootKind.RUN, at(11, 18, 6), 1600)
        assertEquals(FootKind.RUN, b.check(at(11, 19), 1600)?.kind)
    }

    @Test fun walkThenRunIsOneRunAndANewStartEndsTheLast() {
        val b = BoutTracker()
        b.enter(FootKind.WALK, at(11, 6), null)
        assertNull(b.enter(FootKind.RUN, at(11, 6, 5), null))
        b.exit(FootKind.RUN, at(11, 6, 35), null)
        // Next walk an hour later: the run is over, kept with steps from a typical cadence.
        val run = b.enter(FootKind.WALK, at(11, 7, 35), null)!!
        assertEquals(FootKind.RUN, run.kind)
        assertEquals(35 * min, run.movingMs)
        assertEquals(5 * 100 + 30 * 160L, run.steps)
        assertEquals(FootKind.WALK, b.state.kind)
    }

    @Test fun stateSurvivesSaving() {
        val b = BoutTracker()
        b.enter(FootKind.RUN, at(11, 6), 42)
        b.exit(FootKind.RUN, at(11, 6, 20), null)
        assertEquals(b.state, BoutTracker.State.decode(b.state.encode()))
        assertEquals(BoutTracker.State(), BoutTracker.State.decode(null))
    }

    @Test fun anEndNeverReportedStillEnds() {
        val b = BoutTracker()
        b.enter(FootKind.WALK, at(11, 8), 0)
        assertNull(b.check(at(11, 11), 5000))
        val bout = b.check(at(11, 12, 30), 9000)!!
        assertEquals(270 * min, bout.movingMs)
        assertEquals(9000, bout.steps)
    }
}
