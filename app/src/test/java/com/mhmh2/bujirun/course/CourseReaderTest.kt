package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.course.NluModel.Reading
import com.mhmh2.bujirun.course.NluModel.Span
import com.mhmh2.bujirun.tracking.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseReaderTest {
    private fun reader(reading: Reading) = CourseReader { reading }
    private val nothing = Reading(emptyList(), "LOOP", emptyList(), "NONE", false)

    @Test fun withoutAModelItIsTheRules() {
        assertEquals(IntentParser.parse("남산까지 5km"), CourseReader(null).read("남산까지 5km"))
    }

    @Test fun placesShapeAndThemesComeFromTheModel() {
        val text = "올팍 5키로 돌고 강변 따라 반포까지"
        val r = reader(
            Reading(
                spans = listOf(Span(0, 2, "PA"), Span(3, 6, "DIST"), Span(16, 18, "PD")),
                shape = "ONE_WAY", themes = listOf("PARK"), activity = "RUN", flat = false,
            )
        ).read(text)
        assertEquals(listOf(PlaceMention("올팍", PlaceRole.AROUND), PlaceMention("반포", PlaceRole.DEST)), r.places)
        assertEquals(CourseShape.ONE_WAY, r.shape)
        assertEquals(5000, r.distanceM) // the number itself is read by the rules
        assertEquals(setOf(CourseTheme.PARK, CourseTheme.RIVER), r.themes) // "강변" said in words
        assertEquals(Activity.RUN, r.activity)
    }

    @Test fun numbersTheRulesMissedAreReadFromTheModelsSpan() {
        // "산책오킬로": the rules don't read a Korean number glued to a word.
        assertEquals(null, IntentParser.parse("산책오킬로").distanceM)
        val r = reader(nothing.copy(spans = listOf(Span(2, 5, "DIST")))).read("산책오킬로")
        assertEquals(5000, r.distanceM)
    }

    @Test fun oneWayWithoutADestinationIsALoop() {
        assertEquals(CourseShape.LOOP, reader(nothing.copy(shape = "ONE_WAY")).read("5km").shape)
    }

    @Test fun aNameCutShortByTheModelIsTheWholeWord() {
        // The model marked only "맘스" of "맘스터치".
        val r = reader(nothing.copy(spans = listOf(Span(3, 5, "PD")), shape = "OUT_AND_BACK")).read("주변 맘스터치 갔다오려고")
        assertEquals(listOf(PlaceMention("맘스터치", PlaceRole.DEST)), r.places)
        assertEquals("석촌호수", CourseReader.wholeName("석촌호수에서 뛰기", 0, 3))
        assertEquals("올림픽공원", CourseReader.wholeName("올림픽 공원 한바퀴", 0, 6))
    }

    @Test fun whereTheUserIsIsNotAPlace() {
        val r = reader(nothing.copy(spans = listOf(Span(0, 1, "PS"), Span(4, 6, "PD")))).read("집에서 시청까지")
        assertEquals(listOf(PlaceMention("시청", PlaceRole.DEST)), r.places)
    }

    @Test fun realModelLoadsAndReadsTheBasics() {
        val model = java.io.File("src/main/assets/nlu.bin").inputStream().use { NluModel.load(it) }
        val r = CourseReader(model::read).read("남산까지 5km 왕복")
        assertEquals(PlaceMention("남산", PlaceRole.DEST), r.places.single())
        assertEquals(CourseShape.OUT_AND_BACK, r.shape)
        assertEquals(5000, r.distanceM)
    }
}
