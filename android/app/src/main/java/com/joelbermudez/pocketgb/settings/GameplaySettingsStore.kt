package com.joelbermudez.pocketgb.settings

import com.joelbermudez.pocketgb.library.DefaultPreferencesFileOps
import com.joelbermudez.pocketgb.library.PreferencesFileOps
import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Dónde viven los ajustes de gameplay. Interfaz pequeña para inyectar fallos de I/O en las pruebas. */
interface GameplaySettingsStore {
    /** Falla con [IOException] si el disco no responde (no es «corrupto»). */
    fun load(): GameplaySettingsData

    fun save(data: GameplaySettingsData)
}

/**
 * Persistencia atómica en `filesDir/gameplay-settings.json` (mismo patrón que `LibraryPreferencesFile`):
 * temporal sincronizado + rename, recuperación del temporal huérfano y cuarentena del archivo ilegible.
 * Al decodificar, cada campo se valida por separado: un valor inválido vuelve al valor por defecto de ese campo,
 * nunca se descarta todo el archivo.
 */
class GameplaySettingsFile(
    private val file: File,
    private val ops: PreferencesFileOps = DefaultPreferencesFileOps,
    private val clock: () -> Long = System::currentTimeMillis,
) : GameplaySettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val temp get() = File(file.parentFile, file.name + ".tmp")

    override fun load(): GameplaySettingsData {
        if (!file.exists()) return recoverTemp() ?: GameplaySettingsData()
        return decode(file.readText()) ?: run {
            quarantine()
            GameplaySettingsData()
        }
    }

    override fun save(data: GameplaySettingsData) {
        file.parentFile?.mkdirs()
        val temp = temp
        try {
            ops.writeSynced(temp, json.encodeToString(GameplaySettingsData.serializer(), data.sanitized()).toByteArray())
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
        if (!ops.rename(temp, file)) {
            temp.delete()
            throw IOException("No se pudo reemplazar ${file.name}")
        }
    }

    private fun decode(text: String): GameplaySettingsData? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val d = GameplaySettingsData()
        fun <T> field(name: String, serializer: KSerializer<T>, default: T): T {
            val element: JsonElement = root[name] ?: return default
            return try {
                json.decodeFromJsonElement(serializer, element)
            } catch (_: SerializationException) {
                default
            } catch (_: IllegalArgumentException) {
                default
            }
        }
        // `perGame` se decodifica entrada a entrada para que un juego dañado no arrastre a los demás.
        val perGame = (root["perGame"] as? JsonObject)?.mapNotNull { (key, value) ->
            try {
                key to json.decodeFromJsonElement(GameOverrides.serializer(), value)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }?.toMap() ?: emptyMap()
        return GameplaySettingsData(
            schema = field("schema", Int.serializer(), d.schema),
            opacity = field("opacity", Int.serializer(), d.opacity),
            visibility = field("visibility", ControlsVisibility.serializer(), d.visibility),
            haptics = field("haptics", Boolean.serializer(), d.haptics),
            sizeScale = field("sizeScale", Float.serializer(), d.sizeScale),
            portraitLayout = field("portraitLayout", StoredControlLayout.serializer(), d.portraitLayout),
            landscapeLayout = field("landscapeLayout", StoredControlLayout.serializer(), d.landscapeLayout),
            integerScaleLandscape = field("integerScaleLandscape", Boolean.serializer(), d.integerScaleLandscape),
            dpadStyle = field("dpadStyle", DpadStyle.serializer(), d.dpadStyle),
            volume = field("volume", Float.serializer(), d.volume),
            colorForGameBoy = field("colorForGameBoy", Boolean.serializer(), d.colorForGameBoy),
            compatPalette = field("compatPalette", Int.serializer(), d.compatPalette),
            perGame = perGame,
            controllerMapping = field("controllerMapping", ControllerMappingData.serializer().nullable, null),
            showTouchControlsWithController = field(
                "showTouchControlsWithController", Boolean.serializer(), d.showTouchControlsWithController,
            ),
        ).sanitized()
    }

    private fun recoverTemp(): GameplaySettingsData? {
        val temp = temp
        if (!temp.exists()) return null
        val data = decode(temp.readText()) ?: return null
        ops.rename(temp, file) // si falla, la próxima escritura lo regenera igualmente
        return data
    }

    private fun quarantine() {
        val base = "${file.name}.corrupt-${clock()}"
        var target = File(file.parentFile, base)
        var n = 1
        while (target.exists()) target = File(file.parentFile, "$base-${n++}")
        if (!file.renameTo(target)) throw IOException("No se pudo apartar ${file.name} ilegible")
    }
}
