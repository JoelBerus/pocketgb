package com.joelbermudez.pocketgb.library

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N1a · identidad: favoritos y «jugado» pasan a la huella cuando se conoce (la ruta es solo provisional), con migración
 * sin pérdida desde los `preferences.json` de A8 y A9 y versión de formato.
 */
class LibraryIdentityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rom(id: String, title: String = id) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, false, 32768, true, null)

    private val fpRed = "9d4c1e07b3a85f26c0de91ab47f3825e6b10c9d7a2f45e83b6c1d09e7f2a4b58"
    private val fpYellow = "5a2f9b315a2f9b315a2f9b315a2f9b315a2f9b315a2f9b315a2f9b315a2f9b31"
    private val red = rom("Pokemon Red.gb", "POKEMON RED")
    private val yellow = rom("Amarillo/Pokemon Yellow.gbc", "POKEMON YELLOW")
    private val unknown = rom("Sin huella.gb", "SIN HUELLA")
    private val broken = rom("Roto.gb", "ROTO")

    // ---- favoritos y jugado por huella ----

    @Test
    fun aFavoriteWithAKnownFingerprintIsStoredByFingerprintAndFollowsTheRom() {
        val prefs = LibraryPreferencesData().recordFingerprint(red.id, fpRed).toggleFavorite(red)
        assertEquals(setOf(fpRed), prefs.favoriteFingerprints)
        assertTrue("la ruta no guarda nada", prefs.favorites.isEmpty())
        val moved = rom("Pokémon/Gen 1/Pokemon Red.gb", "POKEMON RED")
        assertFalse("sin huella conocida en la ruta nueva aún no se sabe", prefs.isFavorite(moved))
        assertTrue(prefs.recordFingerprint(moved.id, fpRed).isFavorite(moved))
    }

    @Test
    fun withoutAFingerprintTheFavoriteIsProvisionalByPathAndMigratesWhenKnown() {
        var prefs = LibraryPreferencesData().toggleFavorite(unknown)
        assertEquals(setOf(unknown.id), prefs.favorites)
        assertTrue(prefs.isFavorite(unknown))
        prefs = prefs.recordFingerprint(unknown.id, "fp-x")
        assertTrue(prefs.favorites.isEmpty())
        assertEquals(setOf("fp-x"), prefs.favoriteFingerprints)
        assertTrue(prefs.isFavorite(unknown))
    }

    @Test
    fun removingAFavoriteClearsBothThePathAndTheFingerprint() {
        // Un archivo de A8 puede traer el favorito por ruta además de por huella (tras la migración ya no).
        val prefs = LibraryPreferencesData(favorites = setOf(red.id), favoriteFingerprints = setOf(fpRed), fingerprints = mapOf(red.id to fpRed))
        val removed = prefs.toggleFavorite(red)
        assertFalse(removed.isFavorite(red))
        assertTrue(removed.favorites.isEmpty() && removed.favoriteFingerprints.isEmpty())
    }

    @Test
    fun playedIsRecordedByFingerprintAndTheProvisionalPathDateMigrates() {
        var prefs = LibraryPreferencesData(lastPlayed = mapOf(unknown.id to 500L))
        assertEquals(500L, prefs.lastPlayedAt(unknown))
        prefs = prefs.recordFingerprint(unknown.id, "fp-x")
        assertTrue(prefs.lastPlayed.isEmpty())
        assertEquals(mapOf("fp-x" to 500L), prefs.lastPlayedByFingerprint)
        prefs = prefs.recordPlayed(unknown.id, "fp-x", at = 900L)
        assertEquals(900L, prefs.lastPlayedAt(unknown))
        // En otra ruta, con la misma huella, se ve la misma fecha.
        val copy = rom("Copia/Sin huella.gb")
        assertEquals(900L, prefs.recordFingerprint(copy.id, "fp-x").lastPlayedAt(copy))
    }

    @Test
    fun migratingKeepsTheMostRecentPlayedDate() {
        val prefs = LibraryPreferencesData(
            lastPlayed = mapOf(red.id to 100L),
            lastPlayedByFingerprint = mapOf(fpRed to 300L),
        ).recordFingerprint(red.id, fpRed)
        assertEquals(300L, prefs.lastPlayedByFingerprint[fpRed])
    }

    @Test
    fun aPathHiddenGameMovesToTheFingerprintWhenItIsKnown() {
        val prefs = LibraryPreferencesData().hide(unknown).recordFingerprint(unknown.id, "fp-x")
        assertTrue(prefs.hiddenPaths.isEmpty())
        assertEquals(setOf("fp-x"), prefs.hiddenFingerprints)
        assertTrue(prefs.isHidden(unknown))
    }

    @Test
    fun recordingTheSameFingerprintAgainChangesNothing() {
        val prefs = LibraryPreferencesData().recordFingerprint(red.id, fpRed).toggleFavorite(red)
        assertEquals(prefs, prefs.recordFingerprint(red.id, fpRed))
    }

    // ---- migración desde A8 y A9 ----

    private fun load(fixture: String): Pair<LibraryPreferencesData, LibraryPreferencesData> {
        val text = requireNotNull(javaClass.getResource(fixture)).readText()
        // Lo que había en disco, tal cual (sin migrar), para comparar el significado antes y después.
        val raw = Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString(LibraryPreferencesData.serializer(), text)
        val file = File(tmp.root, "preferences.json").apply { writeText(text) }
        val migrated = LibraryPreferencesFile(file).load()
        assertTrue("no se aparta como corrupto", file.exists())
        return raw to migrated
    }

    /** Lo que ve el usuario de cada juego: favorito, jugado, oculto y nombre. */
    private fun meaning(prefs: LibraryPreferencesData, entries: List<RomEntry>) = entries.associate {
        it.id to listOf(prefs.isFavorite(it), prefs.lastPlayedAt(it), prefs.isHidden(it), prefs.displayTitle(it))
    }

    @Test
    fun anA8FileIsMigratedToFingerprintsWithoutLosingAnything() {
        val (raw, migrated) = load("/library/preferences-a8.json")
        assertEquals(LibraryPreferencesFormat.LEGACY, 1)
        assertEquals(LibraryPreferencesFormat.CURRENT, migrated.formatVersion)
        assertEquals(setOf(fpRed), migrated.favoriteFingerprints)
        assertTrue(migrated.favorites.isEmpty())
        assertEquals(mapOf(fpRed to 1_759_700_000_000L, fpYellow to 1_759_600_000_000L), migrated.lastPlayedByFingerprint)
        assertTrue(migrated.lastPlayed.isEmpty())
        assertEquals("sin huella sigue por ruta", setOf("Roto.gb"), migrated.hiddenPaths)
        assertEquals(raw.fingerprints, migrated.fingerprints)
        assertEquals(raw.knownIds, migrated.knownIds)
        assertEquals(LibraryLayout.LIST, migrated.layout)
        assertEquals(LibrarySort.RECENT, migrated.sort)
        val all = listOf(red, yellow, broken)
        assertEquals(meaning(raw, all), meaning(migrated, all))
        // Y ahora siguen al ROM: movido de carpeta y reconocido, conserva favorito y fecha.
        val moved = rom("Pokémon/Pokemon Red.gb", "POKEMON RED")
        val after = migrated.recordFingerprint(moved.id, fpRed)
        assertTrue(after.isFavorite(moved))
        assertEquals(1_759_700_000_000L, after.lastPlayedAt(moved))
    }

    @Test
    fun anA9FileWithAliasesIsMigratedWithoutLosingAnything() {
        val (raw, migrated) = load("/library/preferences-a9.json")
        assertEquals(LibraryPreferencesFormat.CURRENT, migrated.formatVersion)
        assertEquals(setOf(fpRed), migrated.favoriteFingerprints)
        assertEquals("sin huella sigue por ruta", setOf(unknown.id), migrated.favorites)
        assertEquals(mapOf(fpRed to 1_759_700_000_000L, fpYellow to 1_759_600_000_000L), migrated.lastPlayedByFingerprint)
        assertEquals(mapOf(unknown.id to 1_759_500_000_000L), migrated.lastPlayed)
        assertEquals(setOf("Roto.gb"), migrated.hiddenPaths)
        assertTrue(fpYellow in migrated.hiddenFingerprints)
        assertEquals(raw.hiddenFingerprints + fpYellow, migrated.hiddenFingerprints)
        assertEquals(mapOf(fpRed to "Rojo de Joel"), migrated.aliasesByFingerprint)
        assertEquals(mapOf(unknown.id to "Mi juego"), migrated.aliasesByPath)
        val all = listOf(red, yellow, unknown, broken)
        assertEquals(meaning(raw, all), meaning(migrated, all))
        assertEquals("Rojo de Joel", migrated.displayTitle(red))
    }

    @Test
    fun theMigratedFileIsWrittenWithTheNewVersionAndIsNotMigratedAgain() {
        val (_, migrated) = load("/library/preferences-a8.json")
        val store = LibraryPreferencesFile(File(tmp.root, "v2.json"))
        store.save(migrated)
        val text = File(tmp.root, "v2.json").readText()
        assertTrue(text.contains("\"formatVersion\":${LibraryPreferencesFormat.CURRENT}"))
        assertEquals(migrated, store.load())
    }

    @Test
    fun aCurrentFileIsReadAsIsWithoutMigrating() {
        // Datos ya en formato 2 que guardan algo por ruta con huella conocida (lo hace el catálogo): no se tocan.
        val file = File(tmp.root, "p.json").apply {
            writeText("""{"formatVersion":2,"favorites":["Rojo"],"fingerprints":{"Rojo":"fp"}}""")
        }
        val data = LibraryPreferencesFile(file).load()
        assertEquals(setOf("Rojo"), data.favorites)
        assertTrue(data.favoriteFingerprints.isEmpty())
    }

    // ---- N1-H2/H3: versiones futuras o raras ----

    @Test
    fun aFileFromAFutureVersionIsReadButNeverOverwritten() {
        val original = """{"formatVersion":9,"favoriteFingerprints":["fp"],"categoriasVirtuales":{"x":"y"}}"""
        val file = File(tmp.root, "p.json").apply { writeText(original) }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertEquals("lo que esta versión entiende se usa", setOf("fp"), data.favoriteFingerprints)
        assertTrue(store.writeProtected)
        assertThrows(PreferencesWriteProtectedException::class.java) { store.save(data.copy(favoriteFingerprints = emptySet())) }
        assertEquals("el archivo queda intacto, con sus claves", original, file.readText())
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.contains(".corrupt") || it.name.endsWith(".tmp") })
    }

    @Test
    fun oddVersionValuesNeverSendTheFileToQuarantine() {
        // `2.0` y `"2"` son la versión actual; lo demás no se entiende: se lee sin migrar y no se sobrescribe.
        val current = listOf("2.0", "\"2\"")
        val foreign = listOf("true", "-1", "0", "1.5", "99999999999", "\"dos\"", "{}", "[2]")
        for (raw in current + foreign) {
            val file = File(tmp.newFolder(), "p.json").apply {
                writeText("""{"formatVersion":$raw,"favorites":["Rojo.gb"],"fingerprints":{"Rojo.gb":"fp"},"layout":"LIST"}""")
            }
            val store = LibraryPreferencesFile(file)
            val data = store.load()
            assertTrue("$raw: no se aparta", file.exists() && file.parentFile!!.listFiles()!!.none { it.name.contains(".corrupt") })
            assertEquals("$raw: se leen los datos", LibraryLayout.LIST, data.layout)
            assertEquals("$raw: sin migrar", setOf("Rojo.gb"), data.favorites)
            assertEquals("$raw: protegido", raw in foreign, store.writeProtected)
        }
    }

    @Test
    fun aForeignVersionThatChangesTheTypeOfAKnownKeyIsReadTolerantlyProtectedAndNeverQuarantined() {
        // PROBE3 (N1-V2-H3): una versión futura guarda `favorites` como objeto.
        val original = """{"formatVersion":9,"favorites":{"Rojo.gb":true},"favoriteFingerprints":["fp"],"layout":"LIST"}"""
        val file = File(tmp.root, "p.json").apply { writeText(original) }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertTrue(store.writeProtected)
        assertEquals("lo legible se usa", setOf("fp"), data.favoriteFingerprints)
        assertEquals(LibraryLayout.LIST, data.layout)
        assertTrue("lo ilegible se ignora", data.favorites.isEmpty())
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.contains(".corrupt") })
        assertEquals(original, file.readText())
    }

    @Test
    fun aVersionTwoFileFromTheFirstN1DeliveryIsReadAndWrittenAsTheCurrentVersion() {
        val file = File(tmp.root, "p.json").apply {
            writeText("""{"formatVersion":2,"favoriteFingerprints":["fp"],"documents":{"Rojo.gb":{"name":"Rojo.gb","size":1}}}""")
        }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertFalse(store.writeProtected)
        // N4: la versión actual es la 4 (la 3 de N1-V2 se lee igual, ver LibraryFormatV4Test).
        assertEquals(4, LibraryPreferencesFormat.CURRENT)
        assertEquals(LibraryPreferencesFormat.CURRENT, data.formatVersion)
        assertTrue(data.tombstones.isEmpty() && data.inferredFingerprints.isEmpty())
        store.save(data)
        assertTrue(file.readText().contains("\"formatVersion\":${LibraryPreferencesFormat.CURRENT}"))
    }

    @Test
    fun aLegacyFileWithAnExplicitVersionOneIsMigrated() {
        val file = File(tmp.root, "p.json").apply {
            writeText("""{"formatVersion":1,"favorites":["Rojo.gb"],"fingerprints":{"Rojo.gb":"fp"}}""")
        }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertEquals(setOf("fp"), data.favoriteFingerprints)
        assertFalse(store.writeProtected)
    }

    @Test
    fun aReadableFileAfterAProtectedOneIsWritableAgain() {
        val file = File(tmp.root, "p.json").apply { writeText("""{"formatVersion":7}""") }
        val store = LibraryPreferencesFile(file)
        store.load()
        assertTrue(store.writeProtected)
        file.writeText("""{"formatVersion":2}""")
        store.load()
        assertFalse(store.writeProtected)
    }

    @Test
    fun aFreshInstallStartsAtTheCurrentVersion() {
        assertEquals(LibraryPreferencesFormat.CURRENT, LibraryPreferencesData().formatVersion)
        assertEquals(LibraryPreferencesFormat.CURRENT, LibraryPreferencesFile(File(tmp.root, "nada.json")).load().formatVersion)
        assertNull(LibraryPreferencesData().lastPlayedAt(red))
    }
}
