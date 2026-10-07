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
 * Controles táctiles del juego, dibujados sobre una capa oscura localizada (K12) con los roles tonales del esquema
 * Material del juego (N2, [ControlsPalette]): el juego es siempre oscuro y sin color dinámico, así que la paleta es la
 * misma en cualquier tema de la app y se lee sobre cualquier fotograma. Con [editing] se convierte en el lienzo del
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

    /** Selección al activarse una dirección nueva de la cruceta (no en cada cambio de sector). */
    var sectorFeedback: () -> Unit = { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    var renderOptions: ControlsRenderOptions = ControlsRenderOptions()
        set(value) {
            if (field == value) return
            // La geometría depende del menú, del estilo de cruceta (flechas separadas miden otra cosa) y de las diagonales.
            val geometryChanged = field.showMenu != value.showMenu || field.dpadStyle != value.dpadStyle ||
                field.diagonals != value.diagonals
            field = value
            if (geometryChanged) rebuild() else invalidate()
        }

    /**
     * Direcciones de la cruceta dibujadas como pulsadas sin que nadie toque: solo para las capturas del catálogo debug
     * (no manda nada al juego).
     */
    var previewDpadMask: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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
            dpadStyle = renderOptions.dpadStyle,
            diagonals = renderOptions.diagonals,
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
            val drawn = ControlId.entries.filter { it != ControlId.MENU || renderOptions.showMenu }
            // Primero todas las capas oscuras y después los controles: la capa de un control nunca tapa a su vecino.
            drawn.forEach { id -> drawScrim(canvas, id, options, fadeAlpha) }
            drawn.forEach { id -> drawControl(canvas, id, engine, options, fadeAlpha) }
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
            ControlId.DPAD -> drawDpad(canvas, b, engine.dpadMask or previewDpadMask, o, fade)
            ControlId.START, ControlId.SELECT -> {
                val radius = b.height / 2f
                paint.style = Paint.Style.FILL
                paint.color = fill(pressed, o, fade)
                canvas.drawRoundRect(rect, radius, radius, paint)
                ring(canvas, rect, radius, null, o, fade)
                label(canvas, label(id), b, b.height.coerceAtMost(b.width) * 0.34f, pressed, o, fade)
            }
            else -> {
                paint.style = Paint.Style.FILL
                paint.color = fill(pressed, o, fade)
                canvas.drawOval(rect, paint)
                ring(canvas, rect, b.width / 2f, if (id == ControlId.A) RING_A else if (id == ControlId.B) RING_B else null, o, fade)
                label(canvas, label(id), b, b.height * 0.42f, pressed, o, fade)
            }
        }
    }

    /**
     * Cruceta con la estructura de la de iOS (N2) y el tema Material propio: una cruz de una pieza dentro de un disco, o
     * cuatro círculos en rombo. Solo se ilumina lo pulsado: el brazo o el círculo de [mask] (dos en diagonal).
     */
    private fun drawDpad(canvas: Canvas, frame: ControlBounds, mask: Int, o: ControlsRenderOptions, fade: Float) {
        if (controlGeometry.dpadStyle == DpadStyle.ARROWS) drawArrows(canvas, frame, mask, o, fade) else drawCross(canvas, frame, mask, o, fade)
    }

    private fun drawCross(canvas: Canvas, frame: ControlBounds, mask: Int, o: ControlsRenderOptions, fade: Float) {
        val w = frame.width
        val radius = w / 2f
        val palette = o.palette
        // Disco de fondo: superficie y contorno (la capa oscura ya se dibujó en [drawScrim]).
        paint.style = Paint.Style.FILL
        paint.color = argb(o.fillAlpha(false, fade), palette.base)
        canvas.drawCircle(frame.centerX, frame.centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = o.ringWidthDp * density
        paint.color = argb(o.ringAlpha(accent = false, fade = fade), palette.outline)
        canvas.drawCircle(frame.centerX, frame.centerY, radius - paint.strokeWidth / 2f, paint)

        // La cruz es la unión de dos rectángulos redondeados: un solo trazo y un solo relleno, sin alfa doble en el centro.
        val length = DpadShape.CROSS_LENGTH * w
        val thickness = DpadShape.CROSS_THICKNESS * w
        val corner = DpadShape.CROSS_CORNER * thickness
        val horizontal = RectF(frame.centerX - length / 2f, frame.centerY - thickness / 2f, frame.centerX + length / 2f, frame.centerY + thickness / 2f)
        val vertical = RectF(frame.centerX - thickness / 2f, frame.centerY - length / 2f, frame.centerX + thickness / 2f, frame.centerY + length / 2f)
        val cross = Path().apply { addRoundRect(horizontal, corner, corner, Path.Direction.CW) }
        cross.op(Path().apply { addRoundRect(vertical, corner, corner, Path.Direction.CW) }, Path.Op.UNION)
        paint.style = Paint.Style.FILL
        paint.color = fill(false, o, fade)
        canvas.drawPath(cross, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = argb(o.ringAlpha(accent = false, fade = fade), palette.outline)
        canvas.drawPath(cross, paint)

        val arms = DpadShape.crossArms(frame)
        arms.forEach { (button, arm) ->
            val pressed = mask and button.mask != 0
            if (pressed) {
                // Solo el brazo: redondeado por fuera y recto contra el centro, que no se resalta.
                paint.style = Paint.Style.FILL
                paint.color = fill(true, o, fade)
                canvas.drawPath(armPath(button, arm, corner), paint)
            }
            val glyph = DpadShape.CROSS_THICKNESS * w * 0.5f
            DpadIcons.draw(canvas, paint, button, arm.centerX, arm.centerY, glyph, symbolColor(pressed, o, fade))
        }
    }

    private fun drawArrows(canvas: Canvas, frame: ControlBounds, mask: Int, o: ControlsRenderOptions, fade: Float) {
        DpadShape.arrowCircles(frame, controlGeometry.dpadSeparation).forEach { (button, circle) ->
            val pressed = mask and button.mask != 0
            paint.style = Paint.Style.FILL
            paint.color = fill(pressed, o, fade)
            canvas.drawCircle(circle.centerX, circle.centerY, circle.radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = o.ringWidthDp * density
            paint.color = argb(o.ringAlpha(accent = false, fade = fade), o.palette.outline)
            canvas.drawCircle(circle.centerX, circle.centerY, circle.radius - paint.strokeWidth / 2f, paint)
            DpadIcons.draw(canvas, paint, button, circle.centerX, circle.centerY, circle.radius * 0.95f, symbolColor(pressed, o, fade))
        }
        paint.style = Paint.Style.FILL
    }

    /** Un brazo de la cruz: esquinas redondeadas solo en el extremo exterior. */
    private fun armPath(button: GameBoyButton, arm: ControlBounds, corner: Float): Path {
        val c = corner
        // Orden de Android: arriba-izquierda, arriba-derecha, abajo-derecha, abajo-izquierda (x, y por esquina).
        val radii = when (button) {
            GameBoyButton.UP -> floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
            GameBoyButton.DOWN -> floatArrayOf(0f, 0f, 0f, 0f, c, c, c, c)
            GameBoyButton.LEFT -> floatArrayOf(c, c, 0f, 0f, 0f, 0f, c, c)
            else -> floatArrayOf(0f, 0f, c, c, c, c, 0f, 0f)
        }
        return Path().apply { addRoundRect(RectF(arm.left, arm.top, arm.right, arm.bottom), radii, Path.Direction.CW) }
    }

    private fun symbolColor(pressed: Boolean, o: ControlsRenderOptions, fade: Float): Int =
        argb(o.labelAlpha(fade), if (pressed) o.palette.onPressed else o.palette.onSurface)

    /**
     * Capa oscura localizada detrás de cada control: lo separa de fotogramas claros sin oscurecer toda la pantalla. Tiene
     * la forma del control con 1 dp de margen (lo que cubre el contorno): una capa más holgada dejaba un anillo gris
     * separado del borde sobre escenas claras.
     */
    private fun drawScrim(canvas: Canvas, id: ControlId, o: ControlsRenderOptions, fade: Float) {
        val b = controlGeometry.frames.getValue(id)
        val pad = SCRIM_PAD_DP * density
        paint.style = Paint.Style.FILL
        paint.color = argb(o.scrimAlpha(fade), 0)
        when {
            id == ControlId.DPAD && controlGeometry.dpadStyle == DpadStyle.ARROWS ->
                DpadShape.arrowCircles(b, controlGeometry.dpadSeparation).values.forEach {
                    canvas.drawCircle(it.centerX, it.centerY, it.radius + pad, paint)
                }
            id == ControlId.DPAD -> canvas.drawCircle(b.centerX, b.centerY, b.width / 2f + pad, paint)
            id == ControlId.START || id == ControlId.SELECT -> {
                val radius = b.height / 2f + pad
                canvas.drawRoundRect(RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad), radius, radius, paint)
            }
            else -> canvas.drawCircle(b.centerX, b.centerY, b.width / 2f + pad, paint)
        }
    }

    /** Contorno del control: [accent] (A, B) casi opaco; `null` = el neutro del tema. */
    private fun ring(canvas: Canvas, rect: RectF, radius: Float, accent: Int?, o: ControlsRenderOptions, fade: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = o.ringWidthDp * density
        paint.color = argb(o.ringAlpha(accent = accent != null, fade = fade), accent ?: o.palette.outline)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun fill(pressed: Boolean, o: ControlsRenderOptions, fade: Float): Int =
        argb(o.fillAlpha(pressed, fade), if (pressed) o.palette.pressed else o.palette.surface)

    private fun label(canvas: Canvas, text: String, bounds: ControlBounds, size: Float, pressed: Boolean, o: ControlsRenderOptions, fade: Float) {
        paint.style = Paint.Style.FILL
        paint.color = symbolColor(pressed, o, fade)
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
            if (DpadHaptics.shouldTick(lastDpad, dpad)) sectorFeedback()
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
        const val SCRIM_PAD_DP = 1f
        const val RING_A = 0xFFA04D
        const val RING_B = 0x6CB4FF
    }
}
