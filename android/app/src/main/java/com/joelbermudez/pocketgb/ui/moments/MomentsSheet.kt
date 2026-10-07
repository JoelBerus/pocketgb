package com.joelbermudez.pocketgb.ui.moments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.game.MomentsUi
import com.joelbermudez.pocketgb.ui.gameplay.SheetOrDialog

/** N6 · hoja «Momentos» de la pausa (sustituye a la de estados): crear, cargar, recuperar, editar y borrar. */
@Composable
fun MomentsSheet(
    landscape: Boolean,
    ui: MomentsUi,
    snackbar: SnackbarHostState,
    actions: MomentActions,
    currentConfig: Map<String, String>,
    onBack: () -> Unit,
    preview: MomentsPreview? = null,
) {
    SheetOrDialog(landscape = landscape, onDismiss = onBack, modifier = Modifier.testTag("states-sheet")) {
        MomentsSheetContent(ui, snackbar, actions, currentConfig, onBack, preview)
    }
}

@Composable
fun MomentsSheetContent(
    ui: MomentsUi,
    snackbar: SnackbarHostState,
    actions: MomentActions,
    currentConfig: Map<String, String>,
    onBack: () -> Unit,
    preview: MomentsPreview? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("states-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.n6_moments_back))
            }
            Text(stringResource(R.string.n6_moments_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (ui.busy || !ui.loaded) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(24.dp), strokeWidth = 2.dp)
        }
        MomentsList(
            place = MomentsPlace.GAME,
            snapshot = ui.snapshot,
            thumbnails = ui.thumbnails,
            busy = ui.busy,
            actions = actions,
            currentConfig = currentConfig,
            preview = preview,
        )
        SnackbarHost(snackbar)
    }
}
