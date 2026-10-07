package com.joelbermudez.pocketgb.saves

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N6 · momentos desde el detalle: «Recuperar» en un toque, RAM de un momento que ya no carga y exclusión por huella. */
class MomentLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fp = "cd".repeat(32)
    private val ownership = FingerprintOwnership()
    private val root by lazy { tmp.newFolder() }
    private val library by lazy {
        MomentLibrary(File(root, "moments"), File(root, "states"), File(root, "saves"), ownership = ownership, migratedName = { "R:$it" })
    }
    private val save by lazy { SaveStore(File(root, "saves"), fp) }

    @Test fun recoverBringsBackTheNewerGameInOneTapAndKeepsThePreviousInBackup() {
        save.save(version(2, 16)) // partida «nueva»
        // Cargar un momento antiguo dejó la más nueva en el anillo y la del momento como `.sav`.
        library.store(fp).pushBeforeLoad(MomentStore.Capture(null, version(2, 16), null), "Viejo")
        save.save(version(1, 16))
        val entry = library.snapshot(fp).beforeLoad.single()
        library.installSram(fp, MomentStore.Kind.BEFORE_LOAD, entry.id, entry.name, openFingerprint = null)
        assertArrayEquals("vuelve la partida más nueva", version(2, 16), save.load())
        assertArrayEquals("la de antes de recuperar queda en el backup .1", version(1, 16), save.backupFile(1).readBytes())
        assertTrue(
            "y también en el anillo",
            library.snapshot(fp).beforeLoad.any { library.store(fp).loadSram(MomentStore.Kind.BEFORE_LOAD, it.id)!!.contentEquals(version(1, 16)) },
        )
    }

    /** N6A-H2: con el anillo lleno, recuperar su entrada más antigua y fallar al escribir la partida no la pierde. */
    @Test fun recoveringTheOldestRingEntryWithAFullRingSurvivesAFailedSave() {
        val failSaves = java.util.concurrent.atomic.AtomicBoolean(false)
        val savesDir = File(root, "saves")
        val ops = object : SaveFileOps by PosixSaveFileOps {
            override fun writeSynced(file: File, data: ByteArray) {
                if (failSaves.get() && file.absolutePath.startsWith(savesDir.absolutePath)) throw IOException("fallo inyectado")
                PosixSaveFileOps.writeSynced(file, data)
            }
        }
        val lib = MomentLibrary(File(root, "moments"), File(root, "states"), savesDir, ops = ops, ownership = ownership, migratedName = { it })
        val store = SaveStore(savesDir, fp, ops)
        store.save(version(9, 16))
        (1..3).forEach { lib.store(fp).pushBeforeLoad(MomentStore.Capture(null, version(it, 16), null), "M$it") }
        val oldest = lib.snapshot(fp).beforeLoad.last()
        assertArrayEquals(version(1, 16), lib.store(fp).loadSram(MomentStore.Kind.BEFORE_LOAD, oldest.id))
        failSaves.set(true)
        assertThrows(IOException::class.java) { lib.installSram(fp, MomentStore.Kind.BEFORE_LOAD, oldest.id, oldest.name, null) }
        failSaves.set(false)
        assertArrayEquals("la partida no cambió", version(9, 16), store.load())
        assertTrue("la entrada recuperada sigue en el anillo", lib.snapshot(fp).beforeLoad.any { it.id == oldest.id })
        assertArrayEquals("y sus archivos también", version(1, 16), lib.store(fp).loadSram(MomentStore.Kind.BEFORE_LOAD, oldest.id))
        // Reintento con éxito: se instala y la protegida sigue disponible.
        lib.installSram(fp, MomentStore.Kind.BEFORE_LOAD, oldest.id, oldest.name, null)
        assertArrayEquals(version(1, 16), store.load())
        assertEquals(3, lib.snapshot(fp).beforeLoad.size)
    }

    @Test fun theCartridgeRamOfAMomentIsRecoverableEvenIfItsStateNoLongerLoads() {
        save.save(version(5, 16))
        val m = library.store(fp).create(MomentStore.Capture("BASURA".toByteArray(), version(7, 16), null), "Roto")
        library.installSram(fp, MomentStore.Kind.MOMENT, m.id, m.name, openFingerprint = null)
        assertArrayEquals(version(7, 16), save.load())
        assertArrayEquals(version(5, 16), save.backupFile(1).readBytes())
    }

    @Test fun nothingIsInstalledWhileTheFingerprintHasAnOwner() {
        save.save(version(5, 16))
        val m = library.store(fp).create(MomentStore.Capture(null, version(7, 16), null), "M")
        val lease = ownership.tryAcquire(fp, "sesión aparcada")!!
        assertThrows(SavePendingException::class.java) { library.installSram(fp, MomentStore.Kind.MOMENT, m.id, m.name, null) }
        assertArrayEquals("la partida no cambió", version(5, 16), save.load())
        lease.close()
        assertThrows(IllegalStateException::class.java) { library.installSram(fp, MomentStore.Kind.MOMENT, m.id, m.name, openFingerprint = fp) }
        assertArrayEquals(version(5, 16), save.load())
    }

    @Test fun aWrongSizeOrAMissingRamIsRejectedWithoutTouchingTheGame() {
        save.save(version(5, 16))
        SavesIndex(File(root, "saves")).record(fp, "Juego", "j.gb", setOf(16))
        val wrong = library.store(fp).create(MomentStore.Capture(null, version(7, 8), null), "Pequeño")
        assertThrows(SaveStore.InvalidBackupException::class.java) { library.installSram(fp, MomentStore.Kind.MOMENT, wrong.id, wrong.name, null) }
        val noRam = library.store(fp).create(MomentStore.Capture("PGBS".toByteArray(), null, null), "Sin RAM")
        assertThrows(MomentLibrary.NoSramException::class.java) { library.installSram(fp, MomentStore.Kind.MOMENT, noRam.id, noRam.name, null) }
        assertArrayEquals(version(5, 16), save.load())
        assertEquals(0, save.backups().size)
    }

    @Test fun openingTheMomentsMigratesTheOldSlotsOnlyWhenTheFingerprintIsFree() {
        val states = StateStore(File(root, "states"), fp)
        states.save("PGBS1".toByteArray(), null, StateSlot.MANUAL1)
        val lease = ownership.tryAcquire(fp, "sesión")!!
        assertTrue("con la sesión abierta solo se lee", library.snapshot(fp).moments.isEmpty())
        assertTrue(states.stateFile(StateSlot.MANUAL1).exists())
        lease.close()
        assertEquals(listOf("R:slot1"), library.snapshot(fp).moments.map { it.name })
    }
}
