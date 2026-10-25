package com.knightage.btkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** One key on the on-screen keyboard. [weight] is its relative width within the row. */
sealed class Key(val label: String, val weight: Float) {
    class Text(val char: Char, weight: Float = 1f) : Key(char.toString(), weight)
    class Code(label: String, val usage: Int, weight: Float = 1f) : Key(label, weight)
    class Modifier(label: String, val bit: Int, weight: Float = 1f) : Key(label, weight)
    class Media(label: String, val usage: Int, weight: Float = 1f) : Key(label, weight)
    class Layer(label: String, val target: Int, weight: Float = 1f) : Key(label, weight)
}

/**
 * Compact multi-layer keyboard that sends HID reports as keys go down and up, so holding a
 * key (e.g. backspace) auto-repeats on the host and multi-touch chords work.
 *
 * Modifiers are sticky: tap Ctrl then C for Ctrl+C. Double-tap a modifier to lock it.
 */
class KeyboardView(context: Context) : View(context) {

    companion object {
        const val LETTERS = 0
        const val SYMBOLS = 1
        const val SYMBOLS2 = 2
        const val FN = 3

        private val NAV_ROW = listOf(
            Key.Code("Esc", KeyCodes.ESC), Key.Code("Tab", KeyCodes.TAB),
            Key.Modifier("Ctrl", KeyCodes.MOD_CTRL), Key.Modifier("Alt", KeyCodes.MOD_ALT),
            Key.Modifier("Win", KeyCodes.MOD_GUI),
            Key.Code("←", KeyCodes.LEFT), Key.Code("↑", KeyCodes.UP),
            Key.Code("↓", KeyCodes.DOWN), Key.Code("→", KeyCodes.RIGHT)
        )

        private fun chars(s: String) = s.map { Key.Text(it) }

        private val BOTTOM_LETTERS = listOf(
            Key.Layer("?123", SYMBOLS, 1.5f), Key.Layer("Fn", FN, 1.1f), Key.Text(','),
            Key.Code("space", KeyCodes.SPACE, 4f), Key.Text('.'), Key.Code("⏎", KeyCodes.ENTER, 1.8f)
        )
        private val BOTTOM_SYMBOLS = listOf(
            Key.Layer("ABC", LETTERS, 1.5f), Key.Layer("Fn", FN, 1.1f), Key.Text(','),
            Key.Code("space", KeyCodes.SPACE, 4f), Key.Text('.'), Key.Code("⏎", KeyCodes.ENTER, 1.8f)
        )

        val LAYERS: List<List<List<Key>>> = listOf(
            // LETTERS
            listOf(
                NAV_ROW,
                chars("qwertyuiop"),
                chars("asdfghjkl"),
                listOf(Key.Modifier("⇧", KeyCodes.MOD_SHIFT, 1.5f)) + chars("zxcvbnm") +
                    Key.Code("⌫", KeyCodes.BACKSPACE, 1.5f),
                BOTTOM_LETTERS
            ),
            // SYMBOLS
            listOf(
                NAV_ROW,
                chars("1234567890"),
                chars("@#\$_&-+()/"),
                listOf(Key.Layer("=\\<", SYMBOLS2, 1.5f)) + chars("*\"':;!?") +
                    Key.Code("⌫", KeyCodes.BACKSPACE, 1.5f),
                BOTTOM_SYMBOLS
            ),
            // SYMBOLS2
            listOf(
                NAV_ROW,
                chars("~`|\\^={}[]"),
                chars("<>%") + listOf(
                    Key.Code("Del", KeyCodes.DELETE, 1.2f), Key.Code("Home", KeyCodes.HOME, 1.3f),
                    Key.Code("End", KeyCodes.END, 1.2f), Key.Code("PgUp", KeyCodes.PAGE_UP, 1.3f),
                    Key.Code("PgDn", KeyCodes.PAGE_DOWN, 1.3f)
                ),
                listOf(Key.Layer("?123", SYMBOLS, 1.5f), Key.Modifier("⇧", KeyCodes.MOD_SHIFT, 1.2f),
                    Key.Code("Ins", KeyCodes.INSERT, 1.2f), Key.Code("PrtSc", KeyCodes.PRINT_SCREEN, 1.4f),
                    Key.Code("Caps", KeyCodes.CAPS_LOCK, 1.3f)) + Key.Code("⌫", KeyCodes.BACKSPACE, 1.5f),
                BOTTOM_SYMBOLS
            ),
            // FN: function + media keys
            listOf(
                NAV_ROW,
                (1..6).map { Key.Code("F$it", KeyCodes.F1 + it - 1) },
                (7..12).map { Key.Code("F$it", KeyCodes.F1 + it - 1) },
                listOf(
                    Key.Media("Vol−", KeyCodes.MEDIA_VOL_DOWN), Key.Media("Vol+", KeyCodes.MEDIA_VOL_UP),
                    Key.Media("Mute", KeyCodes.MEDIA_MUTE), Key.Media("⏮", KeyCodes.MEDIA_PREV),
                    Key.Media("⏯", KeyCodes.MEDIA_PLAY_PAUSE), Key.Media("⏭", KeyCodes.MEDIA_NEXT)
                ),
                BOTTOM_SYMBOLS
            )
        )
    }

