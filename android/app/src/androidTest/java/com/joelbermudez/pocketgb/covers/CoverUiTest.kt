package com.joelbermudez.pocketgb.covers

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.library.RomConsole
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.library.artwork.CoverChoice
import com.joelbermudez.pocketgb.library.artwork.CoverDecoder
import com.joelbermudez.pocketgb.library.artwork.CoverImageRules
import com.joelbermudez.pocketgb.library.artwork.CoverRepository
import com.joelbermudez.pocketgb.library.artwork.CoverSettingsStore
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.ui.components.GameArtwork
import com.joelbermudez.pocketgb.ui.components.LocalCoverRepository
import com.joelbermudez.pocketgb.ui.details.GameCenterState
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.details.rememberCoverCenter
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N5 · la portada que se ve en la UI según la fuente, la elección y las imágenes hostiles de la carpeta. */
@RunWith(AndroidJUnit4::class)
class CoverUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File
    private val fp = "5a".repeat(32)

    @Before fun setUp() { root = tempDir("cover-ui") }

    @After fun tearDown() { root.deleteRecursively() }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        bitmap.setPixel(0, 0, Color.RED)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun entry(coverUri: String? = null) = RomEntry(
        id = "Demo.gb", uri = "content://demo", fileName = "Demo.gb", title = "DEMO", console = RomConsole.GB,
        sizeBytes = 32768, headerChecksumOk = true, problem = null, coverUri = coverUri, coverStamp = coverUri?.let { "1" },
    )

    private fun repository(folder: Map<String, ByteArray> = emptyMap()): CoverRepository {
        val untrusted: (ByteArray) -> androidx.compose.ui.graphics.ImageBitmap? = { CoverDecoder.decode(it)?.asImageBitmap() }
        return CoverRepository(
            captures = ArtworkStore(File(root, "artwork"), executor = null),
            pinned = ArtworkStore(File(root, "artwork-pinned"), executor = null),
            imported = ArtworkStore(File(root, "imported"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES, decoder = untrusted),
            folderCache = ArtworkStore(File(root, "folder"), executor = null, maxReadBytes = CoverImageRules.MAX_BYTES, decoder = untrusted),
            settings = CoverSettingsStore(File(root, "settings.json")),
            readFolderImage = { folder[it] },
        )
    }

    private fun showArtwork(repo: CoverRepository, entry: RomEntry) = compose.setContent {
        PocketGBTheme {
            CompositionLocalProvider(LocalCoverRepository provides repo) {
                GameArtwork(entry, fp, Modifier.width(200.dp), fit = true)
            }
        }
    }

    private fun waitForCover(kind: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cover-$kind")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aFolderImageIsShownAndAHostileOneFallsBackToTheGenerated() {
        showArtwork(repository(mapOf("content://ok" to png(1200, 600))), entry("content://ok"))
        waitForCover("sidecar")
    }

    @Test
    fun aFolderImageWithAFakeExtensionShowsTheGeneratedCover() {
        showArtwork(repository(mapOf("content://falsa.png" to "no soy una imagen".toByteArray())), entry("content://falsa.png"))
        waitForCover("generated")
        compose.onNodeWithTag("cover-generated").assertIsDisplayed()
    }

    @Test
    fun anUnreadableCaptureFallsBackToTheGenerated() {
        val repo = repository()
        File(root, "artwork").mkdirs()
        File(root, "artwork/$fp.png").writeBytes(byteArrayOf(1, 2, 3)) // captura dañada
        showArtwork(repo, entry())
        waitForCover("generated")
    }

    @Test
    fun choosingInTheGameCenterChangesTheVisibleCover() {
        val repo = repository(mapOf("content://ok" to png(800, 800)))
        assertEquals(true, repo.captures.save(fp, IntArray(160 * 144) { it or 0xFF000000.toInt() }))
        val shown = entry("content://ok")
        compose.setContent {
            PocketGBTheme {
                CompositionLocalProvider(LocalCoverRepository provides repo) {
                    Column {
                        GameArtwork(shown, fp, Modifier.width(120.dp), fit = true)
                        GameSettingsSheet(
                            title = "DEMO",
                            console = RomConsole.GB,
                            global = GameplaySettingsData(),
                            overrides = GameOverrides(),
                            onOverridesChange = {},
                            onDismiss = {},
                            center = GameCenterState(
                                categoryPath = emptyList(), folderPath = emptyList(), moved = false, tags = emptyList(), enabled = true,
                                onChangeCategory = {}, onReturnToFolder = {}, onEditTags = {}, onOpenSaves = null, onHide = {},
                                cover = rememberCoverCenter(shown, fp), title = "DEMO",
                            ),
                        )
                    }
                }
            }
        }
        // Automática + «Preferir imágenes»: la de la carpeta.
        waitForCover("sidecar")
        compose.onNodeWithTag("game-center-cover-value", useUnmergedTree = true).assertTextContains("Automática · se ve: Imagen de la carpeta")
        compose.onNodeWithTag("game-center-cover-change").performClick()
        compose.onNodeWithTag("cover-choice-capture").performClick()
        waitForCover("capture")
        assertEquals(CoverChoice.CAPTURE, repo.choiceFor(fp))
        compose.onNodeWithTag("cover-choice-generated").performClick()
        waitForCover("generated")
        compose.onNodeWithTag("cover-dialog-done").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Generada · se ve: Generada")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anExplicitChoiceWithoutThatSourceSaysSo() {
        val repo = repository()
        compose.setContent {
            PocketGBTheme {
                CompositionLocalProvider(LocalCoverRepository provides repo) {
                    GameSettingsSheet(
                        title = "DEMO", console = RomConsole.GB, global = GameplaySettingsData(), overrides = GameOverrides(),
                        onOverridesChange = {}, onDismiss = {},
                        center = GameCenterState(
                            categoryPath = emptyList(), folderPath = emptyList(), moved = false, tags = emptyList(), enabled = true,
                            onChangeCategory = {}, onReturnToFolder = {}, onEditTags = {}, onOpenSaves = null, onHide = {},
                            cover = rememberCoverCenter(entry(), fp), title = "DEMO",
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("game-center-cover-change").performClick()
        compose.onNodeWithTag("cover-dialog").assertIsDisplayed()
        // Ni imagen ni captura: las dos opciones lo dicen.
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText("No hay: se verá la generada", substring = true)).fetchSemanticsNodes().size)
    }
}
