package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8: el escáner acepta `.gba` (≤ 32 MiB, cabecera de GBA como iOS `RomHeader.parseGBA`) junto a `.gb`/`.gbc`. */
class LibraryScannerGbaTest {
    private class Tree(val dirs: Map<String?, List<TreeNode>>, val heads: Map<String, ByteArray>) : DocumentTree {
        val readLimits = mutableListOf<Int>()

        override fun children(directoryId: String?): List<TreeNode> = dirs[directoryId].orEmpty()

        override fun readHead(node: TreeNode, limit: Int): ByteArray {
            readLimits += limit
            val bytes = heads[node.id] ?: ByteArray(0)
            return bytes.copyOf(minOf(limit, bytes.size))
        }

        override fun uriOf(node: TreeNode) = "content://tree/${node.id}"
    }

    private fun file(id: String, name: String, size: Long = 4L * 1024 * 1024, modified: Long? = null) =
        TreeNode(id, name, isDirectory = false, sizeBytes = size, lastModified = modified)

    private fun dir(id: String, name: String) = TreeNode(id, name, isDirectory = true, sizeBytes = 0)

    /** Cabecera de GBA de 0xC0 bytes: título (0xA0), código (0xAC), fabricante (0xB0), byte fijo 0x96 y checksum (0xBD). */
    private fun gba(title: String, code: String = "PGBA", fixed: Int = 0x96, validChecksum: Boolean = true, size: Int = 0xC0): ByteArray {
        val bytes = ByteArray(maxOf(size, 0xC0))
        title.take(12).forEachIndexed { i, c -> bytes[0xA0 + i] = c.code.toByte() }
        code.forEachIndexed { i, c -> bytes[0xAC + i] = c.code.toByte() }
        "01".forEachIndexed { i, c -> bytes[0xB0 + i] = c.code.toByte() }
        bytes[0xB2] = fixed.toByte()
        var sum = 0
        for (i in 0xA0..0xBC) sum = (sum - (bytes[i].toInt() and 0xFF)) and 0xFF
        val checksum = (sum - 0x19) and 0xFF
        bytes[0xBD] = (if (validChecksum) checksum else checksum xor 0xFF).toByte()
        return bytes.copyOf(size)
    }

    private fun gb(title: String): ByteArray {
        val bytes = ByteArray(0x150)
        title.forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        bytes[0x14D] = x.toByte()
        return bytes
    }