    private var layer = LETTERS
    private var keyRects: List<Pair<Key, RectF>> = emptyList()

    /** Modifiers armed for the next key only, and modifiers locked on by a double tap. */
    private var oneShotMods = 0
    private var lockedMods = 0
    private val lastModTap = HashMap<Int, Long>()

    /** pointerId -> key currently held by that finger. */
    private val held = HashMap<Int, Key>()

    private val density = resources.displayMetrics.density
    private val gap = 3 * density
    private val radius = 6 * density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3140.toInt() }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2530.toInt() }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C8DFF.toInt() }
    private val armedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F5FB3.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8ECF3.toInt()
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(0xFF161B23.toInt())
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (5 * 46 * density).toInt()
        val h = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        val rows = LAYERS[layer]
        val rowH = height.toFloat() / rows.size
        val out = ArrayList<Pair<Key, RectF>>()
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            // Rows with fewer keys are centred at the same key width as a 10-key row.
            val unit = width / maxOf(total, 10f)
            var x = (width - unit * total) / 2
            val top = r * rowH
            for (k in row) {
                val wk = unit * k.weight
                out += k to RectF(x + gap, top + gap, x + wk - gap, top + rowH - gap)
                x += wk
            }
        }
        keyRects = out
        textPaint.textSize = minOf(rowH * 0.38f, 20 * density)
        invalidate()
    }

    private fun activeMods() = oneShotMods or lockedMods

    private fun isShifted() = activeMods() and KeyCodes.MOD_SHIFT != 0

    override fun onDraw(canvas: Canvas) {
        val pressed = held.values.toSet()
        for ((k, rect) in keyRects) {
            val paint = when {
                k in pressed -> pressedPaint
                k is Key.Modifier && (activeMods() and k.bit) != 0 -> armedPaint
                k is Key.Text -> keyPaint
                else -> specialPaint
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            var label = if (k is Key.Text && isShifted()) k.label.uppercase() else k.label
            if (k is Key.Modifier && (lockedMods and k.bit) != 0) label += "•"
            val small = k.label.length > 2
            val oldSize = textPaint.textSize
            if (small) textPaint.textSize = oldSize * 0.72f
            val m = textPaint.fontMetrics
            canvas.drawText(label, rect.centerX(), rect.centerY() - (m.ascent + m.descent) / 2, textPaint)
            textPaint.textSize = oldSize
        }
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keyRects.firstOrNull { it.second.contains(x, y) }?.first
            // Fall back to nearest key so taps in the gaps still register.
            ?: keyRects.minByOrNull { (_, r) ->
                val dx = x - r.centerX(); val dy = y - r.centerY(); dx * dx + dy * dy
            }?.first

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                held[e.getPointerId(i)] = k
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKeyDown(k)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val k = held.remove(e.getPointerId(e.actionIndex))
                if (k != null) onKeyUp(k)
            }
            MotionEvent.ACTION_CANCEL -> {
                val keys = held.values.toList()
                held.clear()
                keys.forEach { onKeyUp(it) }
            }
        }
        invalidate()
        return true
    }

    // --------------------------------------------------------------- HID logic

    private fun onKeyDown(k: Key) {
        when (k) {
            is Key.Modifier -> {
                val now = System.currentTimeMillis()
                val last = lastModTap[k.bit] ?: 0L
                lastModTap[k.bit] = now
                when {
                    lockedMods and k.bit != 0 -> lockedMods = lockedMods and k.bit.inv()
                    now - last < 400 -> {
                        lockedMods = lockedMods or k.bit
                        oneShotMods = oneShotMods and k.bit.inv()
                    }
                    else -> oneShotMods = oneShotMods xor k.bit
                }
            }
            is Key.Layer -> {
                layer = k.target
                layoutKeys()
            }
            is Key.Media -> HidManager.tapConsumer(k.usage)
            is Key.Text, is Key.Code -> sendHeld()
        }
    }

    private fun onKeyUp(k: Key) {
        when (k) {
            is Key.Text, is Key.Code -> {
                sendHeld()
                // A one-shot modifier applies to a single keystroke; clear it once all keys lift.
                if (held.values.none { it is Key.Text || it is Key.Code }) oneShotMods = 0
            }
            else -> Unit
        }
    }

    /** Sends a keyboard report reflecting every key currently under a finger. */
    private fun sendHeld() {
        var mods = activeMods()
        val usages = ArrayList<Int>()
        for (k in held.values) {
            when (k) {
                is Key.Code -> usages += k.usage
                is Key.Text -> {
                    val s = KeyCodes.strokeFor(k.char) ?: continue
                    usages += s.usage
                    if (s.shift) mods = mods or KeyCodes.MOD_SHIFT
                }
                else -> Unit
            }
        }
        HidManager.sendKeys(mods, usages)
    }
}
