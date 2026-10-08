package gr.sv1eex.c3trx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Boolean pixel matrix: m[row][col]. */
typealias Bits = Array<BooleanArray>

class WfInfo(val tones: Int, val slices: Int, val lo: Double, val hi: Double, val seconds: Double)

/**
 * Paints text on a waterfall. The text is rendered to a 1-bit bitmap; each time slice sends one line of
 * pixels, and every lit pixel is a sine at base + k*spacing Hz (USB: RF = carrier + audio).
 * Pure Kotlin so it can be unit-tested; the font rendering lives in [TextBitmap].
 */
object WaterfallText {

    /** -> matrix (slices in transmit order, tones low -> high audio frequency). */
    fun frames(bm: Bits, vertical: Boolean, newestTop: Boolean = true, lsb: Boolean = false): Bits {
        val h = bm.size; val w = bm[0].size
        var f: Bits = if (vertical) {
            // text runs along time: one slice per bitmap column, left to right; tone k = row k
            Array(w) { c -> BooleanArray(h) { r -> bm[r][c] } }.let { t ->
                if (!newestTop) Array(t.size) { s -> t[s].reversedArray() } else t
            }
        } else {
            // text runs along frequency: bottom line first so the top line ends up on top
            if (newestTop) Array(h) { s -> bm[h - 1 - s].copyOf() } else Array(h) { s -> bm[s].copyOf() }
        }
        if (lsb) f = Array(f.size) { s -> f[s].reversedArray() }   // LSB mirrors audio in RF: undo it
        return f
    }

    fun synth(f: Bits, sr: Int, base: Double, spacing: Double, sliceS: Double, leadS: Double = 0.15): FloatArray {
        val nSl = f.size; val nT = f[0].size
        val l = max(1, (sliceS * sr).roundToInt()); val lead = (leadS * sr).toInt()
        val n = lead + nSl * l + lead
        val out = DoubleArray(n)
        val r = max(1, (min(0.010, 0.15 * sliceS) * sr).toInt())
        // raised-cosine edge = cumulative sum of a normalised Hann kernel of length 2r+1
        val ker = hanning(2 * r + 1); val ks = ker.sum()
        val kc = DoubleArray(2 * r + 1); var acc = 0.0
        for (j in ker.indices) { acc += ker[j] / ks; kc[j] = acc }
        val rng = java.util.Random(7)
        val env = DoubleArray(n)
        for (k in 0 until nT) {
            if ((0 until nSl).none { f[it][k] }) continue
            java.util.Arrays.fill(env, 0.0)
            for (s in 0 until nSl) if (f[s][k]) java.util.Arrays.fill(env, lead + s * l, lead + (s + 1) * l, 1.0)
            // smooth each on/off edge (same as numpy convolve(env, ker, "same") for a step input)
            for (s in 0..nSl) {
                val before = s > 0 && f[s - 1][k]; val after = s < nSl && f[s][k]
                if (before == after) continue
                val e = lead + s * l; val sign = if (after) 1.0 else -1.0
                for (t in max(0, e - r) until min(n, e + r + 1)) {
                    val step = if (t >= e) 1.0 else 0.0
                    env[t] += sign * (kc[t + r - e] - step)
                }
            }
            val ph0 = rng.nextDouble() * 2 * PI
            val w = 2 * PI * (base + k * spacing) / sr
            for (t in 0 until n) if (env[t] != 0.0) out[t] += env[t] * sin(w * t + ph0)
        }
        var pk = 0.0
        for (v in out) pk = max(pk, abs(v))
        if (pk == 0.0) pk = 1.0
        return FloatArray(n) { (0.9 * out[it] / pk).toFloat() }
    }

    fun info(bm: Bits, vertical: Boolean, base: Double, spacing: Double, sliceMs: Double): WfInfo {
        val tones = if (vertical) bm.size else bm[0].size
        val sl = if (vertical) bm[0].size else bm.size
        return WfInfo(tones, sl, base, base + (tones - 1) * spacing, sl * sliceMs / 1000 + 0.3)
    }

