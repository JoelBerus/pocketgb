package com.joelbermudez.pocketgb.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El fsync de directorio y el renombrado atómico de [PosixSaveFileOps] sobre el `filesDir` real de la app
 * (ext4/f2fs de Android), donde los tests JVM de escritorio no llegan.
 */
@RunWith(AndroidJUnit4::class)
class SaveFilesOnAndroidTest {
    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private lateinit var dir: File

    private val fingerprint = "00112233445566778899aabbccddeeff"
    private fun version(value: Int, size: Int) = ByteArray(size) { value.toByte() }
    private fun File.names(): List<String> = list()?.sorted() ?: emptyList()

    @Before
    fun setUp() {
        dir = File(filesDir, "saves-android-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun directoryFsyncWorksOnTheRealFilesystemAndTheDirectorySurvives() {
        // La operación real, sin la tolerancia de syncDirectory: en Android no debe lanzar.
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }

        File(dir, "dato.bin").writeBytes(byteArrayOf(1, 2, 3))
        PosixSaveFileOps.syncDirectory(dir)
        PosixSaveFileOps.syncDirectory(filesDir)
        assertTrue(dir.isDirectory)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "dato.bin").readBytes())
    }

    @Test
    fun atomicSaveWithBackupsWorksOnAndroid() {
        val store = SaveStore(dir, fingerprint)
        assertTrue(store.save(version(1, 8192)))
        assertFalse("idéntica: no se reescribe", store.save(version(1, 8192)))
        for (v in 2..8) assertTrue(store.save(version(v, 8192)))

        assertArrayEquals(version(8, 8192), store.load())
        assertEquals(SaveStore.KEEP_BACKUPS, store.backups().size)
        assertArrayEquals(version(7, 8192), File(store.backupsDirectory, "$fingerprint.1.sav").readBytes())
        assertFalse(dir.names().any { it.endsWith(".tmp") })
        assertFalse(store.backupsDirectory.names().any { it.endsWith(".tmp") })
    }
}