    @Test
    fun gbaRomsAreFoundWithTheirConsoleTitleAndCategory() {
        val tree = Tree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), dir("k", "Kirby")),
                "k" to listOf(file("b", "Kirby - Nightmare in Dream Land.gba", size = 8L * 1024 * 1024 + 1)),
            ),
            heads = mapOf("a" to gb("RED"), "b" to gba("KIRBY DREAM", code = "A7KE")),
        )
        val entries = LibraryScanner.scan(tree).associateBy { it.fileName }
        val kirby = entries.getValue("Kirby - Nightmare in Dream Land.gba")
        assertNull("un .gba de más de 8 MiB es jugable", kirby.problem)
        assertEquals(RomConsole.GBA, kirby.console)
        assertEquals(Console.GBA, kirby.core)
        assertEquals("KIRBY DREAM", kirby.title)
        assertEquals("GBA", kirby.systemShort)
        assertEquals("Game Boy Advance", kirby.system)
        assertEquals(listOf("Kirby"), kirby.folderPath)
        assertTrue(kirby.headerChecksumOk)
        assertEquals(RomConsole.GB, entries.getValue("Rojo.gb").console)
        assertEquals(Console.GB, entries.getValue("Rojo.gb").core)
    }

    @Test
    fun theLimitIs32MiBForGbaAnd8MiBForGameBoy() {
        val limit = 32L * 1024 * 1024
        val tree = Tree(
            dirs = mapOf(
                null to listOf(
                    file("exact", "Exacto.gba", size = limit),
                    file("over", "Enorme.gba", size = limit + 1),
                    file("gb", "Grande.gb", size = 8L * 1024 * 1024 + 1),
                ),
            ),
            heads = mapOf("exact" to gba("EXACTO"), "over" to gba("ENORME"), "gb" to gb("GRANDE")),
        )
        val entries = LibraryScanner.scan(tree).associateBy { it.fileName }
        assertNull(entries.getValue("Exacto.gba").problem)
        assertEquals(RomProblem.TOO_LARGE_GBA, entries.getValue("Enorme.gba").problem)
        assertEquals(RomConsole.GBA, entries.getValue("Enorme.gba").console)
        assertEquals(RomProblem.TOO_LARGE, entries.getValue("Grande.gb").problem)
        assertEquals(LibraryScanner.MAX_GBA_ROM_BYTES, LibraryScanner.romLimit(RomConsole.GBA))
        assertEquals(LibraryScanner.MAX_ROM_BYTES, LibraryScanner.romLimit(RomConsole.GBC))
        // Nunca se abre un archivo que ya supera el límite.
        assertEquals(1, tree.readLimits.size)
    }

    @Test
    fun aGbaHeaderOnlyReadsItsFirst0xC0Bytes() {
        val tree = Tree(mapOf(null to listOf(file("a", "Juego.GBA"))), mapOf("a" to gba("JUEGO", size = 0x400)))
        val entry = LibraryScanner.scan(tree).single()
        assertEquals(listOf(RomHeader.GBA_MINIMUM_BYTES), tree.readLimits)
        assertEquals(RomConsole.GBA, entry.console)
        assertEquals("JUEGO", entry.title)
    }

    @Test
    fun hostileGbaHeadersAreRejectedOrSanitized() {
        val badFixed = gba("ROTO", fixed = 0x00)
        val short = gba("CORTO").copyOf(0xBF)
        val weird = gba("X").also { bytes ->
            bytes[0xA0] = 0x01 // no imprimible
            bytes[0xA1] = 0x7F.toByte()
            bytes[0xA2] = 'Z'.code.toByte()
            var sum = 0
            for (i in 0xA0..0xBC) sum = (sum - (bytes[i].toInt() and 0xFF)) and 0xFF
            bytes[0xBD] = ((sum - 0x19) and 0xFF).toByte()
        }
        val tree = Tree(
            dirs = mapOf(
                null to listOf(
                    file("a", "Roto.gba"), file("b", "Corto.gba"), file("c", "Raro.gba"),
                    file("d", "Checksum.gba"), file("e", "Vacio.gba", size = 0),
                ),
            ),
            heads = mapOf("a" to badFixed, "b" to short, "c" to weird, "d" to gba("MALSUMA", validChecksum = false)),
        )
        val entries = LibraryScanner.scan(tree).associateBy { it.fileName }
        assertEquals(RomProblem.INVALID_HEADER_GBA, entries.getValue("Roto.gba").problem)
        assertEquals("sin cabecera, el nombre del archivo", "Roto", entries.getValue("Roto.gba").title)
        assertEquals(RomConsole.GBA, entries.getValue("Roto.gba").console)
        assertEquals(RomProblem.INVALID_HEADER_GBA, entries.getValue("Corto.gba").problem)
        assertNull(entries.getValue("Corto.gba").headerKey)
        assertEquals(RomProblem.INVALID_HEADER_GBA, entries.getValue("Vacio.gba").problem)
        assertEquals("??Z", entries.getValue("Raro.gba").title)
        assertNull(entries.getValue("Raro.gba").problem)
        val bad = entries.getValue("Checksum.gba")
        assertNull("el checksum malo no impide jugar (lo avisa al abrir)", bad.problem)
        assertFalse(bad.headerChecksumOk)
    }

    @Test
    fun theGbaHeaderIdentityFeedsTheHeaderCacheWithoutConfusingConsoles() {
        val dirs = mapOf<String?, List<TreeNode>>(null to listOf(file("a", "Juego.gba", modified = 10L), file("b", "Rojo.gb", modified = 10L)))
        val heads = mapOf("a" to gba("JUEGO"), "b" to gb("RED"))
        val first = LibraryScanner.scanDetailed(Tree(dirs, heads))
        val game = first.entries.single { it.fileName == "Juego.gba" }
        assertEquals("0xA0..0xBF en hexadecimal", 64, game.headerKey!!.length)
        assertEquals(56, first.entries.single { it.fileName == "Rojo.gb" }.headerKey!!.length)
        assertTrue(RomHeader.fromGbaIdentity(game.headerKey!!)!!.contentEquals(gba("JUEGO")))

        val second = LibraryScanner.scanDetailed(Tree(dirs, heads), headerCache = HeaderCache.from(first.entries.map(DocumentStamp::of)))
        assertEquals(0, second.stats.headReads)
        assertEquals(2, second.stats.headerCacheHits)
        assertEquals(first.entries.map { it.id to it.title to it.console }, second.entries.map { it.id to it.title to it.console })

        // Mismo documento, ahora con la extensión de la otra consola: la caché de GB no vale para un .gba (y viceversa).
        val renamed = mapOf<String?, List<TreeNode>>(null to listOf(file("b", "Rojo.gba", modified = 10L)))
        val third = LibraryScanner.scanDetailed(Tree(renamed, mapOf("b" to gb("RED"))), headerCache = HeaderCache.from(first.entries.map(DocumentStamp::of)))
        assertEquals(1, third.stats.headReads)
        assertEquals(RomProblem.INVALID_HEADER_GBA, third.entries.single().problem)
    }

    @Test
    fun parseGbaMatchesIosOnTheChecksumFormula() {
        val header = gba("POKEMON EMER", code = "BPEE")
        val info = RomHeader.parseGba(header)!!
        assertEquals("POKEMON EMER", info.title)
        assertTrue(info.checksumOk)
        assertFalse(info.isColor)
        assertNull(RomHeader.parseGba(ByteArray(0xBF)))
        assertNull(RomHeader.fromGbaIdentity("zz"))
        assertNull("una identidad de GB no es de GBA", RomHeader.fromGbaIdentity(RomHeader.identity(gb("RED"))!!))
    }

    @Test
    fun movingAGbaRomToAnotherFolderKeepsItsFingerprintFavoriteAndAlias() {
        val fp = "cd".repeat(32)
        val heads = mapOf("k" to gba("KIRBY DREAM", code = "A7KE"))
        val before = LibraryScanner.scan(
            Tree(mapOf(null to listOf(file("k", "Kirby.gba", modified = 50L)), "d" to emptyList()), heads),
        ).single()
        var prefs = LibraryPreferencesData().reconciled(listOf(before), complete = true)
            .recordFingerprint(before.id, fp)
            .toggleFavorite(before)
            .setAlias(before, "Kirby de Joel")
        // Se mueve a Kirby/ en Drive: otra ruta, mismo nombre, tamaño, fecha y cabecera de GBA.
        val after = LibraryScanner.scan(
            Tree(mapOf(null to listOf(dir("d", "Kirby")), "d" to listOf(file("k2", "Kirby.gba", modified = 50L))), mapOf("k2" to heads.getValue("k"))),
        ).single()
        assertEquals(listOf("Kirby"), after.folderPath)
        prefs = prefs.reconciled(listOf(after), complete = true)
        assertEquals(fp, prefs.fingerprints[after.id])
        assertTrue(prefs.isFavorite(after))
        assertEquals("Kirby de Joel", prefs.displayTitle(after))
    }
}
