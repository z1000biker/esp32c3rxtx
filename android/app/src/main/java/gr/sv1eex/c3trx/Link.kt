package gr.sv1eex.c3trx

import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.abs

/**
 * Splits the byte stream from the firmware into IQF1 frames and text lines.
 * IQ frames: "IQF1", u16 count, u16 flags, u32 rate, count x (int16 I, int16 Q), little-endian.
 */
class FrameParser(
    private val onIq: (iq: FloatArray, rate: Int) -> Unit,
    private val onLine: (String) -> Unit,
) {
    private var buf = ByteArray(1 shl 18)
    private var len = 0
    var dropped = 0L

    fun feed(src: ByteArray, n: Int) {
        if (len + n > buf.size) {
            if (len + n > (1 shl 22)) { len = 0 }                 // garbage flood: start over
            else buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        }
        System.arraycopy(src, 0, buf, len, n); len += n
        var pos = 0
        while (true) {
            val m = find(MAGIC, pos)
            val nl = findByte('\n'.code.toByte(), pos)
            if (m >= 0 && (nl < 0 || m < nl)) {
                if (m > pos) { dropped += (m - pos); pos = m; continue }
                if (len - pos < 12) break
                val cnt = u16(pos + 4)
                val rate = u32(pos + 8)
                if (cnt == 0 || cnt > 4096) { pos += 4; continue }
                val tot = 12 + cnt * 4
                if (len - pos < tot) break
                val iq = FloatArray(cnt * 2)
                var o = pos + 12
                for (k in 0 until cnt * 2) {
                    val s = ((buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)).toShort()
                    iq[k] = s / 32768f; o += 2
                }
                pos += tot
                onIq(iq, rate)
                continue
            }
            if (nl < 0) {
                if (len - pos > 200000) pos = len - 16
                break
            }
            val s = String(buf, pos, nl - pos, Charsets.UTF_8).trim()
            pos = nl + 1
            if (s.isNotEmpty()) onLine(s)
        }
        if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos }
    }

    private fun u16(i: Int) = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

    private fun findByte(b: Byte, from: Int): Int {
        for (i in from until len) if (buf[i] == b) return i
        return -1
    }

    private fun find(p: ByteArray, from: Int): Int {
        var i = from
        val last = len - p.size
        while (i <= last) {
            if (buf[i] == p[0] && buf[i + 1] == p[1] && buf[i + 2] == p[2] && buf[i + 3] == p[3]) return i
            i++
        }
        return -1
    }

    companion object { val MAGIC = "IQF1".toByteArray() }
}

class IqBlock(val iq: FloatArray)

/** Reader thread + command writer. Lines are delivered on the reader thread; the UI must re-post them. */
class Link(private val ser: SerialIo, private val onLine: (String) -> Unit, private val onError: (String) -> Unit) {
    val iq = ArrayBlockingQueue<IqBlock>(400)
    @Volatile var rate = 0
    @Volatile var running = true
    private val parser = FrameParser(::gotIq) { s -> onLine("< $s") }
    var droppedQ = 0L
    val dropped get() = parser.dropped + droppedQ

    private val thread = Thread({
        val b = ByteArray(16384)
        try {
            while (running) {
                val n = ser.read(b)
                if (n > 0) parser.feed(b, n)
            }
        } catch (e: Exception) {
            if (running) onError("serial error: ${e.message}")
        }
    }, "c3trx-reader")

    init { thread.start() }

    private fun gotIq(z: FloatArray, r: Int) {
        if (r > 0) rate = if (rate == 0 || abs(r - rate) > 0.05 * rate) r else rate
        if (!iq.offer(IqBlock(z))) droppedQ += z.size / 2
    }

    fun send(b: ByteArray) = ser.write(b)
    fun sendLine(s: String) { onLine("> $s"); ser.write((s + "\n").toByteArray()) }

    fun close() {
        running = false
        try { ser.write(byteArrayOf(3)) } catch (_: Exception) {}
        ser.close()
    }
}