    fun build(bm: Bits, vertical: Boolean, base: Double, spacing: Double, sliceMs: Double,
              newestTop: Boolean, lsb: Boolean, sr: Int): Pair<FloatArray, WfInfo> {
        val f = frames(bm, vertical, newestTop, lsb)
        val a = synth(f, sr, base, spacing, sliceMs / 1000.0)
        val i = info(bm, vertical, base, spacing, sliceMs)
        return a to WfInfo(i.tones, i.slices, i.lo, i.hi, a.size.toDouble() / sr)
    }

    class Spectrogram(val db: Array<FloatArray>, val fLo: Double, val fHi: Double, val heightHz: Double)

    /** Spectrogram of the generated audio (rows = time, oldest first), ~1 FFT bin per pixel, like the desktop preview. */
    fun spectrogram(audio: FloatArray, sr: Int, info: WfInfo): Spectrogram {
        val sp = if (info.tones > 1) max(1.0, (info.hi - info.lo) / (info.tones - 1)) else 100.0
        val n = 1 shl kotlin.math.round(kotlin.math.log2(max(128.0, sr / sp))).toInt()
        val hop = max(16, n / 4)
        val win = hanning(n); val fft = Fft(n)
        val re = DoubleArray(n); val im = DoubleArray(n)
        val df = sr.toDouble() / n
        val lo = max(0.0, info.lo - 3 * sp); val hi = info.hi + 3 * sp
        val k0 = kotlin.math.ceil(lo / df).toInt(); val k1 = min(n / 2, kotlin.math.floor(hi / df).toInt())
        val rows = ArrayList<FloatArray>()
        var i = 0
        while (i < max(1, audio.size - n)) {
            for (j in 0 until n) { re[j] = (if (i + j < audio.size) audio[i + j] else 0f) * win[j]; im[j] = 0.0 }
            fft.transform(re, im)
            rows.add(FloatArray(k1 - k0 + 1) { kk ->
                val k = k0 + kk
                (20 * kotlin.math.log10(kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-6)).toFloat()
            })
            i += hop
        }
        val t = audio.size.toDouble() / sr
        val d = max(1e-3, (t - 0.3) / max(1, info.slices))
        return Spectrogram(rows.toTypedArray(), k0 * df, k1 * df, t / d * sp)
    }

    /** Crop a full bitmap to its lit bounding box (1x1 empty if nothing is lit). */
    fun crop(a: Bits): Bits {
        val rows = a.indices.filter { r -> a[r].any { it } }
        if (rows.isEmpty()) return arrayOf(BooleanArray(1))
        val cols = a[0].indices.filter { c -> a.any { it[c] } }
        return Array(rows.last() - rows.first() + 1) { r ->
            BooleanArray(cols.last() - cols.first() + 1) { c -> a[rows.first() + r][cols.first() + c] }
        }
    }

    /** 48 kHz/16-bit mono WAV bytes. */
    fun wav(a: FloatArray, sr: Int): ByteArray {
        val data = java.nio.ByteBuffer.allocate(44 + a.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(36 + a.size * 2); data.put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()); data.putInt(16); data.putShort(1); data.putShort(1)
        data.putInt(sr); data.putInt(sr * 2); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(a.size * 2)
        for (v in a) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return data.array()
    }
}

/** Font rendering with the Android canvas (bold, no anti-aliasing), threshold at 50 %. */
object TextBitmap {
    fun render(text: String, heightPx: Int): Bits {
        val p = android.graphics.Paint()
        p.isAntiAlias = false
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = max(4, heightPx).toFloat()
        p.color = android.graphics.Color.WHITE
        val fm = p.fontMetricsInt
        val w = max(1, p.measureText(text).toInt() + 4)
        val h = fm.descent - fm.ascent + 2
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawText(text, 2f, (1 - fm.ascent).toFloat(), p)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val a = Array(h) { r -> BooleanArray(w) { c -> (px[r * w + c] and 0xFF) > 127 } }
        return WaterfallText.crop(a)
    }
}
