package com.joelbermudez.pocketgb.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class GameControlsView(
    context: Context,
    var onMaskChanged: (Int) -> Unit,
    var onMenu: () -> Unit = {},
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var inputEngine: TouchInputEngine? = null
    private var lastMask = 0
    private var lastPressed = emptySet<ControlId>()

    var hapticsEnabled: Boolean = true
    var foregroundColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }
    var controlBackgroundColor: Int = 0x88404040.toInt()
        set(value) {
            field = value
            invalidate()
        }
    var pressedColor: Int = 0xCC707070.toInt()
        set(value) {
            field = value
            invalidate()
        }
    var hapticFeedback: () -> Unit = {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    lateinit var controlGeometry: ControlGeometry
        private set

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Controles del juego: cruceta, A, B, Start, Select y Menú"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        publishClearedInput()
        val orientation = if (width > height) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
        controlGeometry = ControlGeometry(
            layout = ControlLayout.defaults(orientation),
            orientation = orientation,
            area = ControlBounds(0f, 0f, width.toFloat(), height.toFloat()),
            density = resources.displayMetrics.density,
        )
        inputEngine = TouchInputEngine(controlGeometry)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = inputEngine ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (engine.pointerDown(event.getPointerId(index), event.pointAt(index))) {
                    if (hapticsEnabled) hapticFeedback()
                    onMenu()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { index ->
                    engine.pointerMove(event.getPointerId(index), event.pointAt(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                engine.pointerUp(event.getPointerId(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> engine.cancelAll()
        }
        publish(engine)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = inputEngine ?: return
        ControlId.entries.forEach { id ->
            val bounds = controlGeometry.frames.getValue(id)
            paint.color = if (id in engine.pressed) pressedColor else controlBackgroundColor
            val rect = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            when (id) {
                ControlId.START, ControlId.SELECT -> canvas.drawRoundRect(rect, bounds.height / 2f, bounds.height / 2f, paint)
                else -> canvas.drawOval(rect, paint)
            }
            paint.color = foregroundColor
            paint.textSize = when (id) {
                ControlId.A, ControlId.B -> bounds.height * 0.42f
                else -> bounds.height.coerceAtMost(bounds.width) * 0.18f
            }
            canvas.drawText(label(id), bounds.centerX, textBaseline(bounds), paint)
        }
    }

    fun release() {
        publishClearedInput()
    }

    private fun publish(engine: TouchInputEngine) {
        val pressed = engine.pressed
        if (hapticsEnabled && (pressed - lastPressed).isNotEmpty()) hapticFeedback()
        lastPressed = pressed
        val mask = engine.mask
        if (mask != lastMask) {
            lastMask = mask
            onMaskChanged(mask)
        }
        invalidate()
    }

    private fun publishClearedInput() {
        inputEngine?.cancelAll()
        lastPressed = emptySet()
        if (lastMask != 0) {
            lastMask = 0
            onMaskChanged(0)
        }
        invalidate()
    }

    private fun MotionEvent.pointAt(index: Int) = ControlPoint(getX(index), getY(index))

    private fun label(id: ControlId): String = when (id) {
        ControlId.DPAD -> "✦"
        ControlId.A -> "A"
        ControlId.B -> "B"
        ControlId.START -> "START"
        ControlId.SELECT -> "SELECT"
        ControlId.MENU -> "MENÚ"
    }

    private fun textBaseline(bounds: ControlBounds): Float =
        bounds.centerY - (paint.ascent() + paint.descent()) / 2f
}
