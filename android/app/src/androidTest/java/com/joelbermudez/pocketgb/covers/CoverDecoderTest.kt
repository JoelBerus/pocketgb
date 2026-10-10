package com.joelbermudez.pocketgb.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.artwork.CoverDecoder
import com.joelbermudez.pocketgb.library.artwork.CoverImageRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** N5 · imágenes hostiles con el `BitmapFactory` real: truncadas, dimensiones enormes, extensión falsa y demasiado grandes. */
@RunWith(AndroidJUnit4::class)
class CoverDecoderTest {
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat, quality: Int = 90): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height step 7) for (x in 0 until width step 7) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, 128))
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** PNG con solo la cabecera IHDR de [width]×[height] (CRC correcto) y un IDAT vacío: miente sobre su tamaño. */
    private fun lyingPng(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value.toInt()
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc).array()
        }
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array()
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", ByteArray(0)) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun validImagesOfEveryFormatAreReducedToTheTargetSide() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP)) {
            val decoded = CoverDecoder.decode(image(3000, 1500, format))
            assertNotNull("$format", decoded)
            assertEquals(1024, decoded!!.width)
            assertEquals(512, decoded.height)
        }
        val small = CoverDecoder.decode(image(160, 144, Bitmap.CompressFormat.PNG))!!
        assertEquals(160 to 144, small.width to small.height)
    }

    @Test
    fun theReducedCopyIsAPngOfAtMostTheTargetSide() {
        val reduced = CoverDecoder.reduce(image(2000, 4000, Bitmap.CompressFormat.JPEG))!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(reduced, 0, reduced.size, bounds)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(512 to 1024, bounds.outWidth to bounds.outHeight)
    }

    @Test
    fun truncatedImagesNeverCrashAndNeverExceedTheTarget() {
        val png = image(1500, 1500, Bitmap.CompressFormat.PNG)
        val jpeg = image(1500, 1500, Bitmap.CompressFormat.JPEG)
        // Solo la firma y parte de la cabecera: no hay imagen.
        assertNull(CoverDecoder.decode(png.copyOf(20)))
        assertNull(CoverDecoder.decode(jpeg.copyOf(10)))
        // A medias: el decodificador del sistema puede dar una imagen parcial; nunca debe fallar ni pasar del tope.
        for (bytes in listOf(png.copyOf(png.size / 2), jpeg.copyOf(jpeg.size / 2))) {
            val partial = CoverDecoder.decode(bytes)
            if (partial != null) assertTrue(partial.width <= CoverImageRules.TARGET_SIDE && partial.height <= CoverImageRules.TARGET_SIDE)
        }
    }

    @Test
    fun hugeDeclaredDimensionsAreRejectedBeforeDecoding() {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val lying = lyingPng(60_000, 60_000)
        BitmapFactory.decodeByteArray(lying, 0, lying.size, bounds)
        assertEquals(60_000, bounds.outWidth) // el sistema se cree la cabecera…
        assertNull(CoverDecoder.decode(lying)) // …y PocketGB la rechaza sin reservar 14 GB
        assertNull(CoverDecoder.decode(lyingPng(16_000, 16_000))) // 256 MP
        assertNull(CoverDecoder.decode(lyingPng(0, 10)))
    }

    @Test
    fun aFakeExtensionOrAnUnsupportedFormatIsRejected() {
        assertNull(CoverDecoder.decode("<html>no soy un png</html>".toByteArray()))
        assertNull(CoverDecoder.decode(ByteArray(0)))
        // Un GIF es una imagen válida para el sistema, pero no es un formato aceptado.
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0, 0, 0, 0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B)
        assertNull(CoverDecoder.decode(gif))
        // Un JPEG de verdad con nombre .png vale: manda el contenido.
        assertNotNull(CoverDecoder.decode(image(200, 200, Bitmap.CompressFormat.JPEG)))
    }

    @Test
    fun imagesOverTheByteLimitAreNotReadToTheEnd() {
        val over = ByteArray(CoverImageRules.MAX_BYTES + 1)
        assertNull(CoverDecoder.decode(over))
        var served = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertNull(CoverDecoder.readLimited(endless))
        assertTrue(served <= CoverImageRules.MAX_BYTES + 64 * 1024)
        val ok = image(100, 100, Bitmap.CompressFormat.PNG)
        assertArrayEquals(ok, CoverDecoder.readLimited(ByteArrayInputStream(ok)))
    }
}
