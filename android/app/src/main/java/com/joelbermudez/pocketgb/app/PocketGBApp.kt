package com.joelbermudez.pocketgb.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.LaunchMode
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.joelbermudez.pocketgb.settings.AppearanceRepository
import com.joelbermudez.pocketgb.settings.AppearanceState
import com.joelbermudez.pocketgb.settings.GameplaySettingsRepository
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.saves.SavesBrowser
import com.joelbermudez.pocketgb.ui.gameplay.GameplayRoot
import com.joelbermudez.pocketgb.ui.settings.SavesScreen
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.joelbermudez.pocketgb.ui.about.AboutScreen
import com.joelbermudez.pocketgb.ui.details.GameDetailsScreen
import com.joelbermudez.pocketgb.ui.favorites.FavoritesScreen
import com.joelbermudez.pocketgb.ui.library.LibraryScreen
import com.joelbermudez.pocketgb.ui.settings.AppearanceScreen
import com.joelbermudez.pocketgb.ui.settings.AudioSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.ControllerMappingScreen
import com.joelbermudez.pocketgb.ui.settings.ControlsSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.DisplaySettingsScreen
import com.joelbermudez.pocketgb.ui.settings.EmulationSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.LicensesScreen
import com.joelbermudez.pocketgb.ui.settings.StorageSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.HomeSettingsScreen
import com.joelbermudez.pocketgb.ui.settings.LibrarySettingsScreen
import com.joelbermudez.pocketgb.ui.settings.SettingsScreen
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import com.joelbermudez.pocketgb.ui.library.ENABLE_LIST_DETAIL
import com.joelbermudez.pocketgb.ui.library.LibraryListDetail
import kotlinx.coroutines.launch

@Composable
fun PocketGBApp(
    appearance: AppearanceState,
    appearanceRepository: AppearanceRepository,
    library: LibraryViewModel,
    gameplay: GameplayViewModel,
    gameplaySettings: GameplaySettingsRepository,
    /** N7b: documento recibido por «Abrir con» / «Compartir» (se valida por cabecera). */
    incomingUri: android.net.Uri? = null,
    onIncomingHandled: () -> Unit = {},
) {
    // El estado de navegación vive aquí (no en el Scaffold): al salir de una partida se vuelve al mismo sitio.
    val navigationState = rememberSaveable(saver = AppNavigationState.Saver) { AppNavigationState() }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val travel = remember(gameplay, library) {
        val service = com.joelbermudez.pocketgb.travel.TravelService(appContext)
        com.joelbermudez.pocketgb.ui.travel.TravelEnvironment(
            onSaveChanged = gameplay::didRestoreSave,
            onContinue = { entry -> gameplay.open(entry, LaunchMode.RESUME) },
            onMergeMetadata = { entry, fingerprint, meta -> service.mergeMetadata(entry, fingerprint, meta, library) },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(com.joelbermudez.pocketgb.ui.travel.LocalTravelEnvironment provides travel) {
        GameplayRoot(gameplay) {
            AppContent(navigationState, appearance, appearanceRepository, library, gameplay, gameplaySettings)
            IncomingRoute(incomingUri, library, onIncomingHandled)
            ExchangeInboxRoute(library)
        }
    }
}

/**
 * N7c: al terminar cada escaneo de la biblioteca se mira `PocketGB/Intercambio/`; el primer paquete nuevo de un juego de
 * la biblioteca (no enviado desde aquí) se ofrece para importar. «Ahora no» lo da por visto (sigue en la carpeta).
 */
@Composable
private fun ExchangeInboxRoute(library: LibraryViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val service = remember(context) { com.joelbermudez.pocketgb.travel.TravelService(context.applicationContext) }
    val state by library.state.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val ready = state as? com.joelbermudez.pocketgb.library.LibraryState.Ready ?: return
    class Offer(val item: com.joelbermudez.pocketgb.travel.ExchangeFolder.Item, val bytes: ByteArray, val entry: RomEntry, val device: String)
    var offer by remember { androidx.compose.runtime.mutableStateOf<Offer?>(null) }
    var importing by remember { androidx.compose.runtime.mutableStateOf<Offer?>(null) }
    LaunchedEffect(ready) {
        offer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val exchange = service.exchange() ?: return@runCatching null
                for (item in service.inbox.pending(exchange.list()).take(8)) {
                    val bytes = exchange.read(item.documentId)
                    val peek = service.importer.peek(bytes) as? com.joelbermudez.pocketgb.travel.SaveImporter.Peek.Package
                    val id = peek?.let { p -> prefs.fingerprints.entries.firstOrNull { it.value == p.romFingerprint }?.key }
                    val entry = ready.entries.firstOrNull { it.id == id }
                    if (peek == null || entry == null) { service.inbox.markSeen(item); continue }
                    return@runCatching Offer(item, bytes, entry, peek.meta.deviceName)
                }
                null
            }.getOrNull()
        }
    }
    offer?.let { o ->
        com.joelbermudez.pocketgb.ui.travel.InboxDialog(
            o.device, o.entry.alias ?: o.entry.title,
            onImport = {
                // H6: solo se da por visto cuando la importación termina (onImported) o con «Ahora no».
                offer = null
                importing = o
            },
            onLater = {
                offer = null
                kotlin.concurrent.thread { runCatching { service.inbox.markSeen(o.item) } }
            },
        )
    }
    val current = importing
    com.joelbermudez.pocketgb.ui.travel.IncomingPackageHost(
        current?.bytes, ready.entries, prefs, onDone = { importing = null },
        onImported = { current?.let { o -> kotlin.concurrent.thread { runCatching { service.inbox.markSeen(o.item) } } } },
    )
}

