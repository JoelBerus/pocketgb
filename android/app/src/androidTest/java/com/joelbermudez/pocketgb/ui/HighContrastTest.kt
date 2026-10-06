package com.joelbermudez.pocketgb.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlsRenderOptions
import com.joelbermudez.pocketgb.input.GameControlsView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Contraste alto (R10): los controles táctiles se dibujan sólidos aunque la opacidad elegida sea baja. */
@RunWith(AndroidJUnit4::class)
class HighContrastTest {
    private fun drawn(options: ControlsRenderOptions): Pair<Bitmap, GameControlsView> {
        val view = GameControlsView(ApplicationProvider.getApplicationContext(), onMaskChanged = {}).apply {
            renderOptions = options
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 400, 700)
        }
        val bitmap = Bitmap.createBitmap(400, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE) // fotograma claro: lo que se cuela por la transparencia se nota
        view.draw(canvas)
        return bitmap to view
    }

    /** Un punto dentro del botón A, a la altura de su centro pero apartado de la etiqueta y del anillo. */
    private fun fillPixel(bitmap: Bitmap, view: GameControlsView): Int {
        val a = view.controlGeometry.frames.getValue(ControlId.A)
        return bitmap.getPixel((a.centerX + a.width * 0.32f).toInt(), a.centerY.toInt())
    }

    private fun luminance(pixel: Int) = (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel))

    @Test
    fun withLowOpacityAndNormalContrastTheFillLetsTheBackgroundThrough() {
        val (bitmap, view) = drawn(ControlsRenderOptions(opacity = 30))
        assertTrue("luminancia ${luminance(fillPixel(bitmap, view))}", luminance(fillPixel(bitmap, view)) > 150f)
    }

    @Test
    fun withHighContrastTheFillIsSolidWhateverTheOpacity() {
        listOf(30, 50, 70, 100).forEach { opacity ->
            val (bitmap, view) = drawn(ControlsRenderOptions(opacity = opacity, highContrast = true))
            val pixel = fillPixel(bitmap, view)
            // Relleno 0x1F2024 al 95 % sobre blanco: ≈ (42, 43, 44). Si fuera translúcido sería claro.
            assertTrue("opacidad $opacity: luminancia ${luminance(pixel)}", luminance(pixel) < 70f)
        }
    }
}
