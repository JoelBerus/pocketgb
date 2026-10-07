package com.joelbermudez.pocketgb.debug.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.app.AppNavigationState
import com.joelbermudez.pocketgb.app.AppScaffold
import com.joelbermudez.pocketgb.debug.DebugIntent
import com.joelbermudez.pocketgb.game.MomentsUi
import com.joelbermudez.pocketgb.library.DetailsLoad
import com.joelbermudez.pocketgb.progress.GameProgress
import com.joelbermudez.pocketgb.progress.Milestone
import com.joelbermudez.pocketgb.progress.MilestoneTemplate
import com.joelbermudez.pocketgb.progress.PokemonGame
import com.joelbermudez.pocketgb.progress.PokemonProgress
import com.joelbermudez.pocketgb.progress.ProgressView
import com.joelbermudez.pocketgb.saves.MomentLibrary
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.settings.GameOverrides
import com.joelbermudez.pocketgb.settings.GameplaySettingsData
import com.joelbermudez.pocketgb.ui.details.GameCenterState
import com.joelbermudez.pocketgb.ui.details.GameDetailsContent
import com.joelbermudez.pocketgb.ui.details.GameSettingsSheet
import com.joelbermudez.pocketgb.ui.gameplay.PauseSheet
import com.joelbermudez.pocketgb.ui.moments.MomentActions
import com.joelbermudez.pocketgb.ui.moments.MomentsPreview
import com.joelbermudez.pocketgb.ui.moments.MomentsScreen
import com.joelbermudez.pocketgb.ui.moments.MomentsSheet
import com.joelbermudez.pocketgb.ui.progress.ProgressActions
import com.joelbermudez.pocketgb.ui.progress.ProgressPanel
import java.io.File

/**
 * N6 · momentos y progreso con datos sintéticos (capturas generadas, nada de arte comercial): un juego de rol con cuatro
 * momentos en dos colecciones («Principal», «Experimentos») y uno sin colección, dos entradas en «Antes de cargar», y un
 * Rojo internacional con hitos de la plantilla Pokémon y el panel del lector.
 */
internal object N6Data {
    private const val HOUR = 3_600_000L
    private val now = System.currentTimeMillis()

    private fun m(id: String, name: String, ago: Long, tags: List<String> = emptyList(), collection: String? = null, note: String = "", played: Long? = null, sram: Boolean = true, origin: String? = null) =
        MomentStore.Moment(
            id = id, name = name, tags = tags, collection = collection, note = note, createdMs = now - ago, playTimeMs = played,
            config = mapOf("console" to "GB", "model" to "CGB", "palette" to "0"), hasState = true, hasSram = sram, hasThumbnail = true,
            origin = origin,
        )

    val moments = listOf(
        m("a1", "Antes del gimnasio de Ciudad Celeste", 2 * HOUR, listOf("jefe"), "Principal", "Equipo a nivel 22. Llevar pociones.", 11 * HOUR + 40 * 60_000),
        m("a2", "Captura del legendario", 26 * HOUR, listOf("captura", "jefe"), "Experimentos", "Probar con Ultra Ball.", 9 * HOUR),
        m("a3", "Ruta 3, sin entrenar", 50 * HOUR, listOf("ruta"), "Principal", played = 6 * HOUR + 5 * 60_000),
        m("a4", "Ranura 1", 300 * HOUR, sram = false, origin = "slot1"),
    )
    val ring = listOf(
        MomentStore.Moment(id = "b1", name = "Captura del legendario", createdMs = now - 10 * 60_000, hasState = true, hasSram = true, hasThumbnail = true),
        MomentStore.Moment(id = "b2", name = "Ruta 3, sin entrenar", createdMs = now - 3 * HOUR, hasState = true, hasSram = true, hasThumbnail = true),
    )

    fun ui(empty: Boolean = false): MomentsUi {
        if (empty) return MomentsUi(MomentStore.Snapshot(emptyList(), emptyList()), loaded = true)
        val thumbs = buildMap {
            moments.forEachIndexed { i, x -> CatalogStates.thumbnail(i + 1)?.let { put("m-${x.id}", it) } }
            ring.forEachIndexed { i, x -> CatalogStates.thumbnail(i + 5)?.let { put("b-${x.id}", it) } }
        }
        return MomentsUi(MomentStore.Snapshot(moments, ring), thumbs, loaded = true)
    }

    val noActions = MomentActions(onCreate = {}, onLoad = {}, onRecover = {}, onRecoverSram = {}, onEdit = { _, _, _, _, _ -> }, onDelete = { _, _ -> })