/** Nombre visible del documento recibido (para buscar el juego de un `.sav` crudo, ND20 e). */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

/** N7b: lee (fuera del hilo principal, con tope) el documento recibido y lo pasa al importador. */
@Composable
private fun IncomingRoute(uri: android.net.Uri?, library: LibraryViewModel, onHandled: () -> Unit) {
    if (uri == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val bytes by androidx.compose.runtime.produceState<Result<ByteArray>?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.joelbermudez.pocketgb.travel.TravelService(context.applicationContext).read(uri) }
        }
    }
    val state by library.state.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val entries = when (val s = state) {
        is com.joelbermudez.pocketgb.library.LibraryState.Ready -> s.entries
        is com.joelbermudez.pocketgb.library.LibraryState.Scanning -> s.previous
        else -> emptyList()
    }
    val read = bytes ?: return
    val name = remember(uri) { runCatching { displayName(context, uri) }.getOrNull() }
    read.fold(
        { com.joelbermudez.pocketgb.ui.travel.IncomingPackageHost(it, entries, prefs, onHandled, displayName = name) },
        {
            com.joelbermudez.pocketgb.ui.travel.InfoDialog(
                androidx.compose.ui.res.stringResource(com.joelbermudez.pocketgb.R.string.n7_reject_damaged), onHandled,
            )
        },
    )
}

