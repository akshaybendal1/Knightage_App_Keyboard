package com.knightage.btkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Laptop-style touchpad.
 *  - 1 finger drag: move pointer        - 1 finger tap: left click
 *  - 2 finger drag: scroll              - 2 finger tap: right click
 *  - double-tap and hold, then drag: click-and-drag (select text, move windows)
 * A strip of L / M / R buttons sits at the bottom for press-and-hold dragging.
 */
class TouchpadView(context: Context) : View(context) {

    /** Pointer speed multiplier, adjustable from the UI. */
    var sensitivity = 1.6f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = 220L
    private val doubleTapWindow = 300L
    private val scrollStepPx = 18 * density

    private val padRect = RectF()
    private val buttonRects = Array(3) { RectF() }
    private val buttonBits = intArrayOf(0x01, 0x04, 0x02) // left, middle, right
    private val buttonLabels = arrayOf("L", "M", "R")

    private var heldButtons = 0          // from the on-screen L/M/R buttons
    private var dragButton = 0           // from the double-tap-and-drag gesture
    private val buttonPointers = HashMap<Int, Int>() // pointerId -> button bit

    // gesture state for the pad area
    private var padPointer = -1
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxFingers = 0
    private var moved = false
    private var lastTapUp = 0L
    private var accX = 0f
    private var accY = 0f
    private var accScroll = 0f

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2530.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A4352.toInt(); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3140.toInt() }
    private val btnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C8DFF.toInt() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7587.toInt(); textAlign = Paint.Align.CENTER; textSize = 13 * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8ECF3.toInt(); textAlign = Paint.Align.CENTER; textSize = 15 * density
    }

    init {
        setBackgroundColor(0xFF161B23.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = 6 * density
        val btnH = 44 * density
        padRect.set(m, m, w - m, h - btnH - 2 * m)
        val bw = (w - 2 * m) / 3f
        for (i in 0..2) {
            val l = m + i * bw
            buttonRects[i].set(l + m / 2, h - btnH - m, l + bw - m / 2, h - m)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = 12 * density
        canvas.drawRoundRect(padRect, r, r, padPaint)
        canvas.drawRoundRect(padRect, r, r, borderPaint)
        val cy = padRect.centerY()
        canvas.drawText("Tap = click · 2-finger tap = right click", padRect.centerX(), cy - 10 * density, hintPaint)
        canvas.drawText("2-finger drag = scroll · double-tap & hold = drag", padRect.centerX(), cy + 12 * density, hintPaint)
        for (i in 0..2) {
            val pressed = (heldButtons and buttonBits[i]) != 0
            canvas.drawRoundRect(buttonRects[i], r / 2, r / 2, if (pressed) btnPressedPaint else btnPaint)
            val b = buttonRects[i]
            canvas.drawText(buttonLabels[i], b.centerX(), b.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
        }
    }

    private fun buttons() = heldButtons or dragButton

    private fun buttonAt(x: Float, y: Float): Int {
        for (i in 0..2) if (buttonRects[i].contains(x, y)) return buttonBits[i]
        return 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i); val y = e.getY(i)
                val bit = buttonAt(x, y)
                if (bit != 0) {
                    buttonPointers[e.getPointerId(i)] = bit
                    heldButtons = heldButtons or bit
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    HidManager.sendMouse(buttons(), 0, 0, 0)
                    invalidate()
                } else if (padPointer == -1) {
                    startPadGesture(e.getPointerId(i), x, y, e.eventTime)
                } else {
                    // Second finger on the pad: switches the gesture to scroll / right-click.
                    maxFingers = maxOf(maxFingers, padFingerCount(e))
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(i)
                val bit = buttonPointers.remove(id)
                if (bit != null) {
                    heldButtons = heldButtons and bit.inv()
                    HidManager.sendMouse(buttons(), 0, 0, 0)
                    invalidate()
                } else if (padPointer != -1 && (id == padPointer || e.actionMasked != MotionEvent.ACTION_POINTER_UP)) {
                    endPadGesture(e)
                    // Hand tracking to a remaining pad finger, if any.
                    for (p in 0 until e.pointerCount) {
                        val pid = e.getPointerId(p)
                        if (p != i && pid !in buttonPointers && e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                            padPointer = pid; lastX = e.getX(p); lastY = e.getY(p)
                            moved = true // a leftover finger never counts as a tap
                            break
                        }
                    }
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    buttonPointers.clear(); heldButtons = 0; dragButton = 0
                    HidManager.sendMouse(0, 0, 0, 0)
                }
            }
        }
        return true
    }

    private fun padFingerCount(e: MotionEvent): Int {
        var n = 0
        for (p in 0 until e.pointerCount) if (e.getPointerId(p) !in buttonPointers) n++
        return n
    }

    private fun startPadGesture(id: Int, x: Float, y: Float, t: Long) {
        padPointer = id
        downX = x; downY = y; lastX = x; lastY = y
        downTime = t
        maxFingers = 1
        moved = false
        accX = 0f; accY = 0f; accScroll = 0f
        // Second tap shortly after a tap: hold the left button for dragging.
        if (t - lastTapUp < doubleTapWindow) {
            dragButton = 0x01
            HidManager.sendMouse(buttons(), 0, 0, 0)
        }
    }

    private fun handleMove(e: MotionEvent) {
        val p = e.findPointerIndex(padPointer)
        if (p < 0) return
        val x = e.getX(p); val y = e.getY(p)
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (!moved && hypot(x - downX, y - downY) > touchSlop) moved = true
        if (!moved) return

        val fingers = padFingerCount(e)
        if (fingers >= 2) {
            accScroll += dy
            val steps = (accScroll / scrollStepPx).toInt()
            if (steps != 0) {
                accScroll -= steps * scrollStepPx
                // Natural scrolling: content follows the fingers.
                HidManager.sendMouse(buttons(), 0, 0, steps)
            }
        } else {
            // Mild acceleration: faster swipes travel proportionally further.
            val speed = hypot(dx, dy)
            val accel = 1f + (speed / (12 * density)).coerceAtMost(2f)
            accX += dx * sensitivity * accel / density
            accY += dy * sensitivity * accel / density
            val ix = accX.toInt(); val iy = accY.toInt()
            if (ix != 0 || iy != 0) {
                accX -= ix; accY -= iy
                HidManager.sendMouse(buttons(), ix, iy, 0)
            }
        }
    }

    private fun endPadGesture(e: MotionEvent) {
        val dt = e.eventTime - downTime
        val wasTap = !moved && dt < tapTimeout
        if (dragButton != 0) {
            dragButton = 0
            HidManager.sendMouse(buttons(), 0, 0, 0)
            lastTapUp = 0
        } else if (wasTap) {
            val bit = if (maxFingers >= 2) 0x02 else 0x01
            HidManager.sendMouse(buttons() or bit, 0, 0, 0)
            HidManager.sendMouse(buttons(), 0, 0, 0)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            lastTapUp = if (maxFingers == 1) e.eventTime else 0
        }
        padPointer = -1
        accScroll = 0f
    }
}