    private val badges = (1..8).map { "Medalla $it" } + "Liga Pokémon"
    val progress = ProgressView(
        GameProgress(
            playTimeMs = 31 * HOUR + 12 * 60_000, sessions = 18, firstPlayedMs = now - 40 * 24 * HOUR, lastPlayedMs = now - 2 * HOUR,
            template = MilestoneTemplate.POKEMON,
            milestones = badges.mapIndexed { i, t -> Milestone("h$i", t, done = i < 3) },
            showPercent = true,
        ),
        PokemonProgress(PokemonGame.GEN1, "ROJO", 0x1f, 5, 62, 88, 31, 12, 4, 12_480),
    )
    val noProgressActions = ProgressActions(onToggle = { _, _ -> }, onMarkBadges = {})
}

@Composable
private fun N6Moments(preview: MomentsPreview? = null, empty: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val library = remember(context) {
        val root = File(context.cacheDir, "debug-moments-n6").apply { deleteRecursively() }
        MomentLibrary(File(root, "moments"), File(root, "states"), File(root, "saves"))
    }
    val navigation = remember { AppNavigationState() }
    AppScaffold(navigation) {
        MomentsScreen(
            title = "AVENTURA RPG",
            fingerprint = null,
            library = library,
            openFingerprint = null,
            onPlayMoment = {},
            onSaveChanged = {},
            onBack = {},
            preview = preview,
            initial = N6Data.ui(empty),
        )
    }
}

@Composable
private fun N6Pause(preview: MomentsPreview? = null, configMismatch: Boolean = false) {
    GameplayFrame {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MomentsSheet(
                landscape = false,
                ui = N6Data.ui(),
                snackbar = remember { SnackbarHostState() },
                actions = N6Data.noActions,
                currentConfig = mapOf("console" to "GB", "model" to if (configMismatch) "DMG" else "CGB", "palette" to "0"),
                onBack = {},
                preview = preview,
            )
        }
    }
}

@Composable
private fun N6Details() {
    val navigation = remember { AppNavigationState() }
    AppScaffold(navigation) { N6DetailsBody() }
}

@Composable
private fun N6DetailsBody() {
    val entry = N5Data.blocks
    run {
        GameDetailsContent(
            entry = entry,
            load = DetailsLoad.Loaded(N5Data.details(entry)),
            favorite = false,
            lastPlayedAt = System.currentTimeMillis() - 7_200_000L,
            onPlay = {},
            onToggleFavorite = {},
            onHide = {},
            onBack = {},
            fingerprint = N5Data.fingerprint(entry),
            canResume = true,
            onOpenMoments = {},
            progress = { ProgressPanel(N6Data.progress, N6Data.noProgressActions, editable = false) },
            initialInfoScroll = 1_400,
        )
    }
}

internal val n6CatalogScreens: Map<String, @Composable (DebugIntent) -> Unit> = buildMap {
    // Detalle › Momentos: anillo «Antes de cargar», etiquetas, colecciones y guía.
    put("n6-moments") { N6Moments() }
    put("n6-moments-empty") { N6Moments(empty = true) }
    put("n6-moments-load") { N6Moments(MomentsPreview.LOAD) }
    put("n6-moments-recover") { N6Moments(MomentsPreview.RECOVER) }
    put("n6-moments-edit") { N6Moments(MomentsPreview.EDIT) }
    put("n6-moments-ax5") { N6Moments() }
    // Pausa: el botón «Momentos» y su hoja.
    put("n6-pause") { _ ->
        GameplayFrame {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PauseSheet(landscape = false, busy = false, title = "AVENTURA RPG", onContinue = {}, onStates = {}, onCustomize = {}, onExit = {}, onUseAsCover = {})
            }
        }
    }
    put("n6-pause-moments") { N6Pause() }
    put("n6-pause-create") { N6Pause(MomentsPreview.CREATE) }
    put("n6-pause-load-config") { N6Pause(MomentsPreview.LOAD, configMismatch = true) }
    put("n6-pause-recover") { N6Pause(MomentsPreview.RECOVER) }
    // Detalle con «Momentos» activo y el panel de progreso con el lector Pokémon (desplazado hasta el panel).
    put("n6-details-progress") { N6Details() }
    // Centro de ajustes: fila «Progreso» y su diálogo editable (plantillas, porcentaje y añadir hitos).
    put("n6-game-center") { _ ->
        val entry = N5Data.blocks
        val navigation = remember { AppNavigationState() }
        AppScaffold(navigation) { N6DetailsBody() }
        GameSettingsSheet(
            title = entry.displayTitle,
            headerTitle = entry.title,
            onRename = {},
            console = entry.console,
            global = GameplaySettingsData(),
            overrides = GameOverrides(),
            onOverridesChange = {},
            onDismiss = {},
            center = GameCenterState(
                categoryPath = entry.categoryPath, folderPath = entry.folderPath, moved = false, tags = emptyList(), enabled = true,
                onChangeCategory = {}, onReturnToFolder = {}, onEditTags = {}, onOpenSaves = {}, onHide = {},
                title = entry.displayTitle, onOpenProgress = {},
            ),
        )
    }
    put("n6-progress-editor") { _ ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                ProgressPanel(N6Data.progress, N6Data.noProgressActions, editable = true)
            }
        }
    }
}
