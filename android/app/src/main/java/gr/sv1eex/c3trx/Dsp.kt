package gr.sv1eex.c3trx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// All complex sample arrays are interleaved: [I0, Q0, I1, Q1, ...]

val MODE_BW = mapOf("AM" to 6000, "FM" to 12000, "USB" to 2700, "LSB" to 2700, "CW" to 500)
val MODE_BFO = mapOf("AM" to 0, "FM" to 0, "USB" to 1500, "LSB" to -1500, "CW" to 700)

fun blackmanLpf(cut: Double, fs: Double, n: Int): FloatArray {
    val fc = cut / fs
    val h = DoubleArray(n)
    var sum = 0.0
    for (i in 0 until n) {
        val k = i - (n - 1) / 2.0
        val x = 2 * fc * k
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
        val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
        h[i] = 2 * fc * sinc * w; sum += h[i]
    }
    return FloatArray(n) { (h[it] / sum).toFloat() }
}

/** DC removal + blind IQ amplitude/phase balance, applied before spectrum and demod (in place). */
class Front {
    @Volatile var dcOn = true
    @Volatile var iqOn = true
    private var dcI = 0.0; private var dcQ = 0.0
    private var ii = 1e-6; private var qq = 1e-6; private var iqm = 0.0
    @Volatile var gain = 1.0; @Volatile var phaseDeg = 0.0

    fun process(z: FloatArray) {
        val n = z.size / 2
        if (n == 0) return
        if (dcOn) {
            var mi = 0.0; var mq = 0.0
            for (k in 0 until n) { mi += z[2 * k]; mq += z[2 * k + 1] }
            dcI = 0.98 * dcI + 0.02 * mi / n; dcQ = 0.98 * dcQ + 0.02 * mq / n
            val fi = dcI.toFloat(); val fq = dcQ.toFloat()
            for (k in 0 until n) { z[2 * k] -= fi; z[2 * k + 1] -= fq }
        }
        if (iqOn) {
            var si = 0.0; var sq = 0.0; var sx = 0.0
            for (k in 0 until n) {
                val i = z[2 * k].toDouble(); val q = z[2 * k + 1].toDouble()
                si += i * i; sq += q * q; sx += i * q
            }
            val a = 0.01
            ii += a * (si / n - ii); qq += a * (sq / n - qq); iqm += a * (sx / n - iqm)
            val g = sqrt(qq / max(ii, 1e-12))
            val s = (iqm / sqrt(max(ii * qq, 1e-24))).coerceIn(-0.5, 0.5)
            val c = sqrt(1 - s * s)
            gain = g; phaseDeg = Math.toDegrees(asin(s))
            for (k in 0 until n) {
                val i = z[2 * k]; val q = z[2 * k + 1]
                z[2 * k + 1] = ((q / g - i * s) / c).toFloat()
            }
        }
    }
}

/** VFO/BFO shift -> boxcar decimation to >= 16 kS/s -> complex FIR -> AM/FM/SSB/CW -> AGC -> squelch. */
class Demod {
    @Volatile var mode = "USB"
    @Volatile var bfo = 1500.0
    @Volatile var bw = 2700.0
    @Volatile var agc = "fast"
    @Volatile var vol = 0.6
    @Volatile var sql = 0
    @Volatile var vfo = 0.0
    @Volatile var levelDb = -120.0
    @Volatile private var resetReq = true

    private var key = ""
    private var h = FloatArray(1)
    private var histI = FloatArray(0); private var histQ = FloatArray(0)   // FIR delay line (2x length, mirrored)
    private var hpos = 0
    private var ph = 0.0; private var phB = 0.0
    private var accI = 0.0; private var accQ = 0.0; private var accN = 0
    private var prevI = 1f; private var prevQ = 0f
    private var dc = 0.0; private var env = 0.05

    fun reset() { resetReq = true }

