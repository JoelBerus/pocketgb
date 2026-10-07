package com.joelbermudez.pocketgb.ui.moments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.library.LibraryQuery
import com.joelbermudez.pocketgb.library.LibraryState
import com.joelbermudez.pocketgb.library.LibraryViewModel
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MomentLibrary

/** N6 · ruta «Momentos» de un juego (biblioteca y favoritos): conecta la pantalla con la biblioteca y la partida. */
@Composable
fun GameMomentsRoute(
    library: LibraryViewModel,
    gameplay: GameplayViewModel,
    moments: MomentLibrary,
    gameId: String,
    onBack: () -> Unit,
) {
    val state by library.state.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val openFingerprint by gameplay.openFingerprint.collectAsStateWithLifecycle()
    val entries = when (val current = state) {
        is LibraryState.Ready -> current.entries
        is LibraryState.Scanning -> current.previous
        else -> emptyList()
    }
    val entry = remember(entries, prefs, gameId) { LibraryQuery.presented(entries, prefs, gameId) }
    val fingerprint = entry?.let { prefs.fingerprints[it.id] }?.takeIf { entry.isPlayable && prefs.hasConfirmedFingerprint(entry) }
    MomentsScreen(
        title = entry?.displayTitle.orEmpty(),
        fingerprint = fingerprint,
        library = moments,
        openFingerprint = openFingerprint,
        onPlayMoment = { pending -> entry?.let { gameplay.open(it, LaunchMode.FRESH, pending) } },
        onSaveChanged = gameplay::didRestoreSave,
        onBack = onBack,
    )
}
