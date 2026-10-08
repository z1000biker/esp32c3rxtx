package gr.sv1eex.c3trx

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private val prefs by lazy { getSharedPreferences("c3trx", MODE_PRIVATE) }

    private val audio by lazy { AudioOut() }
    private lateinit var engine: Engine
    private var link: Link? = null
    private var isMock = false
    private var rx = false
    private var pending = false
    private var tx: TxFeeder? = null
    private var txWanted = false
    private val frames = ConcurrentLinkedQueue<SpecFrame>()
    @Volatile private var framePosted = false

    // widgets
    private lateinit var spec: SpectrumView
    private lateinit var bConn: android.widget.Button
    private lateinit var bRx: android.widget.Button
    private lateinit var bPtt: android.widget.Button
    private lateinit var lFw: TextView; private lateinit var lRate: TextView; private lateinit var lLvl: TextView
    private lateinit var lDrop: TextView; private lateinit var lIq: TextView; private lateinit var lVfo: TextView
    private lateinit var freqEdit: EditText
    private lateinit var modeSeg: Seg
    private lateinit var bfoSt: Stepper; private lateinit var bwSt: Stepper; private lateinit var winSt: Stepper
    private lateinit var txModeSeg: Seg; private lateinit var srcSeg: Seg
    private lateinit var ampSt: Stepper; private lateinit var depthSt: Stepper; private lateinit var devSt: Stepper; private lateinit var toneSt: Stepper
    private lateinit var wText: EditText; private lateinit var wLay: Seg; private lateinit var wDir: Seg
    private lateinit var wH: Stepper; private lateinit var wPx: Stepper; private lateinit var wMs: Stepper; private lateinit var wBase: Stepper
    private lateinit var lWf: TextView
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()
    private var zoom = 1.0

    private var cfMHz = 2400.0

    companion object {
        const val ACTION_USB_PERMISSION = "gr.sv1eex.c3trx.USB_PERMISSION"
        const val REQ_WAV = 10
        const val REQ_MIC = 11
        const val ESP_VID = 0x303A
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        engine = Engine(audio) { f ->
            frames.add(f)
            if (!framePosted) { framePosted = true; ui.post(::drainFrames) }
        }
        buildUi()
        val f = IntentFilter(ACTION_USB_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, f)
        if (!audio.ok) log("! audio output could not be opened")
        ui.postDelayed(statusTick, 250)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && link == null) {
            val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                 else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (d != null) { log("USB device attached: ${d.productName ?: d.deviceName}"); openDevice(d) }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        disconnect()
        engine.close(); audio.close(); io.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun drainFrames() {
        framePosted = false
        while (true) { val f = frames.poll() ?: break; spec.push(f) }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val l = link
            if (l != null) {
                if (l.rate > 0) lRate.text = String.format(Locale.US, "IQ %.1f kHz", l.rate / 1000.0)
                lDrop.text = "Dropped ${l.dropped}"
                lLvl.text = String.format(Locale.US, "Level %.0f dB", engine.dem.levelDb)
                lIq.text = String.format(Locale.US, "IQ: gain %.3f · phase %+.1f°", engine.front.gain, engine.front.phaseDeg)
            }
            ui.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows = true }

        bConn = btn("Connect") { if (link == null) connect(false) else disconnect() }
        val bDemo = btn("Demo") { if (link == null) connect(true) }
        bRx = btn("Start RX") { rxToggle() }
        bPtt = btn("PTT (TX)") { ptt() }.apply { background = roundBg(C_PTT); typeface = Typeface.DEFAULT_BOLD }
        lFw = label("FW: —"); lRate = label("IQ —"); lLvl = label("Level —"); lDrop = label("Dropped 0"); lIq = label("IQ: —")
        root.addView(Row(this).add(bConn, bDemo, bRx, bPtt, lFw, lRate, lLvl, lDrop, lIq))

        lVfo = label("VFO —", 20f, 0xFFFF6B6B.toInt()).apply { typeface = Typeface.DEFAULT_BOLD }
        val zoomLbl = label("1.00×")
        val zoomBar = SeekBar(this).apply {
            max = 1000; progress = prefs.getInt("zoom", 0)
            layoutParams = LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    zoom = 2.0.pow(p / 100.0); spec.zoom = zoom; zoomLbl.text = String.format(Locale.US, "%.2f×", zoom); spec.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(Row(this).add(lVfo,
            btn("−1k") { setVfo(engine.dem.vfo - 1000) }, btn("−10") { setVfo(engine.dem.vfo - 10) },
            btn("+10") { setVfo(engine.dem.vfo + 10) }, btn("+1k") { setVfo(engine.dem.vfo + 1000) },
            btn("VFO→0") { setVfo(0.0) }, btn("Re-center") { recenter() }, label("Zoom"), zoomBar, zoomLbl))

        spec = SpectrumView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onTune = { setVfo(it) }
        }
        root.addView(spec)

        // tabs
        val panels = listOf(rxPanel(), txPanel(), textPanel(), logPanel())
        val holder = FrameLayout(this)
        panels.forEach { holder.addView(it); it.visibility = View.GONE }
        panels[0].visibility = View.VISIBLE
        val tabs = Seg(this, listOf("RX", "TX", "WF text", "Log"), "RX") { t ->
            val idx = listOf("RX", "TX", "WF text", "Log").indexOf(t)
            panels.forEachIndexed { i, p -> p.visibility = if (i == idx) View.VISIBLE else View.GONE }
        }
        root.addView(Row(this).add(tabs))
        val sv = ScrollView(this).apply {
            addView(holder)
            val h = (resources.displayMetrics.heightPixels * 0.30).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        }
        root.addView(sv)
        setContentView(root)

        spec.centerMHz = cfMHz
        zoom = 2.0.pow(zoomBar.progress / 100.0); spec.zoom = zoom; zoomLbl.text = String.format(Locale.US, "%.2f×", zoom)
        spec.bfo = engine.dem.bfo; spec.bw = engine.dem.bw
        updateVfoLabel(); wfInfo()
    }

    private fun col(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(4))
        rows.forEach { addView(it) }
    }

    private fun rxPanel(): View {
        cfMHz = prefs.getFloat("freq", 2400f).toDouble()
        freqEdit = EditText(this).apply {
            setText(String.format(Locale.US, "%.3f", cfMHz)); setTextColor(Color.WHITE); minEms = 6
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        winSt = Stepper(this, "RX window", 32, 4096, 32, prefs.getInt("win", 128)) {}
        val mode0 = prefs.getString("mode", "USB")!!
        modeSeg = Seg(this, listOf("AM", "FM", "USB", "LSB", "CW"), mode0) { setMode(it) }
        bfoSt = Stepper(this, "BFO", -50000, 50000, 50, MODE_BFO[mode0]!!, "Hz") { engine.dem.bfo = it.toDouble(); spec.bfo = it.toDouble(); spec.invalidate() }
        bwSt = Stepper(this, "BW", 200, 20000, 100, MODE_BW[mode0]!!, "Hz") { engine.dem.bw = it.toDouble(); spec.bw = it.toDouble(); spec.invalidate() }
        engine.dem.mode = mode0; engine.dem.bfo = bfoSt.value.toDouble(); engine.dem.bw = bwSt.value.toDouble()
        val vol = seek(100, prefs.getInt("vol", 60)) { engine.dem.vol = it / 100.0 }
        engine.dem.vol = prefs.getInt("vol", 60) / 100.0
        val sql = seek(100, 0) { engine.dem.sql = it }
        val agc = Seg(this, listOf("fast", "slow", "off"), "fast") { engine.dem.agc = it }
        val cdc = CheckBox(this).apply { text = "DC notch"; isChecked = true; setTextColor(Color.WHITE); setOnCheckedChangeListener { _, b -> engine.front.dcOn = b } }
        val ciq = CheckBox(this).apply { text = "IQ balance"; isChecked = true; setTextColor(Color.WHITE); setOnCheckedChangeListener { _, b -> engine.front.iqOn = b } }
        return col(
            Row(this).add(label("Freq MHz"), freqEdit, btn("Tune") { tune() }, winSt),
            Row(this).add(label("Mode"), modeSeg),
            Row(this).add(bfoSt, bwSt),
            Row(this).add(label("Vol"), vol, label("Squelch"), sql),
            Row(this).add(label("AGC"), agc, cdc, ciq),
        )
    }

    private fun seek(maxV: Int, v: Int, f: (Int) -> Unit) = SeekBar(this).apply {
        max = maxV; progress = v
        layoutParams = LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = f(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun txPanel(): View {
        txModeSeg = Seg(this, listOf("USB", "LSB", "AM", "FM"), prefs.getString("txmode", "USB")!!) { wfInfo() }
        srcSeg = Seg(this, listOf("mic", "tone", "carrier", "text"), prefs.getString("src", "tone")!!) {}
        ampSt = Stepper(this, "Amp", 1, 480, 10, prefs.getInt("amp", 80)) {}
        depthSt = Stepper(this, "AM %", 1, 95, 5, prefs.getInt("depth", 70)) {}
        devSt = Stepper(this, "FM dev", 100, 12000, 100, prefs.getInt("dev", 2500), "Hz") {}
        toneSt = Stepper(this, "Tone", 50, 5000, 50, prefs.getInt("tone", 1000), "Hz") {}
        return col(
            Row(this).add(label("TX mode"), txModeSeg),
            Row(this).add(label("Source"), srcSeg),
            Row(this).add(ampSt, toneSt),
            Row(this).add(depthSt, devSt),
            label("Transmits on the VFO frequency (2300–2450 MHz). After TX press Start RX.", 12f),
        )
    }

    private fun textPanel(): View {
        wText = EditText(this).apply {
            setText(prefs.getString("wtext", "CQ CQ DE SV1EEX")); setTextColor(Color.WHITE); minEms = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = wfInfo()
            })
        }
        wLay = Seg(this, listOf("vertical (narrow)", "horizontal (wide)"), prefs.getString("wlay", "vertical (narrow)")!!) { wfInfo() }
        wH = Stepper(this, "Height", 6, 40, 1, prefs.getInt("wh", 12), "px") { wfInfo() }
        wPx = Stepper(this, "Pixel", 20, 1000, 10, prefs.getInt("wpx", 150), "Hz") { wfInfo() }
        wMs = Stepper(this, "Line", 10, 1000, 10, prefs.getInt("wms", 80), "ms") { wfInfo() }
        wBase = Stepper(this, "Base", 100, 10000, 50, prefs.getInt("wbase", 400), "Hz") { wfInfo() }
        wDir = Seg(this, listOf("newest on top", "newest at bottom"), prefs.getString("wdir", "newest on top")!!) { wfInfo() }
        lWf = label("", 13f, 0xFFFFD27F.toInt())
        return col(
            Row(this).add(label("WF text"), wText),
            Row(this).add(label("Layout"), wLay),
            Row(this).add(wH, wPx),
            Row(this).add(wMs, wBase),
            Row(this).add(label("RX waterfall"), wDir),
            Row(this).add(btn("Preview") { wfPreview() }, btn("Save WAV") { wfSaveWav() }, lWf),
            label("Set Source = text in the TX tab, then PTT.", 12f),
        )
    }

    private fun logPanel(): View {
        logView = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); typeface = Typeface.MONOSPACE; textSize = 11f
            setTextIsSelectable(true); setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        return col(Row(this).add(btn("Clear") { logLines.clear(); logView.text = "" }), logView)
    }

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss ", Locale.US).format(Date()) + s
        logLines.addLast(line)
        while (logLines.size > 300) logLines.removeFirst()
        if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
    }

    private fun savePrefs() {
        if (!::wText.isInitialized) return
        prefs.edit()
            .putFloat("freq", cfMHz.toFloat()).putInt("win", winSt.value).putString("mode", modeSeg.value)
            .putString("txmode", txModeSeg.value).putString("src", srcSeg.value).putInt("amp", ampSt.value)
            .putInt("depth", depthSt.value).putInt("dev", devSt.value).putInt("tone", toneSt.value)
            .putInt("vol", (engine.dem.vol * 100).roundToInt())
            .putString("wtext", wText.text.toString()).putString("wlay", wLay.value).putInt("wh", wH.value)
            .putInt("wpx", wPx.value).putInt("wms", wMs.value).putInt("wbase", wBase.value).putString("wdir", wDir.value)
            .putInt("zoom", (kotlin.math.log2(zoom) * 100).roundToInt())
            .apply()
    }

    // ------------------------------------------------------------------ USB / link
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                 else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (i.action) {
                ACTION_USB_PERMISSION ->
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) openDevice(d)
                    else log("! USB permission denied")
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (link != null && !isMock && d?.vendorId == ESP_VID) { log("! USB device detached"); disconnect() }
            }
        }
    }

    private fun connect(mock: Boolean) {
        if (mock) { startLink(MockSerial(), true); return }
        val devs = usb.deviceList.values
        val d = devs.firstOrNull { it.vendorId == ESP_VID } ?: run {
            log("! no ESP32-C3 found on USB (connect it with an OTG cable/adapter). Devices: " +
                devs.joinToString { String.format("%04X:%04X", it.vendorId, it.productId) }.ifEmpty { "none" })
            return
        }
        openDevice(d)
    }

    private fun openDevice(d: UsbDevice) {
        if (link != null) return
        if (!usb.hasPermission(d)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(d, pi); log("requesting USB permission…"); return
        }
        try { startLink(UsbCdcPort(usb, d), false) }
        catch (e: Exception) { log("! open USB: ${e.message}") }
    }

    private fun startLink(ser: SerialIo, mock: Boolean) {
        isMock = mock
        val l = Link(ser, { s -> ui.post { onLine(s) } }, { s -> ui.post { log("! $s"); disconnect() } })
        link = l; engine.link = l
        bConn.text = "Disconnect"
        log(if (mock) "connected to the simulator (+1 kHz test carrier)" else "connected over USB")
        io.execute {
            try { l.send("\n".toByteArray()); Thread.sleep(200); l.sendLine("INFO") } catch (e: Exception) { ui.post { log("! ${e.message}") } }
        }
    }

    private fun disconnect() {
        txWanted = false; tx?.stop(); tx = null; resetPtt()
        val l = link ?: return
        link = null; engine.link = null
        try { l.close() } catch (_: Exception) {}
        rx = false; pending = false
        bRx.text = "Start RX"; bConn.text = "Connect"; lFw.text = "FW: —"
        log("disconnected")
    }

    private fun onLine(s: String) {
        log(s)
        if (s.startsWith("< C3TRX ") && s.length > 8 && s[8].isDigit()) {
            val id = s.substring(2).split(" ").take(2).joinToString(" ")
            lFw.text = "FW: $id"
            if (s[8] != '1') log("!!! Wrong firmware on the device ($id). This app needs C3TRX 1: flash c3trx1_merged.bin with esptool first.")
        }
        if (s.startsWith("< RXREADY")) { rx = true; bRx.text = "Stop RX" }
        if (s.startsWith("< RXEND")) {
            rx = false; bRx.text = "Start RX"
            if (pending) { pending = false; rxToggle() }      // restart after a retune
        }
        if (s.startsWith("< TXEND")) txEnded()
    }

    private fun cmd(block: (Link) -> Unit) {
        val l = link ?: return
        io.execute { try { block(l) } catch (e: Exception) { ui.post { log("! ${e.message}") } } }
    }

    // ------------------------------------------------------------------ tuning
    private fun readFreq(): Double {
        val v = freqEdit.text.toString().replace(',', '.').toDoubleOrNull()
        if (v == null || v < 2300 || v > 2500) { log("! frequency must be 2300–2500 MHz"); setFreq(cfMHz); return cfMHz }
        return v
    }

    private fun setFreq(mhz: Double) {
        cfMHz = mhz; freqEdit.setText(String.format(Locale.US, "%.3f", mhz))
        spec.centerMHz = mhz; updateVfoLabel()
    }

    private fun setVfo(off0: Double) {
        val rate = link?.rate?.takeIf { it > 0 } ?: 147000
        val off = ((off0 / 10).roundToLong() * 10).toDouble().coerceIn(-0.48 * rate, 0.48 * rate)
        engine.dem.vfo = off; spec.vfo = off; spec.invalidate(); updateVfoLabel()
    }

    private fun updateVfoLabel() {
        lVfo.text = String.format(Locale.US, "VFO %.5f MHz", cfMHz + engine.dem.vfo / 1e6)
    }

    /** Firmware cannot take commands while streaming: stop, FREQ, restart. */
    private fun retune(cf: Double, vfo: Double) {
        setFreq(cf); engine.dem.vfo = vfo; spec.vfo = vfo; spec.invalidate(); updateVfoLabel()
        if (link == null) return
        if (rx) { pending = true; cmd { it.send(byteArrayOf(3)) } }
        else { val k = (cf * 1000).roundToLong(); cmd { it.sendLine("FREQ $k") } }
    }

    private fun recenter() {
        val target = cfMHz + engine.dem.vfo / 1e6
        retune(target - 0.010, 10000.0)                       // keep the signal 10 kHz away from the DC/LO spur
    }

    private fun tune() = retune(readFreq(), engine.dem.vfo)

    private fun rxToggle() {
        if (link == null) { log("! not connected"); return }
        if (rx) { cmd { it.send(byteArrayOf(3)) }; return }
        val cf = readFreq(); setFreq(cf)
        val k = (cf * 1000).roundToLong(); val w = winSt.value
        engine.dem.reset(); engine.resetSpectrum = true; audio.ring.clear()
        cmd { it.sendLine("FREQ $k"); Thread.sleep(50); it.sendLine("RX $w") }
    }

    private fun setMode(m: String) {
        engine.dem.mode = m
        bfoSt.set(MODE_BFO[m]!!); bwSt.set(MODE_BW[m]!!)
        engine.dem.reset()
        if (m in listOf("AM", "FM", "USB", "LSB")) txModeSeg.select(m, false)
        log("mode -> $m (live)")
        wfInfo()
    }

    // ------------------------------------------------------------------ waterfall text
    private fun wfBitmap() = TextBitmap.render(wText.text.toString().ifEmpty { " " }, wH.value)
    private val vertical get() = wLay.value.startsWith("vertical")

    private fun wfBuild(sr: Int): Pair<FloatArray, WfInfo> =
        WaterfallText.build(wfBitmap(), vertical, wBase.value.toDouble(), wPx.value.toDouble(), wMs.value.toDouble(),
            wDir.value == "newest on top", txModeSeg.value == "LSB", sr)

    private fun wfInfo() {
        if (!::lWf.isInitialized || !::txModeSeg.isInitialized) return
        val i = WaterfallText.info(wfBitmap(), vertical, wBase.value.toDouble(), wPx.value.toDouble(), wMs.value.toDouble())
        val warn = if (i.hi > 15000) "  ⚠ above 15 kHz" else if (i.hi > 3000) "  (wider than an SSB channel)" else ""
        lWf.text = String.format(Locale.US, "%d tones · %.0f–%.0f Hz · %.1f s%s", i.tones, i.lo, i.hi, i.seconds, warn)
    }

    private fun wfPreview() {
        val (a, info) = wfBuild(TX_SR)
        val sg = WaterfallText.spectrogram(a, TX_SR, info)
        val rows = sg.db.size; val cols = sg.db[0].size
        var top = -1e9f
        for (r in sg.db) for (v in r) if (v > top) top = v
        val px = IntArray(rows * cols)
        val newestTop = wDir.value == "newest on top"
        for (r in 0 until rows) {
            val src = sg.db[if (newestTop) rows - 1 - r else r]
            for (c in 0 until cols) px[r * cols + c] = TURBO[(((src[c] - (top - 30)) / 30f).coerceIn(0f, 1f) * 255).toInt()]
        }
        val bmp = Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
        val aspect = (sg.fHi - sg.fLo) / sg.heightHz
        val v = ImageAspectView(this, bmp, aspect).apply { minimumHeight = (resources.displayMetrics.heightPixels * 0.6).toInt() }
        AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, "%d tones · %.0f–%.0f Hz audio (%.0f Hz wide) · %.1f s",
                info.tones, info.lo, info.hi, info.hi - info.lo, info.seconds))
            .setView(v)
            .setMessage("Square pixels. On a real waterfall the shape depends on its FFT size and scroll speed: adjust Pixel (Hz) and Line (ms).")
            .setPositiveButton("OK", null).show()
    }

    private fun wfSaveWav() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/wav").putExtra(Intent.EXTRA_TITLE, "wftext.wav")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_WAV)
    }

    @Deprecated("framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WAV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val (a, info) = wfBuild(48000)
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(WaterfallText.wav(a, 48000)) }
            log(String.format(Locale.US, "saved WAV: %.1f s, 48 kHz — play it into any SSB transmitter", info.seconds))
        } catch (e: Exception) { log("! save WAV: ${e.message}") }
    }

    // ------------------------------------------------------------------ TX
    private fun ptt() {
        if (link == null) { log("! not connected"); return }
        if (txWanted) {
            txWanted = false; tx?.stop(); tx = null
            log("TX: audio stopped, firmware ends TX after 0.3 s"); resetPtt(); return
        }
        val src = srcSeg.value
        if (src == "mic" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            log("microphone permission needed — press PTT again after allowing it"); return
        }
        var m = txModeSeg.value
        if (src == "carrier" && (m == "USB" || m == "LSB")) {
            m = "AM"; txModeSeg.select("AM", false); log("carrier test -> AM (SSB with no audio radiates nothing)")
        }
        var samples: FloatArray? = null
        if (src == "text") {
            if (m != "USB" && m != "LSB") { m = "USB"; txModeSeg.select("USB", false); log("waterfall text -> USB (AM/FM would smear the picture)") }
            val (a, info) = wfBuild(TX_SR)
            if (info.hi > 15000) { log("! waterfall text: top tone above 15 kHz, reduce Pixel/Height/Base"); return }
            log(String.format(Locale.US, "WF text: %d tones %.0f–%.0f Hz, %.1f s", info.tones, info.lo, info.hi, info.seconds))
            samples = a
        }
        val p = when (m) { "AM" -> depthSt.value; "FM" -> devSt.value; else -> 0 }
        val txf = cfMHz + engine.dem.vfo / 1e6                 // transmit on the VFO frequency
        if (txf > 2450) { log("! TX limited to 2300–2450 MHz by firmware"); return }
        val wasRx = rx
        val amp = ampSt.value; val tone = toneSt.value.toDouble(); val mode = m
        txWanted = true
        bPtt.text = "STOP TX"; bPtt.background = roundBg(C_PTT_ON)
        log(String.format(Locale.US, "TX on %.5f MHz (VFO). After TX, press Start RX.", txf))
        cmd {
            if (wasRx) { it.send(byteArrayOf(3)); Thread.sleep(300) }
            it.sendLine("FREQ ${(txf * 1000).roundToLong()}"); Thread.sleep(50)
            it.sendLine("TX $mode $amp $p")
            val f = TxFeeder(it, src, tone, samples) { s -> ui.post { log(s) } }
            ui.post {
                if (txWanted) { tx = f } else { f.stop() }
            }
        }
    }

    private fun txEnded() {
        txWanted = false; tx?.stop(); tx = null
        resetPtt()
    }

    private fun resetPtt() {
        if (!::bPtt.isInitialized) return
        bPtt.text = "PTT (TX)"; bPtt.background = roundBg(C_PTT)
    }
}
