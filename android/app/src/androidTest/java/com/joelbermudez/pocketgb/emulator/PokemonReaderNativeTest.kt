package com.joelbermudez.pocketgb.emulator

import com.joelbermudez.pocketgb.progress.PokemonGame
import com.joelbermudez.pocketgb.progress.PokemonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * N6 · el lector Pokémon (`pgb_progress_read`) a través de JNI con una partida sintética de la 1.ª generación construida
 * byte a byte (nunca partidas reales): con el checksum bien da datos; con uno mal, o con otro título, nada.
 */
class PokemonReaderNativeTest {
    private fun header(title: String, destination: Int = 1) = ByteArray(PokemonReader.HEADER_BYTES).also { h ->
        title.toByteArray(Charsets.US_ASCII).copyInto(h, 0x134)
        h[0x14A] = destination.toByte()
    }

    /** Rojo internacional: «ROJO», 3 medallas, 2 capturados y 5 vistos, 12:34:56 y 001234 ₽. */
    private fun gen1Save(): ByteArray {
        val sav = ByteArray(0x8000)
        byteArrayOf(0x91.toByte(), 0x8E.toByte(), 0x89.toByte(), 0x8E.toByte(), 0x50).copyInto(sav, 0x2598)
        sav[0x25A3] = 0x03 // capturados: 2 bits
        sav[0x25B6] = 0x1F // vistos: 5 bits
        byteArrayOf(0x00, 0x12, 0x34).copyInto(sav, 0x25F3) // BCD
        sav[0x2602] = 0x07
        byteArrayOf(12, 0, 34, 56, 0).copyInto(sav, 0x2CED)
        var sum = 0
        for (i in 0x2598 until 0x3523) sum += sav[i].toInt() and 0xff
        sav[0x3523] = (255 - (sum and 0xff)).toByte()
        return sav
    }

    @Test fun readsASyntheticGen1Save() {
        val p = PokemonReader.native.read(header("POKEMON RED"), gen1Save())
        assertNotNull(p)
        assertEquals(PokemonGame.GEN1, p!!.game)
        assertEquals("ROJO", p.playerName)
        assertEquals(3, p.badges)
        assertEquals(2, p.pokedexOwned)
        assertEquals(5, p.pokedexSeen)
        assertEquals(12, p.hours)
        assertEquals(34, p.minutes)
        assertEquals(56, p.seconds)
        assertEquals(1234, p.money)
    }

    @Test fun aWrongChecksumOrAnotherGameGivesNoData() {
        val bad = gen1Save().also { it[0x3523] = (it[0x3523] + 1).toByte() }
        assertNull(PokemonReader.native.read(header("POKEMON RED"), bad))
        assertNull(PokemonReader.native.read(header("TETRIS"), gen1Save()))
        assertNull("japonés", PokemonReader.native.read(header("POKEMON RED", destination = 0), gen1Save()))
        assertNull(PokemonReader.native.read(ByteArray(10), gen1Save()))
        assertNull(PokemonReader.native.read(header("POKEMON RED"), ByteArray(0x9000)))
    }
}
