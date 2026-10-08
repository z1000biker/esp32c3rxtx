package gr.sv1eex.c3trx

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun roundBg(color: Int, radius: Float = 10f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

const val C_BTN = 0xFF2A3140.toInt()
const val C_SEL = 0xFF2F6FB5.toInt()
const val C_PTT = 0xFFC0392B.toInt()
const val C_PTT_ON = 0xFFFF2D2D.toInt()

fun Context.btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
    background = roundBg(C_BTN); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
}

fun Context.label(text: String, sizeSp: Float = 13f, color: Int = 0xFFB8C0CC.toInt()): TextView = TextView(this).apply {
    this.text = text; textSize = sizeSp; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(6), dp(2), dp(6), dp(2))
}

/** A horizontally scrolling row (keeps every control reachable on narrow phones). */
class Row(ctx: Context) : HorizontalScrollView(ctx) {
    val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    init { isHorizontalScrollBarEnabled = false; addView(inner) }
    fun add(vararg v: View): Row { v.forEach { inner.addView(it) }; return this }
}

/** Segmented choice. */
class Seg(ctx: Context, val options: List<String>, initial: String, private val onSelect: (String) -> Unit) : LinearLayout(ctx) {
    private val buttons = options.map { o -> ctx.btn(o) { select(o, true) } }
    var value = initial; private set
    init {
        orientation = HORIZONTAL
        buttons.forEach { addView(it) }
        select(initial, false)
    }
    fun select(o: String, notify: Boolean) {
        value = o
        buttons.forEachIndexed { i, b -> b.background = roundBg(if (options[i] == o) C_SEL else C_BTN) }
        if (notify) onSelect(o)
    }
}

/** Integer field with -/+ buttons, clamped to [min, max]. */
class Stepper(
    ctx: Context, title: String, private val min: Int, private val max: Int, private val step: Int,
    initial: Int, suffix: String = "", private val onChange: (Int) -> Unit,
) : LinearLayout(ctx) {
    private val edit = EditText(ctx)
    var value = initial.coerceIn(min, max); private set
    private var quiet = false

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ctx.label(title))
        addView(ctx.btn("−") { set(value - step) })
        edit.apply {
            setText(value.toString()); setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or (if (min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            minEms = 3; setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (quiet) return
                    val v = s.toString().toIntOrNull() ?: return
                    if (v in min..max) { value = v; onChange(v) }
                }
            })
            setOnFocusChangeListener { _, has -> if (!has) set(value) }
        }
        addView(edit)
        addView(ctx.btn("+") { set(value + step) })
        if (suffix.isNotEmpty()) addView(ctx.label(suffix))
    }

    fun set(v: Int, notify: Boolean = true) {
        value = v.coerceIn(min, max)
        quiet = true; edit.setText(value.toString()); quiet = false
        if (notify) onChange(value)
    }
}
