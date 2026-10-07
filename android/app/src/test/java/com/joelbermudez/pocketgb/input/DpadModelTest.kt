package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DiagonalMode
import com.joelbermudez.pocketgb.settings.DpadStyle
import com.joelbermudez.pocketgb.settings.MAX_DPAD_SEPARATION
import com.joelbermudez.pocketgb.settings.MIN_DPAD_SEPARATION
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N2: sectores por modo, zona muerta, histéresis, proporciones de la cruceta y háptica. */
class DpadModelTest {
    private val up = GameBoyButton.UP.mask
    private val down = GameBoyButton.DOWN.mask
    private val left = GameBoyButton.LEFT.mask
    private val right = GameBoyButton.RIGHT.mask
    private val radius = 100f

    /** Máscara a [degrees] (0 = derecha, antihorario) y a [fraction] del radio. */
    private fun at(degrees: Float, mode: DiagonalMode, fraction: Float = 0.8f, previous: Int = 0): Int {
        val radians = Math.toRadians(degrees.toDouble())
        return DpadSectors.mask(
            dx = (cos(radians) * fraction * radius).toFloat(),
            dy = (-sin(radians) * fraction * radius).toFloat(),
            radius = radius,
            mode = mode,
            previous = previous,
        )
    }

    // ---- Sectores por modo

    @Test
    fun reducedDiagonalsOnlyCountWithin15DegreesOfTheDiagonal() {
        val m = DiagonalMode.REDUCED
        assertEquals(right, at(0f, m))
        assertEquals(right, at(20f, m))
        assertEquals(right, at(29f, m))
        assertEquals(right or up, at(31f, m))
        assertEquals(right or up, at(45f, m))
        assertEquals(right or up, at(59f, m))
        assertEquals(up, at(61f, m))
        assertEquals(up, at(90f, m))
        assertEquals(up, at(119f, m))
        assertEquals(up or left, at(135f, m))
        assertEquals(left, at(180f, m))
        assertEquals(left or down, at(225f, m))
        assertEquals(down, at(270f, m))
        assertEquals(down or right, at(315f, m))
        assertEquals(right, at(331f, m))
    }

    @Test
    fun normalDiagonalsKeepEightSectorsOf45Degrees() {
        val m = DiagonalMode.NORMAL
        assertEquals(right, at(22f, m))
        assertEquals(right or up, at(23f, m))
        assertEquals(right or up, at(67f, m))
        assertEquals(up, at(68f, m))
        // Con «Normales» un toque a 25° del eje ya es diagonal; con «Reducidas» sigue siendo una sola dirección.
        assertEquals(right or up, at(25f, DiagonalMode.NORMAL))
        assertEquals(right, at(25f, DiagonalMode.REDUCED))
    }

    @Test
    fun disabledDiagonalsGiveOnlyTheFourCardinals() {
        val m = DiagonalMode.DISABLED
        repeat(360) { degrees ->
            val mask = at(degrees.toFloat(), m)
            assertTrue("$degrees° dio $mask", mask in setOf(up, down, left, right))
        }
        assertEquals(right, at(44f, m))
        assertEquals(up, at(46f, m))
        assertEquals(up, at(134f, m))
        assertEquals(left, at(136f, m))
    }

    @Test
    fun noModeEverGivesOppositeDirections() {
        for (mode in DiagonalMode.entries) {
            repeat(720) { half ->
                val mask = at(half / 2f, mode, fraction = 1f)
                assertFalse("$mode ${half / 2f}°", mask and right != 0 && mask and left != 0)
                assertFalse("$mode ${half / 2f}°", mask and up != 0 && mask and down != 0)
                assertTrue("$mode ${half / 2f}° sin dirección", mask != 0)
            }
        }
    }

    @Test
    fun reducedCardinalSectorsAreSixtyDegreesWideAndDiagonalsThirty() {
        // 60° para cada cardinal y 30° para cada diagonal: 4 × 60 + 4 × 30 = 360.
        var cardinal = 0
        var diagonal = 0
        repeat(3600) { tenth ->
            val mask = at(tenth / 10f + 0.05f, DiagonalMode.REDUCED)
            if (mask in setOf(up, down, left, right)) cardinal += 1 else diagonal += 1
        }
        assertEquals(2400, cardinal)
        assertEquals(1200, diagonal)
    }

    // ---- Zona muerta e histéresis

    @Test
    fun deadZoneIsThirtyPercentOfTheRadius() {
        assertEquals(0, at(90f, DiagonalMode.REDUCED, fraction = 0.29f))
        assertEquals(up, at(90f, DiagonalMode.REDUCED, fraction = 0.31f))
        assertEquals(0, DpadSectors.mask(0f, 0f, radius, DiagonalMode.REDUCED))
    }

