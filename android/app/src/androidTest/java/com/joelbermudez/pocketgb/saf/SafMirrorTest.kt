package com.joelbermudez.pocketgb.saf

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SaveTarget
import com.joelbermudez.pocketgb.saves.saf.MirrorDisabledReason
import com.joelbermudez.pocketgb.saves.saf.MirrorExternalChangeException
import com.joelbermudez.pocketgb.saves.saf.MirrorNameAlteredException
import com.joelbermudez.pocketgb.saves.saf.MirrorWriteException
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import com.joelbermudez.pocketgb.testing.SyntheticRom
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Espejo SAF in situ (J1) contra [TestDocumentsProvider], con sus modos de fallo. Solo datos sintéticos. */
@RunWith(AndroidJUnit4::class)
class SafMirrorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val resolver = targetContext.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )
    private val fixtures = TestFixtures(resolver)
    private val fingerprint = "00112233445566778899aabbccddeeff"
    private val validSizes = setOf(8192)
    private val rom = "Juego.gb"
    private val sav = "Juego.sav"
    private lateinit var localDir: File
    private lateinit var store: SaveStore
    private lateinit var registry: MirrorChannelRegistry

    private fun save(value: Int, size: Int = 8192) = ByteArray(size) { value.toByte() }

    @Before
    fun setUp() {
        fixtures.reset()
        fixtures.put(rom, SyntheticRom.sramCounter())
        localDir = File(targetContext.cacheDir, "saf-mirror-${UUID.randomUUID()}").apply { mkdirs() }
        store = SaveStore(localDir, fingerprint)
        registry = MirrorChannelRegistry()
    }

    @After
    fun tearDown() {
        fixtures.reset()
        localDir.deleteRecursively()
    }

    private fun mirror(
        romName: String = rom,
        writeGranted: Boolean = true,
        onDisabled: (MirrorDisabledReason) -> Unit = {},
    ) = SafSaveMirror(resolver, treeUri, TestDocumentsProvider.ROOT_ID, romName, store, validSizes, writeGranted, onDisabled)

    private fun awaitIdle(target: SaveTarget) {
        val latch = CountDownLatch(1)
        target.whenMirrorIdle { latch.countDown() }
        assertTrue("el espejo no quedó en reposo", latch.await(20, TimeUnit.SECONDS))
    }

    private fun backupContents(): List<ByteArray> = store.backups().map { store.backupFile(it.index).readBytes() }

    // ---------------------------------------------------------------- lectura

    @Test
    fun noSavNextToTheRomIsAbsent() {
        assertEquals(SaveMirror.Snapshot.Absent, mirror().snapshot())
    }

    @Test
    fun readsTheSavIgnoringCaseWithItsModificationDate() {
        fixtures.put("JUEGO.Sav", save(7), mtimeMs = 1_700_000_000_000)
        val snapshot = mirror().snapshot() as SaveMirror.Snapshot.Read
        assertArrayEquals(save(7), snapshot.data)
        assertEquals(1_700_000_000_000, snapshot.dateMs)
    }

    @Test
    fun theBaseIsTheFileNameWithoutItsLastExtension() {
        fixtures.put("Super Juego v1.2.gbc", SyntheticRom.sramCounter())
        fixtures.put("Super Juego v1.2.sav", save(3))
        fixtures.put("Super Juego v1.sav", save(9))
        val snapshot = mirror("Super Juego v1.2.gbc").snapshot() as SaveMirror.Snapshot.Read
        assertArrayEquals(save(3), snapshot.data)
    }

    @Test
    fun twoDocumentsWithTheSameNameMakeTheMirrorUnavailable() {
        fixtures.put("Juego.sav", save(1))
        fixtures.put("JUEGO.SAV", save(2))
        assertEquals(SaveMirror.Snapshot.Unavailable, mirror().snapshot())
    }

    @Test
    fun virtualDocumentOrFailingProviderIsUnavailableAndNeverWritten() {
        // Documento virtual: no tiene bytes locales.
        fixtures.put("virtual-Remoto.gb", SyntheticRom.sramCounter())
        fixtures.put("virtual-Remoto.sav", save(1))
        val virtual = mirror("virtual-Remoto.gb")
        assertEquals(SaveMirror.Snapshot.Unavailable, virtual.snapshot())
        assertThrows(MirrorWriteException::class.java) { virtual.write(save(5)) }
        assertArrayEquals(save(1), fixtures.read("virtual-Remoto.sav"))

        // Proveedor que falla al leer (permiso revocado): tampoco se interpreta ni se escribe.
        fixtures.put(sav, save(2))
        val failing = mirror()
        fixtures.deny(true)
        assertEquals(SaveMirror.Snapshot.Unavailable, failing.snapshot())
        fixtures.deny(false)
        // Sin local no se puede abrir; con local se juega sin tocar el espejo.
        val outcomeRefusal = assertThrows(SaveOpening.Refusal::class.java) {
            fixtures.deny(true)
            try {
                SaveOpening.prepare(store, failing, failing.snapshot(), validSizes, registry = registry)
            } finally {
                fixtures.deny(false)
            }
        }
        assertEquals(SaveOpening.Refusal.MirrorNotDownloaded, outcomeRefusal)

        store.save(save(8))
        fixtures.deny(true)
        val outcome = try {
            SaveOpening.prepare(store, failing, failing.snapshot(), validSizes, registry = registry)
        } finally {
            fixtures.deny(false)
        }
        assertArrayEquals(save(8), outcome.data)
        assertEquals(SaveLoadWarning.MirrorUnavailable, outcome.warning)
        assertNull("sin lectura fiable el destino no incluye el espejo", outcome.target!!.mirror)
        assertArrayEquals(save(2), fixtures.read(sav))
    }

    @Test
    fun unknownModificationDateIsNullAndTheLocalWins() {
        fixtures.put(sav, save(4))
        fixtures.omitMtime(true)
        val snapshot = mirror().snapshot() as SaveMirror.Snapshot.Read
        assertNull(snapshot.dateMs)
        store.save(save(5))
        val outcome = SaveOpening.prepare(store, mirror(), snapshot, validSizes, registry = registry)
        assertArrayEquals("sin fecha gana la local", save(5), outcome.data)
        assertTrue("el espejo perdedor queda respaldado", backupContents().any { it.contentEquals(save(4)) })
    }

    @Test
    fun zeroModificationDateIsUnknown() {
        fixtures.put(sav, save(4), mtimeMs = 0)
        assertNull((mirror().snapshot() as SaveMirror.Snapshot.Read).dateMs)
    }

    @Test
    fun wrongSizeMirrorIsReadWholeAndKeptByteForByte() {
        val odd = ByteArray(5000) { (it * 31).toByte() }
        fixtures.put(sav, odd)
        val snapshot = mirror().snapshot() as SaveMirror.Snapshot.Read
        assertArrayEquals(odd, snapshot.data)

        // Sin local: se abre sin partida, no se guarda nada y el espejo no se toca.
        val outcome = SaveOpening.prepare(store, mirror(), snapshot, validSizes, registry = registry)
        assertNull(outcome.data)
        assertNull(outcome.target)
        assertEquals(SaveLoadWarning.MirrorWrongSizeOnly, outcome.warning)
        assertArrayEquals(odd, fixtures.read(sav))

        // Con local válida: se usa la local y el espejo (ilegible para el núcleo) no se toca jamás.
        store.save(save(6))
        val withLocal = SaveOpening.prepare(store, mirror(), snapshot, validSizes, registry = registry)
        assertArrayEquals(save(6), withLocal.data)
        assertNull(withLocal.target!!.mirror)
        withLocal.target!!.persistLocal(save(7))
        assertArrayEquals(odd, fixtures.read(sav))
    }

    @Test
    fun hugeMirrorIsReadOnlyUpToTheCap() {
        fixtures.putSparse(sav, 4L * 1024 * 1024)
        val snapshot = mirror().snapshot() as SaveMirror.Snapshot.Read
        assertEquals(8192 + 1, snapshot.data.size)
    }

    // ---------------------------------------------------------------- carpeta y colisiones

    @Test
    fun scannerGivesEachRomItsFolderDocumentId() {
        fixtures.mkdir("Sub")
        fixtures.put("Sub/Otro.gb", SyntheticRom.sramCounter())
        val entries = LibraryScanner.scan(SafDocumentTree(resolver, treeUri)).associateBy { it.id }
        assertEquals("root", entries.getValue(rom).folderDocumentId)
        assertEquals("root/Sub", entries.getValue("Sub/Otro.gb").folderDocumentId)
        assertEquals(entries.size, entries.values.map { it.id }.toSet().size)
    }

    @Test
    fun siblingRomsSharingTheBaseDisableTheMirrorIgnoringCase() {
        assertEquals(SaveOpening.MirrorMode.ReadWrite, mirror().mirrorMode())
        fixtures.put("JUEGO.GBC", SyntheticRom.sramCounter())
        assertEquals(SaveOpening.MirrorMode.Shared, mirror().mirrorMode())
        assertEquals(SaveOpening.MirrorMode.Shared, mirror("JUEGO.GBC").mirrorMode())

        fixtures.put(sav, save(1))
        val shared = mirror()
        val outcome = SaveOpening.prepare(
            store, shared, shared.snapshot(), validSizes, SaveOpening.MirrorMode.Shared, registry = registry,
        )
        assertNull("con el espejo desactivado ni siquiera se lee", outcome.data)
        assertNull(outcome.target!!.mirror)
        assertEquals(SaveLoadWarning.MirrorShared, outcome.warning)
        assertArrayEquals(save(1), fixtures.read(sav))
    }

    @Test
    fun aCollisionThatAppearsAfterOpeningStopsTheWrite() {
        val mirror = mirror()
        assertEquals(SaveMirror.Snapshot.Absent, mirror.snapshot())
        fixtures.put("juego.GB", SyntheticRom.sramCounter())
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(1)) }
        assertNull(fixtures.read(sav))
    }

    @Test
    fun differentBasesDoNotCollide() {
        fixtures.put("Juego 2.gb", SyntheticRom.sramCounter())
        fixtures.put("Juego.sav.gb", SyntheticRom.sramCounter())
        assertEquals(SaveOpening.MirrorMode.ReadWrite, mirror().mirrorMode())
    }

    @Test
    fun readOnlyFolderOrMissingPermissionIsReadOnlyAndNeverWritten() {
        fixtures.put(sav, save(1))
        assertEquals(SaveOpening.MirrorMode.ReadOnly, mirror(writeGranted = false).mirrorMode())
        assertThrows(MirrorWriteException::class.java) { mirror(writeGranted = false).write(save(2)) }

        fixtures.readOnlyFlags(true)
        val readOnly = mirror()
        assertEquals(SaveOpening.MirrorMode.ReadOnly, readOnly.mirrorMode())
        assertThrows(MirrorWriteException::class.java) { readOnly.write(save(2)) }
        // Sin .sav previo, la carpeta tampoco deja crear.
        fixtures.reset()
        fixtures.put(rom, SyntheticRom.sramCounter())
        fixtures.readOnlyFlags(true)
        assertEquals(SaveOpening.MirrorMode.ReadOnly, mirror().mirrorMode())
        assertThrows(MirrorWriteException::class.java) { mirror().write(save(2)) }
        assertNull(fixtures.read(sav))
    }

    @Test
    fun readOnlyFolderImportsTheSavButNeverWritesIt() {
        fixtures.put(sav, save(9))
        fixtures.readOnlyFlags(true)
        val readOnly = mirror()
        val mode = readOnly.mirrorMode()
        assertEquals(SaveOpening.MirrorMode.ReadOnly, mode)
        val outcome = SaveOpening.prepare(store, readOnly, readOnly.snapshot(), validSizes, mode, registry = registry)
        assertArrayEquals(save(9), outcome.data)
        assertEquals(SaveLoadWarning.MirrorReadOnly, outcome.warning)
        assertNull(outcome.target!!.mirror)
        outcome.target!!.persistLocal(save(10))
        assertArrayEquals(save(10), store.load())
        assertArrayEquals(save(9), fixtures.read(sav))
    }

    // ---------------------------------------------------------------- escritura

    @Test
    fun createsTheSavWithTheExactNameAndWritesItWithWtOnly() {
        val date = mirror().write(save(1))
        assertArrayEquals(save(1), fixtures.read(sav))
        assertEquals(listOf("wt"), fixtures.writeModes())
        assertNotNull("el proveedor da fecha", date)
        // El historial reconoce la escritura como propia (contenido y fecha).
        assertTrue(store.recognizesOwnedMirror(save(1), date))
    }

    @Test
    fun rewritingIdenticalContentDoesNotTouchTheDocumentAgain() {
        val mirror = mirror()
        mirror.write(save(1))
        mirror.write(save(1))
        assertEquals(listOf("wt"), fixtures.writeModes())
    }

    @Test
    fun wtTruncatesEvenWhenWDoesNotAndTheShorterContentIsExact() {
        fixtures.noTruncateOnW(true)
        fixtures.put(sav, save(1, size = 8192))
        val mirror = mirror()
        mirror.snapshot()
        val shorter = save(2, size = 4096)
        mirror.write(shorter)
        assertArrayEquals("con 'w' habría quedado cola del contenido anterior", shorter, fixtures.read(sav))
        assertEquals(listOf("wt"), fixtures.writeModes())
    }

    @Test
    fun aProviderThatNeverTruncatesIsCaughtByTheVerification() {
        fixtures.noTruncateAtAll(true)
        fixtures.put(sav, save(1, size = 8192))
        val mirror = mirror()
        mirror.snapshot()
        val error = assertThrows(MirrorWriteException::class.java) { mirror.write(save(2, size = 4096)) }
        assertTrue(error.message!!.contains("Verificación"))
        // Lo que había se conservó antes de tocarlo.
        assertTrue(backupContents().any { it.contentEquals(save(1, size = 8192)) })
    }

    @Test
    fun anExistingForeignSavIsBackedUpOnceBeforeBeingOverwritten() {
        fixtures.put(sav, save(1))
        val mirror = mirror()
        mirror.snapshot()
        mirror.write(save(2))
        assertArrayEquals(save(2), fixtures.read(sav))
        assertEquals(1, backupContents().count { it.contentEquals(save(1)) })
        mirror.write(save(3))
        mirror.write(save(4))
        assertEquals("lo propio no se respalda otra vez", 1, backupContents().size)
        assertArrayEquals(save(4), fixtures.read(sav))
    }

    @Test
    fun anExternalChangeDuringTheSessionIsKeptAndTheMirrorIsDisabled() {
        fixtures.put(sav, save(1))
        var warned: MirrorDisabledReason? = null
        val mirror = mirror(onDisabled = { warned = it })
        mirror.snapshot()
        mirror.write(save(2))
        // Otra app reescribe el .sav mientras se juega.
        fixtures.put(sav, save(99))
        assertThrows(MirrorExternalChangeException::class.java) { mirror.write(save(3)) }
        assertArrayEquals("el cambio externo no se pisa", save(99), fixtures.read(sav))
        assertTrue("y queda respaldado", backupContents().any { it.contentEquals(save(99)) })
        assertEquals(MirrorDisabledReason.ExternalChange, mirror.disabledReason)
        assertEquals(MirrorDisabledReason.ExternalChange, warned)
        // Desactivado: no vuelve a escribir en toda la sesión.
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(4)) }
        assertArrayEquals(save(99), fixtures.read(sav))
    }

    @Test
    fun aSavCreatedExternallyAfterAnAbsentSnapshotCountsAsAnExternalChange() {
        val mirror = mirror()
        assertEquals(SaveMirror.Snapshot.Absent, mirror.snapshot())
        fixtures.put(sav, save(50))
        assertThrows(MirrorExternalChangeException::class.java) { mirror.write(save(1)) }
        assertArrayEquals(save(50), fixtures.read(sav))
    }

    @Test
    fun aNameAlteredByTheProviderDeletesWhatWasCreatedAndDisablesTheMirror() {
        fixtures.createRenames(true)
        var warned: MirrorDisabledReason? = null
        val mirror = mirror(onDisabled = { warned = it })
        val error = assertThrows(MirrorNameAlteredException::class.java) { mirror.write(save(1)) }
        assertTrue(error.message!!.contains("Juego.sav.bin"))
        assertNull(fixtures.read("Juego.sav.bin"))
        assertNull(fixtures.read(sav))
        assertEquals(MirrorDisabledReason.NameAltered, mirror.disabledReason)
        assertEquals(MirrorDisabledReason.NameAltered, warned)
        fixtures.createRenames(false)
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(1)) }
        assertNull(fixtures.read(sav))
    }

    @Test
    fun writesToAPipeThatCannotBeSyncedAreStillVerified() {
        fixtures.pipeFd(true)
        mirror().write(save(6))
        assertArrayEquals(save(6), fixtures.read(sav))
        assertEquals(listOf("wt"), fixtures.writeModes())
    }

    @Test
    fun aProviderThatDropsTheDataAndReportsAnErrorFailsTheWrite() {
        fixtures.failClose(true)
        val mirror = mirror()
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(1)) }
        fixtures.failClose(false)
        // El reintento escribe bien.
        mirror.write(save(1))
        assertArrayEquals(save(1), fixtures.read(sav))
    }

    @Test
    fun failureToOpenForWritingIsAMirrorFailureAndRetrySucceeds() {
        val mirror = mirror()
        mirror.write(save(1))
        fixtures.failWrite(true)
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(2)) }
        assertArrayEquals("no se tocó", save(1), fixtures.read(sav))
        fixtures.failWrite(false)
        mirror.write(save(2))
        assertArrayEquals(save(2), fixtures.read(sav))
    }

    @Test
    fun aPartialWriteOfOurOwnIsNotTakenForAnExternalChange() {
        val mirror = mirror()
        mirror.snapshot()
        mirror.write(save(1))
        // Simula un `wt` cortado: el documento quedó con un prefijo de lo que se intentaba escribir.
        fixtures.denyMidWrite(true)
        assertThrows(MirrorWriteException::class.java) { mirror.write(save(2)) }
        fixtures.denyMidWrite(false)
        fixtures.deny(false)
        fixtures.put(sav, save(2).copyOf(1000))
        mirror.write(save(2))
        assertArrayEquals(save(2), fixtures.read(sav))
        assertNull(mirror.disabledReason)
    }

    // ---------------------------------------------------------------- con el canal del espejo

    @Test
    fun permissionDeniedMidWriteKeepsTheLocalIntactAndTheRetryWrites() {
        val mirror = mirror()
        val target = SaveOpening.prepare(store, mirror, mirror.snapshot(), validSizes, registry = registry).target!!
        target.persistLocal(save(1))
        awaitIdle(target)
        assertArrayEquals(save(1), fixtures.read(sav))
        assertFalse(target.mirrorPending)

        fixtures.denyMidWrite(true) // el permiso desaparece justo tras entregar el descriptor
        target.persistLocal(save(2))
        awaitIdle(target)
        assertArrayEquals("la local está a salvo", save(2), store.load())
        assertTrue("el espejo queda pendiente", target.mirrorPending)

        fixtures.denyMidWrite(false)
        fixtures.deny(false)
        target.retryMirrorIfNeeded(save(2))
        awaitIdle(target)
        assertFalse(target.mirrorPending)
        assertArrayEquals(save(2), fixtures.read(sav))
    }

    @Test
    fun aFailingMirrorNeverAffectsTheLocalAndTheRetryCatchesUp() {
        val mirror = mirror()
        val target = SaveOpening.prepare(store, mirror, mirror.snapshot(), validSizes, registry = registry).target!!
        target.persistLocal(save(1))
        awaitIdle(target)

        fixtures.failWrite(true)
        target.persistLocal(save(2))
        target.persistLocal(save(3))
        awaitIdle(target)
        assertArrayEquals(save(3), store.load())
        assertArrayEquals("el espejo sigue en la versión anterior", save(1), fixtures.read(sav))
        assertTrue(target.mirrorPending)

        fixtures.failWrite(false)
        target.retryMirrorIfNeeded(save(3))
        awaitIdle(target)
        assertArrayEquals(save(3), fixtures.read(sav))
        assertFalse(target.mirrorPending)
    }

    @Test
    fun aBlockedProviderNeverBlocksTheLocalSaveAndWritesAreCoalesced() {
        val mirror = mirror()
        val target = SaveOpening.prepare(store, mirror, mirror.snapshot(), validSizes, registry = registry).target!!
        target.persistLocal(save(1))
        awaitIdle(target)

        fixtures.blockWrite(true)
        val begin = SystemClock.elapsedRealtime()
        target.persistLocal(save(2))
        waitUntil { fixtures.waitingWriters() == 1 }
        target.persistLocal(save(3))
        target.persistLocal(save(4))
        assertTrue("persistLocal no espera al proveedor", SystemClock.elapsedRealtime() - begin < 10_000)
        assertArrayEquals(save(4), store.load())
        assertTrue(target.mirrorPending)

        fixtures.releaseWrite()
        awaitIdle(target)
        assertArrayEquals("solo se conserva lo más reciente", save(4), fixtures.read(sav))
        assertTrue("2 escrituras como mucho tras la 1ª", fixtures.writeModes().size <= 3)
        assertFalse(target.mirrorPending)
    }

    @Test
    fun theLoserMirrorIsBackedUpOnlyOnceAcrossOpenings() {
        // Espejo antiguo (E, fecha 1000) frente a una local más nueva (L).
        fixtures.put(sav, save(1), mtimeMs = 1_000)
        store.save(save(2))
        val first = mirror()
        val outcome = SaveOpening.prepare(store, first, first.snapshot(), validSizes, registry = registry)
        assertArrayEquals(save(2), outcome.data)
        assertEquals(1, backupContents().count { it.contentEquals(save(1)) })

        // El espejo se actualiza con la local y el backup del perdedor no se duplica.
        outcome.target!!.retryMirrorIfNeeded(save(2))
        awaitIdle(outcome.target!!)
        assertArrayEquals(save(2), fixtures.read(sav))
        assertEquals("respaldo único", 1, backupContents().size)

        // Reabrir: el espejo es nuestro y ya coincide; nada nuevo en los backups.
        val second = mirror()
        val reopened = SaveOpening.prepare(store, second, second.snapshot(), validSizes, registry = registry)
        assertArrayEquals(save(2), reopened.data)
        assertEquals(1, backupContents().size)
    }

    @Test
    fun aNewerExternalMirrorWinsAndTheLocalGoesToBackup() {
        store.save(save(2))
        val newer = System.currentTimeMillis() + 24 * 3600 * 1000L
        fixtures.put(sav, save(1), mtimeMs = newer)
        val mirror = mirror()
        val outcome = SaveOpening.prepare(store, mirror, mirror.snapshot(), validSizes, registry = registry)
        assertArrayEquals("gana el externo más nuevo", save(1), outcome.data)
        assertArrayEquals(save(1), store.load())
        assertTrue("la local anterior queda respaldada", backupContents().any { it.contentEquals(save(2)) })
        assertArrayEquals("y el espejo no se tocó", save(1), fixtures.read(sav))
    }

    // ---------------------------------------------------------------- hilo

    @Test
    fun theMirrorRefusesToRunOnTheMainThread() {
        val failures = ArrayList<Throwable>()
        instrumentation.runOnMainSync {
            try {
                mirror().snapshot()
            } catch (error: IllegalStateException) {
                failures += error
            }
            try {
                mirror().write(save(1))
            } catch (error: IllegalStateException) {
                failures += error
            }
        }
        assertEquals(2, failures.size)
        assertNull(fixtures.read(sav))
    }

    @Test
    fun writingFromAnotherThreadWorksWhileTheTestThreadWaits() {
        var result: Long? = -1
        val worker = thread { result = mirror().write(save(3)) }
        worker.join(20_000)
        assertFalse(worker.isAlive)
        assertNotNull(result)
        assertArrayEquals(save(3), fixtures.read(sav))
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("condición no cumplida", condition())
    }
}
