package com.joelbermudez.pocketgb.library.artwork

import com.joelbermudez.pocketgb.library.DefaultPreferencesFileOps
import com.joelbermudez.pocketgb.library.PreferencesFileOps
import com.joelbermudez.pocketgb.settings.isValidFingerprint
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** N5 · elección de portada por huella y preferencia global, por dispositivo (ND12). */
@Serializable
data class CoverSettings(
    val preference: CoverPreference = CoverPreference.IMAGES,
    val choices: Map<String, CoverChoice> = emptyMap(),
    val formatVersion: Int = FORMAT_VERSION,
) {
    fun choiceFor(fingerprint: String?): CoverChoice = fingerprint?.let(choices::get) ?: CoverChoice.AUTO

    fun withChoice(fingerprint: String, choice: CoverChoice): CoverSettings =
        if (choice == CoverChoice.AUTO) copy(choices = choices - fingerprint) else copy(choices = choices + (fingerprint to choice))

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * Persistencia de [CoverSettings] en `filesDir/covers/settings.json`, aparte de las preferencias de la biblioteca para
 * no subir su formato. Escritura atómica (temporal sincronizado y rename). Un archivo ilegible se aparta como
 * `.corrupt-<fecha>` y se empieza de cero (solo son preferencias de presentación: nunca toca partidas). Un archivo de
 * una versión futura se lee con tolerancia y no se sobrescribe.
 */
class CoverSettingsStore(
    private val file: File,
    private val ops: PreferencesFileOps = DefaultPreferencesFileOps,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private var writeProtected = false
    private val _state = MutableStateFlow(load())
    val state: StateFlow<CoverSettings> = _state.asStateFlow()

    private fun load(): CoverSettings {
        if (!file.isFile) return CoverSettings()
        val decoded = try {
            json.decodeFromString(CoverSettings.serializer(), file.readText())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            return CoverSettings()
        }
        if (decoded == null) {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${clock()}"))
            return CoverSettings()
        }
        if (decoded.formatVersion > CoverSettings.FORMAT_VERSION) writeProtected = true
        // Huellas inválidas (archivo editado a mano) se descartan.
        return decoded.copy(choices = decoded.choices.filterKeys(::isValidFingerprint))
    }

    /** Aplica [change] y lo guarda. `false` si no se pudo escribir (el valor en memoria se mantiene igualmente). */
    @Synchronized
    fun update(change: (CoverSettings) -> CoverSettings): Boolean {
        val next = change(_state.value)
        if (next == _state.value) return true
        _state.value = next
        if (writeProtected) return false
        val temp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            ops.writeSynced(temp, json.encodeToString(CoverSettings.serializer(), next).toByteArray())
            if (!ops.rename(temp, file)) throw IOException("No se pudo reemplazar ${file.name}")
            true
        } catch (_: IOException) {
            temp.delete()
            false
        }
    }

    fun setChoice(fingerprint: String, choice: CoverChoice): Boolean {
        if (!isValidFingerprint(fingerprint)) return false
        return update { it.withChoice(fingerprint, choice) }
    }

    fun setPreference(preference: CoverPreference): Boolean = update { it.copy(preference = preference) }
}
