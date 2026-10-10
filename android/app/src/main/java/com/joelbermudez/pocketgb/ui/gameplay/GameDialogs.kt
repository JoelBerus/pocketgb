package com.joelbermudez.pocketgb.ui.gameplay

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.game.GameDialog
import com.joelbermudez.pocketgb.game.GameNotice
import com.joelbermudez.pocketgb.game.GameplayViewModel
import com.joelbermudez.pocketgb.game.OpenError
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.saf.MirrorDisabledReason

/** Causa legible de un error del sistema para mostrarla junto a una acción de recuperación. */
fun causeText(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

/** H12: el aviso de solo lectura no se pierde cuando hay otro aviso de linaje. */
@Composable
private fun withReadOnly(text: String, readOnly: Boolean): String =
    if (readOnly) text + "\n\n" + stringResource(R.string.warning_mirror_read_only) else text

@Composable
fun saveLoadWarningText(warning: SaveLoadWarning): String = when (warning) {
    SaveLoadWarning.LocalWrongSize -> stringResource(R.string.warning_local_wrong_size)
    SaveLoadWarning.MirrorWrongSizeOnly -> stringResource(R.string.warning_mirror_wrong_size_only)
    SaveLoadWarning.MirrorIgnored -> stringResource(R.string.warning_mirror_ignored)
    SaveLoadWarning.LocalQuarantined -> stringResource(R.string.warning_local_quarantined)
    SaveLoadWarning.MirrorUnavailable -> stringResource(R.string.warning_mirror_unavailable)
    SaveLoadWarning.MirrorShared -> stringResource(R.string.warning_mirror_shared)
    SaveLoadWarning.MirrorReadOnly -> stringResource(R.string.warning_mirror_read_only)
    SaveLoadWarning.LocalSetAside -> stringResource(R.string.n1_warning_local_set_aside)
    is SaveLoadWarning.Unreadable -> stringResource(R.string.warning_unreadable, warning.detail)
    is SaveLoadWarning.ExternalChange -> withReadOnly(stringResource(R.string.n7_warning_external_change), warning.readOnly)
    is SaveLoadWarning.MirrorOlderSetAside -> withReadOnly(stringResource(R.string.n7_warning_mirror_older), warning.readOnly)
    is SaveLoadWarning.Divergence -> withReadOnly(stringResource(R.string.n7_warning_divergence_done), warning.readOnly)
    is SaveLoadWarning.GameSettingsMismatch ->
        stringResource(if (warning.noSave) R.string.n8_warning_settings_no_save else R.string.n8_warning_settings_mismatch)
}

@Composable
fun openErrorText(error: OpenError): String = when (error) {
    is OpenError.Unplayable -> error.problem.message
    OpenError.FolderMissing -> stringResource(R.string.open_error_folder_missing)
    OpenError.PermissionRevoked -> stringResource(R.string.open_error_permission)
    OpenError.RemotePending -> stringResource(R.string.open_error_remote)
    OpenError.RomTooLarge -> stringResource(R.string.open_error_too_large)
    OpenError.RomTooLargeGba -> stringResource(R.string.n8_open_error_too_large_gba)
    is OpenError.RomRejected -> stringResource(R.string.open_error_rom, causeText(error.error))
    OpenError.MirrorNotDownloaded -> stringResource(R.string.open_error_mirror_not_downloaded)
    OpenError.SaveIncompatible -> stringResource(R.string.open_error_save_incompatible)
    is OpenError.LocalSaveFailed -> stringResource(R.string.open_error_local_save, causeText(error.error))
    OpenError.SavePending -> stringResource(R.string.open_error_save_pending)
    OpenError.Unreadable -> stringResource(R.string.open_error_unreadable)
    is OpenError.Core -> stringResource(R.string.open_error_core, causeText(error.error))
    is OpenError.ResumeFailed -> resumeFailureText(error.reason)
    is OpenError.SaveDivergence -> stringResource(R.string.n7_warning_divergence_title)
}

/** Por qué no se pudo continuar (A9); siempre con la partida intacta. */
@Composable
fun resumeFailureText(reason: ResumeFailure): String = stringResource(
    when (reason) {
        ResumeFailure.MISSING -> R.string.a9_resume_failed_missing
        ResumeFailure.UNREADABLE -> R.string.a9_resume_failed_unreadable
        ResumeFailure.NOT_CURRENT -> R.string.a9_resume_failed_not_current
        ResumeFailure.INCOMPATIBLE -> R.string.a9_resume_failed_incompatible
        ResumeFailure.CORRUPT -> R.string.a9_resume_failed_corrupt
        ResumeFailure.SAVE_NOT_LOADED -> R.string.a9_resume_failed_save_not_loaded
    },
)

/**
 * «No se pudo continuar» (A9, el aviso de iOS D8.1): explica por qué el estado automático no vale y ofrece «Jugar desde
 * el inicio», que abre la misma partida sin el estado. Cancelar no abre nada; en ningún caso se ha escrito la partida.
 */
@Composable
fun ResumeFailedDialog(reason: ResumeFailure, onPlayFromStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.a9_resume_failed_title)) },
        text = { Text(stringResource(R.string.a9_resume_failed_body, resumeFailureText(reason))) },
        confirmButton = {
            Button(onClick = onPlayFromStart, modifier = Modifier.heightIn(min = 48.dp).testTag("resume-failed-play")) {
                Text(stringResource(R.string.a9_resume_failed_play))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("resume-failed-cancel")) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
        modifier = Modifier.testTag("resume-failed-dialog"),
    )
}

