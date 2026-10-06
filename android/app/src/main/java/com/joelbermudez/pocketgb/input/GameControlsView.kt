package com.joelbermudez.pocketgb.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DpadStyle

/**
 * Controles táctiles del juego, dibujados con colores fijos sobre una capa oscura localizada (K12): no dependen del
 * tema ni del color dinámico, así que se leen sobre cualquier fotograma. Con [editing] se convierte en el lienzo del
 * editor: arrastrar mueve un control, tocar lo elige, y no manda nada al juego.
 */
class GameControlsView(
    context: Context,
    var onMaskChanged: (Int) -> Unit,
    var onMenu: () -> Unit = {},
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** Se lee en cada uso: con `density` en `configChanges` la actividad no se recrea al cambiar el tamaño de pantalla (DS-H2). */
    private val density: Float get() = resources.displayMetrics.density
    private var inputEngine: TouchInputEngine? = null
    private var lastMask = 0
    private var lastPressed = emptySet<ControlId>()
    private var lastDpad = 0

    /** Reducir movimiento (R11): los controles desaparecen de golpe en vez de desvanecerse. */
    var reduceMotion: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            fade.reduceMotion = value
            invalidate()
        }

    /** Reloj del desvanecido; se sustituye en las pruebas. */
    var clock: () -> Long = SystemClock::uptimeMillis
        set(value) {
            field = value
            fade = ControlsFadeController(fade.visibility, value).also { it.reduceMotion = reduceMotion }
        }
    private var fade = ControlsFadeController(ControlsVisibility.ALWAYS, clock).also { it.reduceMotion = reduceMotion }
    private val a11yHelper = ControlsAccessibilityHelper(this)
    private val fadeTick = Runnable { invalidate() }

    var hapticsEnabled: Boolean = true

    /** Impacto al pulsar A/B/Start/Select. */
    var hapticFeedback: () -> Unit = { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    /** Selección al cambiar de sector de la cruceta. */
    var sectorFeedback: () -> Unit = { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    var renderOptions: ControlsRenderOptions = ControlsRenderOptions()
        set(value) {
            if (field == value) return
            val menuChanged = field.showMenu != value.showMenu
            field = value
            if (menuChanged) rebuild() else invalidate()
        }

    var controlsVisibility: ControlsVisibility
        get() = fade.visibility
        set(value) {
            if (fade.visibility == value) return
            fade.visibility = value
            a11yHelper.invalidateRoot() // el estado «oculto» de los nodos cambia (A7-H4)
            invalidate()
        }

    /** `null` = la de fábrica de la orientación. */
    var controlLayout: ControlLayout? = null
        set(value) {
            if (field == value) return
            field = value
            dragPreview = null // el diseño guardado ya trae la posición soltada
            rebuild()
        }

    var sizeScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    var safeInsets: SafeInsets = SafeInsets.NONE
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** `null` = se deduce del tamaño (ancho > alto es horizontal). */
    var orientationOverride: ControlsOrientation? = null
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    // --- Editor ---
    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            dragPreview = null
            publishClearedInput()
            if (value) fade.restart()
            a11yHelper.invalidateRoot()
            invalidate()
        }
    var selected: ControlId? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var onEditSelect: (ControlId) -> Unit = {}
    var onEditCommit: (ControlId, NormalizedPoint) -> Unit = { _, _ -> }
    private var dragId: ControlId? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragPreview: Pair<ControlId, NormalizedPoint>? = null

    lateinit var controlGeometry: ControlGeometry
        private set

    internal val hasGeometry: Boolean get() = ::controlGeometry.isInitialized

    /** Botones pulsados desde TalkBack (R7): se combinan con OR con lo que haya en pantalla y se sueltan solos. */
    private var accessibilityMask = 0
    private val accessibilityReleases = mutableMapOf<Int, Runnable>()

    init {
        isFocusable = true
        isClickable = true
        // Sin descripción única: cada control es un nodo virtual (R7) y el raíz solo ofrece «Abrir menú».
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11yHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Pulsación accesible: mantiene [mask] durante [durationMs] por el mismo publicador que el táctil. */
    internal fun pressFor(mask: Int, durationMs: Long): Boolean {
        if (editing || mask == 0) return false
        accessibilityReleases.remove(mask)?.let(::removeCallbacks)
        accessibilityMask = accessibilityMask or mask
        if (hapticsEnabled) hapticFeedback()
        publishCombined()
        val release = Runnable {
            accessibilityReleases.remove(mask)
            accessibilityMask = accessibilityMask and mask.inv()
            publishCombined()
        }
        accessibilityReleases[mask] = release
        postDelayed(release, durationMs)
        return true
    }

    internal fun openMenuFromAccessibility(): Boolean {
        if (editing) return false
        onMenu()
        return true
    }

    private fun cancelAccessibilityPresses() {
        accessibilityReleases.values.forEach(::removeCallbacks)
        accessibilityReleases.clear()
        accessibilityMask = 0
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (hasGeometry) rebuild()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        publishClearedInput()
        a11yHelper.invalidateRoot()
        val orientation = orientationOverride
            ?: if (width > height) ControlsOrientation.LANDSCAPE else ControlsOrientation.PORTRAIT
        var layout = controlLayout ?: ControlLayout.defaults(orientation)
        dragPreview?.let { (id, point) -> layout = layout.copy(centers = layout.centers + (id to point)) }
        val insets = safeInsets
        controlGeometry = ControlGeometry(
            layout = layout,
            orientation = orientation,
            area = ControlBounds(
                insets.left.toFloat(),
                insets.top.toFloat(),
                (width - insets.right).toFloat().coerceAtLeast(insets.left + 1f),
                (height - insets.bottom).toFloat().coerceAtLeast(insets.top + 1f),
            ),
            density = density,
            sizeScale = sizeScale,
            showMenu = renderOptions.showMenu,
        )
        inputEngine = TouchInputEngine(controlGeometry)
        updateGestureExclusion()
        invalidate()
    }

    private fun updateGestureExclusion() {
        val rects = GestureExclusion.rects(controlGeometry, width.toFloat(), density).map {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        ViewCompat.setSystemGestureExclusionRects(this, rects)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::controlGeometry.isInitialized) return false
        if (editing) return onEditorTouch(event)
        val engine = inputEngine ?: return false
        fade.onTouch()
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

    private fun onEditorTouch(event: MotionEvent): Boolean {
        val point = ControlPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = pickControl(point)
                dragId = id
                if (id != null) {
                    val frame = controlGeometry.frames.getValue(id)
                    dragOffsetX = frame.centerX - point.x
                    dragOffsetY = frame.centerY - point.y
                    selected = id
                    onEditSelect(id)
                }
            }
            MotionEvent.ACTION_MOVE -> dragId?.let { id ->
                val target = ControlPoint(point.x + dragOffsetX, point.y + dragOffsetY)
                dragPreview = id to controlGeometry.snappedCenter(id, target)
                rebuild()
            }
            MotionEvent.ACTION_UP -> {
                val preview = dragPreview
                dragId = null
                // El diseño guardado llega por `controlLayout`; mientras tanto se conserva la vista previa.
                if (preview != null) onEditCommit(preview.first, preview.second)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragId = null
                if (dragPreview != null) {
                    dragPreview = null
                    rebuild()
                }
            }
        }
        return true
    }

    /** El control tocado en el editor: el más pequeño bajo el dedo, para poder elegir uno que tape a otro. */
    private fun pickControl(point: ControlPoint): ControlId? = ControlId.entries
        .filter { (it != ControlId.MENU || renderOptions.showMenu) && controlGeometry.touchFrame(it).contains(point) }
        .minByOrNull { controlGeometry.touchFrame(it).let { frame -> frame.width * frame.height } }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = inputEngine ?: return
        val fadeAlpha = if (editing) 1f else fade.alpha()
        if (fadeAlpha > 0f) {
            // En el editor los controles se ven siempre al 100 %: la opacidad elegida no se aplica.
            val options = if (editing) renderOptions.copy(opacity = 100) else renderOptions
            ControlId.entries.forEach { id ->
                if (id == ControlId.MENU && !renderOptions.showMenu) return@forEach
                drawControl(canvas, id, engine, options, fadeAlpha)
            }
        }
        if (!editing) drawHint(canvas)
        if (editing) selected?.let { drawSelection(canvas, it) }
        fade.msUntilChange()?.let { delay ->
            removeCallbacks(fadeTick)
            postDelayed(fadeTick, delay)
        }
    }

    private fun drawHint(canvas: Canvas) {
        val alpha = fade.hintAlpha()
        if (alpha <= 0f) return
        val text = context.getString(R.string.controls_hidden_hint)
        paint.style = Paint.Style.FILL
        paint.textSize = 13f * density
        val width = paint.measureText(text) + 28f * density
        val height = 28f * density
        val bounds = ControlBounds(0f, 0f, this.width.toFloat(), this.height.toFloat())
        val rect = RectF(
            bounds.centerX - width / 2f,
            bounds.bottom - 24f * density - height / 2f,
            bounds.centerX + width / 2f,
            bounds.bottom - 24f * density + height / 2f,
        )
        paint.color = argb(0.55f * alpha, 0)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        paint.color = argb(alpha, 0xFFFFFF)
        canvas.drawText(text, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawSelection(canvas: Canvas, id: ControlId) {
        val b = controlGeometry.frames.getValue(id)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.color = 0xFFFFFFFF.toInt()
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        val pad = 6f * density
        canvas.drawRoundRect(RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad), 14f * density, 14f * density, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
    }

    private fun drawControl(canvas: Canvas, id: ControlId, engine: TouchInputEngine, o: ControlsRenderOptions, fade: Float) {
        val b = controlGeometry.frames.getValue(id)
        val pressed = id in engine.pressed
        val rect = RectF(b.left, b.top, b.right, b.bottom)
        when (id) {
            ControlId.DPAD -> drawDpad(canvas, b, engine.dpadMask, o, fade)
            ControlId.START, ControlId.SELECT -> {
                val radius = b.height / 2f
                scrim(canvas, rect, radius, o, fade)
                paint.style = Paint.Style.FILL
                paint.color = fill(pressed, o, fade)
                canvas.drawRoundRect(rect, radius, radius, paint)
                ring(canvas, rect, radius, RING_NEUTRAL, o, fade)
                label(canvas, label(id), b, b.height.coerceAtMost(b.width) * 0.34f, o, fade)
            }
            else -> {
                scrim(canvas, rect, b.width / 2f, o, fade)
                paint.style = Paint.Style.FILL
                paint.color = fill(pressed, o, fade)
                canvas.drawOval(rect, paint)
                ring(canvas, rect, b.width / 2f, if (id == ControlId.A) RING_A else if (id == ControlId.B) RING_B else RING_NEUTRAL, o, fade)
                label(canvas, label(id), b, b.height * 0.42f, o, fade)
            }
        }
    }

    private fun drawDpad(canvas: Canvas, frame: ControlBounds, mask: Int, o: ControlsRenderOptions, fade: Float) {
        val arms = ControlGeometry.dpadArms(frame)
        val third = frame.width / 3f
        val round = third * 0.28f
        val arrows = renderOptions.dpadStyle == DpadStyle.ARROWS
        paint.style = Paint.Style.FILL
        if (arrows) {
            arms.forEach { (button, arm) ->
                val gap = third * 0.12f
                val rect = RectF(arm.left + gap, arm.top + gap, arm.right - gap, arm.bottom - gap)
                scrim(canvas, rect, round, o, fade)
                paint.style = Paint.Style.FILL
                paint.color = fill(mask and button.mask != 0, o, fade)
                canvas.drawRoundRect(rect, round, round, paint)
                ring(canvas, rect, round, RING_NEUTRAL, o, fade)
                arrowIn(canvas, button, rect, o, fade)
            }
        } else {
            // Cruz de una pieza: oscurece y rellena los brazos y el centro; el brazo pulsado se ilumina.
            val horizontal = RectF(frame.left, frame.top + third, frame.right, frame.bottom - third)
            val vertical = RectF(frame.left + third, frame.top, frame.right - third, frame.bottom)
            scrim(canvas, horizontal, round, o, fade)
            scrim(canvas, vertical, round, o, fade)
            paint.style = Paint.Style.FILL
            paint.color = fill(false, o, fade)
            canvas.drawRoundRect(horizontal, round, round, paint)
            canvas.drawRoundRect(vertical, round, round, paint)
            arms.forEach { (button, arm) ->
                if (mask and button.mask != 0) {
                    paint.color = fill(true, o, fade)
                    canvas.drawRoundRect(RectF(arm.left, arm.top, arm.right, arm.bottom), round, round, paint)
                }
                arrow(canvas, button, arm, o, fade)
            }
            val cross = Path().apply {
                addRoundRect(horizontal, round, round, Path.Direction.CW)
                addRoundRect(vertical, round, round, Path.Direction.CW)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = argb(o.ringAlpha(accent = false, fade = fade), RING_NEUTRAL)
            canvas.drawPath(cross, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun arrowIn(canvas: Canvas, button: GameBoyButton, rect: RectF, o: ControlsRenderOptions, fade: Float) = arrow(
        canvas, button, ControlBounds(rect.left, rect.top, rect.right, rect.bottom), o, fade,
    )

    private fun arrow(canvas: Canvas, button: GameBoyButton, arm: ControlBounds, o: ControlsRenderOptions, fade: Float) {
        val size = arm.width.coerceAtMost(arm.height) * 0.26f
        val cx = arm.centerX
        val cy = arm.centerY
        val path = Path()
        when (button) {
            GameBoyButton.UP -> { path.moveTo(cx, cy - size); path.lineTo(cx - size, cy + size * 0.7f); path.lineTo(cx + size, cy + size * 0.7f) }
            GameBoyButton.DOWN -> { path.moveTo(cx, cy + size); path.lineTo(cx - size, cy - size * 0.7f); path.lineTo(cx + size, cy - size * 0.7f) }
            GameBoyButton.LEFT -> { path.moveTo(cx - size, cy); path.lineTo(cx + size * 0.7f, cy - size); path.lineTo(cx + size * 0.7f, cy + size) }
            else -> { path.moveTo(cx + size, cy); path.lineTo(cx - size * 0.7f, cy - size); path.lineTo(cx - size * 0.7f, cy + size) }
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = argb(o.labelAlpha(fade), 0xFFFFFF)
        canvas.drawPath(path, paint)
    }

    /** Capa oscura localizada detrás de cada control: lo separa de fotogramas claros sin oscurecer toda la pantalla. */
    private fun scrim(canvas: Canvas, rect: RectF, radius: Float, o: ControlsRenderOptions, fade: Float) {
        val pad = 5f * density
        paint.style = Paint.Style.FILL
        paint.color = argb(o.scrimAlpha(fade), 0)
        canvas.drawRoundRect(RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad), radius + pad, radius + pad, paint)
    }

    private fun ring(canvas: Canvas, rect: RectF, radius: Float, color: Int, o: ControlsRenderOptions, fade: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = o.ringWidthDp * density
        paint.color = argb(o.ringAlpha(accent = color != RING_NEUTRAL, fade = fade), color)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun fill(pressed: Boolean, o: ControlsRenderOptions, fade: Float): Int =
        argb(o.fillAlpha(pressed, fade), if (pressed) FILL_PRESSED else FILL_NEUTRAL)

    private fun label(canvas: Canvas, text: String, bounds: ControlBounds, size: Float, o: ControlsRenderOptions, fade: Float) {
        paint.style = Paint.Style.FILL
        paint.color = argb(o.labelAlpha(fade), 0xFFFFFF)
        paint.textSize = size
        canvas.drawText(text, bounds.centerX, bounds.centerY - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    fun release() {
        removeCallbacks(fadeTick)
        publishClearedInput()
    }

    private fun publishCombined() {
        val mask = (inputEngine?.mask ?: 0) or accessibilityMask
        if (mask != lastMask) {
            lastMask = mask
            onMaskChanged(mask)
        }
        invalidate()
    }

    private fun publish(engine: TouchInputEngine) {
        val pressed = engine.pressed
        val dpad = engine.dpadMask
        if (hapticsEnabled) {
            if ((pressed - lastPressed - ControlId.DPAD).isNotEmpty()) hapticFeedback()
            if (dpad != 0 && dpad != lastDpad) sectorFeedback()
        }
        lastPressed = pressed
        lastDpad = dpad
        publishCombined()
    }

    private fun publishClearedInput() {
        inputEngine?.cancelAll()
        cancelAccessibilityPresses()
        lastPressed = emptySet()
        lastDpad = 0
        if (lastMask != 0) {
            lastMask = 0
            onMaskChanged(0)
        }
        invalidate()
    }

    private fun MotionEvent.pointAt(index: Int) = ControlPoint(getX(index), getY(index))

    private fun label(id: ControlId): String = when (id) {
        ControlId.DPAD -> ""
        ControlId.A -> "A"
        ControlId.B -> "B"
        ControlId.START -> "START"
        ControlId.SELECT -> "SELECT"
        ControlId.MENU -> "MENÚ"
    }

    private fun argb(alpha: Float, rgb: Int): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    private companion object {
        const val FILL_NEUTRAL = 0x1F2024
        const val FILL_PRESSED = 0x4A4D57
        const val RING_NEUTRAL = 0xC9CDD6
        const val RING_A = 0xFFA04D
        const val RING_B = 0x6CB4FF
    }
}
