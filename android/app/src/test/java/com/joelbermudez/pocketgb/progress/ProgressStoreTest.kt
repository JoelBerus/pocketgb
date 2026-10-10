package com.joelbermudez.pocketgb.progress

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N6 · progreso: hitos y plantillas, porcentaje desactivado por defecto, y tiempo de juego solo con el juego corriendo. */
class ProgressStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fp = "ef".repeat(32)
    private val store by lazy { ProgressStore(File(tmp.root, "progress"), now = { 42L }) }
    private val badges = (1..8).map { "Medalla $it" } + "Liga Pokémon"

    @Test fun milestonesAreOptionalAndThePercentIsOffByDefault() {
        val p = store.load(fp)
        assertTrue(p.milestones.isEmpty())
        assertNull(p.percent)
        assertEquals(false, p.showPercent)
    }

    @Test fun thePokemonTemplateAddsNineMilestonesAndKeepsOwnOnes() {
        store.addMilestone(fp, "Mío")
        store.applyTemplate(fp, MilestoneTemplate.POKEMON, badges)
        store.applyTemplate(fp, MilestoneTemplate.POKEMON, badges) // no duplica
        val p = store.load(fp)
        assertEquals(listOf("Mío") + badges, p.milestones.map { it.title })
        val first = p.milestones[1]
        store.setDone(fp, first.id, true)
        store.setShowPercent(fp, true)
        val q = store.load(fp)
        assertEquals(10, q.percent)
        assertEquals(42L, q.milestones[1].doneAtMs)
        assertTrue(q.showPercent)
        store.removeMilestone(fp, q.milestones[0].id)
        assertEquals(9, store.load(fp).milestones.size)
    }

    @Test fun theReaderSuggestionMarksOnlyTheBadgesItRead() {
        store.applyTemplate(fp, MilestoneTemplate.POKEMON, badges)
        val p = store.load(fp)
        val pokemon = PokemonProgress(PokemonGame.GEN1, "ROJO", 0x07, 3, 10, 20, 5, 6, 7, 1000)
        val ids = ProgressService.badgeSuggestionStatic(p, badges.take(8), pokemon)
        assertEquals(p.milestones.take(3).map { it.id }, ids)
        store.markFirst(fp, ids)
        assertEquals(3, store.load(fp).milestones.count { it.done })
        assertTrue(ProgressService.badgeSuggestionStatic(store.load(fp), badges.take(8), pokemon).isEmpty())
    }

    @Test fun aDamagedFileIsSetAsideNotOverwritten() {
        val file = store.file(fp)
        file.parentFile.mkdirs()
        file.writeText("{ roto")
        assertEquals(0L, store.load(fp).playTimeMs)
        store.addMilestone(fp, "x")
        assertEquals(1, store.load(fp).milestones.size)
        assertTrue(file.parentFile.list()!!.any { it.contains("damaged") })
    }

    @Test fun theRomHeaderRoundTrips() {
        val header = ByteArray(0x150) { it.toByte() }
        store.recordHeader(fp, header)
        assertTrue(header.contentEquals(store.load(fp).romHeader))
    }

    @Test fun playTimeCountsOnlyWhileRunningAndSurvivesAForcedClose() {
        var t = 0L
        var wall = 1_000L
        val tracker = PlayTimeTracker(store, fp, clock = { t }, wall = { wall })
        tracker.onRunning(true)
        t = 10_000
        tracker.onRunning(false) // pausa
        t = 70_000 // en pausa o en segundo plano: no cuenta
        tracker.onRunning(true)
        t = 75_000
        tracker.checkpoint()
        t = 90_000 // cierre forzado aquí: estos 15 s se pierden, lo anterior no
        val p = store.load(fp)
        assertEquals(15_000L, p.playTimeMs)
        assertEquals(1, p.sessions)
        assertEquals(1_000L, p.firstPlayedMs)
        // Una sesión nueva (otro tracker) suma una sesión y no cambia la primera vez.
        wall = 2_000
        val next = PlayTimeTracker(store, fp, clock = { t }, wall = { wall })
        next.onRunning(true)
        t = 95_000
        next.onRunning(false)
        val q = store.load(fp)
        assertEquals(20_000L, q.playTimeMs)
        assertEquals(2, q.sessions)
        assertEquals(1_000L, q.firstPlayedMs)
        assertEquals(2_000L, q.lastPlayedMs)
    }

    @Test fun decodesTheNativeReaderOutput() {
        val name = "ROJO♂".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
        val values = intArrayOf(1, 0x0f, 4, 30, 50, 12, 34, 56, 999_999) + name.toIntArray()
        val p = PokemonProgress.decode(values)!!
        assertEquals(PokemonGame.GEN1, p.game)
        assertEquals("ROJO♂", p.playerName)
        assertEquals(999_999, p.money)
        assertNull(PokemonProgress.decode(null))
        assertNull(PokemonProgress.decode(intArrayOf(9, 0, 0, 0, 0, 0, 0, 0, 0)))
    }
}
