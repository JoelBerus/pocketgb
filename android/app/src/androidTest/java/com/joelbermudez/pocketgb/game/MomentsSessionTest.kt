package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.emulator.SessionState
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.FlushResult
import com.joelbermudez.pocketgb.saves.MomentLibrary
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.SavePendingException
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.openGame
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N6 · momentos sobre el núcleo real con una ROM sintética que cuenta en la SRAM: cargar cambia la partida (con backup y
 * anillo «Antes de cargar»), «Recuperar» la devuelve en un toque, el AUTO no se pisa, un estado que ya no carga deja
 * recuperar la RAM del momento y la huella está excluida mientras haya sesión.
 */
class MomentsSessionTest {
    private fun playAndPause(game: GameSession, ms: Long = 250): FlushResult {
        if (game.state.value == SessionState.Paused) game.resume() else game.start()
        val before = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > before + 2 })
        Thread.sleep(ms)
        return game.pause()
    }

    private fun moments(g: com.joelbermudez.pocketgb.testing.OpenedGame) =
        MomentStore(File(g.root, "moments"), g.game.fingerprint)

    @Test
    fun loadingAnOldMomentChangesTheGameAndRecoverReturnsTheNewerOneInOneTap() {
        openGame(SyntheticRom.sramCounter(), withMoments = true).use { g ->
            val game = g.game
            val store = g.store!!
            assertEquals(FlushResult.Saved, playAndPause(game))
            val old = game.createMoment("Viejo", 1_000)
            val oldSram = moments(g).loadSram(MomentStore.Kind.MOMENT, old.id)!!
            assertArrayEquals("el momento guarda la RAM del instante", game.session.copySram(), oldSram)
            assertEquals(mapOf("console" to "GB", "model" to "AUTO", "palette" to "0"), old.config)

            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val newer = store.load()!!
            assertFalse(newer.contentEquals(oldSram))
            game.saveState(StateSlot.AUTO)
            val autoBefore = g.states.load(StateSlot.AUTO)

            game.loadMoment(MomentStore.Kind.MOMENT, old.id, old.name, null)
            assertArrayEquals("cargar cambia la partida", oldSram, store.load())
            assertTrue("la más nueva queda en backup", store.backups().any { store.backupFile(it.index).readBytes().contentEquals(newer) })
            val ring = game.moments().beforeLoad
            assertEquals(1, ring.size)
            assertArrayEquals("y en el anillo «Antes de cargar»", newer, moments(g).loadSram(MomentStore.Kind.BEFORE_LOAD, ring.single().id))
            assertArrayEquals("cargar no pisa el AUTO", autoBefore, g.states.load(StateSlot.AUTO))

            // «Recuperar» en un toque.
            game.loadMoment(MomentStore.Kind.BEFORE_LOAD, ring.single().id, ring.single().name, null)
            assertArrayEquals("vuelve la partida más nueva", newer, store.load())
            assertArrayEquals(newer, game.session.copySram())
            assertTrue("la del momento queda en backup", store.backups().any { store.backupFile(it.index).readBytes().contentEquals(oldSram) })
            assertEquals(2, game.moments().beforeLoad.size)
            assertArrayEquals(autoBefore, g.states.load(StateSlot.AUTO))
        }
    }

    /** N6A-H2: con el anillo lleno, recuperar su entrada más antigua y que falle la carga no la expulsa ni borra. */
    @Test
    fun recoveringTheOldestRingEntryWithAFullRingSurvivesAFailedLoad() {
        openGame(SyntheticRom.sramCounter(), withMoments = true).use { g ->
            val game = g.game
            playAndPause(game)
            val m = game.createMoment("M", null)
            repeat(3) {
                playAndPause(game, 150)
                game.loadMoment(MomentStore.Kind.MOMENT, m.id, m.name, null)
            }
            val ring = game.moments().beforeLoad
            assertEquals(3, ring.size)
            val oldest = ring.last()
            val oldestSram = moments(g).loadSram(MomentStore.Kind.BEFORE_LOAD, oldest.id)!!
            // Fuerza el fallo de applyLoadedState: su estado ya no lo acepta el núcleo.
            val stateFile = moments(g).stateFile(MomentStore.Kind.BEFORE_LOAD, oldest.id)
            stateFile.writeBytes(stateFile.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte(); it[8] = 0x7f })
            val before = g.store!!.load()!!
            assertThrows(StateError.Core::class.java) { game.loadMoment(MomentStore.Kind.BEFORE_LOAD, oldest.id, oldest.name, null) }
            assertArrayEquals("la partida no cambió", before, g.store!!.load())
            assertTrue("la entrada recuperada sigue en el anillo", game.moments().beforeLoad.any { it.id == oldest.id })
            assertArrayEquals("y su RAM sigue en disco", oldestSram, moments(g).loadSram(MomentStore.Kind.BEFORE_LOAD, oldest.id))
            assertEquals(3, game.moments().beforeLoad.size)
        }
    }

    @Test
    fun aStateTheCoreNoLongerLoadsStillLetsTheMomentRamBeRecovered() {
        val ownership = FingerprintOwnership()
        openGame(SyntheticRom.sramCounter(), withMoments = true, ownership = ownership).use { g ->
            val game = g.game
            val store = g.store!!
            playAndPause(game)
            val m = game.createMoment("Roto", null)
            val momentSram = moments(g).loadSram(MomentStore.Kind.MOMENT, m.id)!!
            // Simula un estado que una versión futura del núcleo ya no carga.
            val stateFile = moments(g).stateFile(MomentStore.Kind.MOMENT, m.id)
            stateFile.writeBytes(stateFile.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte(); it[8] = 0x7f })
            assertEquals(FlushResult.Saved, playAndPause(game, 300))
            val current = store.load()!!
            assertThrows(StateError.Core::class.java) { game.loadMoment(MomentStore.Kind.MOMENT, m.id, m.name, null) }
            assertArrayEquals("la partida no cambió", current, store.load())

            val library = MomentLibrary(File(g.root, "moments"), File(g.root, "states"), File(g.root, "saves"), ownership = ownership)
            // Exclusión por huella: con la sesión abierta (o aparcada) no se toca la partida.
            assertThrows(SavePendingException::class.java) {
                library.installSram(game.fingerprint, MomentStore.Kind.MOMENT, m.id, m.name, openFingerprint = null)
            }
            assertArrayEquals(current, store.load())

            game.exit()
            assertTrue(waitUntil { !game.holdsLease })
            library.installSram(game.fingerprint, MomentStore.Kind.MOMENT, m.id, m.name, openFingerprint = null)
            assertArrayEquals("la RAM del momento es ahora la partida", momentSram, store.load())
            assertTrue(store.backups().any { store.backupFile(it.index).readBytes().contentEquals(current) })
        }
    }

    @Test
    fun aMomentNeedsAPausedSessionAndNeverWritesWhenTheRingCannotBeSaved() {
        openGame(SyntheticRom.sramCounter(), withMoments = true).use { g ->
            val game = g.game
            playAndPause(game)
            val m = game.createMoment("A", null)
            game.resume()
            assertThrows(StateError.NotParked::class.java) { game.loadMoment(MomentStore.Kind.MOMENT, m.id, m.name, null) }
            game.pause()
            val before = g.store!!.load()!!
            // Sin poder escribir el anillo (directorio de momentos de solo lectura), no se carga nada.
            val dir = moments(g).directory
            dir.setWritable(false)
            try {
                assertThrows(StateError::class.java) { game.loadMoment(MomentStore.Kind.MOMENT, m.id, m.name, null) }
            } finally {
                dir.setWritable(true)
            }
            assertArrayEquals(before, g.store!!.load())
            assertArrayEquals(before, game.session.copySram())
        }
    }

    @Test
    fun openingTheGameMigratesSlotsOneToFourAndRescueIntoMomentsWithoutLoss() {
        val root = com.joelbermudez.pocketgb.testing.tempDir("moments-migrate")
        try {
            val rom = SyntheticRom.sramCounter()
            val launcher = GameplayTestHost.launcher(root, rom = rom)
            // Una primera apertura para conocer la huella y tener un estado real que migrar.
            val first = (launcher.openBlocking(GameplayTestHost.entry) as OpenResult.Opened).game
            playAndPause(first)
            first.saveState(StateSlot.MANUAL2)
            first.exit()
            assertTrue(waitUntil { !first.holdsLease })
            val states = com.joelbermudez.pocketgb.saves.StateStore(File(root, "states"), first.fingerprint)
            val slot2 = states.load(StateSlot.MANUAL2)
            // Un rescate (J6) de una salida con fallo de guardado (otro contenido: uno idéntico se reconoce como ya migrado).
            val rescue = slot2 + byteArrayOf(1)
            states.saveRescue(rescue, null)

            val opened = launcher.openBlocking(GameplayTestHost.entry) as OpenResult.Opened
            opened.game.use { game ->
                val moments = game.moments().moments
                assertEquals(setOf("slot2", "rescue"), moments.mapNotNull { it.origin }.toSet())
                val store = MomentStore(File(root, "moments"), game.fingerprint)
                val bySlot = moments.associateBy { it.origin }
                assertArrayEquals("estado byte a byte", slot2, store.loadState(MomentStore.Kind.MOMENT, bySlot.getValue("slot2").id))
                assertArrayEquals(rescue, store.loadState(MomentStore.Kind.MOMENT, bySlot.getValue("rescue").id))
                assertFalse(states.stateFile(StateSlot.MANUAL2).exists())
                assertFalse(states.stateFile(StateSlot.RESCUE).exists())
                assertTrue("se avisa del rescate migrado", opened.notices.contains(GameNotice.RescueMoment))
                // Y el momento migrado se carga en la sesión.
                game.start()
                game.pause()
                val migrated = bySlot.getValue("slot2")
                game.loadMoment(MomentStore.Kind.MOMENT, migrated.id, migrated.name, null)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
