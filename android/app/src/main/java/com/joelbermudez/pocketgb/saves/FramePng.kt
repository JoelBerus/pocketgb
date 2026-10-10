package com.joelbermudez.pocketgb.saves

import android.graphics.Bitmap
import com.joelbermudez.pocketgb.emulator.ScreenSize
import java.io.ByteArrayOutputStream

/**
 * Codifica la captura de un estado o una portada a PNG (mejor esfuerzo: sin captura el estado vale igual). El tamaño
 * sale del número de píxeles (N8): 160×144 en Game Boy y 240×160 en Game Boy Advance ([ScreenSize.ofPixelCount]).
 */
object FramePng {
    fun encode(pixels: IntArray): ByteArray? = try {
        val screen = requireNotNull(ScreenSize.ofPixelCount(pixels.size)) { "Fotograma de ${pixels.size} píxeles" }
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(
                FrameThumbnail.swapRedBlue(pixels), 0, screen.width,
                0, 0, screen.width, screen.height,
            )
            ByteArrayOutputStream().also { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    } catch (_: RuntimeException) {
        null
    }
}
