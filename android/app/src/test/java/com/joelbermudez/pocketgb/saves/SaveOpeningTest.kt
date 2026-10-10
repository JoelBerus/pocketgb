package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveOpeningTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val sizes = setOf(4)
    private val old = 1_000L
    private val new = 2_000L

    /** Cada test usa una huella propia: los canales de espejo son globales a la app. */
    private fun store(fp: String = java.util.UUID.randomUUID().toString().take(12), ops: SaveFileOps = PosixSaveFileOps) =
        SaveStore(dir, fp, ops)

    @Before fun setUp() { dir = File(tmp.newFolder(), "saves") }

    private fun read(d: ByteArray, date: Long?) = SaveMirror.Snapshot.Read(d, date)
    private fun dirTree(): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
    private fun awaitIdle(target: SaveTarget?) {
        val latch = java.util.concurrent.CountDownLatch(1)
        target!!.whenMirrorIdle { latch.countDown() }
        assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
    }

    // MARK: no descargado / no disponible

    @Test fun unavailableMirrorWithoutLocalRefusesToOpen() {
        val s = store()
        val mirror = FakeSaveMirror(SaveMirror.Snapshot.Unavailable)
        try {
            SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
            fail("debía negarse: se empezaría de cero y se pisaría la del espejo")
        } catch (e: SaveOpening.Refusal) {
            assertEquals(SaveOpening.Refusal.MirrorNotDownloaded, e)
        }
        assertFalse(s.saveFile.exists())
        assertTrue(mirror.writes.isEmpty())
    }

    @Test fun unavailableMirrorWithLocalUsesLocalAndNeverWritesTheMirror() {
        val s = store()
        s.save(version(1))
        val mirror = FakeSaveMirror(SaveMirror.Snapshot.Unavailable)
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertArrayEquals(version(1), outcome.data)
        assertEquals(SaveLoadWarning.MirrorUnavailable, outcome.warning)
        outcome.target!!.persistLocal(version(3))
        awaitIdle(outcome.target)
        assertTrue("nunca se escribe un espejo dudoso", mirror.writes.isEmpty())
        assertArrayEquals(version(3), s.load())
    }

    // MARK: ausente / tamaños

    @Test fun nothingAnywhereStartsFreshWithAMirrorTarget() {
        val s = store()
        val mirror = FakeSaveMirror()
        val outcome = SaveOpening.prepare(s, mirror, SaveMirror.Snapshot.Absent, sizes)
        assertNull(outcome.data)
        assertNull(outcome.warning)
        assertNotNull(outcome.target)
        assertFalse(outcome.target!!.mirrorPending)
        outcome.target!!.persistLocal(version(7))
        awaitIdle(outcome.target)
        assertArrayEquals(version(7), mirror.writes.single())
    }

    @Test fun localOnlyMarksTheMirrorPendingAndRetryWritesIt() {
        val s = store()
        s.save(version(1))
        val mirror = FakeSaveMirror()
        val outcome = SaveOpening.prepare(s, mirror, SaveMirror.Snapshot.Absent, sizes)
        assertArrayEquals(version(1), outcome.data)
        assertTrue(outcome.target!!.mirrorPending)
        outcome.target!!.retryMirrorIfNeeded(version(1))
        awaitIdle(outcome.target)
        assertArrayEquals(version(1), mirror.writes.single())
        assertTrue(waitUntil { !outcome.target!!.mirrorPending })
    }

    @Test fun wrongSizeFilesAreKeptByteForByte() {
        // Espejo con tamaño incorrecto y sin local: la sesión no guarda y el espejo queda intacto.
        val only = SaveOpening.prepare(store(), FakeSaveMirror().also { it.snapshotValue = read(bytes(9, 9, 9), new) },
            read(bytes(9, 9, 9), new), sizes)
        assertNull(only.data)
        assertNull(only.target)
        assertEquals(SaveLoadWarning.MirrorWrongSizeOnly, only.warning)

        // Local con tamaño incorrecto y espejo válido: la local se aparta intacta, con nombre único.
        val s2 = store("c3")
        dir.mkdirs()
        s2.saveFile.writeBytes(bytes(7, 7, 7))
        val good = FakeSaveMirror(read(bytes(4, 4, 4, 4), old))
        val outcome = SaveOpening.prepare(s2, good, good.snapshot(), sizes)
        assertArrayEquals(bytes(4, 4, 4, 4), outcome.data)
        assertEquals(SaveLoadWarning.LocalQuarantined, outcome.warning)
        assertArrayEquals(bytes(4, 4, 4, 4), s2.load())
        val quarantined = File(dir, "backups").names().filter { it.startsWith("c3.wrong-size-") }
        assertEquals(1, quarantined.size)
        assertArrayEquals(bytes(7, 7, 7), File(dir, "backups/${quarantined[0]}").readBytes())
    }

    @Test fun wrongSizeLocalAloneIsKeptAndTheSessionDoesNotSave() {
        val s = store("d5")
        dir.mkdirs()
        s.saveFile.writeBytes(bytes(1, 2, 3))
        val outcome = SaveOpening.prepare(s, null, SaveMirror.Snapshot.Absent, sizes)
        assertNull(outcome.data)
        assertNull(outcome.target)
        assertEquals(SaveLoadWarning.LocalWrongSize, outcome.warning)
        assertArrayEquals(bytes(1, 2, 3), s.saveFile.readBytes())
    }

    @Test fun wrongSizeMirrorWithValidLocalIsIgnoredAndNeverWritten() {
        val s = store()
        s.save(version(1))
        val mirror = FakeSaveMirror(read(bytes(9), new))
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertEquals(SaveLoadWarning.MirrorIgnored, outcome.warning)
        outcome.target!!.persistLocal(version(2))
        awaitIdle(outcome.target)
        assertTrue(mirror.writes.isEmpty())
        assertArrayEquals(bytes(9), (mirror.snapshot() as SaveMirror.Snapshot.Read).data)
    }

    // MARK: orden de efectos

    @Test fun quarantineIsDoneBeforeInstallingTheMirrorCopy() {
        val rec = FaultInjectingFileOps() // solo registra
        val s = store("e6", rec)
        dir.mkdirs()
        s.saveFile.writeBytes(bytes(7, 7, 7))
        val mirror = FakeSaveMirror(read(version(4), old))
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        val quarantine = rec.log.indexOfFirst { it.startsWith("copySynced:e6.wrong-size-") }
        val install = rec.log.indexOf("atomicReplace:e6.sav.tmp->e6.sav")
        assertTrue("sin cuarentena ni instalación: ${rec.log}", quarantine >= 0 && install >= 0)
        assertTrue("la cuarentena va antes de instalar", quarantine < install)
    }

    @Test fun addBackupFailureStopsBeforeAnythingElseAndKeepsLocal() {
        val s0 = store("f7")
        s0.save(version(1)) // local más nueva que el espejo -> backupOther
        val probe = FaultInjectingFileOps(failAt = 1)
        val s = store("f7", probe)
        val mirror = FakeSaveMirror(read(version(2), old))
        try {
            SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
            fail("debía propagar el fallo")
        } catch (_: IOException) {
        }
        assertArrayEquals(version(1), s0.load())
    }

    // MARK: resolución con E/S real

    @Test fun losingMirrorGoesToBackupOnceOnly() {
        val s = store("d4")
        s.save(version(1))
        val mirror = FakeSaveMirror(read(version(2), old))
        repeat(3) { // tres aperturas con un espejo que no se puede actualizar
            val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
            assertArrayEquals(version(1), outcome.data)
        }
        assertArrayEquals(version(2), s.backupFile(1).readBytes())
        assertEquals(1, s.backups().size)
    }

    @Test fun staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens() {
        val s = store()
        val mirror = FakeSaveMirror()
        val blocked = BlockingWriter(mirror)
        val first = SaveTarget(s, mirror, mirrorWriter = blocked::write)
        val d1 = version(1)
        val d2 = version(2)
        first.persistLocal(d1)
        assertTrue(blocked.started.tryAcquireWithin())
        first.persistLocal(d2)
        blocked.unblock.release()
        assertTrue(blocked.finished.tryAcquireWithin())
        assertTrue(blocked.started.tryAcquireWithin()) // arranca la escritura de d2 y queda bloqueada
        assertArrayEquals(d1, (mirror.snapshot() as SaveMirror.Snapshot.Read).data)
        assertArrayEquals(d2, s.load())

        val reopened = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        // El espejo (d1) tiene fecha más nueva que la local (d2), pero es propio: gana d2.
        assertArrayEquals(d2, reopened.data)
        // Único backup: el d1 de la rotación normal al guardar d2; no se añade el espejo.
        assertEquals(1, s.backups().size)
        assertArrayEquals(d1, s.backupFile(1).readBytes())
        reopened.target!!.retryMirrorIfNeeded(d2)
        blocked.unblock.release()
        assertTrue(blocked.finished.tryAcquireWithin())
        awaitIdle(reopened.target)
        assertArrayEquals(d2, (mirror.snapshot() as SaveMirror.Snapshot.Read).data)
    }

    /**
     * N7a (§3.4, «espejo = una escritura nuestra anterior»): un contenido que PocketGB escribió antes, aunque vuelva con
     * fecha nueva (restaurado a mano, o una nube que reaplica una versión vieja), NO gana: gana la local y se reescribe el
     * espejo. Sustituye a la regla anterior por fecha (se respaldaba la local y ganaba el espejo).
     */
    @Test fun restoredHistoricalMirrorWithNewDateLosesToLocalAndIsRewritten() {
        val s = store()
        val mirror = FakeSaveMirror(dateOnWrite = 5_000_000L)
        val target = SaveTarget(s, mirror)
        val d1 = version(1)
        val d2 = version(2)
        for (d in listOf(d1, d2)) {
            target.persistLocal(d)
            awaitIdle(target)
        }
        mirror.snapshotValue = read(d1, 9_000_000L)
        assertTrue(s.saveFile.setLastModified(1_000_000L))

        val reopened = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertArrayEquals(d2, reopened.data)
        assertArrayEquals(d2, s.load())
        // ND20 (c), H2: la versión restaurada no se pierde: queda apartada (no rota) y se avisa sin bloquear.
        assertEquals(SaveLoadWarning.MirrorOlderSetAside(), reopened.warning)
        assertArrayEquals(d1, File(s.backupsDirectory, s.setAside().single().name).readBytes())
        assertTrue(reopened.target!!.mirrorPending)
    }

    /** N7a (§3.4, divergencia): la local cambió desde nuestra última escritura y el espejo trae otra desconocida. */
    @Test fun unknownMirrorWhenLocalAlsoChangedIsADivergenceAndNothingIsLost() {
        val s = store()
        val mirror = FakeSaveMirror()
        val own = version(1)
        val local = version(2)
        val external = version(3)
        val target = SaveTarget(s, mirror)
        target.persistLocal(own)
        awaitIdle(target)

        s.save(local)
        assertTrue(s.saveFile.setLastModified(old))
        mirror.snapshotValue = read(external, new)
        val conflicts = ArrayList<ByteArray>()
        // ND20 (a): sin elección se pregunta y no se escribe nada.
        val before = dirTree()
        try {
            SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes, recordConflict = { d, _ -> conflicts += d; "c1" })
            fail("debía preguntar")
        } catch (_: SaveOpening.Refusal.Divergence) {
        }
        assertEquals(before, dirTree())
        assertTrue(conflicts.isEmpty())
        val reopened = SaveOpening.prepare(
            s, mirror, mirror.snapshot(), sizes, recordConflict = { d, _ -> conflicts += d; "c1" },
            divergence = SaveOpening.DivergenceChoice.KEEP_LOCAL,
        )
        assertArrayEquals("sigue la local", local, reopened.data)
        assertArrayEquals(local, s.load())
        assertEquals(SaveLoadWarning.Divergence("c1"), reopened.warning)
        assertArrayEquals("la otra queda como momento «Conflicto»", external, conflicts.single())
        assertArrayEquals("…en backup", external, s.backupFile(1).readBytes())
        assertTrue("…y apartada", s.setAside().any { File(s.backupsDirectory, it.name).readBytes().contentEquals(external) })
    }

    @Test fun mirrorOnlyIsImportedAndMirrorKeptByTheInstall() {
        val s = store()
        val mirror = FakeSaveMirror(read(version(5), old))
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertArrayEquals(version(5), outcome.data)
        assertArrayEquals(version(5), s.load())
        assertNull(outcome.warning)
        assertFalse(outcome.target!!.mirrorPending)
    }

    // MARK: modos de espejo (J7, colisión de nombres)

    @Test fun readOnlyMirrorImportsButIsNeverWritten() {
        val s = store()
        val mirror = FakeSaveMirror(read(version(5), old))
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes, mirrorMode = SaveOpening.MirrorMode.ReadOnly)
        assertArrayEquals(version(5), outcome.data)
        assertArrayEquals(version(5), s.load()) // importada a la local
        assertEquals(SaveLoadWarning.MirrorReadOnly, outcome.warning)
        outcome.target!!.persistLocal(version(6))
        awaitIdle(outcome.target)
        assertTrue(mirror.writes.isEmpty())
        assertFalse(outcome.target!!.mirrorPending)
        assertArrayEquals(version(6), s.load())
    }

    @Test fun readOnlyMirrorWithNoSaveStillWarns() {
        val outcome = SaveOpening.prepare(store(), FakeSaveMirror(), SaveMirror.Snapshot.Absent, sizes,
            mirrorMode = SaveOpening.MirrorMode.ReadOnly)
        assertEquals(SaveLoadWarning.MirrorReadOnly, outcome.warning)
        assertNull(outcome.data)
    }

    @Test fun sharedMirrorIsDisabledEntirelyEvenIfItHasAFile() {
        val s = store()
        s.save(version(1))
        val mirror = FakeSaveMirror(read(version(9), new)) // el .sav de OTRO juego: ni se lee ni se escribe
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes, mirrorMode = SaveOpening.MirrorMode.Shared)
        assertArrayEquals(version(1), outcome.data)
        assertEquals(SaveLoadWarning.MirrorShared, outcome.warning)
        outcome.target!!.persistLocal(version(2))
        awaitIdle(outcome.target)
        assertTrue(mirror.writes.isEmpty())
        assertArrayEquals(version(2), s.load())
        assertEquals(0, s.backups().count { it.index > 1 })
    }

    @Test fun sharedMirrorDoesNotRefuseWhenUnavailableWithoutLocal() {
        val outcome = SaveOpening.prepare(store(), FakeSaveMirror(SaveMirror.Snapshot.Unavailable),
            SaveMirror.Snapshot.Unavailable, sizes, mirrorMode = SaveOpening.MirrorMode.Shared)
        assertEquals(SaveLoadWarning.MirrorShared, outcome.warning)
    }

    @Test fun noMirrorAtAllJustUsesLocal() {
        val s = store()
        s.save(version(1))
        val outcome = SaveOpening.prepare(s, null, SaveMirror.Snapshot.Absent, sizes)
        assertArrayEquals(version(1), outcome.data)
        assertNull(outcome.warning)
        assertFalse(outcome.target!!.mirrorPending)
        outcome.target!!.persistLocal(version(2))
        assertArrayEquals(version(2), s.load())
    }

    // MARK: N1 · perdedor frente a un espejo ajeno, apartado fuera de la rotación

    private fun setAsideFiles(s: SaveStore) = s.setAside().map { File(s.backupsDirectory, it.name) }

    /** Cinco guardados más: la rotación `.1`–`.5` ya no tiene nada de antes. */
    private fun fiveMoreSaves(s: SaveStore) = (10..14).forEach { s.save(version(it)) }

    @Test fun aDuplicateWithANewerMirrorSetsTheLocalAsideOutsideTheRotation() {
        // Dos copias del mismo juego (misma huella, una partida local): la copia B trae su propio `.sav`, más nuevo.
        val s = store("dup1")
        s.save(version(1)) // lo jugado con la copia A
        assertTrue(s.saveFile.setLastModified(old))
        val copyB = FakeSaveMirror(read(version(2), new))
        val outcome = SaveOpening.prepare(s, copyB, copyB.snapshot(), sizes)
        assertArrayEquals("gana el más nuevo, como siempre", version(2), outcome.data)
        assertEquals("N1-H5: se avisa de que la local quedó apartada", SaveLoadWarning.LocalSetAside, outcome.warning)
        val aside = setAsideFiles(s).single()
        assertTrue(aside.name.matches(Regex("dup1\\.mirror-\\d+-[0-9a-f]{8}\\.sav")))
        assertArrayEquals(version(1), aside.readBytes())
        fiveMoreSaves(s)
        assertTrue("la rotación ya la perdió", s.backups().none { s.backupFile(it.index).readBytes().contentEquals(version(1)) })
        assertArrayEquals("la apartada sigue ahí", version(1), aside.readBytes())
    }

    @Test fun aForeignSaveWithTheSameNameIsSetAsideOutsideTheRotation() {
        // Un `.sav` de otro juego con el mismo nombre que el ROM (tamaño válido), más viejo que la local.
        val s = store("ajeno")
        s.save(version(1))
        val foreign = FakeSaveMirror(read(version(9), old))
        val outcome = SaveOpening.prepare(s, foreign, foreign.snapshot(), sizes)
        assertArrayEquals(version(1), outcome.data)
        assertNull("pierde el espejo: la partida no cambia, no hay aviso", outcome.warning)
        assertArrayEquals(version(9), setAsideFiles(s).single().readBytes())
        assertArrayEquals(version(9), s.backupFile(1).readBytes())
        fiveMoreSaves(s)
        assertArrayEquals("cinco guardados no lo borran", version(9), setAsideFiles(s).single().readBytes())
        // Y se puede restaurar (la actual pasa antes a `.1`).
        s.restoreSetAside(setAsideFiles(s).single().name, sizes)
        assertArrayEquals(version(9), s.load())
        assertArrayEquals(version(14), s.backupFile(1).readBytes())
        assertEquals("restaurar no la borra", 1, s.setAside().size)
    }

    @Test fun theSameLoserIsSetAsideOnceAndTwoLosersNeverOverwriteEachOther() {
        val s = store("dos")
        s.save(version(1))
        val mirror = FakeSaveMirror(read(version(7), old))
        repeat(3) { SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes) }
        assertEquals(1, s.setAside().size)
        mirror.snapshotValue = read(version(8), old)
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertEquals(setOf(version(7).toList(), version(8).toList()), setAsideFiles(s).map { it.readBytes().toList() }.toSet())
        // Mismo segundo y mismo sufijo: nunca se pisa (se elige otro nombre).
        val fixed = listOf("aaaaaaaa", "aaaaaaaa", "bbbbbbbb").iterator()
        s.setAsideMirrorLoser(version(5), unixSeconds = 42, rand8 = { fixed.next() })
        s.setAsideMirrorLoser(version(6), unixSeconds = 42, rand8 = { fixed.next() })
        assertArrayEquals(version(5), File(s.backupsDirectory, "dos.mirror-42-aaaaaaaa.sav").readBytes())
        assertArrayEquals(version(6), File(s.backupsDirectory, "dos.mirror-42-bbbbbbbb.sav").readBytes())
    }

    /** ND20 (c): una escritura nuestra anterior SÍ se aparta (una vez); un espejo igual a la local nunca. */
    @Test fun anOwnedOlderMirrorIsSetAsideOnceAndAnEqualOneNever() {
        val s = store()
        val mirror = FakeSaveMirror()
        val target = SaveTarget(s, mirror)
        target.persistLocal(version(1))
        awaitIdle(target)
        s.save(version(2)) // la local avanza; el espejo es nuestra escritura anterior
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertArrayEquals(version(1), File(s.backupsDirectory, s.setAside().single().name).readBytes())
        mirror.snapshotValue = read(version(2), new)
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        assertEquals(1, s.setAside().size)
    }

    @Test fun aMirrorOnlyOrAWrongSizedLocalIsNotSetAsideAsAMirrorLoser() {
        val s = store()
        val mirror = FakeSaveMirror(read(version(5), old))
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes) // sin local: nada pierde
        assertTrue(s.setAside().isEmpty())
        val t = store("ws")
        dir.mkdirs()
        t.saveFile.writeBytes(bytes(7, 7, 7)) // local de tamaño incorrecto: ya va a su cuarentena wrong-size
        SaveOpening.prepare(t, mirror, mirror.snapshot(), sizes)
        assertTrue(t.setAside().isEmpty())
    }

    @Test fun theSetAsideCopyIsWrittenBeforeAnythingIsReplaced() {
        val rec = FaultInjectingFileOps()
        val s = store("orden", rec)
        s.save(version(1))
        assertTrue(s.saveFile.setLastModified(old))
        val mirror = FakeSaveMirror(read(version(2), new))
        SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes)
        val aside = rec.log.indexOfFirst { it.startsWith("atomicReplace:orden.mirror-") }
        val install = rec.log.lastIndexOf("atomicReplace:orden.sav.tmp->orden.sav")
        assertTrue("${rec.log}", aside in 0 until install)
    }

    @Test fun aSetAsideTemporaryLeftByAKillIsCleanedAndARealOneIsKept() {
        val s = store("huerf")
        s.setAsideMirrorLoser(version(3))
        File(s.backupsDirectory, "huerf.mirror-1-deadbeef.sav.tmp").writeBytes(version(4).copyOf(2))
        s.recoverOrphans(sizes)
        assertFalse(File(s.backupsDirectory, "huerf.mirror-1-deadbeef.sav.tmp").exists())
        assertArrayEquals(version(3), setAsideFiles(s).single().readBytes())
    }

    @Test fun restoringSomethingThatIsNotASetAsideOfThisGameIsRefused() {
        val s = store("propia")
        s.save(version(1))
        try {
            s.restoreSetAside("otra.mirror-1-aaaaaaaa.sav")
            fail("debía rechazarse")
        } catch (_: IllegalArgumentException) {
        }
        try {
            s.restoreSetAside("propia.1.sav")
            fail("debía rechazarse")
        } catch (_: IllegalArgumentException) {
        }
        assertArrayEquals(version(1), s.load())
    }

    @Test fun aReadOnlyFolderWhoseNewerSaveReplacesTheLocalStillWarnsAboutTheSetAside() {
        val s = store("solo-lectura")
        s.save(version(1))
        assertTrue(s.saveFile.setLastModified(old))
        val mirror = FakeSaveMirror(read(version(2), new))
        val outcome = SaveOpening.prepare(s, mirror, mirror.snapshot(), sizes, mirrorMode = SaveOpening.MirrorMode.ReadOnly)
        assertArrayEquals(version(2), outcome.data)
        assertEquals(SaveLoadWarning.LocalSetAside, outcome.warning)
    }
}
