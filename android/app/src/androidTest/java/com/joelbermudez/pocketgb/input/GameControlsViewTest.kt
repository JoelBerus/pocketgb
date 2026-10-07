package com.joelbermudez.pocketgb.input

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameControlsViewTest {
    @Test
    fun slideHapticsAndCancelPublishExpectedMasks() {
        val masks = mutableListOf<Int>()
        var haptics = 0
        val view = GameControlsView(
            context = ApplicationProvider.getApplicationContext(),
            onMaskChanged = masks::add,
        ).apply {
            hapticFeedback = { haptics += 1 }
            layout(0, 0, 400, 700)
        }
        val b = view.controlGeometry.frames.getValue(ControlId.B)
        val a = view.controlGeometry.frames.getValue(ControlId.A)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, b.centerX, b.centerY))
        assertEquals(GameBoyButton.B.mask, masks.last())
        assertEquals(1, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, b.centerX, b.centerY))
        assertEquals(1, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, a.centerX, a.centerY))
        assertEquals(GameBoyButton.A.mask, masks.last())
        assertEquals(2, haptics)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, a.centerX, a.centerY))
        assertEquals(0, masks.last())
    }

    @Test
    fun releasingViewAlwaysClearsInput() {
        val masks = mutableListOf<Int>()
        val view = GameControlsView(
            context = ApplicationProvider.getApplicationContext(),
            onMaskChanged = masks::add,
        ).apply { layout(0, 0, 400, 700) }
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))

        view.release()

        assertEquals(0, masks.last())
    }

    private fun view(masks: MutableList<Int> = mutableListOf()) = GameControlsView(
        context = ApplicationProvider.getApplicationContext(),
        onMaskChanged = masks::add,
    ).apply { layout(0, 0, 400, 700) }

    @Test
    fun opacityIsVisualOnlyAndNeverChangesTheTouchArea() {
        val results = mutableListOf<Pair<Int, Int>>()
        listOf(30, 100).forEach { opacity ->
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(opacity = opacity)
            val b = view.controlGeometry.frames.getValue(ControlId.B)
            // Borde del área táctil (más allá del dibujo): se responde igual con cualquier opacidad.
            val touch = view.controlGeometry.touchFrame(ControlId.B)
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, b.centerX, b.centerY))
            results += opacity to masks.last()
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, b.centerX, b.centerY))
            assertEquals(touch, view.controlGeometry.touchFrame(ControlId.B))
        }
        assertEquals(GameBoyButton.B.mask, results[0].second)
        assertEquals(results[0].second, results[1].second)
    }

    @Test
    fun hiddenControlsStillRespondToTouches() {
        val masks = mutableListOf<Int>()
        val view = view(masks)
        view.controlsVisibility = ControlsVisibility.HIDDEN
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(GameBoyButton.A.mask, masks.last())
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, a.centerX, a.centerY))
        assertEquals(0, masks.last())
    }

    @Test
    fun dpadGivesASectorTickPerSectorChangeAndFaceButtonsAnImpact() {
        var impacts = 0
        var ticks = 0
        val view = view().apply {
            hapticFeedback = { impacts += 1 }
            sectorFeedback = { ticks += 1 }
        }
        val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, dpad.left + 3f, dpad.centerY))
        assertEquals("sector izquierda", 1, ticks)
        assertEquals("la cruceta no da impacto", 0, impacts)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.left + 3f, dpad.centerY))
        assertEquals("mismo sector: sin háptica", 1, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.centerX, dpad.top + 3f))
        assertEquals("sector arriba", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dpad.centerX, dpad.centerY))
        assertEquals("zona muerta: sin háptica", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dpad.centerX, dpad.centerY))

        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(1, impacts)
        assertEquals(2, ticks)
    }

    @Test
    fun hapticsOffGivesNoFeedback() {
        var count = 0
        val view = view().apply {
            hapticsEnabled = false
            hapticFeedback = { count += 1 }
            sectorFeedback = { count += 1 }
        }
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, a.centerX, a.centerY))
        assertEquals(0, count)
    }

    @Test
    fun theMenuControlIsNotDrawnNorTouchableByDefault() {
        var menus = 0
        val view = view().apply { onMenu = { menus += 1 } }
        val menu = view.controlGeometry.frames.getValue(ControlId.MENU)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, menu.centerX, menu.centerY))
        assertEquals(0, menus)
        assertEquals(false, view.renderOptions.showMenu)
    }

    @Test
    fun arrowsKeepTheSameSectorsAsTheCross() {
        listOf(DpadStyle.CROSS, DpadStyle.ARROWS).forEach { style ->
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(dpadStyle = style)
            val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, dpad.centerX, dpad.top + 3f))
            assertEquals("$style", GameBoyButton.UP.mask, masks.last())
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dpad.centerX, dpad.top + 3f))
        }
    }

    // ---- N2: cruceta con la estructura de iOS

    /** Dibuja la vista sobre negro y devuelve el color de un punto. */
    private fun render(view: GameControlsView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun luma(argb: Int) = 0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)

    @Test
    fun onlyThePressedArmOfTheCrossIsLitAndTheCentreIsNever() {
        for (pressed in listOf(GameBoyButton.UP, GameBoyButton.RIGHT)) {
            val view = view()
            view.previewDpadMask = pressed.mask
            val bitmap = render(view)
            val frame = view.controlGeometry.frames.getValue(ControlId.DPAD)
            val w = frame.width
            // Un punto de cada brazo apartado del símbolo (a 0,11 W del eje) y uno del centro de la cruz.
            fun sample(button: GameBoyButton): Int {
                val arm = DpadShape.crossArms(frame).getValue(button)
                val horizontal = button == GameBoyButton.LEFT || button == GameBoyButton.RIGHT
                val x = if (horizontal) arm.centerX else arm.centerX + 0.11f * w
                val y = if (horizontal) arm.centerY + 0.11f * w else arm.centerY
                return bitmap.getPixel(x.toInt(), y.toInt())
            }
            val lit = luma(sample(pressed))
            val others = listOf(GameBoyButton.UP, GameBoyButton.DOWN, GameBoyButton.LEFT, GameBoyButton.RIGHT).filter { it != pressed }
            others.forEach { assertTrue("$pressed pulsado: $it también se ilumina", luma(sample(it)) < lit * 0.6) }
            val centre = luma(bitmap.getPixel((frame.centerX + 0.05f * w).toInt(), (frame.centerY + 0.05f * w).toInt()))
            assertEquals("el centro de la cruz no se resalta", luma(sample(others.first())), centre, 6.0)
        }
    }

    @Test
    fun onlyThePressedCircleOfSeparatedArrowsIsLit() {
        val view = view()
        view.renderOptions = ControlsRenderOptions(dpadStyle = DpadStyle.ARROWS)
        view.previewDpadMask = GameBoyButton.UP.mask or GameBoyButton.RIGHT.mask // diagonal: dos encendidas
        val bitmap = render(view)
        val frame = view.controlGeometry.frames.getValue(ControlId.DPAD)
        val circles = DpadShape.arrowCircles(frame, view.controlGeometry.dpadSeparation)
        fun sample(button: GameBoyButton): Double {
            val c = circles.getValue(button)
            // Punto del círculo apartado del símbolo (que ocupa el centro): a 0,7 del radio por debajo.
            return luma(bitmap.getPixel(c.centerX.toInt(), (c.centerY + c.radius * 0.7f).toInt()))
        }
        val up = sample(GameBoyButton.UP)
        val right = sample(GameBoyButton.RIGHT)
        val left = sample(GameBoyButton.LEFT)
        val down = sample(GameBoyButton.DOWN)
        assertTrue("↑ encendida ($up) frente a ← ($left)", up > left * 1.6)
        assertTrue("→ encendida ($right) frente a ↓ ($down)", right > down * 1.6)
        assertEquals("← y ↓ siguen neutras", left, down, 6.0)
    }

    @Test
    fun theDiagonalModeInTheRenderOptionsChangesWhatAFingerAt25DegreesPresses() {
        val expected = mapOf(
            DiagonalMode.NORMAL to (GameBoyButton.RIGHT.mask or GameBoyButton.UP.mask),
            DiagonalMode.REDUCED to GameBoyButton.RIGHT.mask,
            DiagonalMode.DISABLED to GameBoyButton.RIGHT.mask,
        )
        expected.forEach { (mode, mask) ->
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(diagonals = mode)
            val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
            val r = dpad.width * 0.4f
            val x = dpad.centerX + (kotlin.math.cos(Math.toRadians(25.0)) * r).toFloat()
            val y = dpad.centerY - (kotlin.math.sin(Math.toRadians(25.0)) * r).toFloat()
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
            assertEquals("$mode", mask, masks.last())
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y))
        }
    }

    @Test
    fun jitterOfEightDpAroundTheUpArmGivesOnlyUpThroughTheRealView() {
        for (style in DpadStyle.entries) {
            val masks = mutableListOf<Int>()
            val view = view(masks)
            view.renderOptions = ControlsRenderOptions(dpadStyle = style, diagonals = DiagonalMode.REDUCED)
            val frame = view.controlGeometry.frames.getValue(ControlId.DPAD)
            val centre = when (style) {
                DpadStyle.CROSS -> DpadShape.crossArms(frame).getValue(GameBoyButton.UP).let { it.centerX to it.centerY }
                DpadStyle.ARROWS -> DpadShape.arrowCircles(frame, 1f).getValue(GameBoyButton.UP).let { it.centerX to it.centerY }
            }
            val jitter = 8f * view.resources.displayMetrics.density
            var state = 20_261_006L
            fun next(): Float {
                state = state * 6364136223846793005L + 1442695040888963407L
                return ((state ushr 33).toInt() and 0x7FFFFFFF) / 2147483647f
            }
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, centre.first, centre.second))
            var onlyUp = 0
            val samples = 1000
            repeat(samples) {
                val x = centre.first + (next() * 2f - 1f) * jitter
                val y = centre.second + (next() * 2f - 1f) * jitter
                view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, y))
                if (masks.last() == GameBoyButton.UP.mask) onlyUp += 1
            }
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, centre.first, centre.second))
            assertTrue("$style: solo ↑ en $onlyUp de $samples", onlyUp >= samples * 99 / 100)
        }
    }

    @Test
    fun aTouchInsideAnArrowCircleGivesItsDirectionThroughTheRealViewEvenNearTheSideOfTheButton() {
        for (diagonals in DiagonalMode.entries) {
            val masks = mutableListOf<Int>()
            // Una vista grande (en px reales, con la densidad del emulador) para que la cruceta no invada a A/B.
            val view = GameControlsView(ApplicationProvider.getApplicationContext(), onMaskChanged = masks::add)
                .apply { layout(0, 0, 1080, 1920) }
            view.renderOptions = ControlsRenderOptions(dpadStyle = DpadStyle.ARROWS, diagonals = diagonals)
            val frame = view.controlGeometry.frames.getValue(ControlId.DPAD)
            val circles = DpadShape.arrowCircles(frame, view.controlGeometry.dpadSeparation)
            circles.forEach { (button, circle) ->
                // Ocho puntos al 90 % del radio alrededor del centro del botón (los laterales dan >30° respecto al eje del grupo).
                for (step in 0 until 8) {
                    val angle = Math.toRadians(step * 45.0)
                    val x = circle.centerX + (kotlin.math.cos(angle) * circle.radius * 0.9f).toFloat()
                    val y = circle.centerY + (kotlin.math.sin(angle) * circle.radius * 0.9f).toFloat()
                    view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
                    assertEquals("$diagonals: $button, punto $step", button.mask, masks.last())
                    view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y))
                }
            }
        }
    }

    @Test
    fun hapticTicksOnlyWhenANewDirectionIsActivated() {
        var ticks = 0
        val view = view().apply { sectorFeedback = { ticks += 1 } }
        val dpad = view.controlGeometry.frames.getValue(ControlId.DPAD)
        val r = dpad.width * 0.4f
        fun at(degrees: Double) = (dpad.centerX + (kotlin.math.cos(Math.toRadians(degrees)) * r).toFloat()) to
            (dpad.centerY - (kotlin.math.sin(Math.toRadians(degrees)) * r).toFloat())
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, at(90.0).first, at(90.0).second))
        assertEquals("arriba", 1, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, at(60.0).first, at(60.0).second)) // aún arriba (frontera 60°+histéresis)
        assertEquals(1, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, at(45.0).first, at(45.0).second)) // diagonal: se activa → nueva
        assertEquals("diagonal ↑→", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, at(75.0).first, at(75.0).second)) // vuelve a ↑: no se activa nada nuevo
        assertEquals("soltar → sin háptica", 2, ticks)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, at(75.0).first, at(75.0).second))
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        0L,
        10L,
        action,
        x,
        y,
        0,
    )
}
