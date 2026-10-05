package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyTest {
    @Test fun runningOneHourAt10kmh() {
        // 65 kg, 10 km in an hour, flat: VO2 = 0.2 * 166.7 + 3.5 = 36.8 -> ~718 kcal.
        assertEquals(718.0, Calories.kcal(Activity.RUN, 10_000.0, 3600.0, 0.0, 65.0), 3.0)
    }

    @Test fun walkingOneHourAt5kmh() {
        // VO2 = 0.1 * 83.3 + 3.5 = 11.8 -> ~231 kcal.
        assertEquals(231.0, Calories.kcal(Activity.WALK, 5_000.0, 3600.0, 0.0, 65.0), 3.0)
    }

    @Test fun uphillCostsMoreDownhillCountsAsFlat() {
        val flat = Calories.kcal(Activity.RUN, 1000.0, 360.0, 0.0, 65.0)
        val up = Calories.kcal(Activity.RUN, 1000.0, 360.0, 0.05, 65.0)
        val down = Calories.kcal(Activity.RUN, 1000.0, 360.0, -0.05, 65.0)
        assertTrue(up > flat * 1.1)
        assertEquals(flat, down, 0.001)
    }

    @Test fun unknownActivityGoesBySpeed() {
        val fast = Calories.kcal(Activity.UNKNOWN, 1000.0, 300.0, 0.0, 65.0)
        assertEquals(Calories.kcal(Activity.RUN, 1000.0, 300.0, 0.0, 65.0), fast, 0.001)
        val slow = Calories.kcal(Activity.UNKNOWN, 1000.0, 720.0, 0.0, 65.0)
        assertEquals(Calories.kcal(Activity.WALK, 1000.0, 720.0, 0.0, 65.0), slow, 0.001)
    }

    @Test fun ascentIgnoresAltitudeNoise() {
        val e = ElevationTracker()
        // Flat ground, GPS altitude jumping +-2 m.
        listOf(10.0, 12.0, 8.5, 11.5, 9.0, 12.0, 10.0).forEach { e.add(it, 10.0) }
        assertEquals(0.0, e.ascentM, 0.001)
    }

    @Test fun ascentAndGradeOfRealHill() {
        val e = ElevationTracker()
        // Climb 20 m over 200 m (10%), one fix every 10 m.
        (0..20).forEach { e.add(10.0 + it, 10.0) }
        assertEquals(20.0, e.ascentM, 3.0)
        assertEquals(0.10, e.grade, 0.02)
    }

    @Test fun trackerSplitsRunAndWalkTime() {
        val t = WorkoutTracker()
        t.start(0)
        t.activity = Activity.RUN
        (1..60).forEach { t.tick(it * 1000L) }
        t.activity = Activity.WALK
        (61..90).forEach { t.tick(it * 1000L) }
        val s = t.snapshot(90_000)
        assertEquals(60_000L, s.runMs)
        assertEquals(30_000L, s.walkMs)
    }

    @Test fun trackerAccumulatesCalories() {
        val t = WorkoutTracker(weightKg = 65.0)
        t.start(0)
        t.activity = Activity.RUN
        // 10 min at 10 km/h.
        (0..600).forEach { t.onLocation(LocationSample(it * 1000L, 37.5 + it * 2.7778 / 111_195.0, 127.0, 5f)) }
        assertEquals(718.0 / 6, t.calories, 3.0)
    }
}
