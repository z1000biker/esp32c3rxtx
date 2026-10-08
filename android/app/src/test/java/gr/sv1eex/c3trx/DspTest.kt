package gr.sv1eex.c3trx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Port of the desktop --selftest: +1 kHz carrier, DC offset, Q 15 % low, 6° skew, 48 kS/s. */
class DspTest {
    private val rnd = java.util.Random(3)
    private var ph = 0.0

    private fun mockFrame(n: Int = 128): FloatArray {
        val z = FloatArray(2 * n); val w = 2 * PI * 1000 / 48000
        for (k in 0 until n) {
            val p = ph + w * k
            z[2 * k] = (0.3 * cos(p) + 0.05 + 0.001 * rnd.nextGaussian()).toFloat()
            z[2 * k + 1] = (0.255 * sin(p + 0.105) - 0.03 + 0.001 * rnd.nextGaussian()).toFloat()
        }
        ph = (ph + w * n) % (2 * PI)
        return z
    }

    /** Run [seconds] of signal through front+demod, return (rms dB, zero-crossing tone Hz) of the last half. */
    private fun run(front: Front, dem: Demod, seconds: Double): Pair<Double, Double> {
        val out = ArrayList<Float>(); val r = DoubleArray(1)
        repeat((seconds * 48000 / 128).toInt()) {
            val z = mockFrame(); front.process(z)
            out.addAll(dem.process(z, 48000, r).toList())
        }
        val o = out.subList(out.size / 2, out.size)
        val rms = sqrt(o.sumOf { (it * it).toDouble() } / o.size)
        var zc = 0
        for (i in 1 until o.size) if ((o[i] < 0) != (o[i - 1] < 0)) zc++
        return 20 * log10(rms + 1e-9) to zc / 2.0 / (o.size / r[0])
    }

    private fun warmFront(): Front { val f = Front(); repeat(2000) { f.process(mockFrame()) }; return f }

    @Test fun frontEstimatesIqImbalance() {
        val f = warmFront()
        assertEquals(0.85, f.gain, 0.01)
        assertEquals(6.0, f.phaseDeg, 0.3)
    }

    @Test fun imageAndDcSuppressed() {
        val f = warmFront()
        val fft = Fft(FFT_N); val win = hanning(FFT_N)
        val re = DoubleArray(FFT_N); val im = DoubleArray(FFT_N)
        val zs = FloatArray(2 * FFT_N)
        for (b in 0 until 8) { val z = mockFrame(); f.process(z); System.arraycopy(z, 0, zs, b * 256, 256) }
        for (k in 0 until FFT_N) { re[k] = zs[2 * k] * win[k]; im[k] = zs[2 * k + 1] * win[k] }
        fft.transform(re, im)
        fun db(hz: Double): Double {
            val c = Math.floorMod(Math.round(hz / 48000 * FFT_N).toInt(), FFT_N)
            var m = 0.0
            for (d in -1..1) { val k = Math.floorMod(c + d, FFT_N); m = maxOf(m, sqrt(re[k] * re[k] + im[k] * im[k])) }
            return 20 * log10(m / (FFT_N / 2) + 1e-10)
        }
        val sig = db(1000.0); val img = db(-1000.0); val dc = db(0.0)
        println("signal $sig image $img dc $dc")
        assertTrue("signal $sig", sig > -15)
        assertTrue("image rejection ${sig - img}", sig - img > 50)
        assertTrue("dc ${sig - dc}", sig - dc > 50)
    }

    @Test fun ssbVfoAndSidebands() {
        val f = warmFront()
        val d = Demod().apply { mode = "USB"; bfo = 1500.0; bw = 2700.0; agc = "off"; vol = 0.6 }
        val (r0, t0) = run(f, d, 1.0)
        println("USB vfo 0: $r0 dB, $t0 Hz")
        assertEquals(1000.0, t0, 25.0)
        d.vfo = 300.0; d.reset()
        val (_, t3) = run(f, d, 1.0)
        println("USB vfo 300: $t3 Hz")
        assertEquals(700.0, t3, 25.0)
        d.vfo = 1000.0; d.reset()
        val (rz, _) = run(f, d, 1.0)
        println("USB zero beat: $rz dB")
        assertTrue("zero beat $rz vs $r0", rz < r0 - 30)
        d.vfo = 0.0; d.mode = "LSB"; d.bfo = -1500.0; d.reset()
        val (rl, _) = run(f, d, 1.0)
        println("LSB: $rl dB")
        assertTrue("sideband rejection ${r0 - rl}", r0 - rl > 35)
    }

    @Test fun cwAmFm() {
        val f = warmFront()
        val d = Demod().apply { mode = "CW"; bfo = 700.0; bw = 500.0; agc = "off" }
        val (rc, tc) = run(f, d, 1.0)
        println("CW: $rc dB $tc Hz")
        assertEquals(1000.0, tc, 25.0)
        d.mode = "AM"; d.bfo = 0.0; d.bw = 6000.0; d.reset()
        val (ra, _) = run(f, d, 1.0); println("AM: $ra dB")
        d.mode = "FM"; d.bw = 12000.0; d.reset()
        val (rf, _) = run(f, d, 1.0); println("FM: $rf dB")
        assertTrue(rf > -40)
    }

    @Test fun decimatedRateMatchesHardware() {
        val d = Demod(); val r = DoubleArray(1)
        d.process(FloatArray(2 * 1024), 140500, r)
        assertEquals(140500.0 / 8, r[0], 1e-6)
    }

    @Test fun resamplerRatio() {
        val rs = Resampler(48000.0)
        var n = 0
        repeat(100) { n += rs.process(FloatArray(160) { 0.1f }, 16000.0).size }
        assertEquals(48000.0, n.toDouble(), 3.0)       // 1 s in, 1 s out
    }

    @Test fun parserFramesLinesAndGarbage() {
        val iqs = ArrayList<FloatArray>(); val lines = ArrayList<String>(); val rates = ArrayList<Int>()
        val p = FrameParser({ z, r -> iqs.add(z); rates.add(r) }, { lines.add(it) })
        val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("C3TRX READY\r\n".toByteArray())
        bb.put(byteArrayOf(1, 2, 3))                         // garbage
        bb.put("IQF1".toByteArray()); bb.putShort(2); bb.putShort(0); bb.putInt(140500)
        bb.putShort(16384); bb.putShort(-16384); bb.putShort(0); bb.putShort(32767)
        bb.put("RXEND\r\n".toByteArray())
        val all = bb.array().copyOf(bb.position())
        for (b in all) p.feed(byteArrayOf(b), 1)            // byte by byte: worst-case split
        assertEquals(listOf("C3TRX READY", "RXEND"), lines)
        assertEquals(1, iqs.size); assertEquals(140500, rates[0])
        assertEquals(0.5f, iqs[0][0], 1e-6f); assertEquals(-0.5f, iqs[0][1], 1e-6f)
        assertEquals(3L, p.dropped)
    }
}
