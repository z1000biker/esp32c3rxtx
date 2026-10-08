package gr.sv1eex.c3trx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Google "turbo" colormap, polynomial approximation. x in 0..1. */
fun turbo(x0: Double): Int {
    val x = x0.coerceIn(0.0, 1.0)
    val r = 0.13572138 + x * (4.61539260 + x * (-42.66032258 + x * (132.13108234 + x * (-152.94239396 + x * 59.28637943))))
    val g = 0.09140261 + x * (2.19418839 + x * (4.84296658 + x * (-14.18503333 + x * (4.27729857 + x * 2.82956604))))
    val b = 0.10667330 + x * (12.64194608 + x * (-60.58204836 + x * (110.36276771 + x * (-89.90310912 + x * 27.34824973))))
    fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    return Color.rgb(c(r), c(g), c(b))
}

val TURBO = IntArray(256) { turbo(it / 255.0) }

/**
 * Spectrum (top) + waterfall (bottom), frequency-locked, zoomed around the VFO.
 * Touch or drag anywhere = put the VFO there.
 */
class SpectrumView(ctx: Context) : View(ctx) {
    var onTune: ((Double) -> Unit)? = null

    // display state, set from the UI thread
    var centerMHz = 2400.0
    var vfo = 0.0; var bfo = 1500.0; var bw = 2700.0
    var zoom = 1.0

    private var db: FloatArray? = null
    private var rate = 0
    private val wfRows = 300
    private val wf = Bitmap.createBitmap(FFT_N, wfRows, Bitmap.Config.ARGB_8888)
    private var wfTop = 0                     // ring row holding the newest line
    private val rowPx = IntArray(FFT_N)

    private val pSpec = Paint().apply { color = Color.rgb(0x4C, 0xA6, 0xFF); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val pGrid = Paint().apply { color = Color.argb(50, 255, 255, 255); strokeWidth = 1f }
    private val pText = Paint().apply { color = Color.argb(200, 220, 220, 220); textSize = 24f; isAntiAlias = true }
    private val pVfo = Paint().apply { color = Color.rgb(0xFF, 0x4D, 0x4D); strokeWidth = 3f }
    private val pVfoWf = Paint().apply { color = Color.argb(140, 0xFF, 0x4D, 0x4D); strokeWidth = 2f }
    private val pPass = Paint().apply { color = Color.argb(45, 68, 162, 255) }
    private val pBg = Paint().apply { color = Color.rgb(8, 10, 16) }
    private val pBmp = Paint().apply { isFilterBitmap = true }
    private val path = Path()

    init { wf.eraseColor(TURBO[0]) }

    /** Called on the UI thread with a new averaged spectrum; adds a waterfall line. */
    fun push(f: SpecFrame) {
        db = f.db; rate = f.rate
        wfTop = (wfTop - 1 + wfRows) % wfRows
        for (k in 0 until FFT_N) {
            val x = (f.db[k] + 100.0) / 90.0                // levels -100 .. -10 dB
            rowPx[k] = TURBO[(x.coerceIn(0.0, 1.0) * 255).toInt()]
        }
        wf.setPixels(rowPx, 0, FFT_N, 0, wfTop, FFT_N, 1)
        invalidate()
    }

    fun clearWaterfall() { wf.eraseColor(TURBO[0]); db = null; invalidate() }

    /** Visible bin window [s, s+w). */
    private fun window(): Pair<Int, Int> {
        val r = if (rate > 0) rate else 147000
        val w = max(16, (FFT_N / zoom).toInt())
        val cbin = (FFT_N / 2 + vfo / r * FFT_N).roundToInt()
        val s = (cbin - w / 2).coerceIn(0, FFT_N - w)
        return s to w
    }

    private fun xOfHz(hzOff: Double, s: Int, w: Int, width: Float): Float {
        val r = if (rate > 0) rate else 147000
        val bin = FFT_N / 2 + hzOff / r * FFT_N          // fractional bin
        return ((bin - s) / (w - 1) * width).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        val specH = H * 0.40f
        c.drawRect(0f, 0f, W, specH, pBg)
        val (s, w) = window()
        val r = if (rate > 0) rate else 147000

        // dB grid
        for (d in -100..0 step 20) {
            val y = specH * (-d / 110f)
            c.drawLine(0f, y, W, y, pGrid)
            c.drawText("$d", 6f, y - 4f, pText)
        }
        // frequency grid: ~6 ticks at a round step
        val spanHz = w.toDouble() * r / FFT_N
        val raw = spanHz / 6
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val f0 = centerMHz * 1e6 + (s - FFT_N / 2) * r.toDouble() / FFT_N
        var ft = ceil(f0 / step) * step
        val decimals = max(0, min(6, (6 - log10(step).toInt())))
        while (ft < f0 + spanHz) {
            val x = xOfHz(ft - centerMHz * 1e6, s, w, W)
            c.drawLine(x, 0f, x, specH, pGrid)
            c.drawText(String.format("%.${decimals}f", ft / 1e6), x + 4f, specH - 6f, pText)
            ft += step
        }

        // passband + spectrum
        val lo = xOfHz(vfo + bfo - bw / 2, s, w, W); val hi = xOfHz(vfo + bfo + bw / 2, s, w, W)
        c.drawRect(lo, 0f, hi, H, pPass)
        db?.let { d ->
            path.reset()
            for (i in 0 until w) {
                val x = i.toFloat() / (w - 1) * W
                val y = (specH * (-d[s + i] / 110f)).coerceIn(0f, specH)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, pSpec)
        }

        // waterfall: newest line on top; draw the ring in two parts
        val wy0 = specH; val wh = H - specH
        val rowH = wh / wfRows
        val n1 = wfRows - wfTop
        pBmp.isFilterBitmap = w > W               // smooth only when downscaling
        c.drawBitmap(wf, Rect(s, wfTop, s + w, wfRows), RectF(0f, wy0, W, wy0 + n1 * rowH), pBmp)
        if (wfTop > 0) c.drawBitmap(wf, Rect(s, 0, s + w, wfTop), RectF(0f, wy0 + n1 * rowH, W, H), pBmp)

        val vx = xOfHz(vfo, s, w, W)
        c.drawLine(vx, 0f, vx, specH, pVfo)
        c.drawLine(vx, specH, vx, H, pVfoWf)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val (s, w) = window()
                val r = if (rate > 0) rate else 147000
                val bin = s + e.x / width * (w - 1)
                onTune?.invoke((bin - FFT_N / 2) * r / FFT_N)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** Shows the waterfall-text spectrogram with square pixels. */
class ImageAspectView(ctx: Context, private val bmp: Bitmap, private val aspect: Double) : View(ctx) {
    private val p = Paint().apply { isFilterBitmap = false }
    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        var w = W; var h = (W / aspect).toFloat()
        if (h > H) { h = H; w = (H * aspect).toFloat() }
        val x = (W - w) / 2; val y = (H - h) / 2
        c.drawColor(Color.BLACK)
        c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), p)
    }
}
