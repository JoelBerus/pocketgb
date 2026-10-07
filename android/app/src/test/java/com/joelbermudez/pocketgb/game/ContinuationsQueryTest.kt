package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A9 · qué juegos ofrecen «Continuar» en la biblioteca: solo fecha y firma del AUTO frente a la partida local. */
class ContinuationsQueryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var launcher: GameLauncher
    private val a = "aa".repeat(32)
    private val b = "bb".repeat(32)
    private val c = "cc".repeat(32)

    @Before
    fun setUp() {
        launcher = GameLauncher(
            roms = RomSource { _, _ -> error("no se abre nada") },
            savesDirectory = File(tmp.root, "saves"),
            statesRoot = File(tmp.root, "states"),
        )
    }

    private fun auto(fingerprint: String, atMs: Long, bytes: ByteArray = "PGBS-estado".toByteArray()) {
        val states = StateStore(File(tmp.root, "states"), fingerprint)
        states.save(bytes, null, StateSlot.AUTO)
        states.stateFile(StateSlot.AUTO).setLastModified(atMs)
    }

    private fun save(fingerprint: String, atMs: Long) {
        val store = SaveStore(File(tmp.root, "saves"), fingerprint)
        store.save(ByteArray(16) { 1 })
        store.saveFile.setLastModified(atMs)
    }

    @Test
    fun onlyFreshSignedStatesAreOfferedWithTheirDate() {
        save(a, 1_000_000); auto(a, 2_000_000) // vigente
        save(b, 5_000_000); auto(b, 2_000_000) // el juego guardó después
        auto(c, 3_000_000, bytes = "XXXX".toByteArray()) // sin firma
        assertEquals(mapOf(a to 2_000_000L), launcher.continuations(setOf(a, b, c)))
    }

    @Test
    fun aGameWithoutSaveButWithAStateIsOffered() {
        auto(a, 2_000_000)
        assertEquals(setOf(a), launcher.continuations(setOf(a)).keys)
    }

    @Test
    fun aPathThatIsNotAFingerprintIsNeverRead() {
        File(tmp.root, "escape").mkdirs()
        val outside = StateStore(File(tmp.root, "escape"))
        outside.save("PGBS-estado".toByteArray(), null, StateSlot.AUTO)
        assertTrue(launcher.continuations(setOf("../escape", "", "ZZ".repeat(32))).isEmpty())
    }
}
