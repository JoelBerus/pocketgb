package com.joelbermudez.pocketgb.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.progress.GameProgress
import com.joelbermudez.pocketgb.progress.MilestoneTemplate
import com.joelbermudez.pocketgb.progress.PokemonProgress
import com.joelbermudez.pocketgb.progress.ProgressService
import com.joelbermudez.pocketgb.progress.ProgressView
import com.joelbermudez.pocketgb.ui.moments.formatMomentDate
import com.joelbermudez.pocketgb.ui.moments.formatPlayTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Acciones sobre el progreso; con [editable] = `false` (detalle) solo se marcan hitos y se acepta la propuesta. */
class ProgressActions(
    val onToggle: (String, Boolean) -> Unit,
    val onMarkBadges: (List<String>) -> Unit,
    val onTemplate: (MilestoneTemplate) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onShowPercent: (Boolean) -> Unit = {},
)

/** Títulos de la plantilla «Pokémon: 8 medallas + Liga». */
@Composable
fun pokemonTemplateTitles(): List<String> = (1..8).map { stringResource(R.string.n6_progress_badge, it) } + stringResource(R.string.n6_progress_league)

/**
 * N6 · panel de progreso: tiempo de juego, sesiones, primera y última vez; hitos con casillas (y porcentaje si está
 * activado, desactivado por defecto); y el panel del lector Pokémon, que propone marcar las medallas leídas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressPanel(view: ProgressView, actions: ProgressActions, editable: Boolean, modifier: Modifier = Modifier) {
    val p = view.progress
    val badgeTitles = (1..8).map { stringResource(R.string.n6_progress_badge, it) }
    Column(modifier.fillMaxWidth().testTag("progress-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.n6_progress_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.n6_progress_time), formatPlayTime(p.playTimeMs), "progress-time")
            Stat(stringResource(R.string.n6_progress_sessions), p.sessions.toString(), "progress-sessions")
            Stat(stringResource(R.string.n6_progress_first), p.firstPlayedMs?.let(::formatMomentDate) ?: stringResource(R.string.n6_progress_never), "progress-first")
            Stat(stringResource(R.string.n6_progress_last), p.lastPlayedMs?.let(::formatMomentDate) ?: stringResource(R.string.n6_progress_never), "progress-last")
        }
        val percent = p.percent
        if (p.showPercent && percent != null) {
            Text(stringResource(R.string.n6_progress_percent, percent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("progress-percent"))
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        view.pokemon?.let { PokemonPanel(it, ProgressService.badgeSuggestionStatic(p, badgeTitles, it), actions) }
        if (editable) {
            Text(stringResource(R.string.n6_progress_template), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = p.template == MilestoneTemplate.FREE, onClick = { actions.onTemplate(MilestoneTemplate.FREE) },
                    label = { Text(stringResource(R.string.n6_progress_template_free)) }, modifier = Modifier.testTag("progress-template-free"),
                )
                FilterChip(
                    selected = p.template == MilestoneTemplate.POKEMON, onClick = { actions.onTemplate(MilestoneTemplate.POKEMON) },
                    label = { Text(stringResource(R.string.n6_progress_template_pokemon)) }, modifier = Modifier.testTag("progress-template-pokemon"),
                )
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = p.showPercent, role = Role.Switch, onValueChange = actions.onShowPercent)
                    .testTag("progress-show-percent"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.n6_progress_show_percent), modifier = Modifier.weight(1f))
                Switch(checked = p.showPercent, onCheckedChange = null)
            }
        }
        Text(stringResource(R.string.n6_progress_milestones), style = MaterialTheme.typography.titleSmall)
        if (p.milestones.isEmpty()) {
            Text(stringResource(R.string.n6_progress_no_milestones), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (m in p.milestones) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .toggleable(value = m.done, role = Role.Checkbox, onValueChange = { actions.onToggle(m.id, it) })
                        .testTag("progress-milestone-${m.title}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = m.done, onCheckedChange = null)
                    Text(m.title, modifier = Modifier.padding(start = 8.dp))
                }
                if (editable) {
                    val description = stringResource(R.string.n6_progress_remove, m.title)
                    IconButton(onClick = { actions.onRemove(m.id) }) { Icon(Icons.Outlined.Close, contentDescription = description) }
                }
            }
        }
        if (editable) {
            var draft by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(GameProgress.MAX_TITLE) },
                    label = { Text(stringResource(R.string.n6_progress_add_hint)) }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("progress-add-field"),
                )
                OutlinedButton(
                    onClick = { actions.onAdd(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("progress-add"),
                ) { Text(stringResource(R.string.n6_progress_add)) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, tag: String) {
    Column(Modifier.widthIn(min = 120.dp).semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PokemonPanel(pokemon: PokemonProgress, suggestion: List<String>, actions: ProgressActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("progress-pokemon"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.n6_reader_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(stringResource(R.string.n6_reader_player), pokemon.playerName, "pokemon-player")
                Stat(stringResource(R.string.n6_reader_badges), "${pokemon.badges}/${pokemon.game.maxBadges}", "pokemon-badges")
                Stat(stringResource(R.string.n6_reader_pokedex), stringResource(R.string.n6_reader_pokedex_value, pokemon.pokedexOwned, pokemon.pokedexSeen), "pokemon-dex")
                Stat(stringResource(R.string.n6_reader_time), "%d:%02d".format(pokemon.hours, pokemon.minutes), "pokemon-time")
                Stat(stringResource(R.string.n6_reader_money), "₽" + java.text.NumberFormat.getIntegerInstance().format(pokemon.money), "pokemon-money")
            }
            if (suggestion.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { actions.onMarkBadges(suggestion) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pokemon-suggest"),
                ) { Text(stringResource(R.string.n6_reader_suggest, minOf(pokemon.badges, 8))) }
            }
            Text(stringResource(R.string.n6_reader_footer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Panel de progreso conectado al almacén: lee, aplica cambios y se refresca. `null` en [fingerprint] = nada que mostrar. */
