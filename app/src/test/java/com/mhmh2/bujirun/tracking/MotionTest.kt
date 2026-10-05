package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionTest {
    private fun at(timeMs: Long, northM: Double, speed: Double? = null) =
        LocationSample(timeMs, 37.5 + northM / 111_195.0, 127.0, 5f, speedMps = speed)

    @Test fun speedFromDisplacementIgnoresWander() {
        val m = SpeedMeter()
        // Standing still with 2 m of back-and-forth GPS wander.
        listOf(0.0, 2.0, -1.0, 1.5, -2.0, 0.5, 1.0, 0.0).forEachIndexed { i, d -> m.add(at(i * 1000L, d)) }
        assertEquals(0.0, m.speed(8_000, 7_000)!!, 0.3)
    }

    @Test fun usesReportedSpeedWhenHigherThanDisplacement() {
        // Running a tight curve: displacement under-reads, Doppler doesn't.
        val m = SpeedMeter()
        (0..5).forEach { m.add(at(it * 1000L, it * 2.0, speed = 2.9)) }
        assertEquals(2.9, m.speed(3_000, 5_000)!!, 0.001)
    }

    @Test fun bogusZeroReportedSpeedDoesNotHideMovement() {
        // What the emulator does: speed 0 on every fix while the position moves 3 m/s.
        val m = SpeedMeter()
        (0..5).forEach { m.add(at(it * 1000L, it * 3.0, speed = 0.0)) }
        assertEquals(3.0, m.speed(3_000, 5_000)!!, 0.1)
    }

    @Test fun noSpeedWithoutRecentFixes() {
        val m = SpeedMeter()
        m.add(at(0, 0.0))
        m.add(at(1000, 3.0))
        assertNull(m.speed(3_000, 20_000))
    }

    @Test fun cadence() {
        val c = CadenceMeter()
        // 170 steps/min for 10 s.
        var t = 0.0
        while (t < 10_000) { c.onStep(t.toLong()); t += 60_000.0 / 170 }
        assertEquals(170.0, c.cadence(10_000), 6.0)
        assertEquals(0, c.stepsSince(10_001))
    }

    @Test fun classifyByCadenceThenSpeed() {
        assertEquals(Activity.RUN, ActivityClassifier.classify(1.0, 165.0))
        assertEquals(Activity.WALK, ActivityClassifier.classify(3.0, 110.0))
        // Ambiguous cadence band: speed decides.
        assertEquals(Activity.RUN, ActivityClassifier.classify(2.5, 135.0))
        assertEquals(Activity.WALK, ActivityClassifier.classify(1.6, 135.0))
        // No step sensor: speed only.
        assertEquals(Activity.RUN, ActivityClassifier.classify(3.0, null))
        assertEquals(Activity.WALK, ActivityClassifier.classify(1.3, null))
        assertEquals(Activity.STILL, ActivityClassifier.classify(0.2, null))
        // Step sensor sees nothing and GPS has nothing: still.
        assertEquals(Activity.STILL, ActivityClassifier.classify(null, 0.0))
        assertNull(ActivityClassifier.classify(null, null))
    }

    @Test fun classifierNeedsStateToHoldBeforeSwitching() {
        val c = ActivityClassifier()
        (0..5).forEach { c.update(it * 1000L, 3.0, 170.0) }
        assertEquals(Activity.RUN, c.current)
        // A short slowdown (8 s at a crossing) must not flip it.
        (6..13).forEach { c.update(it * 1000L, 1.2, 100.0) }
        assertEquals(Activity.RUN, c.current)
        (14..20).forEach { c.update(it * 1000L, 3.0, 170.0) }
        // A sustained walk does.
        (21..32).forEach { c.update(it * 1000L, 1.2, 100.0) }
        assertEquals(Activity.WALK, c.current)
    }

    @Test fun autoPauseAfterStandingAndResumeOnMove() {
        val d = AutoPauseDetector()
        var paused = false
        fun step(t: Int, slow: Double?, quick: Double?, steps: Int?): AutoPauseDetector.Decision {
            val r = d.update(t * 1000L, paused, slow, quick, steps)
            if (r == AutoPauseDetector.Decision.PAUSE) paused = true
            if (r == AutoPauseDetector.Decision.RESUME) paused = false
            return r
        }
        (0..4).forEach { step(it, 3.0, 3.0, 12) }
        // Stops: still for 3 s before pausing.
        assertEquals(AutoPauseDetector.Decision.NONE, step(5, 0.2, 0.1, 0))
        assertEquals(AutoPauseDetector.Decision.NONE, step(7, 0.2, 0.1, 0))
        assertEquals(AutoPauseDetector.Decision.PAUSE, step(8, 0.2, 0.1, 0))
        // One jittery tick isn't enough to resume; two moving ticks are.
        assertEquals(AutoPauseDetector.Decision.NONE, step(9, 0.3, 1.5, 0))
        assertEquals(AutoPauseDetector.Decision.NONE, step(10, 0.3, 0.2, 0))
        assertEquals(AutoPauseDetector.Decision.NONE, step(11, 0.5, 1.6, 7))
        assertEquals(AutoPauseDetector.Decision.RESUME, step(12, 0.8, 2.5, 9))
    }

    @Test fun noAutoPauseWhenGpsIsLost() {
        val d = AutoPauseDetector()
        (0..20).forEach { assertEquals(AutoPauseDetector.Decision.NONE, d.update(it * 1000L, false, null, null, null)) }
    }

    @Test fun stepsPreventAutoPauseEvenIfGpsLooksStill() {
        // Jogging on the spot at a red light, or GPS lagging: steps mean "not standing".
        val d = AutoPauseDetector()
        (0..20).forEach { assertEquals(AutoPauseDetector.Decision.NONE, d.update(it * 1000L, false, 0.2, 0.1, 10)) }
    }
}
