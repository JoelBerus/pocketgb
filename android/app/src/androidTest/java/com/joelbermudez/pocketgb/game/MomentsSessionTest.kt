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
}
