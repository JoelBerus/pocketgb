package com.joelbermudez.pocketgb.saf

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.testing.SyntheticRom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N1b sobre SAF real (proveedor de pruebas): árbol sintético de más de 5 niveles con `.oculta/`, `_apartada/`,
 * `PocketGB/` y una carpeta demasiado profunda; rutas, `folderPath` y número de consultas al proveedor.
 */
@RunWith(AndroidJUnit4::class)
class SafFoldersTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val fixtures = TestFixtures(resolver)

    @Before
    fun setUp() {
        fixtures.reset()
        fixtures.put("Tetris.gb", SyntheticRom.romOnly("TETRIS"))
        fixtures.put("Pokémon/1ª generación/Pokemon Red.gb", SyntheticRom.romOnly("RED"))
        fixtures.put("Pokémon/1ª generación/Pokemon Red.sav", ByteArray(8192), mtimeMs = 1_700_000_000_000)
        fixtures.put("Pokémon/2ª generación/Pokemon Gold.gbc", SyntheticRom.romOnly("GOLD", color = true))
        fixtures.put("N1/N2/N3/N4/N5/Quinto.gb", SyntheticRom.romOnly("QUINTO"))
        fixtures.put("N1/N2/N3/N4/N5/N6/Sexto.gb", SyntheticRom.romOnly("SEXTO"))
        fixtures.put(".oculta/Oculto.gb", SyntheticRom.romOnly("OCULTO"))
        fixtures.put("_apartada/Apartado.gb", SyntheticRom.romOnly("APARTADO"))
        fixtures.put("Pokémon/_Revisar/Dudoso.gb", SyntheticRom.romOnly("DUDOSO"))
        fixtures.put("PocketGB/Intercambio/Paquete.gb", SyntheticRom.romOnly("PAQUETE"))
        fixtures.put("Pokémon/PocketGB/Anidado.gb", SyntheticRom.romOnly("ANIDADO"))
        fixtures.put("Juegos.zip", ByteArray(64))
    }

    @After
    fun tearDown() {
        fixtures.reset()
    }

    @Test
    fun aSyntheticTreeGivesTheExpectedPathsFolderPathsAndQueries() {
        val before = fixtures.childQueries()
        val result = LibraryScanner.scanDetailed(SafDocumentTree(resolver, treeUri))
        val byId = result.entries.associateBy { it.id }
        assertEquals(
            setOf(
                "Tetris.gb",
                "Pokémon/1ª generación/Pokemon Red.gb",
                "Pokémon/2ª generación/Pokemon Gold.gbc",
                "Pokémon/PocketGB/Anidado.gb",
                "N1/N2/N3/N4/N5/Quinto.gb",
            ),
            byId.keys,
        )
        assertEquals(emptyList<String>(), byId.getValue("Tetris.gb").folderPath)
        assertEquals(listOf("Pokémon", "1ª generación"), byId.getValue("Pokémon/1ª generación/Pokemon Red.gb").folderPath)
        assertEquals(listOf("N1", "N2", "N3", "N4", "N5"), byId.getValue("N1/N2/N3/N4/N5/Quinto.gb").folderPath)
        // El espejo SAF busca el `.sav` hermano en esta carpeta: la del ROM, no la raíz.
        assertEquals("root/N1/N2/N3/N4/N5", byId.getValue("N1/N2/N3/N4/N5/Quinto.gb").folderDocumentId)
        assertEquals(1_700_000_000_000, byId.getValue("Pokémon/1ª generación/Pokemon Red.gb").mirrorSaveDate)
        assertTrue(byId.values.all { it.lastModified != null && it.lastModified!! > 0 })

        val stats = result.stats
        assertTrue(stats.complete)
        assertEquals(1, stats.reservedSkipped) // PocketGB/ de la raíz
        assertEquals(2, stats.setAsideSkipped) // _apartada/ y Pokémon/_Revisar/
        assertEquals(1, stats.hiddenSkipped) // .oculta/
        assertEquals(1, stats.tooDeepSkipped) // N6/
        // Carpetas listadas: raíz, Pokémon, 1ª, 2ª, Pokémon/PocketGB, N1..N5 = 10. El proveedor sirvió las mismas.
        assertEquals(10, stats.folderQueries)
        assertEquals(stats.folderQueries, fixtures.childQueries() - before)
        assertEquals(5, stats.headReads)
        assertEquals(15, stats.providerCalls)
    }
}