    @Test
    fun deadZoneHasHysteresis() {
        val m = DiagonalMode.REDUCED
        // Para activarse hay que pasar de 0,30; una vez activa se mantiene hasta bajar de 0,24.
        assertEquals("sin dirección previa 0,27 es zona muerta", 0, at(90f, m, fraction = 0.27f, previous = 0))
        assertEquals("activa, 0,27 se mantiene", up, at(90f, m, fraction = 0.27f, previous = up))
        assertEquals("activa, 0,25 se mantiene", up, at(90f, m, fraction = 0.25f, previous = up))
        assertEquals("activa, 0,23 la suelta", 0, at(90f, m, fraction = 0.23f, previous = up))
    }

    @Test
    fun sectorBordersHaveHysteresis() {
        val m = DiagonalMode.REDUCED
        // Frontera cardinal/diagonal a 30°: quien está en «derecha» no pasa a la diagonal hasta 38° (histéresis de 8°, como iOS)…
        assertEquals(right, at(34f, m, previous = right))
        assertEquals(right, at(37f, m, previous = right))
        assertEquals(right or up, at(39f, m, previous = right))
        // …y quien está en la diagonal no vuelve a «derecha» hasta bajar de 22°.
        assertEquals(right or up, at(26f, m, previous = right or up))
        assertEquals(right or up, at(23f, m, previous = right or up))
        assertEquals(right, at(21f, m, previous = right or up))
        assertEquals(8f, DpadSectors.ANGLE_HYSTERESIS_DEG, 0f)
        // Sin dirección previa se aplica el borde exacto.
        assertEquals(right or up, at(34f, m, previous = 0))
    }

    @Test
    fun aFingerWobblingAcrossABorderChangesDirectionAtMostOnce() {
        val m = DiagonalMode.REDUCED
        // Oscila ±3° alrededor del borde de 30°.
        var previous = 0
        var changes = 0
        repeat(200) { i ->
            val degrees = 30f + if (i % 2 == 0) 3f else -3f
            val next = at(degrees, m, previous = previous)
            if (previous != 0 && next != previous) changes += 1
            previous = next
        }
        assertEquals("con histéresis no hay parpadeo", 0, changes)
        // Sin histéresis, la misma oscilación cambia de sector en cada muestra.
        var plain = 0
        var last = 0
        repeat(200) { i ->
            val degrees = 30f + if (i % 2 == 0) 3f else -3f
            val next = at(degrees, m)
            if (last != 0 && next != last) plain += 1
            last = next
        }
        assertTrue("sin histéresis parpadea ($plain)", plain > 100)
    }

    @Test
    fun hysteresisNeverKeepsADiagonalWhenDiagonalsAreDisabled() {
        assertEquals(right, at(40f, DiagonalMode.DISABLED, previous = right or up))
    }

    // ---- Criterio: temblor de ±8 dp alrededor del centro del brazo ↑

    /** Generador congruencial lineal: una traza idéntica en cada ejecución. */
    private class Lcg(var state: Long) {
        fun next(): Float {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return ((state ushr 33).toInt() and 0x7FFFFFFF) / 2147483647f
        }
    }

    private fun jitterShare(
        style: DpadStyle,
        mode: DiagonalMode,
        dpadScale: Float,
        sizeScale: Float,
        separation: Float = 1f,
        density: Float = 2.625f,
        samples: Int = 4000,
        targetArm: GameBoyButton = GameBoyButton.UP,
        amplitudeDp: Float = 8f,
    ): Double {
        val area = ControlBounds(0f, 0f, 411f * density, 800f * density)
        val layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
            .copy(scales = mapOf(ControlId.DPAD to dpadScale), separation = separation)
        val geometry = ControlGeometry(
            layout, ControlsOrientation.PORTRAIT, area, density, sizeScale, dpadStyle = style, diagonals = mode,
        )
        val frame = geometry.frames.getValue(ControlId.DPAD)
        val centre = when (style) {
            DpadStyle.CROSS -> DpadShape.crossArms(frame).getValue(targetArm).let { it.centerX to it.centerY }
            DpadStyle.ARROWS -> DpadShape.arrowCircles(frame, geometry.dpadSeparation).getValue(targetArm).let { it.centerX to it.centerY }
        }
        val engine = TouchInputEngine(geometry)
        val rng = Lcg(20_261_006L)
        val jitter = amplitudeDp * density
        engine.pointerDown(1, ControlPoint(centre.first, centre.second))
        var onlyTarget = 0
        repeat(samples) {
            val point = ControlPoint(
                centre.first + (rng.next() * 2f - 1f) * jitter,
                centre.second + (rng.next() * 2f - 1f) * jitter,
            )
            engine.pointerMove(1, point)
            if (engine.mask == targetArm.mask) onlyTarget += 1
        }
        return onlyTarget.toDouble() / samples
    }

