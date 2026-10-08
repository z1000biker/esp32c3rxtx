package gr.sv1eex.c3trx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

const val AUDIO_SR = 48000
const val TX_SR = 32000
const val FFT_N = 1024

/** Mono float ring between the DSP thread and the AudioTrack thread, with drift control. */
class AudioRing {
    private val buf = FloatArray(AUDIO_SR)        // 1 s
    private var r = 0; private var w = 0; private var n = 0
    private val lock = Object()

    fun push(a: FloatArray) = synchronized(lock) {
        for (v in a) {
            buf[w] = v; w = (w + 1) % buf.size
            if (n < buf.size) n++ else r = (r + 1) % buf.size
        }
        if (n > AUDIO_SR * 0.4) {                      // drift: drop back to 100 ms
            val keep = (AUDIO_SR * 0.1).toInt()
            r = (w - keep + buf.size) % buf.size; n = keep
        }
    }

    fun pull(dst: FloatArray): Int = synchronized(lock) {
        val m = minOf(dst.size, n)
        for (i in 0 until m) { dst[i] = buf[r]; r = (r + 1) % buf.size }
        n -= m
        for (i in m until dst.size) dst[i] = 0f
        m
    }

    fun clear() = synchronized(lock) { r = 0; w = 0; n = 0 }
}

class AudioOut {
    val ring = AudioRing()
    @Volatile private var run = true
    private val track: AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(AUDIO_SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(AUDIO_SR)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 4 * 2048))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    } catch (e: Exception) { null }

    private val thread = Thread({
        val t = track ?: return@Thread
        t.play()
        val blk = FloatArray(512)
        while (run) {
            ring.pull(blk)
            t.write(blk, 0, blk.size, AudioTrack.WRITE_BLOCKING)
        }
        t.stop(); t.release()
    }, "c3trx-audio")

    init { thread.start() }
    val ok get() = track != null
    fun close() { run = false }
}

/** Snapshot handed to the display: averaged spectrum (dB, fftshifted) + the parameters it was made with. */
class SpecFrame(val db: FloatArray, val rate: Int)

/**
 * DSP thread: drains the IQ queue, runs front end + demod + resampler, and every ~15 ms
 * computes a 1024-point spectrum (Hann, averaged) for the spectrum/waterfall view.
 */
class Engine(private val audio: AudioOut, private val onSpectrum: (SpecFrame) -> Unit) {
    val front = Front()
    val dem = Demod()
    private val rs = Resampler(AUDIO_SR.toDouble())
    @Volatile var link: Link? = null
    @Volatile private var run = true
    @Volatile var resetSpectrum = false

    private val ringI = FloatArray(FFT_N); private val ringQ = FloatArray(FFT_N)
    private var rpos = 0; private var fill = 0; private var since = 0
    private val win = hanning(FFT_N)
    private val fft = Fft(FFT_N)
    private val re = DoubleArray(FFT_N); private val im = DoubleArray(FFT_N)
    private var avg: FloatArray? = null
    private var lastSpec = 0L
    private val outRate = DoubleArray(1)

    private val thread = Thread({ loop() }, "c3trx-dsp").also { it.priority = Thread.MAX_PRIORITY; it.start() }

    private fun loop() {
        while (run) {
            val l = link
            if (l == null) { Thread.sleep(20); continue }
            val b = l.iq.poll(20, TimeUnit.MILLISECONDS)
            if (b != null) {
                val z = b.iq
                front.process(z)
                val a = dem.process(z, l.rate, outRate)
                if (a.isNotEmpty()) audio.ring.push(rs.process(a, outRate[0]))
                val n = z.size / 2
                for (k in 0 until n) {
                    ringI[rpos] = z[2 * k]; ringQ[rpos] = z[2 * k + 1]; rpos = (rpos + 1) % FFT_N
                }
                fill = minOf(FFT_N, fill + n); since += n
            }
            if (resetSpectrum) { resetSpectrum = false; avg = null }
            val now = System.nanoTime()
            if (fill >= FFT_N && since >= FFT_N / 2 && l.rate > 0 && now - lastSpec > 15_000_000L) {
                lastSpec = now; since = 0
                spectrum(l.rate)
            }
        }
    }

    private fun spectrum(rate: Int) {
        for (k in 0 until FFT_N) {
            val p = (rpos + k) % FFT_N
            re[k] = ringI[p] * win[k]; im[k] = ringQ[p] * win[k]
        }
        fft.transform(re, im)
        val first = avg == null
        val a = avg ?: FloatArray(FFT_N).also { avg = it }
        val out = FloatArray(FFT_N)
        for (k in 0 until FFT_N) {
            val src = (k + FFT_N / 2) % FFT_N                 // fftshift
            val mag = (20 * log10(sqrt(re[src] * re[src] + im[src] * im[src]) / (FFT_N / 2) + 1e-10)).toFloat()
            a[k] = if (first) mag else a[k] + 0.35f * (mag - a[k])
            out[k] = a[k]
        }
        onSpectrum(SpecFrame(out, rate))
    }

    fun close() { run = false }
}
