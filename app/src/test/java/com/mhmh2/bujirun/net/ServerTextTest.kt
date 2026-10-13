package com.mhmh2.bujirun.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTextTest {
    @Test fun placesRomanizedWithTheirKind() {
        assertEquals("Jungang Park", ServerText.place("중앙공원"))
        assertEquals("Haneol Park", ServerText.place("한얼공원"))
        assertEquals("Anyangcheon Stream", ServerText.place("안양천"))
        assertEquals("Hangang River", ServerText.place("한강"))
        assertEquals("Geumjeong Station", ServerText.place("금정역"))
        assertEquals("Seokchon Lake", ServerText.place("석촌호수"))
        assertEquals("Namsan Mountain", ServerText.place("남산"))
        assertEquals("Sillim", ServerText.romanizeAll("신림"))
        assertEquals("Jongno", ServerText.romanizeAll("종로"))
    }

    @Test fun courseTitlesAndPartsInEnglish() {
        assertEquals("Once round Haneol Park", ServerText.en("한얼공원 한 바퀴"))
        assertEquals("3 laps of the Jungang Park track (0.40km a lap)", ServerText.en("중앙공원 트랙 3바퀴 (한 바퀴 0.40km)"))
        assertEquals("To Haneol Park", ServerText.en("한얼공원까지 가는 길"))
        assertEquals("Back", ServerText.en("돌아오는 길"))
        assertEquals("Back along the far bank", ServerText.en("건너편 천변으로 돌아오는 길"))
        assertEquals("Loop along Anyangcheon Stream", ServerText.en("안양천 따라 순환"))
        assertEquals("Out and back to Namsan Mountain", ServerText.en("남산 왕복"))
        assertEquals("Loop via Jungang Park and Haneol Park", ServerText.en("중앙공원·한얼공원 순환"))
        assertEquals("Loop 4.70km", ServerText.en("순환 4.70km"))
        assertEquals("Couldn't find a route.", ServerText.en("경로를 찾지 못했어요."))
        assertEquals("Already English", ServerText.en("Already English"))
    }
}