/**
 * ND20 (a): divergencia al abrir. Nada se ha escrito: «Cancelar» no abre el juego y deja las dos partidas como estaban.
 * La que no se elige queda como momento «Conflicto», en las copias y apartada.
 */
@Composable
fun SaveDivergenceDialog(localDateMs: Long?, mirrorDateMs: Long?, onKeepLocal: () -> Unit, onUseMirror: () -> Unit, onDismiss: () -> Unit) {
    fun date(ms: Long?) = ms?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) }
    val unknown = stringResource(R.string.saves_backup_unknown_date)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.n7_warning_divergence_title)) },
        text = { Text(stringResource(R.string.n7_divergence_ask, date(localDateMs) ?: unknown, date(mirrorDateMs) ?: unknown)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKeepLocal, modifier = Modifier.heightIn(min = 48.dp).testTag("divergence-keep-local")) {
                    Text(stringResource(R.string.n7_divergence_keep_local))
                }
                TextButton(onClick = onUseMirror, modifier = Modifier.heightIn(min = 48.dp).testTag("divergence-use-mirror")) {
                    Text(stringResource(R.string.n7_divergence_use_mirror))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("divergence-cancel")) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        },
        modifier = Modifier.testTag("save-divergence-dialog"),
    )
}

/** Texto del snackbar de un [GameNotice] (fuera de Compose: lo usa un colector de flujo). */
fun noticeText(context: Context, notice: GameNotice): String {
    return when (notice) {
        GameNotice.MirrorTrouble -> context.getString(R.string.notice_mirror_trouble)
        is GameNotice.MirrorDisabled -> context.getString(
            if (notice.reason == MirrorDisabledReason.ExternalChange) R.string.notice_mirror_external else R.string.notice_mirror_name,
        )
        is GameNotice.StateFailed -> context.getString(R.string.notice_state_failed, causeText(notice.error))
        GameNotice.SavePending -> context.getString(R.string.notice_save_pending)
        GameNotice.RescueStateExists -> context.getString(R.string.notice_rescue_state)
        GameNotice.HeaderDamaged -> context.getString(R.string.notice_header_damaged)
        GameNotice.RescueMoment -> context.getString(R.string.n6_rescue_moment)
        is GameNotice.MomentSaved -> context.getString(R.string.n6_saved, notice.name)
        is GameNotice.MomentLoaded -> context.getString(R.string.n6_loaded, notice.name)
        GameNotice.MomentRecovered -> context.getString(R.string.n6_recovered)
        GameNotice.MomentDeleted -> context.getString(R.string.n6_deleted)
        GameNotice.CoverPinned -> context.getString(R.string.n5_notice_cover_pinned)
        GameNotice.CoverPinFailed -> context.getString(R.string.n5_notice_cover_failed)
    }
}

/** Cortina con progreso mientras se abre un juego (lee la ROM y la partida; puede tardar con nube). */
@Composable
fun OpeningOverlay(modifier: Modifier = Modifier) {
    val openingDescription = stringResource(R.string.gameplay_opening_game)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(enabled = true, onClick = {})
            .testTag("opening-overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                .padding(24.dp)
                .semantics { contentDescription = openingDescription },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.opening_game), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SaveLoadWarningDialog(warning: SaveLoadWarning, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        warning is SaveLoadWarning.GameSettingsMismatch && warning.noSave -> R.string.n8_warning_settings_no_save_title
                        warning is SaveLoadWarning.GameSettingsMismatch -> R.string.n8_warning_settings_mismatch_title
                        else -> R.string.warning_title
                    },
                ),
            )
        },
        text = { Text(saveLoadWarningText(warning)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("warning-ok")) {
                Text(stringResource(R.string.warning_ok))
            }
        },
        modifier = Modifier.testTag("save-warning-dialog"),
    )
}

