package gr.sv1eex.c3trx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class WfTextTest {
    // 5x7 "SV1" style test glyph block (rows x cols)
    private val bm: Bits = listOf(
        "1110100101",
        "1000100111",
        "1110100101",
        "0010010101",
        "1110010101",
    ).map { r -> BooleanArray(r.length) { r[it] == '1' } }.toTypedArray()

    private fun goertzel(a: FloatArray, from: Int, n: Int, f: Double, sr: Int): Double {
        val k = 2 * cos(2 * PI * f / sr); var s1 = 0.0; var s2 = 0.0
        for (i in from until from + n) { val s0 = a[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n
    }

    /** Decode the generated audio back into pixels by measuring every tone in every slice. */
    private fun decode(a: FloatArray, slices: Int, tones: Int, base: Double, sp: Double, sliceMs: Double, sr: Int): Bits {
        val l = Math.round(sliceMs / 1000 * sr).toInt(); val lead = (0.15 * sr).toInt()
        val m = Array(slices) { s -> DoubleArray(tones) { k -> goertzel(a, lead + s * l + l / 4, l / 2, base + k * sp, sr) } }
        val peak = m.maxOf { it.max() }
        return Array(slices) { s -> BooleanArray(tones) { k -> m[s][k] > peak * 0.3 } }
    }

    @Test fun roundTripAllOrientations() {
        val sr = TX_SR; val base = 400.0; val sp = 150.0; val ms = 80.0
        var errors = 0; var total = 0
        for (vertical in listOf(true, false)) for (lsb in listOf(false, true)) for (top in listOf(true, false)) {
            val f = WaterfallText.frames(bm, vertical, top, lsb)
            val a = WaterfallText.synth(f, sr, base, sp, ms / 1000)
            val d = decode(a, f.size, f[0].size, base, sp, ms, sr)
            for (s in f.indices) for (k in f[0].indices) { total++; if (f[s][k] != d[s][k]) errors++ }
        }
        println("pixel errors $errors / $total")
        assertEquals(0, errors)
    }

    @Test fun orientationRules() {
        // vertical: slice = bitmap column, tone k = row k
        val v = WaterfallText.frames(bm, vertical = true)
        assertEquals(bm[0].size, v.size); assertEquals(bm.size, v[0].size)
        assertEquals(bm[2][4], v[4][2])
        // horizontal newest-on-top sends the bottom row first
        val h = WaterfallText.frames(bm, vertical = false, newestTop = true)
        assertTrue(h[0].contentEquals(bm[bm.size - 1]))
        // LSB mirrors the tone order
        val hl = WaterfallText.frames(bm, vertical = false, newestTop = true, lsb = true)
        assertTrue(hl[0].contentEquals(bm[bm.size - 1].reversedArray()))
    }

    @Test fun normalisedAndInfo() {
        val (a, info) = WaterfallText.build(bm, true, 400.0, 150.0, 80.0, true, false, TX_SR)
        val pk = a.maxOf { kotlin.math.abs(it) }
        assertEquals(0.9f, pk, 1e-4f)
        assertEquals(5, info.tones); assertEquals(400.0, info.lo, 0.0); assertEquals(1000.0, info.hi, 0.0)
        assertEquals((0.3 + 10 * 0.08) * TX_SR, a.size.toDouble(), 2.0)
    }

    @Test fun cropAndWav() {
        val a = arrayOf(BooleanArray(4), booleanArrayOf(false, true, true, false), BooleanArray(4))
        val c = WaterfallText.crop(a)
        assertEquals(1, c.size); assertEquals(2, c[0].size)
        val w = WaterfallText.wav(FloatArray(10), 48000)
        assertEquals(64, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4))
    }
}
