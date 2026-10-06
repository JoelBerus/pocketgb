package com.joelbermudez.pocketgb.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** A6-H2: la paleta en caliente se observa como flujo de un `StateFlow` (no con `snapshotFlow`). */
class CompatPaletteChangesTest {
    private val fp = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun emitsOnlyLaterDistinctChangesForThisGame() = runBlocking {
        val state = MutableStateFlow(GameplaySettingsData(compatPalette = 2))
        val seen = mutableListOf<Int>()
        val job = launch { state.compatPaletteChanges(fp).toList(seen) }
        yield()
        state.value = state.value.copy(compatPalette = 2) // sin cambio
        state.value = state.value.copy(compatPalette = 7)
        yield()
        state.value = state.value.copy(perGame = mapOf(other to GameOverrides(compatPalette = 3))) // otro juego
        yield()
        state.value = state.value.copy(perGame = mapOf(fp to GameOverrides(compatPalette = 9)))
        yield()
        job.cancel()
        assertEquals(listOf(7, 9), seen)
    }
}