    /** Returns audio at the decimated rate; [outRate] receives that rate. */
    fun process(z: FloatArray, rate: Int, outRate: DoubleArray): FloatArray {
        if (rate <= 0 || z.isEmpty()) { outRate[0] = 16000.0; return FloatArray(0) }
        if (resetReq) {
            resetReq = false; key = ""; ph = 0.0; phB = 0.0; accI = 0.0; accQ = 0.0; accN = 0
            prevI = 1f; prevQ = 0f; dc = 0.0; env = 0.05; levelDb = -120.0
        }
        val d = max(1, rate / 16000)
        val fsd = rate.toDouble() / d
        val bwc = min(bw, 0.9 * fsd)
        val k = "$d/${fsd.roundToInt()}/$bwc"
        if (k != key) {
            val nt = (min(255, max(31, (5.5 * fsd / 250).roundToInt()))) or 1
            h = blackmanLpf(bwc / 2, fsd, nt)
            histI = FloatArray(2 * nt); histQ = FloatArray(2 * nt); hpos = 0
            accI = 0.0; accQ = 0.0; accN = 0; key = k
        }
        outRate[0] = fsd
        val n = z.size / 2
        val w = 2 * PI * (vfo + bfo) / rate
        val wb = 2 * PI * bfo / fsd
        val nt = h.size
        val out = FloatArray(n / d + 2)
        var no = 0
        var pAcc = 0.0
        val md = mode
        val ssb = md == "USB" || md == "LSB" || md == "CW"
        for (s in 0 until n) {
            // 1. mix by -(VFO+BFO)
            val c = cos(ph); val sn = sin(ph)
            ph += w; if (ph > PI) ph -= 2 * PI else if (ph < -PI) ph += 2 * PI
            val xi = z[2 * s]; val xq = z[2 * s + 1]
            accI += xi * c + xq * sn
            accQ += xq * c - xi * sn
            // 2. boxcar decimate
            if (++accN < d) continue
            val yi0 = (accI / d).toFloat(); val yq0 = (accQ / d).toFloat()
            accI = 0.0; accQ = 0.0; accN = 0
            // 3. FIR (mirrored delay line: hist[p] == hist[p+nt])
            hpos = if (hpos == 0) nt - 1 else hpos - 1
            histI[hpos] = yi0; histI[hpos + nt] = yi0
            histQ[hpos] = yq0; histQ[hpos + nt] = yq0
            var yi = 0f; var yq = 0f
            for (t in 0 until nt) { val hv = h[t]; yi += hv * histI[hpos + t]; yq += hv * histQ[hpos + t] }
            pAcc += (yi * yi + yq * yq).toDouble()
            // 4. demod
            var o: Float
            when {
                md == "AM" -> {
                    val e = sqrt((yi * yi + yq * yq).toDouble())
                    dc = 0.9995 * dc + 0.0005 * e; o = (e - dc).toFloat()
                }
                md == "FM" -> {
                    // angle(y * conj(prev)) / pi
                    val re = yi * prevI + yq * prevQ; val im = yq * prevI - yi * prevQ
                    o = (atan2(im.toDouble(), re.toDouble()) / PI).toFloat(); prevI = yi; prevQ = yq
                }
                else -> {   // SSB/CW: shift back by +BFO, real part (rejects opposite sideband)
                    o = (yi * cos(phB) - yq * sin(phB)).toFloat()
                }
            }
            if (ssb) { phB += wb; if (phB > PI) phB -= 2 * PI else if (phB < -PI) phB += 2 * PI }
            // 5. AGC
            val a = agc
            if (a != "off") {
                val rel = if (a == "fast") 0.0015 else 0.0002
                val v = abs(o).toDouble()
                env += (if (v > env) 0.05 else rel) * (v - env)
                o = (o * 0.3 / max(env, 1e-4)).toFloat()
            } else o *= 4f
            out[no++] = o
        }
        if (no > 0) levelDb = 10 * log10(pAcc / no + 1e-12)
        val v = vol.toFloat()
        val mute = sql != 0 && levelDb < -100 + sql
        for (i in 0 until no) out[i] = if (mute) 0f else (out[i] * v).coerceIn(-1f, 1f)
        return out.copyOf(no)
    }
}

/** In-place radix-2 complex FFT on interleaved data. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var size = 2
        while (size <= n) {
            val half = size / 2; val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]; val s = sinT[k]
                    val tr = re[j + half] * c - im[j + half] * s
                    val ti = re[j + half] * s + im[j + half] * c
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/** numpy.hanning (symmetric). */
fun hanning(n: Int) = DoubleArray(n) { if (n == 1) 1.0 else 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

/** Fractional linear resampler, same scheme as the desktop AudioOut.push(). */
class Resampler(private val outRate: Double) {
    private var frac = 0.0
    private var last = 0f

    fun process(a: FloatArray, fs: Double): FloatArray {
        if (a.isEmpty()) return a
        val step = fs / outRate
        val res = FloatArray(((a.size - frac) / step).toInt() + 2)
        var n = 0
        var t = frac                      // position in a[]; -1 == last sample of the previous block
        while (t < a.size) {
            val i = kotlin.math.floor(t).toInt()
            val f = (t - i).toFloat()
            val s0 = if (i < 0) last else a[i]
            val s1 = if (i + 1 < a.size) a[i + 1] else a[a.size - 1]
            res[n++] = s0 + (s1 - s0) * f
            t += step
        }
        frac = t - a.size
        last = a[a.size - 1]
        return res.copyOf(n)
    }
}
