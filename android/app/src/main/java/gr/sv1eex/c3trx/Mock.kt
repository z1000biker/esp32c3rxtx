package gr.sv1eex.c3trx

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Byte pipe to the board. read() blocks for at most a few tens of ms and returns 0 on timeout. */
interface SerialIo {
    fun write(b: ByteArray)
    fun read(dst: ByteArray): Int
    fun close()
}

/** Simulated C3TRX 1: +1 kHz carrier at 48 kS/s with DC offset and IQ imbalance (same as the desktop --mock). */
class MockSerial : SerialIo {
    private val out = java.io.ByteArrayOutputStream()
    private val lock = Object()
    private var rx = false
    private var ph = 0.0
    private var t = System.nanoTime()
    private var tx = false
    private var txN = 0L
    private var txLast = 0L
    private val rnd = java.util.Random(1)

    private fun put(b: ByteArray) = synchronized(lock) { out.write(b) }
    private fun put(s: String) = put(s.toByteArray())

    override fun write(b: ByteArray) {
        val head = String(b, 0, minOf(4, b.size), Charsets.ISO_8859_1)
        if (tx && !(b.size == 1 && b[0] == 3.toByte()) &&
            head !in setOf("INFO", "FREQ", "RX 1", "TX U", "TX L", "TX A", "TX F")) {
            txN += b.size / 2; txLast = System.nanoTime(); return
        }
        if (b.size == 1 && b[0] == 3.toByte()) { rx = false; put("RXEND\r\n"); return }
        for (raw in String(b, Charsets.ISO_8859_1).split("\n")) {
            val line = raw.trim()
            when {
                line == "INFO" -> put("C3TRX 1 RXIQ TXAM TXFM TXUSB TXLSB FREQ=2400000 (MOCK)\r\n")
                line.startsWith("FREQ ") -> put("OK FREQ ${line.substring(5)}\r\n")
                line.startsWith("RX ") -> { put("RXREADY 128 2400000\r\n"); rx = true; t = System.nanoTime() }
                line.startsWith("TX ") -> {
                    put("TXREADY ${line.split(" ")[1]} 2400000 32000\r\n")
                    tx = true; txN = 0; txLast = System.nanoTime()
                }
            }
        }
    }

    override fun read(dst: ByteArray): Int {
        if (tx && System.nanoTime() - txLast > 300_000_000L) { tx = false; put("TXEND samples=$txN underruns=0\r\n") }
        if (rx) {
            val now = System.nanoTime()
            val nfr = ((now - t) / 1e9 * 48000 / 128).toInt()
            t += (nfr * 128 / 48000.0 * 1e9).toLong()
            val w = 2 * PI * 1000 / 48000
            repeat(minOf(nfr, 50)) {
                val bb = ByteBuffer.allocate(12 + 512).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.put("IQF1".toByteArray()); bb.putShort(128.toShort()); bb.putShort(0.toShort()); bb.putInt(48000)
                for (k in 0 until 128) {
                    val p = ph + w * k
                    val i = 0.3 * cos(p) + 0.05 + 0.001 * rnd.nextGaussian()
                    val q = 0.255 * sin(p + 0.105) - 0.03 + 0.001 * rnd.nextGaussian()
                    bb.putShort((i * 32767).toInt().toShort()); bb.putShort((q * 32767).toInt().toShort())
                }
                ph = (ph + w * 128) % (2 * PI)
                put(bb.array())
            }
        }
        Thread.sleep(5)
        synchronized(lock) {
            val all = out.toByteArray()
            val n = minOf(dst.size, all.size)
            System.arraycopy(all, 0, dst, 0, n)
            out.reset(); out.write(all, n, all.size - n)
            return n
        }
    }

    override fun close() {}
}
