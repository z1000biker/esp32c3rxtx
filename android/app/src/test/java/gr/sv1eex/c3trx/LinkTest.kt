package gr.sv1eex.c3trx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Full protocol cycle against the simulator: INFO, FREQ, RX streaming, Ctrl-C, TX + PCM, TXEND. */
class LinkTest {
    @Test fun protocolCycle() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val l = Link(MockSerial(), { lines.add(it) }, { lines.add("ERR $it") })
        fun waitFor(p: String, ms: Long = 2000): Boolean {
            val t = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < t) { if (synchronized(lines) { lines.any { it.startsWith(p) } }) return true; Thread.sleep(10) }
            return false
        }
        l.sendLine("INFO")
        assertTrue(waitFor("< C3TRX 1"))
        l.sendLine("FREQ 2400000"); assertTrue(waitFor("< OK FREQ 2400000"))
        l.sendLine("RX 128"); assertTrue(waitFor("< RXREADY"))
        Thread.sleep(500)
        val n = l.iq.size
        println("IQ frames after 0.5 s: $n, rate ${l.rate}, dropped ${l.dropped}")
        assertTrue("frames $n", n > 50)
        assertEquals(48000L, l.rate.toLong())
        assertEquals(0L, l.dropped)
        l.send(byteArrayOf(3)); assertTrue(waitFor("< RXEND"))
        l.sendLine("TX USB 80 0"); assertTrue(waitFor("< TXREADY USB"))
        repeat(10) { l.send(ByteArray(6400)); Thread.sleep(20) }
        assertTrue(waitFor("< TXEND samples=32000", 3000))
        l.close()
    }
}
