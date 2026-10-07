package com.joelbermudez.pocketgb.ui.moments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.game.MomentsUi
import com.joelbermudez.pocketgb.game.PendingMoment
import com.joelbermudez.pocketgb.saves.MomentLibrary
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.SavePendingException
import com.joelbermudez.pocketgb.saves.SaveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lee los momentos de [fingerprint] con sus miniaturas (fuera del hilo principal). */
internal fun readMomentsUi(library: MomentLibrary, fingerprint: String): MomentsUi = try {
    val snapshot = library.snapshot(fingerprint)
    val thumbs = buildMap {
        for (m in snapshot.moments) library.thumbnail(fingerprint, MomentStore.Kind.MOMENT, m.id)?.let { put("m-${m.id}", it) }
        for (m in snapshot.beforeLoad) library.thumbnail(fingerprint, MomentStore.Kind.BEFORE_LOAD, m.id)?.let { put("b-${m.id}", it) }
    }
    MomentsUi(snapshot, thumbs, loaded = true)
} catch (_: Exception) {
    MomentsUi(loaded = true)
}

/**
 * N6 · pantalla «Momentos» de un juego desde el detalle (sustituye a «Estados (próximamente)»). Sin sesión: «Cargar»
 * abre el juego y carga el momento con la sesión ya dueña de la huella; «Recuperar» instala la partida de antes de
 * cargar en un toque (la de ahora queda en el anillo y en el backup `.1`); editar y borrar no tocan la partida.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    title: String,
    fingerprint: String?,
    library: MomentLibrary,
    openFingerprint: String?,
    onPlayMoment: (PendingMoment) -> Unit,
    onSaveChanged: (String) -> Unit,
    onBack: () -> Unit,
    preview: MomentsPreview? = null,
    initial: MomentsUi? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    val ui by produceState(initial ?: MomentsUi(), fingerprint, generation) {
        if (initial != null && generation == 0) return@produceState
        value = if (fingerprint == null) MomentsUi(loaded = true) else withContext(Dispatchers.IO) { readMomentsUi(library, fingerprint) }
    }
    fun run(block: (String) -> Int?) {
        val fp = fingerprint ?: return
        busy = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    block(fp)?.let { context.getString(it) }
                } catch (_: SavePendingException) {
                    context.getString(R.string.n6_busy_game)
                } catch (_: IllegalStateException) {
                    context.getString(R.string.n6_busy_game)
                } catch (_: MomentLibrary.NoSramException) {
                    context.getString(R.string.n6_no_sram)
                } catch (_: SaveStore.InvalidBackupException) {
                    context.getString(R.string.n6_wrong_size)
                } catch (error: Exception) {
                    context.getString(R.string.n6_failed, error.message ?: error.javaClass.simpleName)
                }
            }
            busy = false
            generation++
            message?.let { snackbar.showSnackbar(it) }
        }
    }
    val actions = MomentActions(
        onLoad = { onPlayMoment(PendingMoment(MomentStore.Kind.MOMENT, it.id, it.name)) },
        onRecover = { entry ->
            run { fp ->
                library.installSram(fp, MomentStore.Kind.BEFORE_LOAD, entry.id, entry.name, openFingerprint)
                onSaveChanged(fp)
                R.string.n6_recovered
            }
        },
        onRecoverSram = { moment ->
            run { fp ->
                library.installSram(fp, MomentStore.Kind.MOMENT, moment.id, moment.name, openFingerprint)
                onSaveChanged(fp)
                R.string.n6_recovered
            }
        },
        onEdit = { m, name, tags, collection, note ->
            run { fp -> library.update(fp, m.id, name, tags, collection, note); null }
        },
        onDelete = { kind, m -> run { fp -> library.delete(fp, kind, m.id); R.string.n6_deleted } },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.n6_moments_title) + " · " + title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("moments-screen-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.n6_moments_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.testTag("moments-screen"),
    ) { padding ->
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MomentsList(
                    place = MomentsPlace.DETAILS,
                    snapshot = ui.snapshot,
                    thumbnails = ui.thumbnails,
                    busy = busy || fingerprint == null,
                    actions = actions,
                    preview = preview,
                )
            }
        }
    }
}
