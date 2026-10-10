package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.saves.saf.SafSiblings
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Auditoría conjunta final del nivel N (docs/auditorias/N-final-respuesta-android.md): H2, H3, H4, H5, H8 y H9, con E/S
 * real sobre directorios temporales. Cargas sintéticas (`version`), nunca partidas reales.
 */
class NFinalSavesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File

    @Before fun setUp() { dir = File(tmp.newFolder(), "saves") }

    private fun store() = SaveStore(dir, java.util.UUID.randomUUID().toString().take(12))
    private fun h(d: ByteArray) = SaveStore(dir, "x").contentHash(d)

    // MARK: H2 · un .gba con el mismo nombre comparte el .sav

    @Test fun aGbaWithTheSameBaseNameSharesTheMirror() {
        assertEquals(2, SafSiblings.romsSharingBase("Juego", listOf("Juego.gb", "Juego.gba", "Juego.sav")))
        assertEquals(2, SafSiblings.romsSharingBase("juego", listOf("JUEGO.GBA", "Juego.gbc")))
        assertEquals(1, SafSiblings.romsSharingBase("Juego", listOf("Juego.gb", "Otro.gba", "Juego.sav", ".Juego.gba")))
        assertEquals(setOf("Juego", "Juego 2"), SafSiblings.romBases(listOf("Juego.gb", "Juego 2.gba", "Juego 2.sav")))
    }

    // MARK: H3 · recordReceived conserva la ubicación del espejo

    @Test fun recordReceivedKeepsTheMirrorLocation() {
        val s = store()
        s.recordSuccessfulMirror(version(1), 10L, location = "carpeta/juego.sav")
        s.recordReceived(version(2))
        assertEquals("carpeta/juego.sav", s.mirrorLocation())
        assertEquals(h(version(2)), s.lineageBase())
    }

    // MARK: H4 · las recibidas cuentan como historial propio (= iOS, ND20 b′)

    /**
     * Mismo caso que iOS (`receivedCountsAsOwnHistory`): el historial solo tiene una partida recibida (importada) y nunca
     * se escribió el espejo. (1) Un espejo igual a esa recibida, aunque tenga una fecha más nueva, es una versión nuestra
     * anterior: gana la local. (2) Con la local sin cambiar desde que se recibió, un espejo desconocido más viejo es un
     * cambio externo (linaje), no se decide por fecha.
     */
    @Test fun receivedCountsAsOwnHistory() {
        val sizes = setOf(4)
        // (1) OWN_OLDER
        val a = store()
        a.recordReceived(version(1))
        a.save(version(2)) // se jugó aquí después de importar
        val m1 = FakeSaveMirror(SaveMirror.Snapshot.Read(version(1), Long.MAX_VALUE))
        val o1 = SaveOpening.prepare(a, m1, m1.snapshot(), sizes)
        assertArrayEquals("gana la local", version(2), o1.data)
        assertEquals(SaveLoadWarning.MirrorOlderSetAside(), o1.warning)
        assertTrue(o1.target!!.mirrorPending)
        // (2) EXTERNAL_CHANGE aunque el espejo sea más viejo que la local
        val b = store()
        b.save(version(1))
        b.recordReceived(version(1))
        val m2 = FakeSaveMirror(SaveMirror.Snapshot.Read(version(3), 1L))
        val o2 = SaveOpening.prepare(b, m2, m2.snapshot(), sizes)
        assertArrayEquals("linaje, no fecha", version(3), o2.data)
        assertEquals(SaveLoadWarning.ExternalChange(), o2.warning)
        assertArrayEquals("la local queda en backup", version(1), b.backupFile(1).readBytes())
        // Y en la tabla pura: con solo recibidas ya no es «sin historial».
        assertTrue(h(version(1)) in b.ownMirrorHashes())
    }

    // MARK: H5 · «Abrir con» de un .sav crudo: nombre exacto primero

    @Test fun rawSaveLooksForTheExactNameFirst() {
        val games = listOf("Tetris 2", "Tetris", "Zelda")
        assertEquals(listOf("Tetris 2"), SaveLineage.gamesForRawSave("Tetris 2", games) { it })
        assertEquals(listOf("Tetris"), SaveLineage.gamesForRawSave("Tetris 2", listOf("Tetris", "Zelda")) { it })
        assertEquals(listOf("Tetris"), SaveLineage.gamesForRawSave("tetris (1)", games) { it })
        assertEquals(listOf("Zelda"), SaveLineage.gamesForRawSave("Zelda (Joel's conflicted copy 2026-10-08)", games) { it })
        assertEquals(emptyList<String>(), SaveLineage.gamesForRawSave("Mario", games) { it })
        // Dropbox en inglés con el nombre del equipo delante de la frase (paridad menor).
        assertEquals("Zelda", SaveLineage.stripConflictSuffix("Zelda (Joel's conflicted copy 2026-10-08)"))
        assertEquals("Zelda", SaveLineage.stripConflictSuffix("Zelda (copia en conflicto de Joel 2026-10-08)"))
        assertEquals("Zelda 1", SaveLineage.stripConflictSuffix("Zelda 1"))
        assertEquals("Zelda (0)", SaveLineage.stripConflictSuffix("Zelda (0)"))
    }

    // MARK: H8 · el anillo no expulsa del índice hasta commit()

    @Test fun anUncommittedPushNeverDropsTheEvictedEntryFromTheIndex() {
        val m = MomentStore(File(tmp.newFolder(), "moments"))
        val ids = (1..MomentStore.RING_SIZE).map { m.pushBeforeLoad(MomentStore.Capture(null, version(it), null), "e$it").id }
        val pending = m.pushBeforeLoadDeferred(MomentStore.Capture(null, version(9), null), "nueva")
        assertEquals(listOf(ids.first()), pending.evicted)
        // La operación falla o el proceso muere aquí: al reabrir, la expulsada sigue en el índice y en disco.
        m.recoverOrphans()
        assertTrue(m.snapshot().beforeLoad.any { it.id == ids.first() })
        assertArrayEquals(version(1), m.loadSram(MomentStore.Kind.BEFORE_LOAD, ids.first()))
        // Al confirmar, sale del índice y sus archivos se borran.
        pending.commit()
        assertEquals(listOf(pending.entry.id) + ids.drop(1).reversed(), m.snapshot().beforeLoad.map { it.id })
        assertTrue(!m.sramFile(MomentStore.Kind.BEFORE_LOAD, ids.first()).exists())
        // Un push sin commit seguido de otro recorta el anillo a su tamaño.
        m.pushBeforeLoadDeferred(MomentStore.Capture(null, version(10), null), "otra")
        m.pushBeforeLoad(MomentStore.Capture(null, version(11), null), "y otra")
        assertEquals(MomentStore.RING_SIZE, m.snapshot().beforeLoad.size)
    }

    @Test fun rollbackRemovesOnlyTheNewEntry() {
        val m = MomentStore(File(tmp.newFolder(), "moments"))
        val ids = (1..MomentStore.RING_SIZE).map { m.pushBeforeLoad(MomentStore.Capture(null, version(it), null), "e$it").id }
        val pending = m.pushBeforeLoadDeferred(MomentStore.Capture(null, version(9), null), "rechazada")
        pending.rollback()
        assertEquals(ids.reversed(), m.snapshot().beforeLoad.map { it.id })
        assertTrue(!m.sramFile(MomentStore.Kind.BEFORE_LOAD, pending.entry.id).exists())
        assertArrayEquals(version(1), m.loadSram(MomentStore.Kind.BEFORE_LOAD, ids.first()))
    }

    // MARK: H9 · un "wt" cortado deja un prefijo: nunca se instala

    @Test fun aTruncatedOwnMirrorWriteIsOwnOlderNotAnExternalChange() {
        val sizes = setOf(4, 8) // un prefijo de 4 bytes de una partida de 8 tiene tamaño válido (p. ej. RTC)
        val s = store()
        val local = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        s.save(local)
        s.recordMirrorAttempt(local) // write-ahead; el proceso murió a mitad del "wt"
        val mirror = FakeSaveMirror(SaveMirror.Snapshot.Read(local.copyOf(4), Long.MAX_VALUE))
        val o = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertArrayEquals("gana la local", local, o.data)
        assertArrayEquals(local, s.load())
        assertEquals(SaveLoadWarning.MirrorOlderSetAside(), o.warning)
        assertTrue("se reescribe el espejo", o.target!!.mirrorPending)
        assertTrue(SaveLineage.isTruncatedOwnWrite(local, local.copyOf(4), h(local), h(local)))
        assertTrue("no es prefijo", !SaveLineage.isTruncatedOwnWrite(local, byteArrayOf(9, 2, 3, 4), h(local), h(local)))
        assertTrue("la local no es la intentada", !SaveLineage.isTruncatedOwnWrite(local, local.copyOf(4), h(local), "otra"))
    }
}
