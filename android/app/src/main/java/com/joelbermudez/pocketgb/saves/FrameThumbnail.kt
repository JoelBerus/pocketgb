package com.joelbermudez.pocketgb.saves

/**
 * Miniatura del fotograma para los estados. El core entrega RGBA8888 con R en el byte bajo (como `Int`
 * little-endian: 0xAABBGGRR) y `Bitmap.setPixels` espera ARGB (0xAARRGGBB): hay que intercambiar R y B.
 * Pura (sin Android) para poder probarla en JVM; la codificación a PNG la hace la capa Android.
 */
object FrameThumbnail {
    fun swapRedBlue(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        (p and 0xFF00FF00.toInt()) or ((p shr 16) and 0xFF) or ((p and 0xFF) shl 16)
    }
}
