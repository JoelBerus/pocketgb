package com.joelbermudez.pocketgb.game

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.library.ContentResolverRomSource
import com.joelbermudez.pocketgb.library.CoreRomInspector
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.library.FolderGrant
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryPreferencesFile
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.library.SafDocumentTree
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.saf.TestDocumentsProvider
import com.joelbermudez.pocketgb.saf.TestFixtures
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.settings.GameplaySettingsFile
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N1 de extremo a extremo con SAF (proveedor de pruebas), el núcleo real y la ROM contador sintética: mover un ROM (y su
 * `.sav`) entre subcarpetas conserva favorito, alias, oculto, ajustes del juego, partida (local y espejo), estados y
 * portada, sin leer el ROM para reconocerlo; y «Continuar» exacto sigue funcionando en la carpeta nueva.
 */
@RunWith(AndroidJUnit4::class)
class MoveRomEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
    private val fixtures = TestFixtures(resolver)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var root: File
    private val opened = mutableListOf<GameSession>()
    private val libraryRomReads = AtomicInteger()

    private val oldDir = "Pokémon/Gen 1"
    private val newDir = "Clásicos/Nintendo/Pokémon/Rojo/Kanto" // 5 niveles: el máximo que se escanea
    private val romMtime = 1_700_000_000_000L

    private class Folders(private val uri: String) : FolderStore {
        override fun currentUri() = uri
        override fun hasPersistedPermission() = true
        override fun select(uri: String) = FolderGrant(writeGranted = true)
        override fun forget() {}
        override fun displayName() = "Roms"
    }

    private val prefsFile get() = LibraryPreferencesFile(File(root, "library/preferences.json"))
    private val settingsFile get() = GameplaySettingsFile(File(root, "gameplay-settings.json"))
    private val artwork get() = ArtworkStore(File(root, "artwork"), executor = null)

    private val launcher get() = GameLauncher(
        roms = ContentResolverRomSource(resolver),
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        mirrors = MirrorLocator { entry, store, sizes, onDisabled ->
            val mirror = SafSaveMirror(resolver, treeUri, entry.folderDocumentId!!, entry.fileName, store, sizes, true, onDisabled)
            MirrorSetup(mirror, mirror.mirrorMode())
        },
        registry = MirrorChannelRegistry(),
        // Como la app: modelo y paleta según los ajustes por huella (A6-H1).
        emulationFor = { fingerprint, isCgb -> settingsFile.load().emulation(fingerprint, isCgb).toOptions() },
    )

    private fun viewModel(): LibraryViewModel {
        val source = ContentResolverRomSource(resolver)
        return LibraryViewModel(
            folders = Folders(treeUri.toString()),
            openTree = { SafDocumentTree(resolver, Uri.parse(it)) },
            roms = RomSource { uri, limit ->
                libraryRomReads.incrementAndGet()
                source.read(uri, limit)
            },
            inspector = CoreRomInspector(),
            preferencesFile = prefsFile,
            io = Dispatchers.IO,
            scope = scope,
        )
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    private fun LibraryViewModel.scanNow(): LibraryState.Ready {
        val before = state.value
        rescan()
        return await { state.first { it is LibraryState.Ready && it !== before } } as LibraryState.Ready
    }

    @Before
    fun setUp() {
        fixtures.reset()
        fixtures.put("$oldDir/Contador.gb", SyntheticRom.sramCounter(), mtimeMs = romMtime)
        fixtures.put("Tetris.gb", SyntheticRom.romOnly("TETRIS"))
        root = tempDir("n1-move")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        scope.cancel()
        fixtures.reset()
        root.deleteRecursively()
    }

    /** Juega un rato, guarda un estado manual y sale (la salida guarda la partida y después el AUTO). */
    private fun playAndExit(entry: RomEntry, mode: LaunchMode = LaunchMode.FRESH): OpenResult.Opened {
        val result = launcher.openBlocking(entry, mode = mode) as OpenResult.Opened
        val game = result.game.also { opened += it }
        game.start()
        val start = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > start + 3 })
        game.pause()
        game.saveState(StateSlot.MANUAL1)
        assertEquals(ExitResult.Clean, game.exit())
        return result
    }

    @Test
    fun movingARomAndItsSaveKeepsEverythingAndContinueStillResumesExactly() {
        val vm = viewModel()
        val entry = vm.scanNow().entries.single { it.fileName == "Contador.gb" }
        assertEquals(listOf("Pokémon", "Gen 1"), entry.folderPath)
        val fingerprint = (await { vm.loadDetails(entry.id) } as DetailsLoad.Loaded).details.fingerprint

        // Todo lo que el usuario asocia al juego.
        vm.toggleFavorite(entry)
        vm.setAlias(entry, "Contador de Joel")
        val overrides = GameOverrides(colorForGameBoy = true, compatPalette = 3)
        settingsFile.save(GameplaySettingsData().setOverrides(fingerprint, overrides))
        assertTrue(artwork.save(fingerprint, IntArray(160 * 144) { if (it % 7 == 0) 0xFF99CCFF.toInt() else 0xFF336699.toInt() }))
        val first = playAndExit(entry)
        assertEquals(fingerprint, first.game.fingerprint)
        vm.recordPlayed(entry, fingerprint, System.currentTimeMillis())
        val store = SaveStore(File(root, "saves"), fingerprint)
        val local = store.load()!!
        assertTrue("el espejo se pone al día", waitUntil(15_000) { fixtures.read("$oldDir/Contador.sav")?.contentEquals(local) == true })
        val states = StateStore(File(root, "states"), fingerprint)
        assertTrue(states.stateFile(StateSlot.AUTO).exists())
        assertTrue(states.stateFile(StateSlot.MANUAL1).exists())
        vm.hide(entry)
        val backupsBefore = store.backups().size
        val readsBefore = libraryRomReads.get()

        // Joel mueve el ROM y su `.sav` en el gestor de archivos (la fecha se conserva; el id de documento cambia).
        fixtures.move("$oldDir/Contador.gb", "$newDir/Contador.gb")
        fixtures.move("$oldDir/Contador.sav", "$newDir/Contador.sav")
        val moved = vm.scanNow().entries.single { it.fileName == "Contador.gb" }
        assertEquals("$newDir/Contador.gb", moved.id)
        assertEquals(listOf("Clásicos", "Nintendo", "Pokémon", "Rojo", "Kanto"), moved.folderPath)
        assertEquals("root/$newDir", moved.folderDocumentId)
        assertEquals("se reconoce sin leer el ROM", readsBefore, libraryRomReads.get())

        val prefs = vm.prefs.value
        assertEquals(fingerprint, prefs.fingerprints[moved.id])
        assertTrue("favorito", prefs.isFavorite(moved))
        assertEquals("alias", "Contador de Joel", prefs.displayTitle(moved))
        assertTrue("oculto", prefs.isHidden(moved))
        assertTrue("jugado", prefs.lastPlayedAt(moved) != null)
        assertFalse("no es «Nuevo»", moved.isNew)
        val known = prefs.fingerprints.getValue(moved.id)
        assertEquals("ajustes", overrides, settingsFile.load().perGame[known])
        assertTrue("portada", artwork.has(known))
        assertTrue("estado manual", StateStore(File(root, "states"), known).stateFile(StateSlot.MANUAL1).exists())
        assertArrayEquals("partida local intacta", local, SaveStore(File(root, "saves"), known).load())
        assertArrayEquals("el espejo viajó con el ROM", local, fixtures.read("$newDir/Contador.sav"))
        assertNull(fixtures.read("$oldDir/Contador.sav"))
        assertEquals(true, vm.lastScan.value?.complete)

        // «Continuar» exacto en la carpeta nueva: retoma el AUTO (con el modelo CGB del ajuste por huella; si el ajuste
        // se hubiera perdido, el AUTO sería de otro modelo y no se retomaría).
        val resumed = launcher.openBlocking(moved, mode = LaunchMode.RESUME)
        assertTrue("retoma el AUTO: $resumed", resumed is OpenResult.Opened && resumed.resumed)
        val game = (resumed as OpenResult.Opened).game.also { opened += it }
        assertNull("espejo = local: sin avisos", resumed.warning)
        assertArrayEquals(local, game.session.copySram())
        assertEquals("abrir no creó backups", backupsBefore, store.backups().size)
        // La partida sigue escribiéndose junto al ROM, ahora en su carpeta nueva.
        game.start()
        val start = game.session.sramDirtySequence()
        assertTrue(waitUntil { game.session.sramDirtySequence() > start + 3 })
        assertEquals(ExitResult.Clean, game.exit())
        val after = store.load()!!
        assertFalse(after.contentEquals(local))
        assertTrue(waitUntil(15_000) { fixtures.read("$newDir/Contador.sav")?.contentEquals(after) == true })
        assertNull("nada vuelve a la carpeta vieja", fixtures.read("$oldDir/Contador.sav"))
    }

    @Test
    fun movingOnlyTheRomKeepsTheLocalSaveAndLeavesTheOldSaveUntouched() {
        val vm = viewModel()
        val entry = vm.scanNow().entries.single { it.fileName == "Contador.gb" }
        val first = playAndExit(entry)
        val fingerprint = first.game.fingerprint
        val local = SaveStore(File(root, "saves"), fingerprint).load()!!
        assertTrue(waitUntil(15_000) { fixtures.read("$oldDir/Contador.sav")?.contentEquals(local) == true })

        fixtures.move("$oldDir/Contador.gb", "$newDir/Contador.gb")
        val moved = vm.scanNow().entries.single { it.fileName == "Contador.gb" }
        val reopened = launcher.openBlocking(moved) as OpenResult.Opened
        opened += reopened.game
        assertEquals(fingerprint, reopened.game.fingerprint)
        assertArrayEquals("la partida local manda", local, reopened.game.session.copySram())
        // Al salir (vaciado síncrono) se reintenta el espejo pendiente: se crea el `.sav` junto al ROM en su carpeta nueva.
        assertEquals(ExitResult.Clean, reopened.game.exit())
        assertTrue("se crea el .sav en la carpeta nueva", waitUntil(15_000) { fixtures.read("$newDir/Contador.sav")?.contentEquals(local) == true })
        assertArrayEquals("el .sav viejo no se toca", local, fixtures.read("$oldDir/Contador.sav"))
    }
}