@Composable
private fun AppContent(
    navigationState: AppNavigationState,
    appearance: AppearanceState,
    appearanceRepository: AppearanceRepository,
    library: LibraryViewModel,
    gameplay: GameplayViewModel,
    gameplaySettings: GameplaySettingsRepository,
) {
    val scope = rememberCoroutineScope()

    // A9 · «Continuar» exacto: qué huellas tienen estado automático vigente. Se recalcula al conocer huellas nuevas y
    // al salir de una partida (lo hace el ViewModel). «Continuar» retoma el estado; «Jugar desde el inicio», la partida.
    val resumable by gameplay.resumable.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val knownFingerprints = remember(prefs.fingerprints) { prefs.fingerprints.values.toSet() }
    LaunchedEffect(knownFingerprints) { gameplay.refreshContinuations(knownFingerprints) }
    val currentResumable by rememberUpdatedState(resumable)
    val currentFingerprints by rememberUpdatedState(prefs.fingerprints)
    val play: (RomEntry) -> Unit = remember(gameplay) {
        { entry ->
            val fingerprint = currentFingerprints[entry.id]
            val resume = entry.isPlayable && fingerprint != null && fingerprint in currentResumable
            gameplay.open(entry, if (resume) LaunchMode.RESUME else LaunchMode.FRESH)
        }
    }
    val playFromStart: (RomEntry) -> Unit = remember(gameplay) { { entry -> gameplay.open(entry, LaunchMode.FRESH) } }

    BackHandler(enabled = navigationState.currentBackStack.size > 1) {
        navigationState.pop()
    }
    val context = LocalContext.current
    val savesBrowser = remember(context) { SavesBrowser(File(context.filesDir, "saves")) }
    val gameSaves: @Composable (String, () -> Unit) -> Unit = { fingerprint, onBack ->
        SavesScreen(savesBrowser, gameplay, onBack = onBack, onlyFingerprint = fingerprint)
    }
    val momentLibrary = remember(context) {
        com.joelbermudez.pocketgb.saves.MomentLibrary(
            momentsRoot = File(context.filesDir, "moments"),
            statesRoot = File(context.filesDir, "states"),
            savesDirectory = File(context.filesDir, "saves"),
            migratedName = { com.joelbermudez.pocketgb.game.migratedMomentName(context, it) },
        )
    }
    val gameMoments: @Composable (String, () -> Unit) -> Unit = { gameId, onBack ->
        com.joelbermudez.pocketgb.ui.moments.GameMomentsRoute(library, gameplay, momentLibrary, gameId, onBack)
    }
    val libraryDeps = LibraryRouteDeps(
        library = library,
        play = play,
        playFromStart = playFromStart,
        resumable = resumable,
        gameplaySettings = gameplaySettings,
        saves = gameSaves,
        moments = gameMoments,
    )

    AppScaffold(navigationState) {
        NavDisplay(
            backStack = navigationState.currentBackStack,
            onBack = { navigationState.pop() },
            entryProvider = { route ->
                when (route) {
                    LibraryRoute.Root -> NavEntry(route) {
                        if (ENABLE_LIST_DETAIL && isExpandedWidth(currentWindowAdaptiveInfo().windowSizeClass.minWidthDp)) {
                            // Expanded: lista y detalle a la vez (A7 R14); desactivado hasta pulir el panel doble.
                            LibraryListDetail(
                                list = { openDetails ->
                                    LibraryScreen(
                                        viewModel = library,
                                        onOpenDetails = openDetails,
                                        onPlay = play,
                                        gameplaySettings = gameplaySettings,
                                        onPlayFromStart = playFromStart,
                                        resumable = resumable,
                                    )
                                },
                                detail = { id ->
                                    GameDetailsScreen(
                                        library, id, onPlay = play, onBack = {},
                                        gameplaySettings = gameplaySettings,
                                        onPlayFromStart = playFromStart,
                                        resumable = resumable,
                                    )
                                },
                            )
                        } else {
                            LibraryRouteContent(LibraryRoute.Root, navigationState, libraryDeps)
                        }
                    }
                    is LibraryRoute.Details, is LibraryRoute.Category, is LibraryRoute.GameSaves, is LibraryRoute.Moments -> NavEntry(route) {
                        LibraryRouteContent(route as LibraryRoute, navigationState, libraryDeps)
                    }
                    FavoritesRoute.Root -> NavEntry(route) {
                        FavoritesScreen(
                            viewModel = library,
                            onOpenDetails = { navigationState.push(FavoritesRoute.Details(it)) },
                            onPlay = play,
                            gameplaySettings = gameplaySettings,
                            onPlayFromStart = playFromStart,
                            resumable = resumable,
                            onOpenSaves = { navigationState.push(FavoritesRoute.GameSaves(it)) },
                            onOpenMoments = { navigationState.push(FavoritesRoute.Moments(it)) },
                        )
                    }
                    is FavoritesRoute.Details -> NavEntry(route) {
                        GameDetailsScreen(
                            library, route.gameId, onPlay = play, onBack = { navigationState.pop() },
                            gameplaySettings = gameplaySettings,
                            onPlayFromStart = playFromStart,
                            resumable = resumable,
                            onOpenSaves = { navigationState.push(FavoritesRoute.GameSaves(it)) },
                            onOpenMoments = { navigationState.push(FavoritesRoute.Moments(it)) },
                        )
                    }
                    is FavoritesRoute.GameSaves -> NavEntry(route) { gameSaves(route.fingerprint) { navigationState.pop() } }
                    is FavoritesRoute.Moments -> NavEntry(route) { gameMoments(route.gameId) { navigationState.pop() } }
                    SettingsRoute.Root -> NavEntry(route) {
                        SettingsScreen(
                            onEmulation = { navigationState.push(SettingsRoute.SettingsEmulation) },
                            onControls = { navigationState.push(SettingsRoute.SettingsControls) },
                            onAudio = { navigationState.push(SettingsRoute.SettingsAudio) },
                            onDisplay = { navigationState.push(SettingsRoute.SettingsDisplay) },
                            onStorage = { navigationState.push(SettingsRoute.SettingsStorage) },
                            onAppearance = { navigationState.push(SettingsRoute.Appearance) },
                            onLibrary = { navigationState.push(SettingsRoute.Library) },
                            onSaves = { navigationState.push(SettingsRoute.Saves) },
                            onAbout = { navigationState.push(SettingsRoute.About) },
                        )
                    }
                    SettingsRoute.Appearance -> NavEntry(route) {
                        AppearanceScreen(
                            appearance = appearance,
                            onThemeModeChange = { mode ->
                                scope.launch { appearanceRepository.setThemeMode(mode) }
                            },
                            onDynamicColorChange = { enabled ->
                                scope.launch { appearanceRepository.setDynamicColor(enabled) }
                            },
                            onBack = { navigationState.pop() },
                        )
                    }
                    SettingsRoute.Library -> NavEntry(route) {
                        LibrarySettingsScreen(
                            library,
                            onBack = { navigationState.pop() },
                            onOpenHome = { navigationState.push(SettingsRoute.LibraryHome) },
                        )
                    }
                    SettingsRoute.LibraryHome -> NavEntry(route) {
                        HomeSettingsScreen(library, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.Saves -> NavEntry(route) {
                        SavesScreen(savesBrowser, gameplay, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.About -> NavEntry(route) {
                        AboutScreen(
                            onBack = { navigationState.pop() },
                            onLicenses = { navigationState.push(SettingsRoute.SettingsLicenses) },
                        )
                    }
                    SettingsRoute.SettingsControls -> NavEntry(route) {
                        ControlsSettingsScreen(
                            gameplaySettings,
                            onBack = { navigationState.pop() },
                            onController = { navigationState.push(SettingsRoute.SettingsController) },
                        )
                    }
                    SettingsRoute.SettingsController -> NavEntry(route) {
                        ControllerMappingScreen(gameplaySettings, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.SettingsDisplay -> NavEntry(route) {
                        DisplaySettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.SettingsEmulation -> NavEntry(route) {
                        EmulationSettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.SettingsAudio -> NavEntry(route) {
                        AudioSettingsScreen(gameplaySettings, onBack = { navigationState.pop() })
                    }
                    SettingsRoute.SettingsStorage -> NavEntry(route) {
                        StorageSettingsScreen(onBack = { navigationState.pop() })
                    }
                    SettingsRoute.SettingsLicenses -> NavEntry(route) {
                        LicensesScreen(onBack = { navigationState.pop() })
                    }
                }
            },
        )
    }
}
