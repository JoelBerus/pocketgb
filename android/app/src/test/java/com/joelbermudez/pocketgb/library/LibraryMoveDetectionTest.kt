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

    private fun rom(
        id: String,
        size: Long? = 32768,
        modified: Long? = 1_000L,
        name: String = id.substringAfterLast('/'),
        documentId: String? = null,
    ) = RomEntry(
        id, "content://$id", name, name.substringBeforeLast('.').uppercase(), false, size ?: 0L, true, null,
        lastModified = modified, documentId = documentId,
    )

    private val fp = "ab".repeat(32)

    /** Preferencias tras un primer escaneo de [entries] (sellos guardados). */
    private fun scanned(vararg entries: RomEntry, base: LibraryPreferencesData = LibraryPreferencesData()) =
        base.reconciled(entries.toList(), complete = true)

    @Test
    fun theFirstScanRecordsTheStampOfEveryPath() {
        val prefs = scanned(rom("Rojo.gb", 1024, 5L), rom("A/Azul.gb", null, null))
        assertEquals(DocumentStamp("Rojo.gb", 1024, 5L), prefs.documents["Rojo.gb"])
        assertEquals(DocumentStamp("Azul.gb", null, null), prefs.documents["A/Azul.gb"])
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
    fun aRenamedFileIsRecognisedBySizeAndDateWhenThereIsNoAmbiguity() {
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
        assertTrue(old.id in after.documents && "B/Azul.gb" in after.documents && moved.id in after.documents)
    }

    @Test
    fun anEmptyListingChangesNothing() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertSame(prefs, prefs.reconciled(emptyList(), complete = true))
    }

    @Test
    fun aCompleteScanForgetsTheFingerprintOfAPathThatIsGoneButKeepsTheMetadata() {
        val old = rom("Rojo.gb")
        val prefs = scanned(old, rom("Otro.gb", 1)).recordFingerprint(old.id, fp).toggleFavorite(old)
        val after = prefs.reconciled(listOf(rom("Otro.gb", 1)), complete = true)
        assertNull(after.fingerprints[old.id])
        assertFalse(old.id in after.documents)
        assertTrue("el favorito por huella no se pierde", fp in after.favoriteFingerprints)
    }

    @Test
    fun theSamePathWithAnotherSizeOrDateForgetsItsFingerprintUntilItIsReadAgain() {
        val old = rom("Rojo.gb", 32768, 1_000L)
        val prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old)
        val replaced = rom("Rojo.gb", 65536, 9_000L)
        val after = prefs.reconciled(listOf(replaced), complete = true)
        assertNull("puede ser otro ROM con el mismo nombre", after.fingerprints[replaced.id])
        assertFalse(after.isFavorite(replaced))
        assertTrue(fp in after.favoriteFingerprints)
        assertEquals(DocumentStamp("Rojo.gb", 65536, 9_000L), after.documents[replaced.id])
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
        val previous = mapOf(
            "a" to DocumentStamp("x.gb", 10, 1),
            "b" to DocumentStamp("y.gb", 20, 2),
            "e" to DocumentStamp("e.gb", 30, 3),
            "same" to DocumentStamp("s.gb", 40, 4),
        )
        val current = mapOf(
            "c" to DocumentStamp("x.gb", 10, 1), // a, mismo sello: movido
            "d" to DocumentStamp("z.gb", 20, 2), // b renombrado (tamaño y fecha únicos)
            "b2" to DocumentStamp("y.gb", 20, 3), // otra fecha: otro documento
            "f" to DocumentStamp("f.gb", 30, 3), // e renombrado... pero hay dos candidatos
            "g" to DocumentStamp("g.gb", 30, 3),
            "same" to DocumentStamp("s.gb", 40, 4), // sigue en su sitio
        )
        assertEquals(mapOf("a" to "c", "b" to "d"), MoveDetection.detect(previous, current))
    }

    @Test
    fun anotherDocumentAtTheSamePathWithTheSameNameSizeAndDateDoesNotInheritTheFingerprint() {
        // Drive: se borra «Rojo.gb» y se sube otro ROM con el mismo nombre, tamaño y fecha: otro id de documento.
        val old = rom("Rojo.gb", documentId = "drive:1AbC")
        val prefs = scanned(old).recordFingerprint(old.id, fp).toggleFavorite(old)
        val other = rom("Rojo.gb", documentId = "drive:9XyZ")
        val after = prefs.reconciled(listOf(other), complete = true)
        assertNull(after.fingerprints[other.id])
        assertFalse(after.isFavorite(other))
        assertTrue("lo guardado por huella sigue ahí", fp in after.favoriteFingerprints)
    }

    @Test
    fun theSameDocumentIdAtTheSamePathKeepsTheFingerprint() {
        val old = rom("Rojo.gb", documentId = "drive:1AbC")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        assertEquals(fp, prefs.reconciled(listOf(rom("Rojo.gb", documentId = "drive:1AbC")), complete = true).fingerprints["Rojo.gb"])
    }

    @Test
    fun aMoveIsStillRecognisedWhenTheProviderChangesTheDocumentIdWithThePath() {
        // ExternalStorage: el id es la ruta, así que cambia al mover; el sello (nombre, tamaño, fecha) sigue valiendo.
        val old = rom("A/Rojo.gb", documentId = "primary:Roms/A/Rojo.gb")
        val prefs = scanned(old).recordFingerprint(old.id, fp)
        val moved = rom("B/Rojo.gb", documentId = "primary:Roms/B/Rojo.gb")
        assertEquals(fp, prefs.reconciled(listOf(moved), complete = true).fingerprints[moved.id])
    }
}