    @Test
    fun jitterOfEightDpAroundTheUpArmGivesOnlyUpWithReducedDiagonals() {
        for (style in DpadStyle.entries) {
            val share = jitterShare(style, DiagonalMode.REDUCED, dpadScale = 1f, sizeScale = 1f)
            println("TEMBLOR ±8 dp sobre ↑ ($style, reducidas, 4000 muestras): solo ↑ en %.2f %%".format(share * 100))
            assertTrue("$style: solo ↑ en ${share * 100} % de las muestras (mínimo 99 %)", share >= 0.99)
        }
    }

    @Test
    fun jitterCriterionAlsoHoldsAtTheSmallestAndLargestSizesAndAtEveryDirection() {
        for (style in DpadStyle.entries) {
            for ((dpadScale, sizeScale) in listOf(1f to 0.85f, 1f to 1.15f, 1.6f to 1.15f, 0.8f to 1f)) {
                for (arm in listOf(GameBoyButton.UP, GameBoyButton.DOWN, GameBoyButton.LEFT, GameBoyButton.RIGHT)) {
                    val share = jitterShare(style, DiagonalMode.REDUCED, dpadScale, sizeScale, targetArm = arm)
                    assertTrue("$style $arm escala $dpadScale×$sizeScale: ${share * 100} %", share >= 0.99)
                }
            }
        }
    }

    @Test
    fun jitterCriterionHoldsWithEverySeparationOfTheArrows() {
        for (separation in listOf(MIN_DPAD_SEPARATION, 0.85f, 1f, 1.25f, MAX_DPAD_SEPARATION)) {
            val share = jitterShare(DpadStyle.ARROWS, DiagonalMode.REDUCED, 1f, 1f, separation)
            assertTrue("separación $separation: ${share * 100} %", share >= 0.99)
        }
    }

    @Test
    fun withAWiderTremorReducedDiagonalsStillBeatNormalOnes() {
        // A ±20 dp (casi el ancho del brazo) ya hay muestras fuera del sector cardinal; con «Reducidas» son menos que con «Normales».
        val reduced = jitterShare(DpadStyle.CROSS, DiagonalMode.REDUCED, 1f, 1f, amplitudeDp = 20f)
        val normal = jitterShare(DpadStyle.CROSS, DiagonalMode.NORMAL, 1f, 1f, amplitudeDp = 20f)
        println("TEMBLOR ±20 dp sobre ↑: solo ↑ con reducidas %.1f %%, con normales %.1f %%".format(reduced * 100, normal * 100))
        assertTrue("reducidas $reduced frente a normales $normal", reduced > normal + 0.02)
    }

    @Test
    fun disabledDiagonalsGiveOnlyUpInEverySample() {
        for (style in DpadStyle.entries) {
            assertEquals(1.0, jitterShare(style, DiagonalMode.DISABLED, 1f, 1f), 0.0)
        }
    }

    // ---- Proporciones: las de iOS

    @Test
    fun crossArmsFollowTheIosProportions() {
        val frame = ControlBounds(100f, 200f, 240f, 340f) // W = 140
        val arms = DpadShape.crossArms(frame)
        val up = arms.getValue(GameBoyButton.UP)
        // Cruz de 0,76 W de largo y 0,29 W de grosor: brazo de 40,6 dp de ancho que parte del centro ± 0,145 W.
        assertEquals(0.29f * 140f, up.width, 0.01f)
        assertEquals(170f - 0.145f * 140f, up.left, 0.01f)
        assertEquals(270f - 0.38f * 140f, up.top, 0.01f)
        assertEquals(270f - 0.145f * 140f, up.bottom, 0.01f)
        arms.forEach { (button, arm) ->
            assertTrue("$button dentro del marco", arm.left >= frame.left && arm.right <= frame.right && arm.top >= frame.top && arm.bottom <= frame.bottom)
            // El centro del brazo cae en el sector de su dirección con cualquier modo de diagonales.
            DiagonalMode.entries.forEach { mode ->
                val mask = ControlGeometry.dpadMask(arm.centerX - frame.centerX, arm.centerY - frame.centerY, frame.width / 2f, mode)
                assertEquals("$button $mode", button.mask, mask)
            }
        }
        // El centro de la cruz no pertenece a ningún brazo (no se resalta).
        val core = ControlBounds(170f - 0.145f * 140f, 270f - 0.145f * 140f, 170f + 0.145f * 140f, 270f + 0.145f * 140f)
        arms.values.forEach { arm ->
            assertTrue(arm.right <= core.left + 0.01f || arm.left >= core.right - 0.01f || arm.bottom <= core.top + 0.01f || arm.top >= core.bottom - 0.01f)
        }
    }

