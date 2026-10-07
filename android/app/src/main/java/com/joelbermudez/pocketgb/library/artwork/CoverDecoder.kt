package com.joelbermudez.pocketgb.library.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * N5 · decodificación de imágenes como entrada no confiable. `BitmapFactory` con `inSampleSize` (no `ImageDecoder`,
 * que exige API 28; el `minSdk` es 26):
 * 1. tope de [CoverImageRules.MAX_BYTES] y formato reconocido por la firma (PNG, JPEG o WebP; la extensión no cuenta);
 * 2. solo la cabecera (`inJustDecodeBounds`) y dimensiones máximas, antes de reservar nada;
 * 3. decodificación submuestreada y reducción final a ≤ [CoverImageRules.TARGET_SIDE] px.
 * Cualquier fallo devuelve `null`: quien llama dibuja la siguiente fuente o la portada generada.
 */
object CoverDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > CoverImageRules.MAX_BYTES) return null
        CoverImageRules.sniff(bytes) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (!CoverImageRules.dimensionsOk(bounds.outWidth, bounds.outHeight)) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = CoverImageRules.sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (decoded.width <= 0 || decoded.height <= 0) return null
            val (width, height) = CoverImageRules.scaledSize(decoded.width, decoded.height)
            if (width == decoded.width && height == decoded.height) return decoded
            val scaled = decoded.scale(width, height)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** PNG de la copia reducida (sin pérdida; respeta la transparencia). `null` si no se pudo codificar. */
    fun encodePng(bitmap: Bitmap): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    } catch (_: RuntimeException) {
        null
    }

    /** Copia reducida lista para guardar, o `null` si la imagen no vale. */
    fun reduce(bytes: ByteArray): ByteArray? {
        val bitmap = decode(bytes) ?: return null
        return try {
            encodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Lee como mucho [CoverImageRules.MAX_BYTES] de [input]. `null` si hay más (imagen demasiado grande: no se sigue
     * leyendo) o si falla la lectura.
     */
    fun readLimited(input: InputStream): ByteArray? = try {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > CoverImageRules.MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
