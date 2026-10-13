package com.mhmh2.bujirun.coach

import com.mhmh2.bujirun.Lang
import com.mhmh2.bujirun.course.CourseCue
import com.mhmh2.bujirun.course.TurnDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CueTextEnglishTest {
    @Before fun english() { Lang.english = true }
    @After fun korean() { Lang.english = false }

    @Test fun kmCueInEnglish() {
        assertEquals(
            "3 kilometers done. This kilometer 5 minutes 40 seconds. Average pace 5 minutes 50 seconds. Total time 17 minutes 30 seconds.",
            CueText.of(Cue.Split(3000.0, 340_000, 1_050_000)),
        )
    }

    @Test fun turnsAndCountdownInEnglish() {
        assertEquals("three", CueText.of(Cue.Countdown(3)))
        assertEquals("In 100 meters, turn right.", CueText.course(CourseCue.TurnAhead(TurnDirection.RIGHT, 100)))
        assertEquals("Turn left now. Then bear right.", CueText.course(CourseCue.TurnNow(TurnDirection.LEFT, TurnDirection.SLIGHT_RIGHT)))
        assertEquals("1 kilometer", CueText.km(1000.0))
        assertEquals("1 minute 1 second", CueText.duration(61_000))
    }
}