    @Test
    fun separatedArrowsAreFourCirclesOf036TimesTheWidthInADiamond() {
        val frame = ControlBounds(0f, 0f, 140f, 140f)
        val circles = DpadShape.arrowCircles(frame, separation = 1f)
        circles.values.forEach { assertEquals(0.36f * 140f / 2f, it.radius, 0.001f) }
        // Con la separación de fábrica los círculos tocan el borde del control, como en iOS.
        assertEquals(70f, circles.getValue(GameBoyButton.UP).centerX, 0.001f)
        assertEquals(0f, circles.getValue(GameBoyButton.UP).centerY - circles.getValue(GameBoyButton.UP).radius, 0.001f)
        assertEquals(140f, circles.getValue(GameBoyButton.RIGHT).centerX + circles.getValue(GameBoyButton.RIGHT).radius, 0.001f)
        assertEquals(0f, circles.getValue(GameBoyButton.LEFT).centerX - circles.getValue(GameBoyButton.LEFT).radius, 0.001f)
        assertEquals(1f, DpadShape.footprint(1f), 1e-6f)
    }

    @Test
    fun separationMovesTheCirclesAndTheFootprintFollowsIt() {
        var previousOffset = -1f
        var previousFootprint = -1f
        for (step in 0..8) {
            val separation = MIN_DPAD_SEPARATION + step * 0.1f
            val offset = DpadShape.arrowOffset(separation)
            val footprint = DpadShape.footprint(separation)
            assertTrue("el desplazamiento crece con la separación ($separation)", offset > previousOffset)
            assertTrue("el cuadro que envuelve el grupo crece ($separation)", footprint > previousFootprint)
            previousOffset = offset
            previousFootprint = footprint

            val frame = ControlBounds(0f, 0f, 140f * footprint, 140f * footprint)
            val circles = DpadShape.arrowCircles(frame, separation)
            val d = 0.36f * 140f
            circles.values.forEach { assertEquals(d / 2f, it.radius, 0.01f) }
            // El grupo ocupa exactamente su cuadro.
            assertEquals(frame.right, circles.getValue(GameBoyButton.RIGHT).centerX + d / 2f, 0.01f)
            assertEquals(frame.top, circles.getValue(GameBoyButton.UP).centerY - d / 2f, 0.01f)
            // Los círculos vecinos nunca se solapan.
            val gap = hypot(
                circles.getValue(GameBoyButton.UP).centerX - circles.getValue(GameBoyButton.RIGHT).centerX,
                circles.getValue(GameBoyButton.UP).centerY - circles.getValue(GameBoyButton.RIGHT).centerY,
            ) - d
            assertTrue("hueco entre vecinos con separación $separation: $gap", gap > 0f)
        }
        assertEquals(0.32f * 1.5f, DpadShape.arrowOffset(1.5f), 1e-5f)
        assertEquals(0.32f, DpadShape.arrowOffset(1f), 1e-6f)
    }

    @Test
    fun separationOutsideTheRangeIsClamped() {
        assertEquals(DpadShape.arrowOffset(MIN_DPAD_SEPARATION), DpadShape.arrowOffset(0.1f), 0f)
        assertEquals(DpadShape.arrowOffset(MAX_DPAD_SEPARATION), DpadShape.arrowOffset(9f), 0f)
    }

    @Test
    fun clampedArrowGroupsShrinkTogetherInsteadOfOverflowingTheirFrame() {
        // Cuadro recortado a la mitad del tamaño que pide la separación 1,5: el grupo se escala entero.
        val wanted = 140f * DpadShape.footprint(1.5f)
        val frame = ControlBounds(0f, 0f, wanted / 2f, wanted / 2f)
        val circles = DpadShape.arrowCircles(frame, 1.5f)
        circles.values.forEach { circle ->
            assertTrue(circle.centerX - circle.radius >= frame.left - 0.01f)
            assertTrue(circle.centerX + circle.radius <= frame.right + 0.01f)
            assertTrue(circle.centerY - circle.radius >= frame.top - 0.01f)
            assertTrue(circle.centerY + circle.radius <= frame.bottom + 0.01f)
        }
    }

    // ---- Háptica

    @Test
    fun hapticTicksOnlyWhenANewDirectionIsActivated() {
        assertTrue(DpadHaptics.shouldTick(previous = 0, next = up))
        assertTrue("de arriba a la diagonal se activa la derecha", DpadHaptics.shouldTick(up, up or right))
        assertTrue("de arriba a izquierda", DpadHaptics.shouldTick(up, left))
        assertFalse("misma dirección", DpadHaptics.shouldTick(up, up))
        assertFalse("de la diagonal a una dirección sola no se activa nada nuevo", DpadHaptics.shouldTick(up or right, up))
        assertFalse("soltar", DpadHaptics.shouldTick(up, 0))
        assertFalse("nada a nada", DpadHaptics.shouldTick(0, 0))
    }
}
