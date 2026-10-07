package com.joelbermudez.pocketgb.input

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowLeft
import androidx.compose.material.icons.automirrored.rounded.ArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.graphics.withTranslation
import kotlin.math.max

/**
 * Símbolos Material de las direcciones de la cruceta (N2). Los controles se dibujan en un `View`, no en Compose, así que
 * el trazo del `ImageVector` de `material-icons-extended` se convierte una vez en un `android.graphics.Path`. Se
 * escala por el tamaño de la propia figura (no por el cuadro de 24 dp del icono) para que crezca con el control.
 *
 * Las flechas laterales no se espejan en RTL: cada botón mantiene su lado físico.
 */
internal object DpadIcons {
    private class Glyph(val path: Path, val bounds: RectF)

    private val glyphs = mutableMapOf<GameBoyButton, Glyph>()

    private fun vectorOf(button: GameBoyButton): ImageVector = when (button) {
        GameBoyButton.UP -> Icons.Rounded.ArrowDropUp
        GameBoyButton.DOWN -> Icons.Rounded.ArrowDropDown
        GameBoyButton.LEFT -> Icons.AutoMirrored.Rounded.ArrowLeft
        else -> Icons.AutoMirrored.Rounded.ArrowRight
    }

    private fun glyph(button: GameBoyButton): Glyph = glyphs.getOrPut(button) {
        val path = android.graphics.Path()
        fun collect(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorPath -> path.addPath(node.pathData.toPath().asAndroidPath())
                    is VectorGroup -> collect(node)
                }
            }
        }
        collect(vectorOf(button).root)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        Glyph(path, bounds)
    }

    /** Dibuja el símbolo de [button] centrado en ([cx], [cy]) con su lado mayor igual a [size], del color [color]. */
    fun draw(canvas: Canvas, paint: Paint, button: GameBoyButton, cx: Float, cy: Float, size: Float, color: Int) {
        val glyph = glyph(button)
        val extent = max(glyph.bounds.width(), glyph.bounds.height())
        if (extent <= 0f || size <= 0f) return
        val scale = size / extent
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.withTranslation(cx, cy) {
            scale(scale, scale)
            translate(-glyph.bounds.centerX(), -glyph.bounds.centerY())
            drawPath(glyph.path, paint)
        }
    }
}
