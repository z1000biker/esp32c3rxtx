package gr.sv1eex.c3trx

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * CDC-ACM over the Android USB host API, written for the ESP32-C3 native USB Serial/JTAG
 * (VID 0x303A, PID 0x1001). Reads use several queued UsbRequests so the ~560 kB/s IQ stream
 * keeps flowing between calls.
 */
class UsbCdcPort(manager: UsbManager, device: UsbDevice) : SerialIo {
    private val conn: UsbDeviceConnection
    private val claimed = ArrayList<UsbInterface>()
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private val reqs = ArrayList<UsbRequest>()
    private val wlock = Object()
    @Volatile private var open = true

    init {
        conn = manager.openDevice(device) ?: throw IOException("openDevice failed (no permission?)")
        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM && comm == null) comm = itf
            if (itf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && data == null) data = itf
        }
        if (data == null) {   // fall back to any interface with a bulk IN + bulk OUT pair
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (bulk(itf, UsbConstants.USB_DIR_IN) != null && bulk(itf, UsbConstants.USB_DIR_OUT) != null) { data = itf; break }
            }
        }
        if (data == null) { conn.close(); throw IOException("no CDC data interface") }
        for (itf in listOfNotNull(comm, data)) {
            if (!conn.claimInterface(itf, true)) { close(); throw IOException("claimInterface ${itf.id} failed") }
            claimed.add(itf)
        }
        epIn = bulk(data, UsbConstants.USB_DIR_IN) ?: throw IOException("no bulk IN")
        epOut = bulk(data, UsbConstants.USB_DIR_OUT) ?: throw IOException("no bulk OUT")
        val ctl = comm?.id ?: 0
        // SET_LINE_CODING 921600 8N1 (ignored by USB Serial/JTAG, harmless)
        val lc = byteArrayOf(0x00, 0x10, 0x0E, 0x00, 0, 0, 8)
        conn.controlTransfer(0x21, 0x20, 0, ctl, lc, lc.size, 200)
        // SET_CONTROL_LINE_STATE: DTR=1, RTS=0. On the ESP32-C3, RTS=1 resets the chip, so keep it low.
        conn.controlTransfer(0x21, 0x22, 0x0001, ctl, null, 0, 200)
        repeat(8) {
            val r = UsbRequest()
            r.initialize(conn, epIn)
            r.clientData = ByteBuffer.allocate(16384)
            reqs.add(r)
            if (!r.queue(r.clientData as ByteBuffer)) throw IOException("UsbRequest.queue failed")
        }
    }

    private fun bulk(itf: UsbInterface, dir: Int): UsbEndpoint? {
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == dir) return ep
        }
        return null
    }

    override fun write(b: ByteArray) {
        synchronized(wlock) {
            var off = 0
            while (off < b.size) {
                if (!open) throw IOException("closed")
                val n = minOf(16384, b.size - off)
                val chunk = if (off == 0 && n == b.size) b else b.copyOfRange(off, off + n)
                val r = conn.bulkTransfer(epOut, chunk, n, 1000)
                if (r < 0) throw IOException("USB write failed")
                off += r
            }
        }
    }

    // Data returned by a completed request but not yet handed out (dst may be smaller than 16 kB)
    private var pend: ByteBuffer? = null
    private var pendReq: UsbRequest? = null

    override fun read(dst: ByteArray): Int {
        if (!open) throw IOException("closed")
        var p = pend
        if (p == null) {
            val r = try { conn.requestWait(50) } catch (e: TimeoutException) { return 0 }
                ?: throw IOException("USB device gone")
            val buf = r.clientData as ByteBuffer
            buf.flip()
            p = buf; pend = buf; pendReq = r
        }
        val n = minOf(dst.size, p.remaining())
        p.get(dst, 0, n)
        if (!p.hasRemaining()) {
            p.clear()
            val r = pendReq!!
            pend = null; pendReq = null
            if (open && !r.queue(p)) throw IOException("UsbRequest.queue failed")
        }
        return n
    }

    override fun close() {
        if (!open) return
        open = false
        for (r in reqs) { try { r.cancel(); r.close() } catch (_: Exception) {} }
        for (itf in claimed) { try { conn.releaseInterface(itf) } catch (_: Exception) {} }
        try { conn.close() } catch (_: Exception) {}
    }
}

