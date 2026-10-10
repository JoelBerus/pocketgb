package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.emulator.NativeLibrary

/**
 * N7b · contenedor `.pgbm` (docs/12-formato-pgbm.md). El C (`core/src/pgbm.c`, por JNI) valida y empaqueta; aquí solo
 * hay tipos. Los códigos son el contrato numérico de `pgbm_result` (nunca se reordenan).
 */
object PgbmResult {
    const val OK = 0
    const val MAGIC = 1
    const val VERSION = 2
    const val TRUNCATED = 3
    const val BOUNDS = 4
    const val CRC = 5
    const val DUPLICATE = 6
    const val MISSING = 7
    const val UTF8 = 8
    const val PNG = 9
    const val TOO_LARGE = 10
    const val ARG = 11
    const val NOSPACE = 12
    const val CRITICAL = 13

    /** El paquete viene de una versión más nueva: «actualiza la app». */
    fun needsNewerApp(code: Int) = code == VERSION || code == CRITICAL

    const val MAX_TOTAL = 4_194_304
    const val ROMF_BYTES = 32
    val MAGIC_BYTES = byteArrayOf(0x50, 0x47, 0x42, 0x4D)

    /** ¿Empieza por «PGBM»? Para los intents genéricos (validación por cabecera, no por tipo MIME ni extensión). */
    fun hasMagic(head: ByteArray) = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC_BYTES)
}

/** Contenido de un paquete. `sav` vacío = SAVE vacía (juego sin batería); las opcionales `null` = ausentes. */
class PgbmPackage(
    val romFingerprint: ByteArray,
    val meta: ByteArray?,
    val sav: ByteArray,
    val state: ByteArray? = null,
    val thumbnail: ByteArray? = null,
)

class PgbmException(val code: Int) : Exception("pgbm: código $code")

interface PgbmCodec {
    /** Lanza [PgbmException] con el código de `pgbm_parse` si el paquete no es válido. */
    fun parse(bytes: ByteArray): PgbmPackage

    fun encode(pkg: PgbmPackage): ByteArray
}

/** La implementación de la app: el parser C por JNI (el mismo que prueban `unit_pgbm.c` y el fuzzer). */
object NativePgbmCodec : PgbmCodec {
    override fun parse(bytes: ByteArray): PgbmPackage {
        if (bytes.size > PgbmResult.MAX_TOTAL) throw PgbmException(PgbmResult.TOO_LARGE)
        val spans = IntArray(8)
        val fp = ByteArray(PgbmResult.ROMF_BYTES)
        val code = NativeLibrary.nativePgbmParse(bytes, spans, fp)
        if (code != PgbmResult.OK) throw PgbmException(code)
        fun span(i: Int): ByteArray? {
            val off = spans[2 * i]
            val len = spans[2 * i + 1]
            if (off < 0 || len <= 0) return null
            return bytes.copyOfRange(off, off + len)
        }
        return PgbmPackage(fp, span(0), span(1) ?: ByteArray(0), span(2), span(3))
    }

    override fun encode(pkg: PgbmPackage): ByteArray {
        val result = IntArray(1)
        val out = NativeLibrary.nativePgbmEncode(pkg.romFingerprint, pkg.meta, pkg.sav, pkg.state, pkg.thumbnail, result)
        if (result[0] != PgbmResult.OK || out == null) throw PgbmException(result[0])
        return out
    }
}
