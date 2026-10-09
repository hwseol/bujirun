package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The app must compute exactly what PyTorch computed for the exported model. The fixture is
 * written by tools/nlu/train.py together with the model, so retraining updates both.
 */
class NluModelTest {
    private val model = File("src/main/assets/nlu.bin").inputStream().use { NluModel.load(it) }

    @Test fun matchesPyTorchOutputs() {
        val lines = javaClass.classLoader!!.getResourceAsStream("nlu_fixture.tsv")!!.bufferedReader().readLines()
        check(lines.isNotEmpty())
        for (line in lines) {
            val (text, numbers) = line.split('\t')
            val want = numbers.split(' ').map { it.toFloat() }
            val (tags, sent) = model.logits(text)
            val got = tags.flatMap { it.asList() } + sent.asList()
            assertEquals(text, want.size, got.size)
            val worst = want.indices.maxOf { abs(want[it] - got[it]) }
            assertTrue("$text: off by $worst", worst < 2e-3)
        }
    }

    @Test fun decodesLikePython() {
        val lines = javaClass.classLoader!!.getResourceAsStream("nlu_fixture_readings.tsv")!!.bufferedReader().readLines()
        check(lines.size > 100)
        for (line in lines) {
            val f = line.split('\t')
            val want = NluModel.Reading(
                spans = f[1].split(';').filter { it.isNotEmpty() }.map { s ->
                    val (a, b, k) = s.split(','); NluModel.Span(a.toInt(), b.toInt(), k)
                },
                shape = f[2],
                themes = f[3].split(',').filter { it.isNotEmpty() },
                activity = f[4],
                flat = f[5] == "1",
            )
            assertEquals(f[0], want, model.read(f[0]))
        }
    }

    @Test fun jamoMatchesUnicodeDecomposition() {
        assertEquals(Triple(1, 1, 1), NluModel.jamo('가'))
        assertEquals(Triple(19, 21, 28), NluModel.jamo('힣'))
        assertEquals(Triple(0, 0, 0), NluModel.jamo('5'))
    }

    @Test fun normalizesLikePython() {
        assertEquals("한강 따라 5km", NluModel.normalize("  한강   따라\t5KM "))
    }
}
