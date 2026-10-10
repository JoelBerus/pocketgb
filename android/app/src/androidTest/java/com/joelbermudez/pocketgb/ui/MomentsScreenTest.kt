package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joelbermudez.pocketgb.game.PendingMoment
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.MomentLibrary
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import com.joelbermudez.pocketgb.ui.moments.MomentsScreen
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N6 · pantalla «Momentos» del detalle con el almacén real en disco: colecciones, filtro por etiqueta, editar, cargar
 * (abre el juego con el momento pendiente), «Recuperar» en un toque y exclusión por huella.
 */
@RunWith(AndroidJUnit4::class)
class MomentsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val root = tempDir("moments-ui")
    private val fp = "12".repeat(32)
    private val ownership = FingerprintOwnership()
    private val library = MomentLibrary(File(root, "moments"), File(root, "states"), File(root, "saves"), ownership = ownership)
    private val save = SaveStore(File(root, "saves"), fp)
    private val played = mutableListOf<PendingMoment>()
    private var changed = 0

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun sram(v: Int) = ByteArray(32) { v.toByte() }

    private fun show() = compose.setContent {
        PocketGBTheme {
            MomentsScreen(
                title = "Juego", fingerprint = fp, library = library, openFingerprint = null,
                onPlayMoment = { played += it }, onSaveChanged = { changed++ }, onBack = {},
            )
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun groupsByCollectionFiltersByTagEditsAndLoads() {
        val store = library.store(fp)
        val a = store.create(MomentStore.Capture("PGBS".toByteArray(), sram(1), null), "Jefe final", tags = listOf("jefe"), collection = "Principal")
        val b = store.create(MomentStore.Capture("PGBS".toByteArray(), sram(2), null), "Prueba rara", tags = listOf("bug"), collection = "Experimentos")
        show()
        waitTag("moment-${a.id}")
        compose.onNodeWithTag("moments-collection-Principal").assertIsDisplayed()
        compose.onNodeWithTag("moments-collection-Experimentos").assertIsDisplayed()

        compose.onNodeWithTag("moments-tag-jefe").performClick()
        compose.onAllNodesWithTag("moment-${b.id}").assertCountEquals(0)
        compose.onNodeWithTag("moments-tag-jefe").performClick()
        waitTag("moment-${b.id}")

        compose.onNodeWithTag("moment-edit-${b.id}").performScrollTo().performClick()
        waitTag("moments-edit-name")
        compose.onNodeWithTag("moments-edit-name").performTextClearance()
        compose.onNodeWithTag("moments-edit-name").performTextInput("Experimento 2")
        compose.onNodeWithTag("moments-edit-note").performTextInput("con nota")
        compose.onNodeWithTag("moments-confirm").performClick()
        assertTrue(waitUntil(8_000) { library.store(fp).snapshot().moments.any { it.name == "Experimento 2" && it.note == "con nota" } })
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("Experimento 2")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("moment-load-${a.id}").performScrollTo().performClick()
        waitTag("moments-dialog-body")
        compose.onNode(hasText("Se abrirá el juego", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("moments-confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PendingMoment(MomentStore.Kind.MOMENT, a.id, "Jefe final")), played)
    }

    @Test fun recoverInOneTapAndNothingWhileTheGameOwnsTheFingerprint() {
        save.save(sram(1)) // partida tras cargar un momento antiguo
        library.store(fp).pushBeforeLoad(MomentStore.Capture(null, sram(9), null), "Antiguo") // la más nueva
        val lease = ownership.tryAcquire(fp, "sesión aparcada")!!
        show()
        waitTag("moments-recover-0")
        compose.onNodeWithTag("moments-recover-0").performClick()
        compose.onNodeWithTag("moments-confirm").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("El juego está abierto", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals("con la huella ocupada no cambia nada", sram(1), save.load())
        lease.close()

        compose.onNodeWithTag("moments-recover-0").performClick()
        compose.onNodeWithTag("moments-confirm").performClick()
        assertTrue(waitUntil(8_000) { save.load()!!.contentEquals(sram(9)) })
        assertArrayEquals("la de antes queda en el backup", sram(1), save.backupFile(1).readBytes())
        assertTrue(waitUntil { changed == 1 })
    }
}
