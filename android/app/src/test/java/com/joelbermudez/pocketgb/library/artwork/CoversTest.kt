package com.joelbermudez.pocketgb.library.artwork

import com.joelbermudez.pocketgb.library.DocumentTree
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.TreeNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N5 · prioridad de fuentes, imagen junto al ROM, reglas de imágenes hostiles y elección persistida. */
class CoversTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fp = "ab".repeat(32)
    private val all = CoverAvailability(imported = true, sidecar = true, capture = true)
    private val none = CoverAvailability()

    // ---- prioridad

    @Test
    fun automaticFollowsTheGlobalPreference() {
        assertEquals(
            listOf(CoverKind.IMPORTED, CoverKind.SIDECAR, CoverKind.CAPTURE, CoverKind.GENERATED),
            CoverResolver.candidates(CoverChoice.AUTO, CoverPreference.IMAGES, all),
        )
        assertEquals(
            listOf(CoverKind.CAPTURE, CoverKind.IMPORTED, CoverKind.SIDECAR, CoverKind.GENERATED),
            CoverResolver.candidates(CoverChoice.AUTO, CoverPreference.CAPTURES, all),
        )
        // Sin imagen, «preferir imágenes» usa la captura; sin nada, la generada.
        assertEquals(CoverKind.CAPTURE, CoverResolver.resolve(CoverChoice.AUTO, CoverPreference.IMAGES, CoverAvailability(capture = true)))
        assertEquals(CoverKind.SIDECAR, CoverResolver.resolve(CoverChoice.AUTO, CoverPreference.CAPTURES, CoverAvailability(sidecar = true)))
        assertEquals(CoverKind.GENERATED, CoverResolver.resolve(CoverChoice.AUTO, CoverPreference.IMAGES, none))
    }

    @Test
    fun importedImageWinsOverTheFolderImage() {
        assertEquals(CoverKind.IMPORTED, CoverResolver.resolve(CoverChoice.IMAGE, CoverPreference.CAPTURES, all))
        assertEquals(CoverKind.SIDECAR, CoverResolver.resolve(CoverChoice.IMAGE, CoverPreference.IMAGES, CoverAvailability(sidecar = true, capture = true)))
    }

    @Test
    fun explicitChoiceNeverFallsBackToAnotherSourceButTheGenerated() {
        assertEquals(listOf(CoverKind.GENERATED), CoverResolver.candidates(CoverChoice.IMAGE, CoverPreference.IMAGES, CoverAvailability(capture = true)))
        assertEquals(listOf(CoverKind.GENERATED), CoverResolver.candidates(CoverChoice.CAPTURE, CoverPreference.CAPTURES, CoverAvailability(imported = true)))
        assertEquals(listOf(CoverKind.CAPTURE, CoverKind.GENERATED), CoverResolver.candidates(CoverChoice.CAPTURE, CoverPreference.IMAGES, all))
        assertEquals(listOf(CoverKind.GENERATED), CoverResolver.candidates(CoverChoice.GENERATED, CoverPreference.IMAGES, all))
    }

    @Test
    fun theGeneratedCoverIsAlwaysTheLastCandidate() {
        for (choice in CoverChoice.entries) for (pref in CoverPreference.entries) {
            for (mask in 0 until 8) {
                val a = CoverAvailability(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
                val list = CoverResolver.candidates(choice, pref, a)
                assertEquals(CoverKind.GENERATED, list.last())
                assertEquals(list.size, list.toSet().size)
            }
        }
    }

    // ---- imagen junto al ROM

    private fun file(id: String, name: String, size: Long = 1000, virtual: Boolean = false) =
        TreeNode(id, name, isDirectory = false, sizeBytes = size, isVirtual = virtual, lastModified = 5)

    @Test
    fun sidecarMatchesTheRomNameIgnoringCaseWithExtensionPriority() {
        val siblings = listOf(
            file("r", "Pokemon Red.gb"),
            file("w", "pokemon red.WEBP"),
            file("j", "Pokemon Red.jpg"),
            file("p", "Pokemon Red.png"),
        )
        assertEquals("p", SidecarCover.find("Pokemon Red.gb", siblings, 1)?.id)
        assertEquals("j", SidecarCover.find("Pokemon Red.gb", siblings.filter { it.id != "p" }, 1)?.id)
        assertEquals("w", SidecarCover.find("Pokemon Red.gb", siblings.filter { it.id == "w" }, 1)?.id)
    }

    @Test
    fun folderCoverOnlyCountsWithASingleGame() {
        val siblings = listOf(file("r", "Kirby.gba"), file("c", "Portada.JPEG"), file("e", "cover.png"))
        assertEquals("c", SidecarCover.find("Kirby.gba", siblings, 1)?.id)
        assertNull(SidecarCover.find("Kirby.gba", siblings + file("o", "Otro.gb"), 2))
        // La del mismo nombre gana a «portada».
        assertEquals("k", SidecarCover.find("Kirby.gba", siblings + file("k", "Kirby.png"), 1)?.id)
    }

    @Test
    fun sidecarIgnoresHiddenVirtualEmptyFoldersAndOtherExtensions() {
        val siblings = listOf(
            file("h", ".Kirby.png"),
            file("v", "Kirby.png", virtual = true),
            file("z", "Kirby.jpg", size = 0),
            TreeNode("d", "Kirby.webp", isDirectory = true, sizeBytes = 0),
            file("g", "Kirby.gif"),
            file("t", "Kirby.png.txt"),
        )
        assertNull(SidecarCover.find("Kirby.gba", siblings, 1))
    }

    @Test
    fun scannerAttachesTheSidecarUriAndStampWithoutReadingIt() {
        val head = ByteArray(0x150).also { bytes ->
            "DEMO".forEachIndexed { i, c -> bytes[0x134 + i] = c.code.toByte() }
            var x = 0
            for (i in 0x134..0x14C) x = (x - (bytes[i].toInt() and 0xFF) - 1) and 0xFF
            bytes[0x14D] = x.toByte()
        }
        val read = mutableListOf<String>()
        val tree = object : DocumentTree {
            override val rootId: String? = null
            override fun children(directoryId: String?) = when (directoryId) {
                null -> listOf(file("a", "Demo.gb", 32768), file("ai", "Demo.PNG", 2000), TreeNode("d", "Solo", true, 0))
                "d" -> listOf(file("b", "Uno.gb", 32768), file("bi", "cover.webp", 3000))
                else -> emptyList()
            }
            override fun readHead(node: TreeNode, limit: Int): ByteArray {
                read += node.id
                return head
            }
            override fun uriOf(node: TreeNode) = "content://tree/${node.id}"
        }
        val entries = LibraryScanner.scan(tree).associateBy { it.id }
        assertEquals("content://tree/ai", entries.getValue("Demo.gb").coverUri)
        assertEquals("ai|2000|5", entries.getValue("Demo.gb").coverStamp)
        assertEquals("content://tree/bi", entries.getValue("Solo/Uno.gb").coverUri)
        assertEquals(setOf("a", "b"), read.toSet()) // las imágenes no se leen al escanear
    }

    // ---- reglas de imágenes no confiables

    @Test
    fun formatIsDecidedByTheSignatureNotTheExtension() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertEquals(CoverFormat.PNG, CoverImageRules.sniff(png))
        assertEquals(CoverFormat.JPEG, CoverImageRules.sniff(jpeg))
        assertEquals(CoverFormat.WEBP, CoverImageRules.sniff(webp))
        // Extensión falsa: un texto llamado .png, un GIF, un RIFF que no es WebP, vacío o truncado.
        assertNull(CoverImageRules.sniff("esto no es un png".toByteArray()))
        assertNull(CoverImageRules.sniff("GIF89a".toByteArray()))
        assertNull(CoverImageRules.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull(CoverImageRules.sniff(ByteArray(0)))
        assertNull(CoverImageRules.sniff(png.copyOf(5)))
    }

    @Test
    fun hugeOrInvalidDimensionsAreRejected() {
        assertTrue(CoverImageRules.dimensionsOk(1024, 1024))
        assertTrue(CoverImageRules.dimensionsOk(10_000, 10_000))
        assertFalse(CoverImageRules.dimensionsOk(16_385, 10))
        assertFalse(CoverImageRules.dimensionsOk(12_000, 12_000)) // 144 MP
        assertFalse(CoverImageRules.dimensionsOk(0, 100))
        assertFalse(CoverImageRules.dimensionsOk(-1, -1))
        assertFalse(CoverImageRules.dimensionsOk(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun sampleSizeKeepsTheDecodedImageSmall() {
        assertEquals(1, CoverImageRules.sampleSize(160, 144))
        assertEquals(1, CoverImageRules.sampleSize(2047, 100))
        assertEquals(2, CoverImageRules.sampleSize(2048, 100))
        assertEquals(2, CoverImageRules.sampleSize(4000, 3000))
        assertEquals(4, CoverImageRules.sampleSize(4096, 3000))
        assertEquals(16, CoverImageRules.sampleSize(16_384, 16_384))
        for (side in listOf(1, 999, 1024, 3000, 9999, 16_384)) {
            val sample = CoverImageRules.sampleSize(side, side)
            assertTrue(side / sample < 2 * CoverImageRules.TARGET_SIDE)
        }
        assertEquals(1024 to 768, CoverImageRules.scaledSize(4000, 3000))
        assertEquals(160 to 144, CoverImageRules.scaledSize(160, 144))
        assertEquals(1024 to 1, CoverImageRules.scaledSize(10_000, 2))
    }

    // ---- elección persistida

    @Test
    fun choicesAndPreferenceSurviveAReload() {
        val file = File(tmp.root, "covers/settings.json")
        val store = CoverSettingsStore(file)
        assertEquals(CoverChoice.AUTO, store.state.value.choiceFor(fp))
        assertEquals(CoverPreference.IMAGES, store.state.value.preference)
        assertTrue(store.setChoice(fp, CoverChoice.CAPTURE))
        assertTrue(store.setPreference(CoverPreference.CAPTURES))
        val reloaded = CoverSettingsStore(file)
        assertEquals(CoverChoice.CAPTURE, reloaded.state.value.choiceFor(fp))
        assertEquals(CoverPreference.CAPTURES, reloaded.state.value.preference)
        // Volver a Automática no deja la clave.
        assertTrue(reloaded.setChoice(fp, CoverChoice.AUTO))
        assertTrue(CoverSettingsStore(file).state.value.choices.isEmpty())
        assertFalse(store.setChoice("no-es-huella", CoverChoice.IMAGE))
    }

    @Test
    fun aCorruptSettingsFileIsSetAsideAndAFutureOneIsNotOverwritten() {
        val dir = File(tmp.root, "covers").apply { mkdirs() }
        val file = File(dir, "settings.json")
        file.writeText("{no es json")
        val store = CoverSettingsStore(file, clock = { 42 })
        assertEquals(CoverSettings(), store.state.value)
        assertTrue(File(dir, "settings.json.corrupt-42").isFile)

        val future = """{"formatVersion":9,"preference":"CAPTURES","choices":{"$fp":"IMAGE","malo":"IMAGE"},"nuevo":1}"""
        file.writeText(future)
        val read = CoverSettingsStore(file)
        assertEquals(CoverPreference.CAPTURES, read.state.value.preference)
        assertEquals(mapOf(fp to CoverChoice.IMAGE), read.state.value.choices)
        assertFalse(read.setChoice(fp, CoverChoice.GENERATED))
        assertEquals(future, file.readText())
    }

    // ---- repositorio (sin decodificar: reducción falsa)

    private fun entry(coverUri: String? = null) = RomEntry(
        id = "Demo.gb", uri = "content://demo", fileName = "Demo.gb", title = "DEMO", console = RomConsole.GB,
        sizeBytes = 32768, headerChecksumOk = true, problem = null, coverUri = coverUri, coverStamp = coverUri?.let { "s" },
    )

    private fun repository(reduce: (ByteArray) -> ByteArray? = { if (it.isEmpty()) null else byteArrayOf(9, 9) }) = CoverRepository(
        captures = ArtworkStore(File(tmp.root, "artwork"), encoder = { byteArrayOf(1) }, executor = null),
        pinned = ArtworkStore(File(tmp.root, "artwork-pinned"), encoder = { byteArrayOf(2) }, executor = null),
        imported = ArtworkStore(File(tmp.root, "covers/imported"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES),
        folderCache = ArtworkStore(File(tmp.root, "covers/folder"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES),
        settings = CoverSettingsStore(File(tmp.root, "covers/settings.json")),
        reduce = reduce,
    )

    @Test
    fun importingSavesTheReducedCopyAndChoosesImage() {
        val repo = repository()
        assertEquals(CoverKind.GENERATED, repo.resolve(entry(), fp))
        assertTrue(repo.importImage(fp, byteArrayOf(1, 2, 3)))
        assertEquals(CoverChoice.IMAGE, repo.choiceFor(fp))
        assertEquals(CoverKind.IMPORTED, repo.resolve(entry(), fp))
        assertEquals(2L, File(tmp.root, "covers/imported/$fp.png").length())
        assertTrue(fp in repo.fingerprintsWithCover())

        // Una imagen que no vale no cambia nada.
        val bad = repository(reduce = { null })
        assertFalse(bad.importImage("cd".repeat(32), byteArrayOf(1)))
        assertEquals(CoverChoice.AUTO, bad.choiceFor("cd".repeat(32)))

        // Quitarla vuelve a Automática (no hay imagen en la carpeta).
        repo.removeImported(entry(), fp)
        assertEquals(CoverChoice.AUTO, repo.choiceFor(fp))
        assertEquals(CoverKind.GENERATED, repo.resolve(entry(), fp))
    }

    @Test
    fun pinningACaptureChoosesCaptureAndSurvivesTheCloseCapture() {
        val repo = repository()
        val frame = IntArray(160 * 144) { it }
        assertTrue(repo.captures.save(fp, frame))
        assertTrue(repo.pinCapture(fp, frame))
        assertEquals(CoverChoice.CAPTURE, repo.choiceFor(fp))
        assertTrue(repo.hasPinned(fp))
        assertEquals(CoverKind.CAPTURE, repo.resolve(entry(coverUri = "content://img"), fp))
        // Un fotograma uniforme no se fija.
        assertFalse(repo.pinCapture("cd".repeat(32), IntArray(160 * 144)))
        repo.unpinCapture(fp)
        assertFalse(repo.hasPinned(fp))
        assertEquals(CoverKind.CAPTURE, repo.resolve(entry(), fp)) // sigue la del cierre
    }

    @Test
    fun aFolderImageThatFailsIsNotRetriedAndFallsBackToTheGenerated() {
        var reads = 0
        val repo = CoverRepository(
            captures = ArtworkStore(File(tmp.root, "artwork"), executor = null),
            folderCache = ArtworkStore(File(tmp.root, "covers/folder"), executor = null),
            readFolderImage = { reads++; "texto con extensión .png".toByteArray() },
            reduce = { null }, // la firma no cuadra
        )
        val withImage = entry(coverUri = "content://tree/falsa.png")
        assertEquals(CoverKind.SIDECAR, repo.resolve(withImage, fp))
        assertEquals(ShownCover.GENERATED, repo.load(withImage, fp))
        assertEquals(ShownCover.GENERATED, repo.load(withImage, fp))
        assertEquals(1, reads)
        assertEquals(CoverKind.GENERATED, repo.resolve(withImage, fp))
    }
}
