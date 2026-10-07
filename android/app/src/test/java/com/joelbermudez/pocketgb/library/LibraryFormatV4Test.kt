package com.joelbermudez.pocketgb.library

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N4 · formato 4 de `preferences.json`: etiquetas y categoría virtual por huella, ajustes del inicio y vista por categoría
 * (por dispositivo, ND12). Un archivo v3 (N1/N3) se lee tal cual y se escribe como v4 sin perder nada; uno de una
 * versión futura se lee con tolerancia y nunca se sobrescribe ni se aparta (misma robustez que N1-H2/H3).
 */
class LibraryFormatV4Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rom(id: String, title: String = id) =
        RomEntry(id, "content://$id", id.substringAfterLast('/'), title, false, 32768, true, null)

    private val fpRed = "9d4c1e07b3a85f26c0de91ab47f3825e6b10c9d7a2f45e83b6c1d09e7f2a4b58"
    private val red = rom("Pokémon/1ª generación/Pokemon Red.gb", "POKEMON RED")
    private val gold = rom("Pokémon/2ª generación/Pokemon Gold.gbc", "POKEMON GOLD")
    private val kirby = rom("Kirby/Kirby.gb", "KIRBY")
    private val unknown = rom("Sin huella.gb", "SIN HUELLA")
    private val broken = rom("Roto.gb", "ROTO")
    private val entries = listOf(red, gold, kirby, unknown, broken)

    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun fixture(name: String): Pair<String, File> {
        val text = requireNotNull(javaClass.getResource(name)).readText()
        return text to File(tmp.root, "preferences.json").apply { writeText(text) }
    }

    /** Lo que ve el usuario de cada juego. */
    private fun meaning(prefs: LibraryPreferencesData) = entries.associate {
        it.id to listOf(prefs.isFavorite(it), prefs.lastPlayedAt(it), prefs.isHidden(it), prefs.displayTitle(it), prefs.hasConfirmedFingerprint(it))
    }

    @Test
    fun theCurrentFormatIsFour() {
        assertEquals(4, LibraryPreferencesFormat.CURRENT)
        assertEquals(3, LibraryPreferencesFormat.V3)
    }

    @Test
    fun aVersionThreeFileIsReadAsIsAndKeepsEverything() {
        val (text, file) = fixture("/library/preferences-n3.json")
        val raw = lenient.decodeFromString(LibraryPreferencesData.serializer(), text)
        val store = LibraryPreferencesFile(file)
        val loaded = store.load()
        assertFalse("v3 es una versión conocida: se puede escribir", store.writeProtected)
        assertEquals(LibraryPreferencesFormat.CURRENT, loaded.formatVersion)
        assertEquals("sin migrar nada: todo igual salvo la versión", raw.copy(formatVersion = LibraryPreferencesFormat.CURRENT), loaded)
        assertEquals(meaning(raw), meaning(loaded))
        assertEquals("las lápidas y huellas sin confirmar de N1 siguen", raw.tombstones, loaded.tombstones)
        assertEquals(setOf(kirby.id), loaded.inferredFingerprints)
        // Lo nuevo de v4 empieza vacío.
        assertTrue(loaded.tagsByFingerprint.isEmpty() && loaded.virtualFoldersByFingerprint.isEmpty() && loaded.categoryLayouts.isEmpty())
        assertEquals(HomeSettings(), loaded.home)
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.contains(".corrupt") })
    }

    @Test
    fun aVersionThreeFileIsWrittenAsVersionFourWithItsNewDataAndReadBackEqual() {
        val (_, file) = fixture("/library/preferences-n3.json")
        val store = LibraryPreferencesFile(file)
        val changed = store.load()
            .addTag(red, "rpg")
            .moveToCategory(red, listOf("Favoritas"))
            .withCategoryLayout(LibraryCategory.Folder("Pokémon"), LibraryLayout.GRID)
            .let { it.copy(home = it.home.withHidden("Kirby", true).copy(showFavorites = false)) }
        store.save(changed)
        val text = file.readText()
        assertTrue(text.contains("\"formatVersion\":4"))
        assertTrue(text.contains("\"tagsByFingerprint\":{\"$fpRed\":[\"rpg\"]}"))
        assertTrue(text.contains("\"virtualFoldersByFingerprint\":{\"$fpRed\":[\"Favoritas\"]}"))
        val reloaded = LibraryPreferencesFile(file).load()
        assertEquals(changed, reloaded)
        assertEquals(listOf("rpg"), reloaded.tagsOf(red))
        assertEquals(listOf("Favoritas"), reloaded.virtualFolderOf(red))
        assertFalse(reloaded.home.showFavorites)
        assertEquals(LibraryLayout.GRID, reloaded.layoutFor(LibraryCategory.Folder("Pokémon")))
    }

    @Test
    fun olderFormatsStillMigrateAndArriveAtVersionFour() {
        for (name in listOf("/library/preferences-a8.json", "/library/preferences-a9.json")) {
            val (_, file) = fixture(name)
            val loaded = LibraryPreferencesFile(file).load()
            assertEquals(name, LibraryPreferencesFormat.CURRENT, loaded.formatVersion)
            assertTrue(name, loaded.tagsByFingerprint.isEmpty())
        }
    }

    @Test
    fun aVersionTwoFileIsAlsoReadAsIs() {
        val file = File(tmp.root, "p.json").apply { writeText("""{"formatVersion":2,"favorites":["Rojo"],"fingerprints":{"Rojo":"fp"}}""") }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertFalse(store.writeProtected)
        assertEquals(setOf("Rojo"), data.favorites)
        assertEquals(LibraryPreferencesFormat.CURRENT, data.formatVersion)
    }

    @Test
    fun aFileFromVersionFiveIsUsedButNeverOverwrittenNorQuarantined() {
        val original = """{"formatVersion":5,"favoriteFingerprints":["$fpRed"],"fingerprints":{"${red.id}":"$fpRed"},""" +
            """"tagsByFingerprint":{"$fpRed":["rpg"]},"virtualFoldersByFingerprint":{"$fpRed":{"ruta":"x"}},""" +
            """"home":{"showFavorites":false,"orden":[1,2]},"etiquetasDeColor":{"rpg":"rojo"}}"""
        val file = File(tmp.root, "preferences.json").apply { writeText(original) }
        val store = LibraryPreferencesFile(file)
        val data = store.load()
        assertTrue(store.writeProtected)
        assertTrue("lo legible se usa", data.isFavorite(red))
        assertEquals(listOf("rpg"), data.tagsOf(red))
        assertTrue("una clave con otro tipo se ignora", data.virtualFoldersByFingerprint.isEmpty())
        assertFalse(data.home.showFavorites)
        assertThrows(PreferencesWriteProtectedException::class.java) { store.save(data.addTag(red, "otra")) }
        assertEquals(original, file.readText())
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.contains(".corrupt") || it.name.endsWith(".tmp") })
    }

    @Test
    fun brokenNewKeysInAVersionFourFileSendItToQuarantineLikeAnyCorruptFile() {
        // Una versión conocida que no se puede decodificar es un archivo dañado (N1): se aparta y se avisa.
        val file = File(tmp.root, "preferences.json").apply { writeText("""{"formatVersion":4,"tagsByFingerprint":"no es un mapa"}""") }
        val data = LibraryPreferencesFile(file).load()
        assertEquals(LibraryPreferencesData(), data)
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.contains(".corrupt") })
    }
}
