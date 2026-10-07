package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.ui.theme.PocketDarkColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketDarkHighContrastColorScheme
import com.joelbermudez.pocketgb.ui.theme.PocketDarkMediumContrastColorScheme
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsRenderOptionsTest {
    @Test
    fun normalFillFollowsTheChosenOpacity() {
        val low = ControlsRenderOptions(opacity = 30)
        val full = ControlsRenderOptions(opacity = 100)
        assertEquals(0.72f * 0.3f, low.fillAlpha(pressed = false, fade = 1f), 0.001f)
        assertEquals(0.72f, full.fillAlpha(pressed = false, fade = 1f), 0.001f)
        assertEquals(0.92f, full.fillAlpha(pressed = true, fade = 1f), 0.001f)
    }

    @Test
    fun highContrastIsSolidWhateverTheOpacity() {
        for (opacity in listOf(30, 50, 70, 100)) {
            val options = ControlsRenderOptions(opacity = opacity, highContrast = true)
            assertTrue(options.fillAlpha(pressed = false, fade = 1f) >= 0.9f)
            assertTrue(options.fillAlpha(pressed = true, fade = 1f) >= 0.9f)
        }
    }

    @Test
    fun highContrastStillHonoursTheFade() {
        val options = ControlsRenderOptions(opacity = 30, highContrast = true)
        assertEquals(0f, options.fillAlpha(pressed = false, fade = 0f), 0f)
    }

    @Test
    fun highContrastLabelsAndRingAreFullyOpaqueAndTheRingIsThick() {
        val options = ControlsRenderOptions(opacity = 30, highContrast = true)
        assertEquals(1f, options.labelAlpha(fade = 1f), 0f)
        assertEquals(1f, options.ringAlpha(accent = false, fade = 1f), 0f)
        assertEquals(2f, ControlsRenderOptions.RING_WIDTH_DP, 0f)
        assertTrue(options.ringWidthDp >= 2f)
    }

    @Test
    fun normalRingIsTranslucentForNeutralAndNearlyOpaqueForAccents() {
        val options = ControlsRenderOptions(opacity = 100)
        assertEquals(0.5f, options.ringAlpha(accent = false, fade = 1f), 0.001f)
        assertEquals(0.95f, options.ringAlpha(accent = true, fade = 1f), 0.001f)
    }

    @Test
    fun pressedControlsKeepTheirFillWhateverTheOpacity() {
        // Pulsar siempre se ve (N2): el relleno pulsado no baja con la opacidad elegida.
        for (opacity in listOf(30, 50, 70, 100)) {
            assertEquals(0.92f, ControlsRenderOptions(opacity = opacity).fillAlpha(pressed = true, fade = 1f), 0.001f)
        }
    }

    @Test
    fun theScrimNeverDisappearsAndGrowsWithTheOpacity() {
        val alphas = listOf(30, 50, 70, 100).map { ControlsRenderOptions(opacity = it).scrimAlpha(1f) }
        assertEquals(alphas.sorted(), alphas)
        assertTrue(alphas.first() >= 0.35f)
        assertEquals(0f, ControlsRenderOptions(opacity = 70).scrimAlpha(0f), 0f)
    }

    // ---- Contraste pulsado vs neutro (N2): ≥ 3:1 (WCAG 1.4.11, indicador de estado)

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(r: Double, g: Double, b: Double): Double =
        0.2126 * channel(r.toInt()) + 0.7152 * channel(g.toInt()) + 0.0722 * channel(b.toInt())

    private fun luminanceOf(argb: Int) = luminance(((argb shr 16) and 255).toDouble(), ((argb shr 8) and 255).toDouble(), (argb and 255).toDouble())

    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    @Test
    fun pressedAndNeutralColorsGiveAtLeastThreeToOneInEveryDarkScheme() {
        val schemes = mapOf(
            "estándar" to PocketDarkColorScheme,
            "contraste medio" to PocketDarkMediumContrastColorScheme,
            "contraste alto" to PocketDarkHighContrastColorScheme,
        )
        schemes.forEach { (name, scheme) ->
            val palette = ControlsPalette.from(scheme)
            val pressed = luminanceOf(palette.pressed)
            val neutral = luminanceOf(palette.surface)
            assertTrue("$name: pulsado/neutro ${contrast(pressed, neutral)}", contrast(pressed, neutral) >= 3.0)
            // El símbolo se lee en cada estado (≥ 4,5:1).
            assertTrue("$name: símbolo neutro", contrast(luminanceOf(palette.onSurface), neutral) >= 4.5)
            assertTrue("$name: símbolo pulsado", contrast(luminanceOf(palette.onPressed), pressed) >= 4.5)
            println("CONTRASTE $name pulsado/neutro = %.2f, símbolo neutro = %.2f, símbolo pulsado = %.2f".format(
                contrast(pressed, neutral),
                contrast(luminanceOf(palette.onSurface), neutral),
                contrast(luminanceOf(palette.onPressed), pressed),
            ))
        }
    }

    /** Compone [fgArgb] con [alpha] sobre un fondo gris [background] 0..255; devuelve los tres canales. */
    private fun over(fgArgb: Int, alpha: Float, background: DoubleArray): DoubleArray = doubleArrayOf(
        alpha * ((fgArgb shr 16) and 255) + (1 - alpha) * background[0],
        alpha * ((fgArgb shr 8) and 255) + (1 - alpha) * background[1],
        alpha * (fgArgb and 255) + (1 - alpha) * background[2],
    )

    private fun lum(c: DoubleArray) = luminance(c[0], c[1], c[2])

    /** Contraste del brazo (sobre el disco) y del círculo pulsado frente al neutro, tal como se componen al dibujarlos. */
    private fun composed(options: ControlsRenderOptions, gameGray: Int): Pair<Double, Double> {
        val p = options.palette
        val background = doubleArrayOf(gameGray.toDouble(), gameGray.toDouble(), gameGray.toDouble())
        val scrimmed = over(0, options.scrimAlpha(1f), background)
        val neutral = options.fillAlpha(pressed = false, fade = 1f)
        val pressed = options.fillAlpha(pressed = true, fade = 1f)
        val disc = over(p.base, neutral, scrimmed)
        val armNeutral = over(p.surface, neutral, disc)
        val armPressed = over(p.pressed, pressed, armNeutral)
        val circleNeutral = over(p.surface, neutral, scrimmed)
        val circlePressed = over(p.pressed, pressed, scrimmed)
        return contrast(lum(armPressed), lum(armNeutral)) to contrast(lum(circlePressed), lum(circleNeutral))
    }

    @Test
    fun theCompositedPressedStateKeepsThreeToOneOverTheGameFrame() {
        val grays = listOf(0, 64, 128, 255)
        val report = StringBuilder("CONTRASTE COMPUESTO (brazo de la cruz / círculo de flechas), fondo de juego negro 0, gris 64, gris 128, blanco 255\n")
        for (opacity in listOf(30, 50, 70, 100)) {
            report.append("opacidad $opacity %: ")
            for (gray in grays) {
                val (arm, circle) = composed(ControlsRenderOptions(opacity = opacity), gray)
                report.append("[%d] %.2f / %.2f  ".format(gray, arm, circle))
                // Garantía: cualquier opacidad sobre fondos de negro a gris medio; con 70 % o más, también sobre blanco.
                if (gray <= 128 || opacity >= 70) {
                    assertTrue("opacidad $opacity sobre $gray: brazo $arm", arm >= 3.0)
                    assertTrue("opacidad $opacity sobre $gray: círculo $circle", circle >= 3.0)
                }
                // Con 50 % sobre blanco el brazo (sobre su disco) sigue cumpliendo.
                if (opacity == 50 && gray == 255) assertTrue("brazo 50 % sobre blanco: $arm", arm >= 3.0)
            }
            report.append('\n')
        }
        print(report)
    }

    @Test
    fun highContrastKeepsThreeToOneOverAnyFrame() {
        for (gray in listOf(0, 128, 255)) {
            val (arm, circle) = composed(ControlsRenderOptions(opacity = 30, highContrast = true, palette = ControlsPalette.from(PocketDarkHighContrastColorScheme)), gray)
            assertTrue("contraste alto sobre $gray: brazo $arm", arm >= 3.0)
            assertTrue("contraste alto sobre $gray: círculo $circle", circle >= 3.0)
        }
    }

    // ---- H3: contraste COMPUESTO de lo que se dibuja (etiquetas y símbolos), no solo de los colores opacos

    /** Contraste de la etiqueta (o símbolo) de un control frente a su relleno, tal como se componen al dibujarlos. */
    private fun labelContrast(options: ControlsRenderOptions, gameGray: Int, pressed: Boolean): Double {
        val p = options.palette
        val background = doubleArrayOf(gameGray.toDouble(), gameGray.toDouble(), gameGray.toDouble())
        val scrimmed = over(0, options.scrimAlpha(1f), background)
        val fill = over(if (pressed) p.pressed else p.surface, options.fillAlpha(pressed, 1f), scrimmed)
        val label = over(if (pressed) p.onPressed else p.onSurface, options.labelAlpha(1f), fill)
        return contrast(lum(label), lum(fill))
    }

    @Test
    fun labelsStayAtLeastFourPointFiveToOneOverDarkAndMidGrayFramesAtEveryOpacity() {
        val report = StringBuilder("CONTRASTE DE ETIQUETAS (START/SELECT neutro / pulsado), fondo negro 0, gris 64, gris 128, gris 224, blanco 255\n")
        for (opacity in listOf(30, 50, 70, 100)) {
            val options = ControlsRenderOptions(opacity = opacity)
            report.append("opacidad $opacity %: ")
            for (gray in listOf(0, 64, 128, 224, 255)) {
                val neutral = labelContrast(options, gray, pressed = false)
                val pressed = labelContrast(options, gray, pressed = true)
                report.append("[%d] %.2f / %.2f  ".format(gray, neutral, pressed))
                if (gray <= 128) assertTrue("etiqueta neutra al $opacity % sobre $gray: $neutral", neutral >= 4.5)
                // El pulsado va siempre sobre el relleno opaco (0,92): también cumple sobre blanco.
                assertTrue("etiqueta pulsada al $opacity % sobre $gray: $pressed", pressed >= 4.5)
            }
            report.append('\n')
        }
        print(report)
    }

    @Test
    fun theDefaultSeventyPercentGivesLabelsOverFourPointFiveOverMidGray() {
        // El caso que midió la auditoría: START/SELECT al 70 % sobre gris 128 (con onSurfaceVariant y 70 % de alfa: 3,75).
        val options = ControlsRenderOptions(opacity = 70)
        assertTrue("${labelContrast(options, 128, pressed = false)}", labelContrast(options, 128, pressed = false) >= 4.5)
        assertEquals(1f, options.labelAlpha(1f), 0f)
        assertEquals(0.5f, options.labelAlpha(0.5f), 0f)
    }

    @Test
    fun labelsInHighContrastKeepTheirContrastToo() {
        val options = ControlsRenderOptions(opacity = 30, highContrast = true, palette = ControlsPalette.from(PocketDarkHighContrastColorScheme))
        for (gray in listOf(0, 128, 255)) {
            assertTrue("contraste alto sobre $gray: ${labelContrast(options, gray, false)}", labelContrast(options, gray, false) >= 4.5)
            assertTrue(labelContrast(options, gray, true) >= 4.5)
        }
    }
}
