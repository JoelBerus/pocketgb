package com.joelbermudez.pocketgb.saves

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N7a · la tabla de linaje de N-README §3.4 completa, con E/S real (directorios temporales) y casos de latencia, copias
 * en conflicto del proveedor y reloj desfasado. Cargas sintéticas (`version`), nunca partidas reales.
 */
class SaveLineageTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val sizes = setOf(4)

    @Before fun setUp() { dir = File(tmp.newFolder(), "saves") }

    private fun store() = SaveStore(dir, java.util.UUID.randomUUID().toString().take(12))
    private fun read(d: ByteArray, date: Long?) = SaveMirror.Snapshot.Read(d, date)
    private fun h(d: ByteArray) = SaveStore(dir, "x").contentHash(d)

    private fun awaitIdle(target: SaveTarget?) {
        val latch = java.util.concurrent.CountDownLatch(1)
        target!!.whenMirrorIdle { latch.countDown() }
        assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
    }

    /** Juega [d] y deja que PocketGB lo escriba en el espejo: entra en el historial de escrituras propias. */
    private fun playAndMirror(s: SaveStore, mirror: FakeSaveMirror, vararg d: ByteArray) {
        val target = SaveTarget(s, mirror)
        for (x in d) {
            target.persistLocal(x)
            awaitIdle(target)
        }
    }

    // MARK: tabla pura

    @Test fun pureTableRows() {
        val a = "a"; val b = "b"; val c = "c"
        assertEquals(SaveLineage.Mirror.SAME, SaveLineage.classifyMirror(a, a, listOf(a), setOf(a)))
        assertEquals(SaveLineage.Mirror.OWN_OLDER, SaveLineage.classifyMirror(b, a, listOf(b, a), setOf(b)))
        assertEquals(SaveLineage.Mirror.EXTERNAL_CHANGE, SaveLineage.classifyMirror(a, c, listOf(a), setOf(a)))
        assertEquals(SaveLineage.Mirror.DIVERGENCE, SaveLineage.classifyMirror(b, c, listOf(a), setOf(a)))
        assertEquals(SaveLineage.Mirror.NO_HISTORY, SaveLineage.classifyMirror(a, c, emptyList(), emptySet()))
        assertEquals(SaveLineage.Mirror.MIRROR_MISSING, SaveLineage.classifyMirror(a, null, listOf(a), setOf(a)))
    }

    // MARK: tabla con E/S

    @Test fun mirrorEqualToLocalDoesNothing() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        val writes = m.writes.size
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(1), o.data)
        assertNull(o.warning)
        assertTrue(s.backups().isEmpty())
        assertEquals(writes, m.writes.size)
    }

    @Test fun ownOlderWriteLosesAndIsRewritten() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2)) // la escritura del espejo de la 2 no llegó
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(2), o.data)
        assertTrue(o.target!!.mirrorPending)
        assertEquals("ND20 (c): el espejo viejo se aparta", 1, s.setAside().size)
    }

    @Test fun externalChangeIsInstalledWithBackupAndWarning() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        m.snapshotValue = read(version(5), 1L) // el otro equipo escribió; la local no cambió
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(5), o.data)
        assertArrayEquals(version(5), s.load())
        assertEquals(SaveLoadWarning.ExternalChange(), o.warning)
        assertArrayEquals("la anterior queda en backup", version(1), s.backupFile(1).readBytes())
        assertArrayEquals("…y apartada fuera de la rotación", version(1), File(s.backupsDirectory, s.setAside().single().name).readBytes())
        assertEquals("pasa a ser la base del linaje", h(version(5)), s.lineageBase())
    }

    private fun tree(): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }

    @Test fun divergenceAsksWithoutWritingAnythingUntilChosen() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2))
        m.snapshotValue = read(version(6), Long.MAX_VALUE)
        val before = tree()
        val writes = m.writes.size
        try {
            SaveOpening.prepare(s, m, m.snapshot(), sizes, recordConflict = { _, _ -> fail("nada antes de elegir"); null })
            fail("debía preguntar")
        } catch (_: SaveOpening.Refusal.Divergence) {
        }
        assertEquals(before, tree())
        assertEquals(writes, m.writes.size)
    }

    @Test fun divergenceKeepLocalKeepsTheOtherAsConflictMomentBackupAndSetAside() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2))
        m.snapshotValue = read(version(6), Long.MAX_VALUE)
        var conflict: ByteArray? = null
        var isLocal: Boolean? = null
        val o = SaveOpening.prepare(
            s, m, m.snapshot(), sizes, recordConflict = { d, l -> conflict = d; isLocal = l; "id" },
            divergence = SaveOpening.DivergenceChoice.KEEP_LOCAL,
        )
        assertArrayEquals(version(2), o.data)
        assertEquals(SaveLoadWarning.Divergence("id"), o.warning)
        assertArrayEquals(version(6), conflict)
        assertEquals(false, isLocal)
        assertArrayEquals(version(6), s.backupFile(1).readBytes())
        assertEquals(1, s.setAside().size)
        assertTrue(o.target!!.mirrorPending)
    }

    @Test fun divergenceUseMirrorInstallsItAndKeepsTheLocalWithItsState() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2))
        m.snapshotValue = read(version(6), 1L)
        var isLocal: Boolean? = null
        val o = SaveOpening.prepare(
            s, m, m.snapshot(), sizes, recordConflict = { d, l -> assertArrayEquals(version(2), d); isLocal = l; "id" },
            divergence = SaveOpening.DivergenceChoice.USE_MIRROR,
        )
        assertArrayEquals(version(6), o.data)
        assertArrayEquals(version(6), s.load())
        assertEquals("el momento lleva el estado de la local (ND20 k)", true, isLocal)
        assertArrayEquals(version(2), s.backupFile(1).readBytes())
        assertArrayEquals(version(2), File(s.backupsDirectory, s.setAside().single().name).readBytes())
        assertEquals(h(version(6)), s.lineageBase())
    }

    @Test fun divergenceWithoutMomentsStillKeepsTheOther() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2))
        m.snapshotValue = read(version(6), 1L)
        val o = SaveOpening.prepare(
            s, m, m.snapshot(), sizes, recordConflict = { _, _ -> throw java.io.IOException("lleno") },
            divergence = SaveOpening.DivergenceChoice.KEEP_LOCAL,
        )
        assertEquals(SaveLoadWarning.Divergence(null), o.warning)
        assertArrayEquals(version(6), s.backupFile(1).readBytes())
    }

    /** H1 / ND20 (b): iPhone → Android (cambio externo) → abrir y cerrar sin jugar → el iPhone vuelve a escribir: avance, no divergencia. */
    @Test fun receivedSaveCountsAsUnchangedSoTheNextExternalChangeIsNotADivergence() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        m.snapshotValue = read(version(5), 1L) // el iPhone escribe
        assertEquals(SaveLoadWarning.ExternalChange(), SaveOpening.prepare(s, m, m.snapshot(), sizes).warning)
        // Abrir y cerrar sin jugar: nada cambia.
        val same = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertNull(same.warning)
        m.snapshotValue = read(version(7), 2L) // el iPhone vuelve a jugar
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes) // sin elección: no puede ser divergencia
        assertArrayEquals(version(7), o.data)
        assertEquals(SaveLoadWarning.ExternalChange(), o.warning)
        assertTrue("el espejo no se reescribe con la vieja", m.writes.none { it.contentEquals(version(5)) })
    }

    @Test fun ownOlderMirrorIsSetAsideAndWarnedWithoutBlocking() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        s.save(version(2))
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes, mirrorMode = SaveOpening.MirrorMode.ReadOnly)
        assertArrayEquals(version(2), o.data)
        assertEquals("H12: el de solo lectura no se pierde", SaveLoadWarning.MirrorOlderSetAside(readOnly = true), o.warning)
        assertArrayEquals(version(1), File(s.backupsDirectory, s.setAside().single().name).readBytes())
    }

    /** H4 / ND20 (m): un duplicado del mismo ROM con su propio `.sav` MÁS VIEJO, en otra ubicación, no hereda el linaje. */
    @Test fun aMirrorAtAnotherLocationUsesTheN1DateRule() {
        val s = store()
        val a = FakeSaveMirror(location = "carpetaA/juego.sav", dateOnWrite = 5_000L)
        playAndMirror(s, a, version(1))
        assertEquals("carpetaA/juego.sav", s.mirrorLocation())
        assertTrue(s.saveFile.setLastModified(10_000L))
        val b = FakeSaveMirror(read(version(9), 2_000L), location = "copias/juego.sav")
        val o = SaveOpening.prepare(s, b, b.snapshot(), sizes)
        assertArrayEquals("el más viejo no gana", version(1), o.data)
        assertArrayEquals(version(9), File(s.backupsDirectory, s.setAside().single().name).readBytes())
    }

    @Test fun firstTimeWithoutHistoryUsesTheDateRuleWithBackup() {
        val s = store()
        s.save(version(1))
        assertTrue(s.saveFile.setLastModified(1_000L))
        val m = FakeSaveMirror(read(version(2), 2_000L))
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(2), o.data)
        assertEquals(SaveLoadWarning.LocalSetAside, o.warning)
        assertEquals(h(version(2)), s.lineageBase())
    }

    @Test fun deletedMirrorIsRecreatedFromLocal() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        m.snapshotValue = SaveMirror.Snapshot.Absent
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(1), o.data)
        assertTrue(o.target!!.mirrorPending)
        o.target!!.retryMirrorIfNeeded(version(1))
        awaitIdle(o.target)
        assertArrayEquals(version(1), m.writes.last())
    }

    // MARK: reloj desfasado

    @Test fun skewedClockCannotMakeAnOwnOldWriteWin() {
        val s = store(); val m = FakeSaveMirror(dateOnWrite = 10L)
        playAndMirror(s, m, version(1), version(2))
        assertTrue(s.saveFile.setLastModified(1_000L))
        m.snapshotValue = read(version(1), 99_999_999_999L) // la nube «del futuro» reaplica la versión vieja
        assertArrayEquals(version(2), SaveOpening.prepare(s, m, m.snapshot(), sizes).data)
    }

    @Test fun skewedClockCannotHideAnExternalChange() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        assertTrue(s.saveFile.setLastModified(99_999_999_000L))
        m.snapshotValue = read(version(7), 1_000L) // el otro equipo tiene el reloj atrasado
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(7), o.data)
        assertEquals(SaveLoadWarning.ExternalChange(), o.warning)
    }

    // MARK: latencia (paquete que anuncia un sha que aún no llegó)

    @Test fun incomingTable() {
        val known = setOf("k1", "k2")
        assertEquals(SaveLineage.Incoming.INSTALL, SaveLineage.classifyIncoming(null, "n", null, known))
        assertEquals(SaveLineage.Incoming.ALREADY_CURRENT, SaveLineage.classifyIncoming("n", "N", null, known))
        assertEquals(SaveLineage.Incoming.ADVANCE, SaveLineage.classifyIncoming("l", "n", "L", known))
        assertEquals(SaveLineage.Incoming.STALE, SaveLineage.classifyIncoming("l", "k1", "k2", known))
        assertEquals(SaveLineage.Incoming.DIVERGENCE, SaveLineage.classifyIncoming("l", "n", "k1", known))
        assertEquals("sin base no se prueba el avance", SaveLineage.Incoming.DIVERGENCE, SaveLineage.classifyIncoming("l", "n", null, known))
    }

    @Test fun aPackageWhoseBaseHasNotArrivedYetIsNeverInstalledSilently() {
        // El iPhone avanzó desde «s2», que este teléfono aún no recibió (la nube va con retraso): la local es «s1».
        assertEquals(
            SaveLineage.Incoming.DIVERGENCE,
            SaveLineage.classifyIncoming(h(version(1)), h(version(3)), h(version(2)), setOf(h(version(1)))),
        )
    }

    @Test fun aLateMirrorAfterAnImportIsRecognisedAndNeverUndoesIt() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, version(1))
        // Llega un paquete con la 4 (avance desde la 1): se instala y se anota como recibida.
        s.save(version(4)); s.recordReceived(version(4))
        // El espejo aún tiene la 1 (nuestra): gana la local.
        assertArrayEquals(version(4), SaveOpening.prepare(s, m, m.snapshot(), sizes).data)
        // Y si la nube por fin trae la 4 del otro equipo: es la misma, nada.
        m.snapshotValue = read(version(4), 5L)
        val o = SaveOpening.prepare(s, m, m.snapshot(), sizes)
        assertArrayEquals(version(4), o.data)
        assertNull(o.warning)
    }

    // MARK: copias en conflicto del proveedor

    @Test fun providerConflictCopiesAreRecognised() {
        val yes = listOf(
            "Pokemon Rojo 2.sav", "Pokemon Rojo (1).sav", "pokemon rojo (12).SAV",
            "Pokemon Rojo.sync-conflict-20261008-101010-ABCDEFG.sav",
            "Pokemon Rojo (conflicted copy 2026-10-08).sav", "Pokemon Rojo (Joel's conflicted copy 2026-10-08).sav",
        )
        val no = listOf("Pokemon Rojo.sav", "Pokemon Rojo 2.gb", "Pokemon Rojo Azul.sav", "Pokemon 2.sav", "Pokemon Rojo2.sav")
        for (n in yes) assertTrue(n, SaveLineage.isProviderConflictCopy("Pokemon Rojo", n))
        for (n in no) assertFalse(n, SaveLineage.isProviderConflictCopy("Pokemon Rojo", n))
    }

    @Test fun providerConflictsAreListedAndNeverTouchTheSave() {
        val s = store()
        s.save(version(1))
        s.recordProviderConflicts(listOf(SaveStore.ProviderConflict("X 2.sav", 5L), SaveStore.ProviderConflict("X (1).sav", null)))
        assertEquals(listOf("X 2.sav", "X (1).sav"), s.providerConflicts().map { it.name })
        assertEquals(listOf("X 2.sav", "X (1).sav"), SavesBrowser(dir).list().single().providerConflicts.map { it.name })
        assertArrayEquals(version(1), s.load())
        s.recordProviderConflicts(emptyList())
        assertTrue(s.providerConflicts().isEmpty())
    }

    @Test fun historyIsBoundedAndSurvivesTheOldFormat() {
        val s = store(); val m = FakeSaveMirror()
        playAndMirror(s, m, *(1..12).map { version(it) }.toTypedArray())
        assertTrue(s.ownMirrorHashes().size <= 16)
        assertTrue(h(version(12)) in s.lastOwnMirrorHashes())
        s.mirrorHistoryFile.writeText("""{"successful":["abc"],"pending":[]}""")
        assertEquals(listOf("abc"), s.ownMirrorHashes())
        assertNull(s.lineageBase())
    }
}
