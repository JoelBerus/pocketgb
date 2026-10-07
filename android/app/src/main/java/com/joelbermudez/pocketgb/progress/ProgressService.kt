package com.joelbermudez.pocketgb.progress

import android.content.Context
import com.joelbermudez.pocketgb.saves.SaveStore
import java.io.File
import java.io.IOException

/** Lo que muestra el panel de progreso: lo del usuario y, si es un Pokémon soportado con datos válidos, lo leído. */
data class ProgressView(val progress: GameProgress, val pokemon: PokemonProgress?)

/**
 * N6 · progreso de un juego desde el detalle y el centro de ajustes. El lector Pokémon usa la cabecera del ROM que se
 * guardó al abrir el juego y la partida local (`saves/<huella>.sav`, escrita siempre de forma atómica): solo lee.
 */
class ProgressService(
    val store: ProgressStore,
    private val savesDirectory: File,
    private val reader: PokemonReader = PokemonReader.native,
) {
    /** Bloquea (lee archivos): fuera del hilo principal. */
    fun view(fingerprint: String): ProgressView {
        val progress = store.load(fingerprint)
        val header = progress.romHeader?.takeIf { it.size >= PokemonReader.HEADER_BYTES }
        val pokemon = header?.let {
            try {
                SaveStore(savesDirectory, fingerprint).load()?.let { sram -> reader.read(it, sram) }
            } catch (_: IOException) {
                null
            }
        }
        return ProgressView(progress, pokemon)
    }

    companion object {
        /**
         * Ids de los hitos que el lector propone marcar: «Medalla 1»…«Medalla N» (N = medallas leídas, como mucho las 8
         * de la plantilla) que aún no están marcados. Nunca marca nada por sí solo.
         */
        fun badgeSuggestionStatic(progress: GameProgress, badgeTitles: List<String>, pokemon: PokemonProgress): List<String> {
            val wanted = badgeTitles.take(pokemon.badges).map { it.lowercase() }.toSet()
            return progress.milestones.filter { !it.done && it.title.lowercase() in wanted }.map { it.id }
        }

        @Volatile private var instance: ProgressService? = null

        fun shared(context: Context): ProgressService = instance ?: synchronized(this) {
            instance ?: ProgressService(
                ProgressStore(File(context.applicationContext.filesDir, "progress")),
                File(context.applicationContext.filesDir, "saves"),
            ).also { instance = it }
        }
    }
}
