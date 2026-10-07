package com.joelbermudez.pocketgb.saves

import android.graphics.Bitmap
import com.joelbermudez.pocketgb.emulator.CoreBridge
import java.io.ByteArrayOutputStream

/** Codifica la captura de un estado a PNG (mejor esfuerzo: sin captura el estado vale igual). */
object FramePng {
    fun encode(pixels: IntArray): ByteArray? = try {
        require(pixels.size == CoreBridge.FRAME_PIXELS)
        val bitmap = Bitmap.createBitmap(CoreBridge.SCREEN_WIDTH, CoreBridge.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(
                FrameThumbnail.swapRedBlue(pixels), 0, CoreBridge.SCREEN_WIDTH,
                0, 0, CoreBridge.SCREEN_WIDTH, CoreBridge.SCREEN_HEIGHT,
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