@Composable
fun OpenErrorDialog(error: OpenError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_failed_title)) },
        text = { Text(openErrorText(error)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("open-error-ok")) {
                Text(stringResource(R.string.open_failed_ok))
            }
        },
        modifier = Modifier.testTag("open-error-dialog"),
    )
}

/**
 * No se pudo guardar la partida local al salir (SPEC §6). Nunca se cierra limpio ignorándolo: reintentar,
 * seguir jugando o salir con riesgo, y esto último pide una segunda confirmación en estilo de error.
 */
@Composable
fun ExitSaveFailedDialog(
    error: Throwable,
    confirmingRisk: Boolean,
    onRetry: () -> Unit,
    onKeepPlaying: () -> Unit,
    onRequestLeave: () -> Unit,
    onConfirmLeave: () -> Unit,
    onCancelLeave: () -> Unit,
) {
    if (confirmingRisk) {
        AlertDialog(
            onDismissRequest = onCancelLeave,
            title = { Text(stringResource(R.string.exit_risk_title), color = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.exit_risk_body)) },
            confirmButton = {
                Button(
                    onClick = onConfirmLeave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("exit-risk-confirm"),
                ) { Text(stringResource(R.string.exit_risk_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelLeave, modifier = Modifier.heightIn(min = 48.dp).testTag("exit-risk-cancel")) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            modifier = Modifier.testTag("exit-risk-dialog"),
        )
        return
    }
    AlertDialog(
        onDismissRequest = onKeepPlaying,
        title = { Text(stringResource(R.string.exit_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.exit_failed_cause, causeText(error)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.exit_failed_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onRequestLeave,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("exit-failed-leave"),
                ) { Text(stringResource(R.string.exit_failed_leave)) }
            }
        },
        confirmButton = {
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("exit-failed-retry")) {
                Text(stringResource(R.string.exit_failed_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepPlaying, modifier = Modifier.heightIn(min = 48.dp).testTag("exit-failed-keep")) {
                Text(stringResource(R.string.exit_failed_keep))
            }
        },
        modifier = Modifier.testTag("exit-failed-dialog"),
    )
}

/** Los diálogos de la partida, dirigidos por el ViewModel. Se pintan encima de la biblioteca o del juego. */
@Composable
fun GameDialogs(viewModel: GameplayViewModel) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    when (val current = dialog) {
        null -> Unit
        is GameDialog.OpenFailed -> OpenErrorDialog(current.error, viewModel::dismissDialog)
        is GameDialog.LoadWarning -> SaveLoadWarningDialog(current.warning, viewModel::dismissDialog)
        is GameDialog.ExitSaveFailed -> ExitSaveFailedDialog(
            error = current.error,
            confirmingRisk = current.confirmingRisk,
            onRetry = { viewModel.exit(force = false) },
            onKeepPlaying = viewModel::keepPlaying,
            onRequestLeave = viewModel::requestRiskyExit,
            onConfirmLeave = { viewModel.exit(force = true) },
            onCancelLeave = viewModel::cancelRiskyExit,
        )
        is GameDialog.ResumeFailed -> ResumeFailedDialog(
            reason = current.reason,
            onPlayFromStart = viewModel::playFromStartAfterResumeFailure,
            onDismiss = viewModel::dismissDialog,
        )
        is GameDialog.SaveDivergence -> SaveDivergenceDialog(
            localDateMs = current.localDateMs,
            mirrorDateMs = current.mirrorDateMs,
            onKeepLocal = { viewModel.resolveDivergence(com.joelbermudez.pocketgb.saves.SaveOpening.DivergenceChoice.KEEP_LOCAL) },
            onUseMirror = { viewModel.resolveDivergence(com.joelbermudez.pocketgb.saves.SaveOpening.DivergenceChoice.USE_MIRROR) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

/**
 * Indicador PERSISTENTE de guardado pendiente o fallido: se ve mientras exista el problema (a diferencia de un
 * snackbar, que desaparece). Se oculta solo cuando el guardado local vuelve a estar confirmado.
 */
@Composable
fun SaveProblemBanner(problem: Throwable?, modifier: Modifier = Modifier) {
    if (problem == null) return
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.testTag("save-problem-indicator"),
    ) {
        Text(
            stringResource(R.string.save_problem_indicator),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
