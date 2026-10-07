package com.joelbermudez.pocketgb.progress

/** Generación que reconoce el lector (= `pgb_prog_game`). */
enum class PokemonGame(val native: Int) {
    GEN1(1), GEN2_GOLD_SILVER(2), GEN2_CRYSTAL(3);

    /** Medallas que existen en el juego: 8 en la 1.ª generación, 16 (Johto + Kanto) en la 2.ª. */
    val maxBadges: Int get() = if (this == GEN1) 8 else 16

    /** Especies de la Pokédex nacional del juego. */
    val pokedexSize: Int get() = if (this == GEN1) 151 else 251

    companion object {
        fun fromNative(value: Int): PokemonGame? = entries.firstOrNull { it.native == value }
    }
}

/**
 * N6 · progreso leído de la partida por `pgb_progress_read` (core/, ND5). Solo informativo y de solo lectura: el lector
 * no escribe nada y solo devuelve datos si las validaciones internas de la partida cuadran.
 */
data class PokemonProgress(
    val game: PokemonGame,
    val playerName: String,
    val badgesMask: Int,
    val badges: Int,
    val pokedexOwned: Int,
    val pokedexSeen: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val money: Int,
) {
    companion object {
        private const val FIXED = 9

        /** Decodifica la salida de `nativeProgressRead`; `null` si está vacía o es incoherente. */
        fun decode(values: IntArray?): PokemonProgress? {
            if (values == null || values.size < FIXED) return null
            val game = PokemonGame.fromNative(values[0]) ?: return null
            val name = ByteArray(values.size - FIXED) { values[FIXED + it].toByte() }.toString(Charsets.UTF_8)
            return PokemonProgress(
                game = game,
                playerName = name,
                badgesMask = values[1],
                badges = values[2],
                pokedexOwned = values[3],
                pokedexSeen = values[4],
                hours = values[5],
                minutes = values[6],
                seconds = values[7],
                money = values[8],
            )
        }
    }
}

/** Quien lee el progreso (la app usa el núcleo nativo; las pruebas JVM, uno simulado). */
fun interface PokemonReader {
    /** [header] = al menos los primeros 0x150 bytes del ROM; [sram] = la partida (`.sav`). `null` = sin datos. */
    fun read(header: ByteArray, sram: ByteArray): PokemonProgress?

    companion object {
        const val HEADER_BYTES = 0x150

        /** El lector del núcleo (`pgb_progress_read`). Nunca lanza: un fallo es «sin datos». */
        val native = PokemonReader { header, sram ->
            try {
                PokemonProgress.decode(com.joelbermudez.pocketgb.emulator.NativeLibrary.nativeProgressRead(header, sram))
            } catch (_: Throwable) {
                null
            }
        }
    }
}
