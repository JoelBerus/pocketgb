package com.joelbermudez.pocketgb.travel

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `config` de META v1 (docs/12-formato-pgbm.md), con los tipos del esquema (ND20 h): un valor de tipo o enumerado
 * erróneo hace la META inválida; las claves desconocidas se ignoran. Todas opcionales (`null` = ausente).
 */
data class PgbmConfig(
    val model: String? = null,
    val compatPalette: String? = null,
    val gbaSaveType: String? = null,
    val gbaRtc: String? = null,
    val gbaBios: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == PgbmConfig()

    fun toJson(): JsonObject = buildJsonObject {
        model?.let { put("model", it) }
        compatPalette?.let { put("compat_palette", it) }
        gbaSaveType?.let { put("gba_save_type", it) }
        gbaRtc?.let { put("gba_rtc", it) }
        gbaBios?.let { put("gba_bios", it) }
    }

    /**
     * ¿Un estado tomado con [other] es incompatible con esta configuración? Solo cuentan las claves que cambian la
     * máquina (modelo de GB; tipo de partida, reloj y BIOS de GBA) y que están en las dos; la paleta no.
     */
    fun conflictsWith(other: PgbmConfig): Boolean {
        fun differ(a: Any?, b: Any?) = a != null && b != null && a != b
        return differ(model, other.model) || differ(gbaSaveType, other.gbaSaveType) || differ(gbaRtc, other.gbaRtc) ||
            differ(gbaBios, other.gbaBios)
    }

    companion object {
        val MODELS = setOf("auto", "dmg", "cgb")
        val GBA_SAVE_TYPES = setOf("auto", "none", "sram", "flash64", "flash128", "eeprom512", "eeprom8k")
        val GBA_RTC = setOf("auto", "on", "off")

        /** `null` si algún valor conocido tiene un tipo o un valor fuera del esquema. */
        fun parse(o: JsonObject): PgbmConfig? {
            fun str(key: String, allowed: Set<String>?, max: Int = 64): Result<String?> {
                val e = o[key] ?: return Result.success(null)
                val p = e as? JsonPrimitive
                if (e is JsonNull || p == null || !p.isString) return Result.failure(IllegalArgumentException(key))
                val v = p.content
                if (v.codePointCount(0, v.length) > max || (allowed != null && v !in allowed)) return Result.failure(IllegalArgumentException(key))
                return Result.success(v)
            }
            val bios = o["gba_bios"]?.let { e ->
                val p = e as? JsonPrimitive
                if (p == null || p.isString || (p.content != "true" && p.content != "false")) return null
                p.content == "true"
            }
            return PgbmConfig(
                model = str("model", MODELS).getOrElse { return null },
                compatPalette = str("compat_palette", null).getOrElse { return null },
                gbaSaveType = str("gba_save_type", GBA_SAVE_TYPES).getOrElse { return null },
                gbaRtc = str("gba_rtc", GBA_RTC).getOrElse { return null },
                gbaBios = bios,
            )
        }
    }
}
