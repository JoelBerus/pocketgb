package com.joelbermudez.pocketgb.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {
    private class FakeTree(
        val dirs: Map<String?, List<TreeNode>>,
        val heads: Map<String, ByteArray> = emptyMap(),
        val failures: Map<String, IOException> = emptyMap(),
        val failingDirs: Set<String> = emptySet(),
        val revokedDirs: Set<String> = emptySet(),
    ) : DocumentTree {
        override fun children(directoryId: String?): List<TreeNode> {
            if (directoryId in revokedDirs) throw TreePermissionException()
            if (directoryId in failingDirs) throw IOException("sin permiso")
            return dirs[directoryId].orEmpty()
        }

        override fun readHead(node: TreeNode, limit: Int): ByteArray {
            failures[node.id]?.let { throw it }
            return (heads[node.id] ?: ByteArray(0)).copyOf(minOf(limit, heads[node.id]?.size ?: 0))
        }

        override fun uriOf(node: TreeNode) = "content://tree/${node.id}"
    }

    private fun file(id: String, name: String, size: Long = 32768, virtual: Boolean = false) =
        TreeNode(id, name, isDirectory = false, sizeBytes = size, isVirtual = virtual)

    private fun dir(id: String, name: String) = TreeNode(id, name, isDirectory = true, sizeBytes = 0)

    private fun rom(title: String, cgb: Int = 0, validChecksum: Boolean = true): ByteArray {
        val bytes = ByteArray(0x150)
        title.take(if (cgb and 0x80 != 0) 15 else 16).forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
        bytes[0x143] = cgb.toByte()
        var x = 0
        for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
        bytes[0x14D] = (if (validChecksum) x else x xor 0xFF).toByte()
        return bytes
    }

    @Test
    fun findsRomsInRootAndOneSubfolderOnly() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), dir("d1", "Amarillo"), file("n", "notas.txt")),
                "d1" to listOf(file("b", "Yellow.GBC"), dir("deep", "Mas"), file("x", "x.png")),
                "deep" to listOf(file("c", "Oculto.gb")),
            ),
            heads = mapOf("a" to rom("POKEMON RED"), "b" to rom("POKEMON YELLOW", cgb = 0x80)),
        )
        val entries = LibraryScanner.scan(tree)
        assertEquals(listOf("Rojo.gb", "Amarillo/Yellow.GBC"), entries.sortedBy { it.id.length }.map { it.id })
        assertEquals(setOf("POKEMON RED", "POKEMON YELLOW"), entries.map { it.title }.toSet())
        assertTrue(entries.single { it.id == "Amarillo/Yellow.GBC" }.isColor)
        assertEquals("Amarillo", entries.single { it.title == "POKEMON YELLOW" }.subfolder)
        assertEquals("content://tree/a", entries.single { it.id == "Rojo.gb" }.uri)
    }

    @Test
    fun skipsHiddenFilesAndFolders() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("h", ".oculto.gb"), dir("hd", ".Trash"), file("a", "Ok.gb")),
                "hd" to listOf(file("t", "Basura.gb")),
            ),
            heads = mapOf("a" to rom("OK")),
        )
        assertEquals(listOf("Ok.gb"), LibraryScanner.scan(tree).map { it.id })
    }

    @Test
    fun reportsEachProblemSeparately() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(
                    file("big", "Enorme.gb", size = LibraryScanner.MAX_ROM_BYTES + 1),
                    file("short", "Corto.gb"),
                    file("virt", "Nube.gb", virtual = true),
                    file("remote", "Remoto.gb"),
                    file("io", "Roto.gb"),
                ),
            ),
            heads = mapOf("short" to ByteArray(10)),
            failures = mapOf(
                "remote" to DocumentReadException(remote = true),
                "io" to IOException("boom"),
            ),
        )
        val byId = LibraryScanner.scan(tree).associateBy { it.id }
        assertEquals(RomProblem.TOO_LARGE, byId.getValue("Enorme.gb").problem)
        assertEquals(RomProblem.INVALID_HEADER, byId.getValue("Corto.gb").problem)
        assertEquals(RomProblem.REMOTE_UNAVAILABLE, byId.getValue("Nube.gb").problem)
        assertEquals(RomProblem.REMOTE_UNAVAILABLE, byId.getValue("Remoto.gb").problem)
        assertEquals(RomProblem.UNREADABLE, byId.getValue("Roto.gb").problem)
        // Con problema se muestra el nombre del archivo, no un título inventado.
        assertEquals("Corto", byId.getValue("Corto.gb").title)
    }

    @Test
    fun unreadableSubfolderDoesNotAbortTheScan() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(dir("bad", "Privada"), file("a", "Ok.gb"))),
            heads = mapOf("a" to rom("OK")),
            failingDirs = setOf("bad"),
        )
        assertEquals(listOf("Ok.gb"), LibraryScanner.scan(tree).map { it.id })
    }

    @Test
    fun permissionRevokedOnASubfolderPropagatesInsteadOfReturningAPartialLibrary() {
        // La raíz se lista bien y el permiso desaparece justo al entrar en la subcarpeta.
        val tree = FakeTree(
            dirs = mapOf(null to listOf(file("a", "Ok.gb"), dir("sub", "Sub"))),
            heads = mapOf("a" to rom("OK")),
            revokedDirs = setOf("sub"),
        )
        assertThrows(TreePermissionException::class.java) { LibraryScanner.scan(tree) }
    }

    @Test
    fun missingSubfolderIsRecoverableAndIgnored() {
        val tree = object : DocumentTree by FakeTree(
            dirs = mapOf(null to listOf(file("a", "Ok.gb"), dir("sub", "Sub"))),
            heads = mapOf("a" to rom("OK")),
        ) {
            override fun children(directoryId: String?): List<TreeNode> =
                if (directoryId == "sub") throw TreeMissingException() else if (directoryId == null) {
                    listOf(file("a", "Ok.gb"), dir("sub", "Sub"))
                } else {
                    emptyList()
                }
        }
        assertEquals(listOf("Ok.gb"), LibraryScanner.scan(tree).map { it.id })
    }

    @Test
    fun repeatedNamesGetDeterministicStableAndUniqueIds() {
        // Proveedores remotos permiten dos "Juego.gb" en la misma carpeta y dos subcarpetas homónimas.
        val dirs = mapOf(
            null to listOf(
                file("d1", "Juego.gb"),
                file("d2", "Juego.gb"),
                file("solo", "Unico.gb"),
                dir("s1", "Rojo"),
                dir("s2", "Rojo"),
            ),
            "s1" to listOf(file("s1a", "Pokemon.gb"), file("s1b", "Otro.gb")),
            "s2" to listOf(file("s2a", "Pokemon.gb")),
        )
        val heads = listOf("d1", "d2", "solo", "s1a", "s1b", "s2a").associateWith { rom(it.uppercase()) }
        val first = LibraryScanner.scan(FakeTree(dirs, heads))
        val ids = first.map { it.id }
        assertEquals("ids únicos", ids.size, ids.toSet().size)
        assertEquals(6, ids.size)
        // Solo se renombran los que colisionan; el resto conserva su ruta relativa.
        assertTrue("Unico.gb" in ids)
        assertTrue("Rojo/Otro.gb" in ids)
        assertEquals(2, ids.count { it.startsWith("Juego.gb#") })
        assertEquals(2, ids.count { it.startsWith("Rojo/Pokemon.gb#") })
        // La subcarpeta mostrada no cambia por el sufijo.
        assertEquals(setOf("Rojo"), first.filter { it.id.startsWith("Rojo/") }.map { it.subfolder }.toSet())
        // Estable: el mismo contenido en otro orden de proveedor da el mismo conjunto de ids por documento.
        val reversed = dirs.mapValues { (_, nodes) -> nodes.reversed() }
        val second = LibraryScanner.scan(FakeTree(reversed, heads))
        assertEquals(first.associate { it.uri to it.id }, second.associate { it.uri to it.id })
    }

    @Test
    fun idsStayStableEvenWhenDocumentIdsShareAJavaHashCode() {
        // "Aa" y "BB" tienen el mismo String.hashCode(): el id no puede depender de ese hash ni del orden.
        val a = file("Aa", "Juego.gb")
        val b = file("BB", "Juego.gb")
        val heads = mapOf("Aa" to rom("A"), "BB" to rom("B"))
        val forward = LibraryScanner.scan(FakeTree(mapOf(null to listOf(a, b)), heads))
        val backward = LibraryScanner.scan(FakeTree(mapOf(null to listOf(b, a)), heads))
        assertEquals(forward.associate { it.uri to it.id }, backward.associate { it.uri to it.id })
        assertEquals(2, forward.map { it.id }.toSet().size)
    }

    @Test
    fun rootListingFailurePropagates() {
        val throwing = object : DocumentTree by FakeTree(dirs = emptyMap()) {
            override fun children(directoryId: String?): List<TreeNode> = throw IOException("revocado")
        }
        try {
            LibraryScanner.scan(throwing)
            throw AssertionError("debía fallar")
        } catch (expected: IOException) {
            assertEquals("revocado", expected.message)
        }
    }

    @Test
    fun emptyTitleFallsBackToFileNameAndChecksumFlagIsKept() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(file("a", "Sin titulo.gb"))),
            heads = mapOf("a" to rom("", validChecksum = false)),
        )
        val entry = LibraryScanner.scan(tree).single()
        assertEquals("Sin titulo", entry.title)
        assertEquals(false, entry.headerChecksumOk)
        assertNull(entry.problem)
    }

    @Test
    fun headerParsingHandlesCgbTitleLengthAndNonPrintable() {
        val bytes = rom("ABCDEFGHIJKLMNOP", cgb = 0x80)
        bytes[0x136] = 0x01
        val info = RomHeader.parse(bytes)!!
        assertEquals("AB?DEFGHIJKLMNO", info.title)
        assertTrue(info.isColor)
        assertNull(RomHeader.parse(ByteArray(0x14F)))
    }

    @Test
    fun naturalOrderComparesNumbersByValueAndIgnoresCase() {
        val sorted = listOf("juego 10", "Juego 2", "juego 1", "Zelda", "álbum").sortedWith(NaturalOrder::compare)
        assertEquals(listOf("álbum", "juego 1", "Juego 2", "juego 10", "Zelda"), sorted)
    }
}
