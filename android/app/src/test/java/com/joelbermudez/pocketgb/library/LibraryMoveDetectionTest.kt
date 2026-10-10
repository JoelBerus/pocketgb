package com.joelbermudez.pocketgb.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N1a · caché por documento y detección de movimientos sin leer el ROM: una ruta que desaparece y UNA ruta nueva con
 * el mismo sello (nombre, tamaño, fecha) son el mismo documento; se trasladan su huella y sus registros por ruta.
 */
class LibraryMoveDetectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Identidad de cabecera de prueba (56 hex): por defecto la misma para todos, salvo que el test diga otra. */
    private fun header(tag: Int) = "%02x".format(tag).repeat(28)
    private val red = header(0x52)

    private fun rom(
        id: String,
        size: Long? = 32768,
        modified: Long? = 1_000L,
        name: String = id.substringAfterLast('/'),
        documentId: String? = null,
        header: String? = red,
    ) = RomEntry(
        id, "content://$id", name, name.substringBeforeLast('.').uppercase(), com.joelbermudez.pocketgb.library.RomConsole.GB, size ?: 0L, true, null,
        lastModified = modified, documentId = documentId, headerKey = header,
    )

    private val fp = "ab".repeat(32)

    /** Preferencias tras un primer escaneo de [entries] (sellos guardados). */
    private fun scanned(vararg entries: RomEntry, base: LibraryPreferencesData = LibraryPreferencesData()) =
        base.reconciled(entries.toList(), complete = true)

    @Test
    fun theFirstScanRecordsTheStampOfEveryPath() {
        val prefs = scanned(rom("Rojo.gb", 1024, 5L), rom("A/Azul.gb", null, null))
        assertEquals(DocumentStamp("Rojo.gb", 1024, 5L, header = red), prefs.documents["Rojo.gb"])
        assertEquals(DocumentStamp("Azul.gb", null, null, header = red), prefs.documents["A/Azul.gb"])
        assertTrue(prefs.documents.getValue("Rojo.gb").isComplete)
        assertFalse(prefs.documents.getValue("A/Azul.gb").isComplete)
    }

    @Test
    fun aMoveWithTheSameNameSizeAndDateCarriesTheFingerprintAndEveryPathRecord() {
        val old = rom("Pokémon/Rojo.gb")
        var prefs = scanned(old, rom("Otro.gb", 999))
            .recordFingerprint(old.id, fp)
            .toggleFavorite(old)
            .setAlias(old, "Rojo de Joel")
            .hide(old)
            .acknowledge(setOf(old.id))
        val moved = rom("Pokémon/Gen 1/Rojo.gb")
        prefs = prefs.reconciled(listOf(moved, rom("Otro.gb", 999)), complete = true)
        assertEquals(fp, prefs.fingerprints[moved.id])
        assertNull(prefs.fingerprints[old.id])
        assertTrue(prefs.isFavorite(moved))
        assertEquals("Rojo de Joel", prefs.displayTitle(moved))
        assertTrue(prefs.isHidden(moved))
        assertTrue("no vuelve a ser «Nuevo»", moved.id in prefs.knownIds && old.id !in prefs.knownIds)
        assertEquals(setOf(moved.id, "Otro.gb"), prefs.documents.keys)
    }

    @Test
    fun withoutAFingerprintThePathRecordsStillMove() {
        val old = rom("Rojo.gb")
        var prefs = scanned(old)
            .toggleFavorite(old)
            .setAlias(old, "Mi Rojo")
            .hide(old)
            .copy(lastPlayed = mapOf(old.id to 77L))
        val moved = rom("Sub/Rojo.gb")
        prefs = prefs.reconciled(listOf(moved), complete = true)
        assertEquals(setOf(moved.id), prefs.favorites)
        assertEquals(mapOf(moved.id to "Mi Rojo"), prefs.aliasesByPath)
        assertEquals(setOf(moved.id), prefs.hiddenPaths)
        assertEquals(mapOf(moved.id to 77L), prefs.lastPlayed)
        assertNull(prefs.fingerprints[moved.id])
    }

    @Test
    fun aRenamedFileIsRecognisedBySizeDateAndHeaderWhenThereIsNoAmbiguity() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        val renamed = rom("Pokémon/Pokemon Red.gb")
        assertEquals(fp, prefs.reconciled(listOf(renamed), complete = true).fingerprints[renamed.id])
    }

    @Test
    fun twoCandidatesForOneDisappearedPathTransferNothing() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old)
        val a = rom("A/Rojo.gb")
        val b = rom("B/Rojo.gb")
        val after = prefs.reconciled(listOf(a, b), complete = true)
        assertNull(after.fingerprints[a.id])
        assertNull(after.fingerprints[b.id])
        assertTrue("el favorito por huella sigue ahí, se recupera al abrir", fp in after.favoriteFingerprints)
        assertTrue(after.recordFingerprint(a.id, fp).isFavorite(a))
    }

    @Test
    fun twoDisappearedPathsWithTheSameStampTransferNothing() {
        val one = rom("A/Rojo.gb")
        val two = rom("B/Rojo.gb")
        val prefs = scanned(one, two).recordFingerprint(one.id, fp).recordFingerprint(two.id, "cd".repeat(32))
        val moved = rom("C/Rojo.gb")
        assertNull(prefs.reconciled(listOf(moved), complete = true).fingerprints[moved.id])
    }

    @Test
    fun aRenameIsNotGuessedWhenAnotherFileSharesSizeAndDate() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        // Desaparece «Rojo.gb» y aparecen dos nombres nuevos con el mismo tamaño y fecha: no se adivina.
        val after = prefs.reconciled(listOf(rom("Pokemon Red.gb"), rom("Copia de Red.gb")), complete = true)
        assertTrue(after.fingerprints.isEmpty())
    }

    @Test
    fun anExactMatchWinsOverARenameCandidateWithTheSameSizeAndDate() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        val sameName = rom("Sub/Rojo.gb")
        val after = prefs.reconciled(listOf(sameName, rom("Sub/Otro nombre.gb")), complete = true)
        assertEquals(fp, after.fingerprints[sameName.id])
        assertNull(after.fingerprints["Sub/Otro nombre.gb"])
    }

    @Test
    fun withoutSizeOrDateFromTheProviderNothingMoves() {
        for ((size, modified) in listOf<Pair<Long?, Long?>>(null to 1_000L, 32768L to null, null to null)) {
            val old = rom("Rojo.gb", size, modified)
            val prefs = scanned(old).recordFingerprint(old.id, fp)
            val moved = rom("Sub/Rojo.gb", size, modified)
            assertNull("size=$size modified=$modified", prefs.reconciled(listOf(moved), complete = true).fingerprints[moved.id])
        }
        // Sin cabecera (documento remoto sin descargar) tampoco (N1-H1).
        val old = rom("Rojo.gb", header = null)
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertNull(prefs.reconciled(listOf(rom("Sub/Rojo.gb", header = null)), complete = true).fingerprints["Sub/Rojo.gb"])
    }

    @Test
    fun aDifferentSizeOrDateIsAnotherDocument() {
        val old = rom("Rojo.gb", 32768, 1_000L)
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertNull(prefs.reconciled(listOf(rom("Sub/Rojo.gb", 65536, 1_000L)), complete = true).fingerprints["Sub/Rojo.gb"])
        assertNull(prefs.reconciled(listOf(rom("Sub/Rojo.gb", 32768, 2_000L)), complete = true).fingerprints["Sub/Rojo.gb"])
    }

    @Test
    fun anIncompleteScanNeitherMovesNorForgets() {
        val old = rom("A/Rojo.gb")
        val prefs = scanned(old, rom("B/Azul.gb")).recordFingerprint(old.id, fp)
        val moved = rom("C/Rojo.gb")
        val after = prefs.reconciled(listOf(moved), complete = false)
        assertNull("una carpeta falló: «A/Rojo.gb» puede seguir ahí", after.fingerprints[moved.id])
        assertEquals(fp, after.fingerprints[old.id])
        assertTrue(old.id in after.documents && "B/Azul.gb" in after.documents)
        assertFalse("N1-V2-H2: la ruta nueva no se anota hasta un escaneo completo", moved.id in after.documents)
    }

    @Test
    fun anEmptyListingChangesNothing() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertSame(prefs, prefs.reconciled(emptyList(), complete = true))
    }

    @Test
    fun aCompleteScanForgetsTheFingerprintOfAPathThatIsGoneButKeepsTheMetadataAndATombstone() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old, rom("Otro.gb", 1)).recordFingerprint(old.id, fp).toggleFavorite(old)
        val after = prefs.reconciled(listOf(rom("Otro.gb", 1)), complete = true, now = 5_000L)
        assertNull(after.fingerprints[old.id])
        assertFalse(old.id in after.documents)
        assertTrue("el favorito por huella no se pierde", fp in after.favoriteFingerprints)
        assertEquals(listOf(Tombstone(old.id, DocumentStamp.of(old), fp, known = false, goneAt = 5_000L)), after.tombstones)
    }

    @Test
    fun theSamePathWithAnotherSizeOrHeaderForgetsItsFingerprintUntilItIsReadAgain() {
        val old = rom("Rojo.gb", 32768, 1_000L)
        val prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old)
        for (replaced in listOf(rom("Rojo.gb", 65536, 9_000L), rom("Rojo.gb", 32768, 1_000L, header = header(0x42)))) {
            val after = prefs.reconciled(listOf(replaced), complete = true)
            assertNull("puede ser otro ROM con el mismo nombre", after.fingerprints[replaced.id])
            assertFalse(after.isFavorite(replaced))
            assertTrue(fp in after.favoriteFingerprints)
            assertEquals(DocumentStamp.of(replaced), after.documents[replaced.id])
        }
    }

    @Test
    fun theSamePathWithOnlyAnotherDateOrDocumentIdKeepsItsFingerprint() {
        // N1-H4: Drive puede cambiar la fecha o el id sin tocar el archivo; mismo tamaño y cabecera = mismo contenido.
        val old = rom("Rojo.gb", 32768, 1_000L, documentId = "drive:1")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        for (same in listOf(rom("Rojo.gb", 32768, 9_000L, documentId = "drive:1"), rom("Rojo.gb", 32768, 1_000L, documentId = "drive:2"))) {
            val after = prefs.reconciled(listOf(same), complete = true)
            assertEquals(fp, after.fingerprints[same.id])
            assertFalse("N1-V2-H1: pasa a sin confirmar", after.hasConfirmedFingerprint(same))
        }
    }

    @Test
    fun reconcilingTheSameListingTwiceIsANoOp() {
        val entries = listOf(rom("Rojo.gb"), rom("A/Azul.gb", 4096, 7L))
        val prefs = LibraryPreferencesData().reconciled(entries, complete = true).recordFingerprint("Rojo.gb", fp)
        assertEquals(prefs, prefs.reconciled(entries, complete = true))
    }

    @Test
    fun twoMovesInOneScanAreBothDetected() {
        val red = rom("Rojo.gb", 100)
        val blue = rom("Azul.gb", 200)
        val prefs = scanned(red, blue).recordFingerprint(red.id, fp).recordFingerprint(blue.id, "cd".repeat(32))
        val after = prefs.reconciled(listOf(rom("Gen 1/Rojo.gb", 100), rom("Gen 1/Azul.gb", 200)), complete = true)
        assertEquals(fp, after.fingerprints["Gen 1/Rojo.gb"])
        assertEquals("cd".repeat(32), after.fingerprints["Gen 1/Azul.gb"])
    }

    @Test
    fun theStampCacheSurvivesARoundTripOnDisk() {
        val file = LibraryPreferencesFile(File(tmp.root, "p.json"))
        val prefs = scanned(rom("Rojo.gb"), rom("A/Azul.gb", null, null)).recordFingerprint("Rojo.gb", fp)
        file.save(prefs)
        assertEquals(prefs, file.load())
    }

    @Test
    fun detectionIsPureOnStamps() {
        val h = red
        val previous = mapOf(
            "a" to DocumentStamp("x.gb", 10, 1, header = h),
            "b" to DocumentStamp("y.gb", 20, 2, header = h),
            "e" to DocumentStamp("e.gb", 30, 3, header = h),
            "same" to DocumentStamp("s.gb", 40, 4, header = h),
            "otro" to DocumentStamp("o.gb", 50, 5, header = h),
        )
        val current = mapOf(
            "c" to DocumentStamp("x.gb", 10, 1, header = h), // a, mismo sello: movido
            "d" to DocumentStamp("z.gb", 20, 2, header = h), // b renombrado (tamaño, fecha y cabecera únicos)
            "b2" to DocumentStamp("y.gb", 20, 3, header = h), // otra fecha: otro documento
            "f" to DocumentStamp("f.gb", 30, 3, header = h), // e renombrado... pero hay dos candidatos
            "g" to DocumentStamp("g.gb", 30, 3, header = h),
            "same" to DocumentStamp("s.gb", 40, 4, header = h), // sigue en su sitio
            "p" to DocumentStamp("p.gb", 50, 5, header = header(0x01)), // mismo tamaño y fecha que «otro», otra cabecera
        )
        assertEquals(mapOf("a" to "c", "b" to "d"), MoveDetection.detect(previous, current))
    }

    @Test
    fun anotherRomAtTheSamePathWithTheSameNameSizeAndDateButAnotherHeaderDoesNotInheritTheFingerprint() {
        // Se borra «Rojo.gb» y se sube otro ROM con el mismo nombre, tamaño y fecha: la cabecera lo delata.
        val old = rom("Rojo.gb", documentId = "drive:1AbC")
        val prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old)
        val other = rom("Rojo.gb", documentId = "drive:9XyZ", header = header(0x42))
        val after = prefs.reconciled(listOf(other), complete = true)
        assertNull(after.fingerprints[other.id])
        assertFalse(after.isFavorite(other))
        assertTrue("lo guardado por huella sigue ahí", fp in after.favoriteFingerprints)
    }

    @Test
    fun aMoveIsStillRecognisedWhenTheProviderChangesTheDocumentIdWithThePath() {
        // ExternalStorage: el id es la ruta, así que cambia al mover; el sello (nombre, tamaño, fecha) sigue valiendo.
        val old = rom("A/Rojo.gb", documentId = "primary:Roms/A/Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        val moved = rom("B/Rojo.gb", documentId = "primary:Roms/B/Rojo.gb")
        assertEquals(fp, prefs.reconciled(listOf(moved), complete = true).fingerprints[moved.id])
    }

    // ---- N1-H1: identidad de cabecera ----

    @Test
    fun aDeletedRomAndADifferentNewRomWithTheSameSizeAndDateShareNothing() {
        // Sonda de la auditoría: se borra Rojo (huella, alias, oculto, favorito) y aparece Azul con el mismo tamaño y fecha.
        val rojo = rom("Pokemon Red.gb", 1_048_576, 7_000L)
        var prefs = scanned(rojo).recordFingerprint(rojo.id, fp).setAlias(rojo, "Rojo de Joel").hide(rojo).toggleFavorite(rojo)
        val azul = rom("Pokémon/Pokemon Blue.gb", 1_048_576, 7_000L, header = header(0x42))
        prefs = prefs.reconciled(listOf(azul), complete = true)
        assertNull("azul no hereda la huella", prefs.fingerprints[azul.id])
        assertEquals("POKEMON BLUE", prefs.displayTitle(azul))
        assertFalse(prefs.isHidden(azul))
        assertFalse(prefs.isFavorite(azul))
        assertTrue(prefs.inferredFingerprints.isEmpty())
        // Lo mismo con el mismo nombre (regla 1).
        val prefs2 = scanned(rojo).recordFingerprint(rojo.id, fp)
        val impostor = rom("Otra/Pokemon Red.gb", 1_048_576, 7_000L, header = header(0x42))
        assertNull(prefs2.reconciled(listOf(impostor), complete = true).fingerprints[impostor.id])
    }

    @Test
    fun aMovedFingerprintStaysUnconfirmedUntilTheRomIsRead() {
        val old = rom("A/Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertTrue(prefs.hasConfirmedFingerprint(old))
        val moved = rom("B/Rojo.gb")
        val after = prefs.reconciled(listOf(moved), complete = true)
        assertEquals(fp, after.fingerprints[moved.id])
        assertFalse("heredada sin leer el ROM", after.hasConfirmedFingerprint(moved))
        assertEquals(setOf(moved.id), after.inferredFingerprints)
        val confirmed = after.recordFingerprint(moved.id, fp)
        assertTrue(confirmed.hasConfirmedFingerprint(moved))
        assertTrue(confirmed.inferredFingerprints.isEmpty())
    }

    // ---- N1-H4: lápidas ----

    @Test
    fun aGameThatDisappearsInACompleteScanAndComesBackKeepsItsFavoriteAndIsNotNew() {
        // Sonda de la auditoría: A y B (B favorito) → escaneo solo con A (Drive dio la carpeta vacía) → A y B.
        val a = rom("A.gb", 100)
        val b = rom("Sub/B.gb", 200, header = header(0x42))
        val fpB = "cd".repeat(32)
        var prefs = scanned(a, b).recordFingerprint(b.id, fpB).toggleFavorite(b).acknowledge(setOf(a.id, b.id))
        prefs = prefs.reconciled(listOf(a), complete = true).let { it.copy(knownIds = it.knownIds - b.id) } // como markNew
        assertNull(prefs.fingerprints[b.id])
        prefs = prefs.reconciled(listOf(a, b), complete = true)
        assertEquals(fpB, prefs.fingerprints[b.id])
        assertTrue(prefs.isFavorite(b))
        assertTrue("no vuelve a ser «Nuevo»", b.id in prefs.knownIds)
        assertTrue("la lápida se gasta", prefs.tombstones.isEmpty())
        assertFalse(prefs.hasConfirmedFingerprint(b))
    }

    @Test
    fun theSamePathComingBackWithANewDateStillRecoversButAnotherHeaderDoesNot() {
        val b = rom("B.gb", 200, 1_000L)
        val prefs = scanned(b, rom("A.gb", 1)).recordFingerprint(b.id, fp)
            .reconciled(listOf(rom("A.gb", 1)), complete = true)
        assertEquals(fp, prefs.reconciled(listOf(rom("A.gb", 1), rom("B.gb", 200, 8_000L)), complete = true).fingerprints["B.gb"])
        assertNull(prefs.reconciled(listOf(rom("A.gb", 1), rom("B.gb", 200, 1_000L, header = header(0x42))), complete = true).fingerprints["B.gb"])
    }

    @Test
    fun aGameSetAsideAndBroughtBackToAnotherFolderRecoversItsDataAndPathRecords() {
        // Apartado en `_Revisar/` (no se escanea) y devuelto más tarde a otra carpeta.
        val old = rom("Pokémon/Rojo.gb")
        var prefs = scanned(old, rom("Otro.gb", 1)).setAlias(old, "Mi Rojo").recordFingerprint(old.id, fp).toggleFavorite(old)
        prefs = prefs.reconciled(listOf(rom("Otro.gb", 1)), complete = true)
        val back = rom("Clásicos/Rojo.gb")
        prefs = prefs.reconciled(listOf(rom("Otro.gb", 1), back), complete = true)
        assertEquals(fp, prefs.fingerprints[back.id])
        assertTrue(prefs.isFavorite(back))
        assertEquals("Mi Rojo", prefs.displayTitle(back))
    }

    @Test
    fun anIncompleteScanLeavesNoTombstonesAndTombstonesAreBounded() {
        val old = rom("Rojo.gb")
        val base = scanned(old, rom("Otro.gb", 1)).recordFingerprint(old.id, fp)
        assertTrue(base.reconciled(listOf(rom("Otro.gb", 1)), complete = false).tombstones.isEmpty())
        // Más de 30 días: se descarta.
        val withTomb = base.reconciled(listOf(rom("Otro.gb", 1)), complete = true, now = 1_000L)
        assertEquals(1, withTomb.tombstones.size)
        assertTrue(withTomb.reconciled(listOf(rom("Otro.gb", 1)), complete = true, now = 1_000L + Tombstones.MAX_AGE_MS + 1).tombstones.isEmpty())
        // Como mucho MAX_ENTRIES, las más recientes.
        val many = (0 until Tombstones.MAX_ENTRIES + 20).map { Tombstone("g$it.gb", DocumentStamp("g$it.gb", 1, 1, header = red), null, false, goneAt = it.toLong()) }
        val bounded = LibraryPreferencesData(tombstones = many).reconciled(listOf(rom("Otro.gb", 1)), complete = true, now = 300L)
        assertEquals(Tombstones.MAX_ENTRIES, bounded.tombstones.size)
        assertEquals(Tombstones.MAX_ENTRIES + 19L, bounded.tombstones.first().goneAt)
    }

    // ---- N1-V2: segunda vuelta de la auditoría (sondas convertidas en tests) ----

    @Test
    fun aScanWithoutHeaderKeepsTheLastHeaderUnconfirmsTheFingerprintAndAnotherRomStillLosesIt() {
        // PROBE1: Rojo.gb con huella y alias → un escaneo sin cabecera (sin descargar) → otro ROM del mismo tamaño.
        val rojo = rom("Rojo.gb", 32768, 1_000L, documentId = "d1")
        var prefs = scanned(rojo).recordFingerprint(rojo.id, fp).setAlias(rojo, "Rojo de Joel")
        prefs = prefs.reconciled(listOf(rom("Rojo.gb", 32768, 1_000L, documentId = "d1", header = null)), complete = true)
        assertEquals("la huella sigue", fp, prefs.fingerprints["Rojo.gb"])
        assertFalse("pero sin confirmar: detalle y ajustes releen el ROM", prefs.hasConfirmedFingerprint(rojo))
        val stamp = prefs.documents.getValue("Rojo.gb")
        assertEquals("se conserva la cabecera anterior", red, stamp.header)
        assertTrue(stamp.headerCarried)
        assertTrue("no alimenta la caché de cabeceras", HeaderCache.from(prefs.documents.values).lookup(
            TreeNode("d1", "Rojo.gb", isDirectory = false, sizeBytes = 32768, lastModified = 1_000L),
        ) == null)
        val otro = rom("Rojo.gb", 32768, 2_000L, documentId = "d2", header = header(0x42))
        val after = prefs.reconciled(listOf(otro), complete = true)
        assertNull("la cabecera conservada delata al otro ROM", after.fingerprints[otro.id])
        assertEquals("ROJO", after.displayTitle(otro))
    }

    @Test
    fun aMoveFirstSeenInAnIncompleteScanIsRecognisedByTheNextCompleteOne() {
        // PROBE2: A/Rojo.gb → escaneo incompleto que ya ve B/Rojo.gb (la carpeta A falló) → escaneo completo.
        val old = rom("A/Rojo.gb")
        var prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old).acknowledge(setOf(old.id))
        val moved = rom("B/Rojo.gb")
        prefs = prefs.reconciled(listOf(moved), complete = false)
        prefs = prefs.reconciled(listOf(moved), complete = true)
        assertEquals("huella trasladada", fp, prefs.fingerprints[moved.id])
        assertTrue(prefs.isFavorite(moved))
        assertTrue("no es «Nuevo»", moved.id in prefs.knownIds)
    }

    @Test
    fun aMoveDoesNotOverwriteAFingerprintConfirmedInTheMeantime() {
        val old = rom("A/Rojo.gb")
        var prefs = scanned(old).recordFingerprint(old.id, fp)
        val moved = rom("B/Rojo.gb")
        prefs = prefs.reconciled(listOf(moved), complete = false).recordFingerprint(moved.id, "cd".repeat(32)) // se abrió
        prefs = prefs.reconciled(listOf(moved), complete = true)
        assertEquals("cd".repeat(32), prefs.fingerprints[moved.id])
        assertTrue(prefs.hasConfirmedFingerprint(moved))
    }

    @Test
    fun pathRecordsOfARomReplacedInPlaceGoToItsTombstoneAndComeBackWithIt() {
        // PROBE4a: favorito, alias y oculto sin huella en X.gb; otro ROM ocupa X.gb (otra cabecera).
        val x = rom("X.gb", 32768, 1_000L)
        var prefs = scanned(x).toggleFavorite(x).setAlias(x, "Rojo de Joel").hide(x).acknowledge(setOf(x.id))
        val other = rom("X.gb", 32768, 2_000L, header = header(0x42))
        prefs = prefs.reconciled(listOf(other), complete = true)
        assertFalse(prefs.isFavorite(other))
        assertFalse(prefs.isHidden(other))
        assertEquals("X", prefs.displayTitle(other))
        assertFalse("otro ROM: «Nuevo»", other.id in prefs.knownIds)
        val tomb = prefs.tombstones.single()
        assertTrue(tomb.favorite && tomb.hidden && tomb.alias == "Rojo de Joel")
        // Al abrir el otro ROM nada se migra a su huella.
        val opened = prefs.recordFingerprint(other.id, "cd".repeat(32))
        assertTrue(opened.hiddenFingerprints.isEmpty() && opened.favoriteFingerprints.isEmpty() && opened.aliasesByFingerprint.isEmpty())
        // Vuelve el original a X.gb: recupera lo suyo.
        prefs = prefs.reconciled(listOf(x), complete = true)
        assertTrue(prefs.isFavorite(x))
        assertTrue(prefs.isHidden(x))
        assertEquals("Rojo de Joel", prefs.displayTitle(x))
        assertTrue(x.id in prefs.knownIds)
    }

    @Test
    fun aHiddenRomWithoutFingerprintThatIsDeletedDoesNotHideTheNextRomAtItsPath() {
        // PROBE4b: oculto sin huella → borrado → otro ROM con el mismo nombre.
        val x = rom("X.gb", 32768, 1_000L)
        var prefs = scanned(x, rom("Y.gb", 1)).hide(x).acknowledge(setOf(x.id, "Y.gb"))
        prefs = prefs.reconciled(listOf(rom("Y.gb", 1)), complete = true).let { it.copy(knownIds = it.knownIds - x.id) }
        assertTrue("el oculto se va con la lápida", prefs.hiddenPaths.isEmpty())
        val next = rom("X.gb", 65536, 5_000L, header = header(0x42))
        prefs = prefs.reconciled(listOf(rom("Y.gb", 1), next), complete = true)
        assertFalse(prefs.isHidden(next))
        assertFalse(next.id in prefs.knownIds)
        // Y el mismo X de antes, si vuelve, sí sale oculto.
        val back = scanned(x, rom("Y.gb", 1)).hide(x)
            .reconciled(listOf(rom("Y.gb", 1)), complete = true)
            .reconciled(listOf(rom("Y.gb", 1), x), complete = true)
        assertTrue(back.isHidden(x))
    }

    @Test
    fun aTombstoneWithoutHeaderMatchesTheSamePathBySizeAndDate() {
        val x = rom("X.gb", 32768, 1_000L, header = null)
        val prefs = scanned(x, rom("Y.gb", 1)).toggleFavorite(x)
            .reconciled(listOf(rom("Y.gb", 1)), complete = true)
        assertTrue(prefs.reconciled(listOf(rom("Y.gb", 1), x), complete = true).isFavorite(x))
        assertFalse(prefs.reconciled(listOf(rom("Y.gb", 1), rom("X.gb", 32768, 9_000L, header = null)), complete = true).isFavorite(x))
    }
}
