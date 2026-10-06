package com.joelbermudez.pocketgb.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUsageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: String, bytes: Int) {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun measuresEachFolderRecursivelyAndMissingIsZero() {
        write("saves/a.sav", 100)
        write("saves/backups/a.1.sav", 50)
        write("states/a/slot1.state", 1000)
        val usage = StorageUsage.measure(tmp.root)
        assertEquals(StorageUsage(saves = 150, states = 1000, artwork = 0), usage)
        assertEquals(0L, StorageUsage.size(File(tmp.root, "no-existe")))
    }

    @Test
    fun doesNotFollowSymlinksOutsideTheFolder() {
        write("outside/big.bin", 5000)
        write("saves/a.sav", 7)
        java.nio.file.Files.createSymbolicLink(
            File(tmp.root, "saves/link").toPath(),
            File(tmp.root, "outside").toPath(),
        )
        assertEquals(7L, StorageUsage.size(File(tmp.root, "saves")))
    }
}
