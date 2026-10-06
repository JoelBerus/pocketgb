package com.joelbermudez.pocketgb.library.artwork

import com.joelbermudez.pocketgb.library.DefaultPreferencesFileOps
import com.joelbermudez.pocketgb.library.PreferencesFileOps
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fp = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private val frame = IntArray(160 * 144) { it or 0xFF000000.toInt() }
    private fun dir() = File(tmp.root, "artwork")
    private fun store(
        ops: PreferencesFileOps = DefaultPreferencesFileOps,
        encoder: (IntArray) -> ByteArray? = { byteArrayOf(1, 2, 3) },
    ) = ArtworkStore(dir(), ops = ops, encoder = encoder, executor = null)

    @Test
    fun uniformFramesAreDiscarded() {
        val s = store()
        assertFalse(s.save(fp, IntArray(160 * 144) { 0xFFFFFFFF.toInt() }))
        assertFalse(File(dir(), "$fp.png").exists())
        assertTrue(ArtworkCapture.isUniform(IntArray(10)))
        assertFalse(ArtworkCapture.isUniform(IntArray(10).also { it[9] = 1 }))
        assertTrue(ArtworkCapture.isUniform(IntArray(0)))
    }

    @Test
    fun savesAtomicallyAndLeavesNoTemporaryFile() {
        val s = store()
        assertTrue(s.save(fp, frame))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir(), "$fp.png").readBytes())
        assertEquals(emptyList<String>(), dir().list()!!.filter { it.endsWith(".tmp") })
        assertEquals(setOf(fp), s.fingerprints())
        assertTrue(s.has(fp))
    }

    @Test
    fun aFailureMidWriteKeepsThePreviousCover() {
        store(encoder = { byteArrayOf(9, 9) }).save(fp, frame)
        val failing = object : PreferencesFileOps {
            override fun writeSynced(file: File, bytes: ByteArray) {
                file.writeBytes(bytes.copyOf(1)) // escritura a medias
                throw IOException("disco lleno")
            }
            override fun rename(from: File, to: File) = from.renameTo(to)
        }
        assertFalse(store(ops = failing).save(fp, frame))
        assertArrayEquals(byteArrayOf(9, 9), File(dir(), "$fp.png").readBytes())
        assertEquals(emptyList<String>(), dir().list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun aFailedRenameKeepsThePreviousCoverAndCleansTheTemporary() {
        store(encoder = { byteArrayOf(9, 9) }).save(fp, frame)
        val noRename = object : PreferencesFileOps {
            override fun writeSynced(file: File, bytes: ByteArray) = DefaultPreferencesFileOps.writeSynced(file, bytes)
            override fun rename(from: File, to: File) = false
        }
        assertFalse(store(ops = noRename).save(fp, frame))
        assertArrayEquals(byteArrayOf(9, 9), File(dir(), "$fp.png").readBytes())
        assertEquals(emptyList<String>(), dir().list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun anEncoderThatFailsStoresNothing() {
        assertFalse(store(encoder = { null }).save(fp, frame))
        assertFalse(store().has(fp))
    }

    @Test
    fun removeAllDeletesOnlyTheArtworkFolderContent() {
        val saves = File(tmp.root, "saves").apply { mkdirs() }
        val sav = File(saves, "$fp.sav").apply { writeBytes(byteArrayOf(5)) }
        val states = File(tmp.root, "states").apply { mkdirs() }
        val state = File(states, "x.state").apply { writeBytes(byteArrayOf(6)) }
        val s = store()
        s.save(fp, frame)
        s.save(other, frame)
        assertEquals(2, s.removeAll())
        assertEquals(0L, s.sizeBytes())
        assertEquals(emptySet<String>(), s.fingerprints())
        assertTrue(sav.exists() && state.exists())
    }

    @Test
    fun sizeIsTheSumOfTheCovers() {
        val s = store()
        s.save(fp, frame)
        s.save(other, frame)
        assertEquals(6L, s.sizeBytes())
    }

    @Test
    fun readsAreCappedAt512KiB() {
        val s = store()
        dir().mkdirs()
        File(dir(), "$fp.png").writeBytes(ByteArray(ArtworkStore.MAX_READ_BYTES))
        assertEquals(ArtworkStore.MAX_READ_BYTES, s.readBytes(fp)!!.size)
        File(dir(), "$other.png").writeBytes(ByteArray(ArtworkStore.MAX_READ_BYTES + 1))
        assertNull(s.readBytes(other))
        assertNull(s.readBytes("ef".repeat(32)))
    }

    @Test
    fun invalidFingerprintsNeverTouchDisk() {
        val s = store()
        assertFalse(s.save("../saves/x", frame))
        assertFalse(s.save("AB".repeat(32), frame))
        assertNull(s.readBytes("../x"))
        assertFalse(s.has("../x"))
        assertEquals(emptySet<String>(), s.fingerprints())
    }

    @Test
    fun versionChangesWhenTheContentChanges() {
        val s = store()
        val v0 = s.version.value
        s.save(fp, frame)
        val v1 = s.version.value
        assertTrue(v1 > v0)
        s.removeAll()
        assertTrue(s.version.value > v1)
    }
}
