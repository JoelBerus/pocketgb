package com.joelbermudez.pocketgb.travel

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * N7b · `META v1` (docs/12-formato-pgbm.md §META v1, normativa). Lo escribe y lo lee igual en iOS y Android. El orden de
 * escritura de las claves es fijo (el de la tabla), así que dos equipos con los mismos datos producen los mismos bytes
 * (los vectores cruzados X1…X7 lo comprueban).
 */
data class PgbmMeta(
    val romSha256: String,
    val savSha256: String,
    val baseSavSha256: String?,
    val devicePlatform: String,
    val deviceName: String,
    val createdMs: Long,
    val coreName: String,
    val coreVersion: String,
    val config: Map<String, JsonPrimitive> = emptyMap(),
    val stateOfSavSha256: String? = null,
    val playTimeMs: Long? = null,
    val title: String? = null,
    val alias: String? = null,
    val tags: List<String>? = null,
    val milestones: List<Milestone>? = null,
    val moment: Moment? = null,
    val format: Int = FORMAT,
) {
    data class Milestone(val id: String, val title: String, val done: Boolean)
    data class Moment(val name: String, val collection: String? = null, val note: String? = null, val createdMs: Long? = null)

    /** El estado del paquete es exactamente el de su partida: se puede ofrecer «Continuar donde lo dejaste» (ND6). */
    val stateMatchesSave: Boolean get() = stateOfSavSha256 != null && stateOfSavSha256.equals(savSha256, ignoreCase = true)

    class Invalid(val reason: String, val newerFormat: Boolean = false) : Exception("META inválida: $reason")

    fun toJson(): ByteArray = buildJsonObject {
        put("format", format)
        put("rom_sha256", romSha256.lowercase(Locale.ROOT))
        put("sav_sha256", savSha256.lowercase(Locale.ROOT))
        if (baseSavSha256 != null) put("base_sav_sha256", baseSavSha256.lowercase(Locale.ROOT)) else put("base_sav_sha256", JsonNull)
        put("device", buildJsonObject { put("platform", devicePlatform); put("name", deviceName) })
        put("created_ms", createdMs)
        put("core", buildJsonObject { put("name", coreName); put("version", coreVersion) })
        if (config.isNotEmpty()) put("config", JsonObject(config))
        stateOfSavSha256?.let { put("state_of_sav_sha256", it.lowercase(Locale.ROOT)) }
        playTimeMs?.let { put("play_time_ms", it) }
        title?.let { put("title", it) }
        alias?.let { put("alias", it) }
        tags?.let { t -> put("tags", buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) }
        milestones?.let { ms ->
            put("milestones", buildJsonArray {
                ms.forEach { m -> add(buildJsonObject { put("id", m.id); put("title", m.title); put("done", m.done) }) }
            })
        }
        moment?.let { m ->
            put("moment", buildJsonObject {
                put("name", m.name)
                m.collection?.let { put("collection", it) }
                m.note?.let { put("note", it) }
                m.createdMs?.let { put("created_ms", it) }
            })
        }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT = 1
        private const val MAX_INT = (1L shl 53) - 1
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
        private val INT = Regex("0|[1-9][0-9]{0,15}")
        private val PLATFORMS = setOf("ios", "android")
        private val CORES = setOf("gb", "gba")
        private val json = Json { isLenient = false }

        /** Valida `META v1` entera; lanza [Invalid] ante cualquier tipo, clave obligatoria o límite que no encaje. */
        fun parse(bytes: ByteArray): PgbmMeta {
            if (bytes.isEmpty() || bytes.size > 65_536) throw Invalid("longitud")
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) throw Invalid("BOM")
            val root = try {
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw Invalid("no es un objeto JSON")
            val format = int(root, "format") ?: throw Invalid("format")
            if (format != FORMAT.toLong()) throw Invalid("format $format", newerFormat = format > FORMAT)
            val rom = hex(root, "rom_sha256") ?: throw Invalid("rom_sha256")
            val sav = hex(root, "sav_sha256") ?: throw Invalid("sav_sha256")
            val base = when (val b = root["base_sav_sha256"]) {
                null, JsonNull -> null
                else -> (b as? JsonPrimitive)?.takeIf { it.isString && HEX64.matches(it.content) }?.content?.lowercase(Locale.ROOT)
                    ?: throw Invalid("base_sav_sha256")
            }
            val device = root["device"] as? JsonObject ?: throw Invalid("device")
            val platform = str(device, "platform", 16)?.takeIf { it in PLATFORMS } ?: throw Invalid("device.platform")
            val name = str(device, "name", 128) ?: throw Invalid("device.name")
            val created = int(root, "created_ms") ?: throw Invalid("created_ms")
            val core = root["core"] as? JsonObject ?: throw Invalid("core")
            val coreName = str(core, "name", 8)?.takeIf { it in CORES } ?: throw Invalid("core.name")
            val coreVersion = optStr(core, "version", 32) ?: ""
            val config = when (val c = root["config"]) {
                null -> emptyMap()
                is JsonObject -> c.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }.toMap()
                else -> throw Invalid("config")
            }
            val stateOf = when (root["state_of_sav_sha256"]) {
                null -> null
                else -> hex(root, "state_of_sav_sha256") ?: throw Invalid("state_of_sav_sha256")
            }
            val playTime = if (root.containsKey("play_time_ms")) int(root, "play_time_ms") ?: throw Invalid("play_time_ms") else null
            val tags = (root["tags"])?.let { t ->
                val arr = t as? JsonArray ?: throw Invalid("tags")
                if (arr.size > 64) throw Invalid("tags")
                arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 64 }?.content ?: throw Invalid("tags") }
            }
            val milestones = root["milestones"]?.let { t ->
                val arr = t as? JsonArray ?: throw Invalid("milestones")
                if (arr.size > 256) throw Invalid("milestones")
                arr.map { e ->
                    val o = e as? JsonObject ?: throw Invalid("milestones")
                    val done = (o["done"] as? JsonPrimitive)?.takeIf { !it.isString && (it.content == "true" || it.content == "false") }
                        ?: throw Invalid("milestones.done")
                    Milestone(str(o, "id", 64) ?: throw Invalid("milestones.id"), str(o, "title", 256) ?: throw Invalid("milestones.title"), done.content == "true")
                }
            }
            val moment = root["moment"]?.let { m ->
                val o = m as? JsonObject ?: throw Invalid("moment")
                Moment(
                    str(o, "name", 256) ?: throw Invalid("moment.name"),
                    optStr(o, "collection", 256), optStr(o, "note", 4096),
                    if (o.containsKey("created_ms")) int(o, "created_ms") ?: throw Invalid("moment.created_ms") else null,
                )
            }
            return PgbmMeta(
                romSha256 = rom, savSha256 = sav, baseSavSha256 = base, devicePlatform = platform, deviceName = name,
                createdMs = created, coreName = coreName, coreVersion = coreVersion, config = config, stateOfSavSha256 = stateOf,
                playTimeMs = playTime, title = optStr(root, "title", 256), alias = optStr(root, "alias", 256), tags = tags,
                milestones = milestones, moment = moment, format = format.toInt(),
            )
        }

        private fun int(o: JsonObject, key: String): Long? {
            val p = o[key] as? JsonPrimitive ?: return null
            if (p.isString || !INT.matches(p.content)) return null
            return p.content.toLongOrNull()?.takeIf { it in 0..MAX_INT }
        }

        private fun hex(o: JsonObject, key: String): String? =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString && HEX64.matches(it.content) }?.content?.lowercase(Locale.ROOT)

        private fun str(o: JsonObject, key: String, max: Int): String? =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.codePointCount(0, it.content.length) <= max }?.content

        /** Opcional: ausente = null; presente con otro tipo o largo = inválida. */
        private fun optStr(o: JsonObject, key: String, max: Int): String? {
            if (o[key] == null) return null
            return str(o, key, max) ?: throw Invalid(key)
        }
    }
}