@Composable
fun ProgressHost(fingerprint: String?, editable: Boolean, modifier: Modifier = Modifier, service: ProgressService? = null) {
    if (fingerprint == null) return
    val context = LocalContext.current
    val progress = service ?: remember(context) { ProgressService.shared(context) }
    var generation by remember { mutableIntStateOf(0) }
    val view by produceState<ProgressView?>(null, fingerprint, generation) {
        value = withContext(Dispatchers.IO) { try { progress.view(fingerprint) } catch (_: Exception) { null } }
    }
    val scope = rememberCoroutineScope()
    val pokemonTitles = pokemonTemplateTitles()
    fun change(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { try { block() } catch (_: Exception) {} }
            generation++
        }
    }
    val store = progress.store
    val actions = ProgressActions(
        onToggle = { id, done -> change { store.setDone(fingerprint, id, done) } },
        onMarkBadges = { ids -> change { store.markFirst(fingerprint, ids) } },
        onTemplate = { t -> change { store.applyTemplate(fingerprint, t, if (t == MilestoneTemplate.POKEMON) pokemonTitles else emptyList()) } },
        onAdd = { title -> change { store.addMilestone(fingerprint, title) } },
        onRemove = { id -> change { store.removeMilestone(fingerprint, id) } },
        onShowPercent = { show -> change { store.setShowPercent(fingerprint, show) } },
    )
    view?.let { ProgressPanel(it, actions, editable, modifier) }
}

/** «Progreso» del centro de ajustes: hitos, plantillas, porcentaje y lector, en un diálogo a pantalla casi completa. */
@Composable
fun ProgressDialog(fingerprint: String, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.94f).testTag("progress-dialog"),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                ProgressHost(fingerprint, editable = true)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("progress-dialog-close")) {
                    Text(stringResource(R.string.n6_close))
                }
            }
        }
    }
}
