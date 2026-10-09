package com.mhmh2.bujirun.course

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

/**
 * The small on-device model that reads a course request (docs/NL_COURSES.md section 6). Trained
 * and exported by tools/nlu/train.py; the lists and the maths here must match that file and
 * tools/nlu/schema.py exactly (NluModelTest checks the numbers against PyTorch's).
 *
 * A character embedding plus Hangul jamo embeddings, one bidirectional GRU layer, a BIO tag per
 * character (where the distance, time and places are) and a few sentence-level outputs. About
 * 0.1 MB of int8 weights; a sentence takes well under a millisecond. Plain loops, no library.
 */
class NluModel private constructor(
    private val vocab: Map<Int, Int>,
    private val emb: Int,
    private val hidden: Int,
    private val t: Map<String, Matrix>,
) {
    class Matrix(val rows: Int, val cols: Int, val v: FloatArray) {
        operator fun get(r: Int, c: Int) = v[r * cols + c]
    }

    data class Span(val start: Int, val end: Int, val kind: String)

    data class Reading(
        val spans: List<Span>,
        val shape: String,
        val themes: List<String>,
        val activity: String,
        val flat: Boolean,
    )

    /** Raw outputs: tag logits per character (text.length x TAGS.size) and the sentence logits. */
    fun logits(text: String): Pair<Array<FloatArray>, FloatArray> {
        val s = normalize(text).take(MAX_LEN)
        val n = s.length
        val x = Array(n) { i ->
            val c = s[i]
            val row = FloatArray(emb)
            addRow(row, t.getValue("char.weight"), vocab[c.code] ?: 1)
            val (cho, jung, jong) = jamo(c)
            addRow(row, t.getValue("cho.weight"), cho)
            addRow(row, t.getValue("jung.weight"), jung)
            addRow(row, t.getValue("jong.weight"), jong)
            row
        }
        val fwd = gru(x, "", reverse = false)
        val bwd = gru(x, "_reverse", reverse = true)
        val out = Array(n) { i -> fwd[i] + bwd[i] }

        val tag = t.getValue("tag.weight")
        val tagBias = t.getValue("tag.bias")
        val tags = Array(n) { i -> linear(out[i], tag, tagBias) }

        val width = 2 * hidden
        // Mean and max over the sentence, as train.py's Model.forward.
        val pooled = FloatArray(2 * width)
        for (j in 0 until width) {
            var sum = 0f
            var mx = -1e4f
            for (i in 0 until n) { sum += out[i][j]; mx = max(mx, out[i][j]) }
            pooled[j] = if (n > 0) sum / n else 0f
            pooled[width + j] = mx
        }
        return tags to linear(pooled, t.getValue("sent.weight"), t.getValue("sent.bias"))
    }

    fun read(text: String): Reading {
        val s = normalize(text).take(MAX_LEN)
        val (tagLogits, sent) = logits(s)
        val tags = tagLogits.map { TAGS[argmax(it, 0, it.size)] }
        val spans = mutableListOf<Span>()
        var cur: IntArray? = null
        var kind: String? = null
        fun close() { cur?.let { spans += Span(it[0], it[1], kind!!) }; cur = null }
        tags.forEachIndexed { i, tag ->
            val k = if (tag == "O") null else tag.substring(2)
            when {
                tag.startsWith("B-") || (tag.startsWith("I-") && (cur == null || kind != k)) -> {
                    close(); cur = intArrayOf(i, i + 1); kind = k
                }
                tag.startsWith("I-") -> cur!![1] = i + 1
                else -> close()
            }
        }
        close()
        // A place name split into touching pieces of different roles ("삼|광사") is one place; it
        // takes the role that covers most of it (the later one on a tie). Same as train.py.
        val merged = mutableListOf<Span>()
        for (sp in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.end == sp.start && last.kind in PLACE_KINDS && sp.kind in PLACE_KINDS) {
                val kind = if (last.end - last.start > sp.end - sp.start) last.kind else sp.kind
                merged[merged.size - 1] = Span(last.start, sp.end, kind)
            } else {
                merged += sp
            }
        }
        val trimmed = merged.mapNotNull { sp ->
            var a = sp.start
            var b = sp.end
            while (a < b && s[a] == ' ') a++
            while (b > a && s[b - 1] == ' ') b--
            if (a < b) Span(a, b, sp.kind) else null
        }
        val themesAt = SHAPES.size
        val activityAt = themesAt + THEMES.size
        val flatAt = activityAt + ACTIVITIES.size
        return Reading(
            spans = trimmed,
            shape = SHAPES[argmax(sent, 0, themesAt)],
            themes = THEMES.filterIndexed { i, _ -> sent[themesAt + i] > 0 },
            activity = ACTIVITIES[argmax(sent, activityAt, flatAt) - activityAt],
            flat = sent[flatAt] > 0,
        )
    }

    /** PyTorch's GRU, gates in its order (reset, update, new). */
    private fun gru(x: Array<FloatArray>, suffix: String, reverse: Boolean): Array<FloatArray> {
        val wi = t.getValue("gru.weight_ih_l0$suffix")
        val wh = t.getValue("gru.weight_hh_l0$suffix")
        val bi = t.getValue("gru.bias_ih_l0$suffix")
        val bh = t.getValue("gru.bias_hh_l0$suffix")
        val n = x.size
        val out = Array(n) { FloatArray(hidden) }
        var h = FloatArray(hidden)
        val order = if (reverse) (n - 1 downTo 0) else (0 until n)
        for (step in order) {
            val gi = linear(x[step], wi, bi)
            val gh = linear(h, wh, bh)
            val next = FloatArray(hidden)
            for (j in 0 until hidden) {
                val r = sigmoid(gi[j] + gh[j])
                val z = sigmoid(gi[hidden + j] + gh[hidden + j])
                val cand = tanh(gi[2 * hidden + j] + r * gh[2 * hidden + j])
                next[j] = (1 - z) * cand + z * h[j]
            }
            h = next
            out[step] = h
        }
        return out
    }

    companion object {
        val TAGS = listOf("O") + listOf("DIST", "TIME", "PS", "PD", "PA").flatMap { listOf("B-$it", "I-$it") }
        val SHAPES = listOf("LOOP", "OUT_AND_BACK", "ONE_WAY")
        val THEMES = listOf("RIVER", "PARK", "LAKE", "COAST", "SIGHTS")
        val ACTIVITIES = listOf("NONE", "RUN", "WALK")
        private val PLACE_KINDS = setOf("PS", "PD", "PA")
        const val MAX_LEN = 64
        private const val VERSION = 1
        private val TENSORS = listOf(
            "char.weight", "cho.weight", "jung.weight", "jong.weight",
            "gru.weight_ih_l0", "gru.weight_hh_l0", "gru.bias_ih_l0", "gru.bias_hh_l0",
            "gru.weight_ih_l0_reverse", "gru.weight_hh_l0_reverse", "gru.bias_ih_l0_reverse", "gru.bias_hh_l0_reverse",
            "tag.weight", "tag.bias", "sent.weight", "sent.bias",
        )

        fun normalize(text: String) = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        fun load(input: InputStream): NluModel {
            val bytes = input.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic) == "BJNL") { "not a Bujirun NLU model" }
            require(buf.int == VERSION) { "unsupported NLU model version" }
            val vocab = HashMap<Int, Int>()
            repeat(buf.int) { i -> vocab[buf.int] = i + 2 }
            val emb = buf.int
            val hidden = buf.int
            val tensors = TENSORS.associateWith {
                val rows = buf.int
                val cols = buf.int
                val scales = FloatArray(rows) { buf.float }
                val v = FloatArray(rows * cols)
                for (r in 0 until rows) for (c in 0 until cols) v[r * cols + c] = buf.get() * scales[r]
                Matrix(rows, cols, v)
            }
            check(!buf.hasRemaining()) { "trailing bytes in NLU model" }
            return NluModel(vocab, emb, hidden, tensors)
        }

        /** Hangul syllable -> (choseong+1, jungseong+1, jongseong+1); zeros for anything else. */
        fun jamo(c: Char): Triple<Int, Int, Int> {
            val code = c.code - 0xAC00
            if (code < 0 || code >= 11172) return Triple(0, 0, 0)
            return Triple(code / 588 + 1, (code % 588) / 28 + 1, code % 28 + 1)
        }

        private fun addRow(into: FloatArray, m: Matrix, row: Int) {
            val base = row * m.cols
            for (j in into.indices) into[j] += m.v[base + j]
        }

        private fun linear(x: FloatArray, w: Matrix, b: Matrix): FloatArray {
            val out = FloatArray(w.rows)
            for (r in 0 until w.rows) {
                var acc = b.v[r]
                val base = r * w.cols
                for (c in 0 until w.cols) acc += w.v[base + c] * x[c]
                out[r] = acc
            }
            return out
        }

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        private fun argmax(v: FloatArray, from: Int, until: Int): Int {
            var best = from
            for (i in from until until) if (v[i] > v[best]) best = i
            return best
        }
    }
}
