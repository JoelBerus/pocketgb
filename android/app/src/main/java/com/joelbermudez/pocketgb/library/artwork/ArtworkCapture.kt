package com.joelbermudez.pocketgb.library.artwork

/** Criterios para decidir si un fotograma merece ser portada (K9). Puro: se prueba en JVM. */
object ArtworkCapture {
    /** `true` si todos los píxeles son del mismo color (pantalla en blanco o en negro): no es una portada. */
    fun isUniform(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val first = pixels[0]
        for (i in 1 until pixels.size) if (pixels[i] != first) return false
        return true
    }
}
