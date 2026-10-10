package com.joelbermudez.pocketgb.travel

import java.security.MessageDigest

/**
 * Vectores dorados cruzados X1…X7 y G1…G4 (docs/12-formato-pgbm.md), construidos aquí con las llamadas de la app
 * ([PgbmMeta.toJson] + un [PgbmCodec]) a partir de la carga sintética `pat`. Comparten código las pruebas JVM (con
 * [ReferencePgbmCodec]) y las instrumentadas (con [NativePgbmCodec], el C por JNI). Nunca hay archivos `.pgbm` en el repo.
 */
object CrossVectors {
    fun pat(seed: Int, n: Int) = ByteArray(n) { i -> ((seed + i * 37 + (i shr 8) * 11) and 0xFF).toByte() }
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    val ROM = pat(0x10, 32)
    val ROM_HEX = ROM.joinToString("") { "%02x".format(it) }
    const val SAV_SIZE = 32768
    val S1 = pat(0x21, SAV_SIZE)
    val B = pat(0x22, SAV_SIZE)
    val S2 = pat(0x23, SAV_SIZE)
    val T1 = pat(0x42, 4096)
    val T2 = pat(0x43, 4096)
    val S4 = pat(0x21, 8000)

    /** Cartucho de referencia: huella `pat(0x10, 32)`, GB, con batería, 32 768 B. */
    val TARGET = ImportTarget(ROM_HEX, "gb", hasBattery = true, validSizes = setOf(SAV_SIZE))

    private fun meta(sav: String, base: String?, platform: String, name: String, created: Long, stateOf: String? = null, format: Int = 1,
                     playTime: Long? = null, title: String? = null, tags: List<String>? = null) =
        PgbmMeta(ROM_HEX, sav, base, platform, name, created, "gb", "1.0.0", stateOfSavSha256 = stateOf, playTimeMs = playTime,
            title = title, tags = tags, format = format).toJson()

    val EXPECTED_SHA = mapOf(
        "X1" to "b7d163cf9fda7b6ccbcd240b1a25b97518caccdda45b5a38e37a021e713bf796",
        "X2" to "e79d7993c7cd380ac7496f792766362e0689fe285ff2c06a9424508b40db5f2f",
        "X3" to "c1f033894a9d9f7bc15cfcc353b776caf6303fadb51f7168829045cfbb430e17",
        "X4" to "336401621230102beadbadbdba1643acdc58da343fe36e2105d9e07966933771",
        "X5" to "8b1fb567aa21ffd5c907b59111c93eb4f67d7dba91bb57aff5e4b5804f30718a",
        "X6" to "c7f5650966a804b620f8d29a63766b2583d53a2a30384b030ceafe224b236a2e",
        "X7" to "6d8b3af30e6fe76adf7fef36fc21f80a3db0a86d4df1a8faa00d573b07a39880",
        "X8" to "08c1ad1b5c7422765a54d1e2d0bc19276c5feeb9db8d1fe49f7aadc0ebf7c95b",
        "G2" to "5548b8cac9de16d52d17aec2907fd61832443bdebb4dc747499f726da9ef41c9",
        "G4" to "b6b3f3e96df34995d4bdf171e368acae20f5b72bb512b824fae509d3979a4a53",
    )

    fun build(name: String, codec: PgbmCodec): ByteArray = when (name) {
        "X1" -> codec.encode(PgbmPackage(ROM, meta(sha(S1), sha(B), "android", "Pixel de prueba", 1790003600000, sha(S1),
            playTime = 7200000, title = "Prueba cruzada", tags = listOf("cruzado")), S1, T1))
        "X2" -> codec.encode(PgbmPackage(ROM, meta(sha(S2), sha(S1), "ios", "iPhone de prueba", 1790007200000, sha(pat(0x24, SAV_SIZE))), S2, T2))
        "X3" -> codec.encode(PgbmPackage(ROM, meta(sha(ByteArray(0)), null, "android", "Pixel de prueba", 1790000000000), ByteArray(0)))
        "X4" -> codec.encode(PgbmPackage(ROM, meta(sha(S4), null, "ios", "iPhone de prueba", 1790000000000), S4))
        "X5" -> codec.encode(PgbmPackage(ROM, meta(sha(S1), null, "android", "Pixel de prueba", 1790000000000, format = 2), S1))
        "X6" -> codec.encode(PgbmPackage(ROM, null, S1))
        "X7" -> codec.encode(PgbmPackage(ROM, meta(sha(B), null, "android", "Pixel de prueba", 1790000000000), S1))
        "X8" -> codec.encode(PgbmPackage(ROM, String(meta(sha(S1), null, "android", "Pixel de prueba", 1790000000000)).replace(
            ",\"core\":", ",\"created_ms\":1790000000001,\"core\":",
        ).toByteArray(), S1))
        "G2" -> codec.encode(PgbmPackage(ROM, null, pat(0x21, 16)))
        "G4" -> ReferencePgbmCodec.encodeRaw(listOf("ROMF" to ROM, "XTRA" to "hola!".toByteArray(), "SAVE" to pat(0x21, 16)))
        else -> error(name)
    }
}
