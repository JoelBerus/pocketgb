package com.joelbermudez.pocketgb.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override val rootId: String? = null,
        /** N1-H4: carpetas que el proveedor da a medias (`EXTRA_LOADING`/`EXTRA_ERROR`). */
        val partialDirs: Set<String?> = emptySet(),
    ) : DocumentTree {
        /** Carpetas listadas, en orden (N1b: cada una es una consulta SAF; en Drive, una llamada de red). */
        val queried = mutableListOf<String?>()
        var headReads = 0

        override fun children(directoryId: String?): List<TreeNode> {
            queried += directoryId
            if (directoryId in revokedDirs) throw TreePermissionException()
            if (directoryId in failingDirs) throw IOException("sin permiso")
            if (directoryId in partialDirs) throw PartialListingException(dirs[directoryId].orEmpty(), "cargando")
            return dirs[directoryId].orEmpty()
        }

        override fun readHead(node: TreeNode, limit: Int): ByteArray {
            headReads++
            failures[node.id]?.let { throw it }
            return (heads[node.id] ?: ByteArray(0)).copyOf(minOf(limit, heads[node.id]?.size ?: 0))
        }

        override fun uriOf(node: TreeNode) = "content://tree/${node.id}"
    }

    private fun file(id: String, name: String, size: Long = 32768, virtual: Boolean = false, modified: Long? = null) =
        TreeNode(id, name, isDirectory = false, sizeBytes = size, isVirtual = virtual, lastModified = modified)

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
    fun findsRomsInRootAndSubfoldersWithTheirFolderPath() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), dir("d1", "Amarillo"), file("n", "notas.txt")),
                "d1" to listOf(file("b", "Yellow.GBC"), dir("deep", "Mas"), file("x", "x.png")),
                "deep" to listOf(file("c", "Hondo.gb")),
            ),
            heads = mapOf("a" to rom("POKEMON RED"), "b" to rom("POKEMON YELLOW", cgb = 0x80), "c" to rom("HONDO")),
        )
        val entries = LibraryScanner.scan(tree)
        assertEquals(listOf("Rojo.gb", "Amarillo/Yellow.GBC", "Amarillo/Mas/Hondo.gb"), entries.sortedBy { it.id.length }.map { it.id })
        assertEquals(setOf("POKEMON RED", "POKEMON YELLOW", "HONDO"), entries.map { it.title }.toSet())
        assertTrue(entries.single { it.id == "Amarillo/Yellow.GBC" }.console == com.joelbermudez.pocketgb.library.RomConsole.GBC)
        assertEquals("Amarillo", entries.single { it.title == "POKEMON YELLOW" }.subfolder)
        assertEquals(listOf("Amarillo", "Mas"), entries.single { it.title == "HONDO" }.folderPath)
        assertEquals(emptyList<String>(), entries.single { it.id == "Rojo.gb" }.folderPath)
        assertEquals("content://tree/a", entries.single { it.id == "Rojo.gb" }.uri)
    }

    @Test
    fun entriesRememberTheFolderThatHoldsThem() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), dir("d1", "Amarillo")),
                "d1" to listOf(file("b", "Yellow.gb"), file("c", "Azul.gb")),
            ),
            heads = mapOf("a" to rom("RED"), "b" to rom("YELLOW"), "c" to rom("BLUE")),
            rootId = "raiz",
        )
        val entries = LibraryScanner.scan(tree).associateBy { it.id }
        assertEquals("raiz", entries.getValue("Rojo.gb").folderDocumentId)
        assertEquals("d1", entries.getValue("Amarillo/Yellow.gb").folderDocumentId)
        assertEquals("d1", entries.getValue("Amarillo/Azul.gb").folderDocumentId)
    }

    @Test
    fun aTreeThatDoesNotKnowItsRootLeavesTheRootFolderUnknown() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(file("a", "Rojo.gb"))),
            heads = mapOf("a" to rom("RED")),
        )
        assertNull(LibraryScanner.scan(tree).single().folderDocumentId)
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

    // ---- A6-L3 (K20): fecha del .sav junto al ROM ----

    private fun sav(id: String, name: String, modified: Long?) =
        TreeNode(id, name, isDirectory = false, sizeBytes = 8192, lastModified = modified)

    @Test
    fun mirrorSaveDateComesFromTheSiblingSavInRootAndSubfolder() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), sav("as", "Rojo.sav", 1_000L), dir("d", "Sub"), file("n", "Sin.gb")),
                "d" to listOf(file("b", "Azul.GBC"), sav("bs", "AZUL.SAV", 2_000L)),
            ),
            heads = mapOf("a" to rom("RED"), "b" to rom("BLUE", cgb = 0x80), "n" to rom("NONE")),
        )
        val byId = LibraryScanner.scan(tree).associateBy { it.id }
        assertEquals(1_000L, byId.getValue("Rojo.gb").mirrorSaveDate)
        assertEquals(2_000L, byId.getValue("Sub/Azul.GBC").mirrorSaveDate)
        assertNull(byId.getValue("Sin.gb").mirrorSaveDate)
        assertEquals(3, byId.size) // el .sav no es una entrada de la biblioteca
    }

    @Test
    fun mirrorSaveDateIsNullWhenTheProviderHidesTheModificationDate() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(file("a", "Rojo.gb"), sav("as", "Rojo.sav", null))),
            heads = mapOf("a" to rom("RED")),
        )
        assertNull(LibraryScanner.scan(tree).single().mirrorSaveDate)
    }

    // ---- N1b: escaneo recursivo, nombres reservados (ND11), tope y consultas ----

    /** Cadena de carpetas `N1/N2/…/N<levels>` con un ROM en cada nivel (y otro en la raíz). */
    private fun chain(levels: Int): FakeTree {
        val dirs = HashMap<String?, List<TreeNode>>()
        val heads = HashMap<String, ByteArray>()
        dirs[null] = listOf(file("r0", "Raiz.gb"), dir("n1", "N1"))
        heads["r0"] = rom("RAIZ")
        for (level in 1..levels) {
            val inner = mutableListOf(file("r$level", "Nivel $level.gb"))
            if (level < levels) inner += dir("n${level + 1}", "N${level + 1}")
            dirs["n$level"] = inner
            heads["r$level"] = rom("NIVEL $level")
        }
        return FakeTree(dirs, heads, rootId = "raiz")
    }

    @Test
    fun scansFiveFolderLevelsAndNeverListsTheSixth() {
        val tree = chain(levels = 6)
        val result = LibraryScanner.scanDetailed(tree)
        val byTitle = result.entries.associateBy { it.title }
        assertEquals(LibraryScanner.MAX_FOLDER_DEPTH, 5)
        assertEquals((0..5).map { if (it == 0) "RAIZ" else "NIVEL $it" }.toSet(), byTitle.keys)
        assertEquals(listOf("N1", "N2", "N3", "N4", "N5"), byTitle.getValue("NIVEL 5").folderPath)
        assertEquals("N1/N2/N3/N4/N5/Nivel 5.gb", byTitle.getValue("NIVEL 5").id)
        assertEquals("n5", byTitle.getValue("NIVEL 5").folderDocumentId)
        assertEquals("raiz", byTitle.getValue("RAIZ").folderDocumentId)
        // La carpeta del sexto nivel ni siquiera se lista: en Drive sería una llamada de red inútil.
        assertFalse("n6" in tree.queried)
        assertEquals(listOf(null, "n1", "n2", "n3", "n4", "n5"), tree.queried)
        assertEquals(1, result.stats.tooDeepSkipped)
        assertEquals(6, result.stats.folderQueries)
        assertEquals(6, result.stats.headReads)
        assertTrue("demasiado profundo no hace incompleto el escaneo", result.stats.complete)
    }

    @Test
    fun reservedNamesFollowNd11() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(
                    file("a", "Rojo.gb"),
                    file("u", "_Borrador.gb"),
                    file("z", "Juegos.zip"),
                    file("h", ".Escondido.gb"),
                    dir("hidden", ".oculta"),
                    dir("aside", "_apartada"),
                    dir("app", "PocketGB"),
                    dir("poke", "Pokémon"),
                ),
                "hidden" to listOf(file("h1", "Nada.gb")),
                "aside" to listOf(file("s1", "Revisar.gb")),
                "app" to listOf(dir("inter", "Intercambio"), file("p1", "Exportado.gb")),
                "inter" to listOf(file("p2", "Paquete.gb")),
                "poke" to listOf(dir("gen2", "2ª generación"), dir("rev", "_Revisar"), dir("nested", "PocketGB"), dir("dot", ".cache")),
                "gen2" to listOf(file("g", "Oro.gbc")),
                "rev" to listOf(file("r", "Dudoso.gb")),
                "nested" to listOf(file("np", "Anidado.gb")),
                "dot" to listOf(file("d", "Cache.gb")),
            ),
            heads = mapOf(
                "a" to rom("RED"),
                "u" to rom("BORRADOR"),
                "g" to rom("GOLD", cgb = 0x80),
                "np" to rom("ANIDADO"),
            ),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertEquals(
            setOf("Rojo.gb", "_Borrador.gb", "Pokémon/2ª generación/Oro.gbc", "Pokémon/PocketGB/Anidado.gb"),
            result.entries.map { it.id }.toSet(),
        )
        // Ni las carpetas ocultas, ni las apartadas, ni la de la app en la raíz se consultan.
        assertTrue(tree.queried.none { it in setOf("hidden", "aside", "app", "inter", "rev", "dot") })
        assertEquals(listOf("Pokémon", "2ª generación"), result.entries.single { it.title == "GOLD" }.folderPath)
        assertEquals(1, result.stats.reservedSkipped)
        assertEquals(2, result.stats.setAsideSkipped)
        assertEquals(3, result.stats.hiddenSkipped)
        assertTrue(result.stats.complete)
    }

    @Test
    fun theEntryCapStopsTheScanAndMarksItIncomplete() {
        val many = (0 until LibraryScanner.MAX_SCAN_ENTRIES + 50).map { file("f$it", "Juego $it.gb") }
        val tree = FakeTree(
            dirs = mapOf(null to listOf(dir("late", "Zeta")) + many, "late" to listOf(file("zz", "Tarde.gb"))),
            heads = many.associate { it.id to rom(it.name.uppercase().take(16)) } + ("zz" to rom("TARDE")),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertTrue(result.stats.truncated)
        assertFalse(result.stats.complete)
        assertTrue(result.entries.size <= LibraryScanner.MAX_SCAN_ENTRIES)
        assertTrue("la carpeta que no cupo no se lista", "late" !in tree.queried)
    }

    @Test
    fun aFailingFolderDoesNotAbortTheRestButMakesTheScanIncomplete() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(dir("a", "A"), dir("b", "B")),
                "a" to listOf(dir("a1", "Hondo")),
                "a1" to listOf(file("x", "X.gb")),
                "b" to listOf(file("y", "Y.gb")),
            ),
            heads = mapOf("x" to rom("X"), "y" to rom("Y")),
            failingDirs = setOf("a1"),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertEquals(listOf("B/Y.gb"), result.entries.map { it.id })
        assertEquals(1, result.stats.folderErrors)
        assertFalse(result.stats.complete)
    }

    @Test
    fun aRevokedDeepFolderStillPropagates() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(dir("a", "A")), "a" to listOf(dir("b", "B")), "b" to emptyList()),
            revokedDirs = setOf("b"),
        )
        assertThrows(TreePermissionException::class.java) { LibraryScanner.scan(tree) }
    }

    @Test
    fun deepRepeatedPathsStillGetUniqueIdsAndKeepTheirFolderPath() {
        // Dos carpetas «Rojo» dentro de «Pokémon/Gen 1» (Drive lo permite), cada una con un «Juego.gb».
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(dir("p", "Pokémon")),
                "p" to listOf(dir("g", "Gen 1")),
                "g" to listOf(dir("r1", "Rojo"), dir("r2", "Rojo")),
                "r1" to listOf(file("j1", "Juego.gb")),
                "r2" to listOf(file("j2", "Juego.gb")),
            ),
            heads = mapOf("j1" to rom("UNO"), "j2" to rom("DOS")),
        )
        val entries = LibraryScanner.scan(tree)
        assertEquals(2, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.id.startsWith("Pokémon/Gen 1/Rojo/Juego.gb#") })
        assertTrue(entries.all { it.folderPath == listOf("Pokémon", "Gen 1", "Rojo") })
        assertEquals(setOf("r1", "r2"), entries.map { it.folderDocumentId }.toSet())
    }

    @Test
    fun theSiblingSaveDateAndTheRomStampWorkInDeepFolders() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(dir("a", "A")),
                "a" to listOf(dir("b", "B")),
                "b" to listOf(dir("c", "C")),
                "c" to listOf(file("r", "Rojo.gb", modified = 5_000L), sav("s", "Rojo.sav", 7_000L)),
            ),
            heads = mapOf("r" to rom("RED")),
        )
        val entry = LibraryScanner.scan(tree).single()
        assertEquals("A/B/C/Rojo.gb", entry.id)
        assertEquals(7_000L, entry.mirrorSaveDate)
        assertEquals(5_000L, entry.lastModified)
        assertEquals("c", entry.folderDocumentId)
    }

    @Test
    fun aFolderThatAppearsUnderTwoParentsIsListedOnce() {
        // Drive deja que una carpeta tenga dos padres: no se lista dos veces (ni se duplican sus juegos).
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(dir("a", "A"), dir("b", "B")),
                "a" to listOf(dir("shared", "Compartida")),
                "b" to listOf(dir("shared", "Compartida")),
                "shared" to listOf(file("x", "X.gb")),
            ),
            heads = mapOf("x" to rom("X")),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertEquals(1, result.entries.size)
        assertEquals(1, tree.queried.count { it == "shared" })
    }

    @Test
    fun cancellationIsCheckedBeforeEachFolderAndEachHeader() {
        val tree = chain(levels = 4)
        var checks = 0
        val error = assertThrows(IllegalStateException::class.java) {
            LibraryScanner.scan(tree, checkCancelled = { if (++checks > 2) throw IllegalStateException("cancelado") })
        }
        assertEquals("cancelado", error.message)
        assertEquals("se detiene sin listar el resto", 2, tree.queried.size)
    }

    // ---- N1-H1/H6: identidad de cabecera y caché ----

    @Test
    fun eachRomCarriesItsHeaderIdentityAndACachedHeaderIsNotReadAgain() {
        val dirs = mapOf(
            null to listOf(file("a", "Rojo.gb", modified = 10L), dir("d", "Sub")),
            "d" to listOf(file("b", "Oro.gbc", modified = 20L), file("c", "SinFecha.gb")),
        )
        val heads = mapOf("a" to rom("RED"), "b" to rom("GOLD", cgb = 0x80), "c" to rom("NODATE"))
        val first = LibraryScanner.scanDetailed(FakeTree(dirs, heads))
        assertEquals(3, first.stats.headReads)
        val red = first.entries.single { it.id == "Rojo.gb" }
        assertEquals(RomHeader.identity(rom("RED")), red.headerKey)
        assertEquals(56, red.headerKey!!.length)

        // Segundo escaneo con la caché de los sellos: solo se abre el que no tiene fecha (no se puede validar).
        val cache = HeaderCache.from(first.entries.map(DocumentStamp::of))
        val tree = FakeTree(dirs, heads)
        val second = LibraryScanner.scanDetailed(tree, headerCache = cache)
        assertEquals(1, second.stats.headReads)
        assertEquals(2, second.stats.headerCacheHits)
        assertEquals(first.entries.map { it.id to it.title }, second.entries.map { it.id to it.title })
        assertTrue(second.entries.single { it.id == "Sub/Oro.gbc" }.console == com.joelbermudez.pocketgb.library.RomConsole.GBC)
        assertEquals(red.headerKey, second.entries.single { it.id == "Rojo.gb" }.headerKey)

        // Si cambia el tamaño o la fecha, se vuelve a leer.
        val changed = mapOf(
            null to listOf(file("a", "Rojo.gb", size = 65536, modified = 10L), dir("d", "Sub")),
            "d" to listOf(file("b", "Oro.gbc", modified = 99L), file("c", "SinFecha.gb")),
        )
        assertEquals(3, LibraryScanner.scanDetailed(FakeTree(changed, heads), headerCache = cache).stats.headReads)
    }

    @Test
    fun aMalformedCachedHeaderIsIgnored() {
        val stamp = DocumentStamp("Rojo.gb", 32768, 10L, documentId = "a", header = "zz")
        val tree = FakeTree(mapOf(null to listOf(file("a", "Rojo.gb", modified = 10L))), mapOf("a" to rom("RED")))
        val result = LibraryScanner.scanDetailed(tree, headerCache = HeaderCache.from(listOf(stamp)))
        assertEquals(1, result.stats.headReads)
        assertEquals("RED", result.entries.single().title)
    }

    // ---- N1-H4: listados a medias ----

    @Test
    fun aFolderTheProviderIsStillLoadingIsUsedButTheScanIsIncomplete() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(file("a", "Rojo.gb"), dir("d", "Sub")),
                "d" to listOf(file("b", "Azul.gb")),
            ),
            heads = mapOf("a" to rom("RED"), "b" to rom("BLUE")),
            partialDirs = setOf("d"),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertEquals(setOf("Rojo.gb", "Sub/Azul.gb"), result.entries.map { it.id }.toSet())
        assertEquals(1, result.stats.folderErrors)
        assertFalse(result.stats.complete)
    }

    @Test
    fun aPartialRootIsAlsoIncompleteInsteadOfFailing() {
        val tree = FakeTree(
            dirs = mapOf(null to listOf(file("a", "Rojo.gb"))),
            heads = mapOf("a" to rom("RED")),
            partialDirs = setOf(null),
        )
        val result = LibraryScanner.scanDetailed(tree)
        assertEquals(listOf("Rojo.gb"), result.entries.map { it.id })
        assertFalse(result.stats.complete)
    }

    /** Paridad con iOS (auditoría final N): las copias en conflicto del `.sav` se ven al escanear, con su fecha. */
    @Test
    fun scanListsProviderConflictCopiesNextToEachRom() {
        val tree = FakeTree(
            dirs = mapOf(
                null to listOf(
                    file("z", "Zelda.gb"), file("zs", "Zelda.sav"),
                    file("z1", "Zelda (Joel's conflicted copy 2026-10-08).sav", modified = 5L),
                    file("z2", "Zelda 2.sav", modified = 6L),
                    file("t", "Tetris.gb"), file("t2", "Tetris 2.gba"), file("t2s", "Tetris 2.sav"),
                ),
            ),
            heads = mapOf("z" to rom("ZELDA"), "t" to rom("TETRIS")),
        )
        val entries = LibraryScanner.scan(tree)
        assertEquals(
            listOf("Zelda (Joel's conflicted copy 2026-10-08).sav" to 5L, "Zelda 2.sav" to 6L),
            entries.single { it.fileName == "Zelda.gb" }.conflictCopies,
        )
        assertEquals("`Tetris 2.sav` es de `Tetris 2.gba`", emptyList<Pair<String, Long?>>(), entries.single { it.fileName == "Tetris.gb" }.conflictCopies)
        val byFp = scannedConflictsByFingerprint(entries, mapOf("Zelda.gb" to "fz", "Tetris.gb" to "ft"))
        assertEquals(listOf("Zelda (Joel's conflicted copy 2026-10-08).sav", "Zelda 2.sav"), byFp["fz"]!!.map { it.name })
        assertEquals(emptyList<String>(), byFp["ft"]!!.map { it.name })
    }
}
