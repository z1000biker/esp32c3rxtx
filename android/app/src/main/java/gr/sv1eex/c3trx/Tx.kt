package gr.sv1eex.c3trx

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.sin

/** Streams 32 kS/s int16 LE PCM to the firmware after "TX ...". Sources: mic, tone, carrier, text. */
class TxFeeder(
    private val link: Link,
    private val source: String,
    private val toneHz: Double,
    private val samples: FloatArray?,
    private val onLog: (String) -> Unit,
) {
    @Volatile private var run = true
    private var mic: AudioRecord? = null

    private val thread = Thread({ loop() }, "c3trx-tx")

    init {
        if (source == "mic") mic = openMic()
        thread.start()
    }

    @SuppressLint("MissingPermission")   // checked by MainActivity before PTT
    private fun openMic(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(TX_SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(MediaRecorder.AudioSource.MIC, TX_SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 640 * 2 * 4)).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw IllegalStateException("AudioRecord init") }
            it.startRecording()
        }
    } catch (e: Exception) { onLog("! microphone: ${e.message}"); null }

    private fun pcm(a: FloatArray, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (a[i].coerceIn(-1f, 1f) * 32767).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun loop() {
        try {
            if (source == "mic") {
                val m = mic ?: return
                val s = ShortArray(640)
                while (run) {
                    val n = m.read(s, 0, s.size)
                    if (n <= 0) continue
                    val b = ByteArray(n * 2)
                    for (i in 0 until n) { b[2 * i] = s[i].toInt().toByte(); b[2 * i + 1] = (s[i].toInt() shr 8).toByte() }
                    link.send(b)
                }
                m.release()
                return
            }
            val t0 = System.nanoTime(); var sent = 0L; var ph = 0.0; var pos = 0
            val buf = FloatArray(4096)
            while (run) {
                val due = ((System.nanoTime() - t0) / 1e9 * TX_SR).toLong() + 2560 - sent
                if (due <= 0) { Thread.sleep(10); continue }
                val n = minOf(due, 4096L).toInt()
                var m = n
                when (source) {
                    "text" -> {
                        val src = samples ?: return
                        m = minOf(n, src.size - pos)
                        if (m <= 0) { run = false; break }          // message done: firmware ends TX on silence
                        System.arraycopy(src, pos, buf, 0, m); pos += m
                    }
                    "tone" -> {
                        val w = 2 * PI * toneHz / TX_SR
                        for (i in 0 until n) { buf[i] = (0.5 * sin(ph)).toFloat(); ph += w }
                        ph %= 2 * PI
                    }
                    else -> java.util.Arrays.fill(buf, 0, n, 0f)
                }
                sent += m
                link.send(pcm(buf, m))
            }
        } catch (e: Exception) {
            if (run) onLog("! TX: ${e.message}")
        }
    }

    fun stop() {
        run = false
        mic?.let { try { it.stop() } catch (_: Exception) {} }   // the TX thread releases it
    }
}
