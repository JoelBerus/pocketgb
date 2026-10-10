package com.joelbermudez.pocketgb.travel

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Referencia en Kotlin del contenedor `.pgbm` SOLO para los tests JVM (sin la biblioteca nativa). Sigue
 * docs/12-formato-pgbm.md; la app usa [NativePgbmCodec] (el C), y las pruebas instrumentadas comprueban que los dos
 * producen los mismos bytes (vectores X1…X7 y G1…G4).
 */
object ReferencePgbmCodec : PgbmCodec {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    override fun parse(bytes: ByteArray): PgbmPackage {
        fun fail(c: Int): Nothing = throw PgbmException(c)
        if (bytes.size < 4 || !PgbmResult.hasMagic(bytes)) fail(PgbmResult.MAGIC)
        if (bytes.size >= 6 && u16(bytes, 4) != 1) fail(PgbmResult.VERSION)
        if (bytes.size < 12) fail(PgbmResult.TRUNCATED)
        val total = u32(bytes, 8)
        if (total < 16) fail(PgbmResult.BOUNDS)
        if (total > PgbmResult.MAX_TOTAL) fail(PgbmResult.TOO_LARGE)
        if (bytes.size < total) fail(PgbmResult.TRUNCATED)
        if (bytes.size > total) fail(PgbmResult.BOUNDS)
        val end = bytes.size - 4
        val crc = CRC32().apply { update(bytes, 0, end) }.value
        if (crc != u32(bytes, end)) fail(PgbmResult.CRC)
        val count = u16(bytes, 6)
        if (count > 64) fail(PgbmResult.TOO_LARGE)
        val found = HashMap<String, ByteArray>()
        var p = 12
        repeat(count) {
            if (p + 8 > end) fail(PgbmResult.BOUNDS)
            val tag = bytes.copyOfRange(p, p + 4)
            if (tag.any { it < 0x21 || it > 0x7E }) fail(PgbmResult.BOUNDS)
            val name = String(tag, Charsets.US_ASCII)
            val len = u32(bytes, p + 4)
            if (len > end - p - 8) fail(PgbmResult.BOUNDS)
            val data = bytes.copyOfRange(p + 8, p + 8 + len.toInt())
            when (name) {
                "ROMF", "META", "THMB", "SAVE", "STAT" -> {
                    if (name == "ROMF" && len != 32L) fail(PgbmResult.BOUNDS)
                    if (name != "SAVE" && name != "ROMF" && len == 0L) fail(PgbmResult.BOUNDS)
                    if (found.put(name, data) != null) fail(PgbmResult.DUPLICATE)
                }
                else -> if (name[0] in 'A'..'Z') fail(PgbmResult.CRITICAL)
            }
            p += 8 + len.toInt()
        }
        if (p != end) fail(PgbmResult.BOUNDS)
        val rom = found["ROMF"] ?: fail(PgbmResult.MISSING)
        val sav = found["SAVE"] ?: fail(PgbmResult.MISSING)
        found["META"]?.let { m -> if (0.toByte() in m || !validUtf8(m)) fail(PgbmResult.UTF8) }
        return PgbmPackage(rom, found["META"], sav, found["STAT"], found["THMB"])
    }

    private fun validUtf8(b: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); true
    } catch (_: Exception) {
        false
    }

    override fun encode(pkg: PgbmPackage): ByteArray = encodeRaw(
        listOfNotNull(
            "ROMF" to pkg.romFingerprint, pkg.meta?.takeIf { it.isNotEmpty() }?.let { "META" to it },
            pkg.thumbnail?.takeIf { it.isNotEmpty() }?.let { "THMB" to it }, "SAVE" to pkg.sav,
            pkg.state?.takeIf { it.isNotEmpty() }?.let { "STAT" to it },
        ),
    )

    /** Secciones en el orden dado (permite G3/G4, que el codificador canónico no produce). */
    fun encodeRaw(sections: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((t, d) in sections) {
            body.write(t.toByteArray(Charsets.US_ASCII)); le32(body, d.size.toLong()); body.write(d)
        }
        val out = ByteArrayOutputStream()
        out.write(PgbmResult.MAGIC_BYTES)
        out.write(1); out.write(0)
        out.write(sections.size and 0xFF); out.write(sections.size shr 8)
        le32(out, 12L + body.size() + 4)
        out.write(body.toByteArray())
        val pre = out.toByteArray()
        le32(out, CRC32().apply { update(pre) }.value)
        return out.toByteArray()
    }

    private fun le32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
}
