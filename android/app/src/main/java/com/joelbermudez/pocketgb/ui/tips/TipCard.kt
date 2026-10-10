package com.joelbermudez.pocketgb.ui.tips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.tips.LocalTips
import com.joelbermudez.pocketgb.tips.Tip

private fun Tip.titleRes(): Int = when (this) {
    Tip.MOMENTS -> R.string.n9_tip_moments_title
    Tip.CATEGORY -> R.string.n9_tip_category_title
    Tip.SEND -> R.string.n9_tip_send_title
    Tip.ARROWS -> R.string.n9_tip_arrows_title
}

private fun Tip.bodyRes(): Int = when (this) {
    Tip.MOMENTS -> R.string.n9_tip_moments_body
    Tip.CATEGORY -> R.string.n9_tip_category_body
    Tip.SEND -> R.string.n9_tip_send_body
    Tip.ARROWS -> R.string.n9_tip_arrows_body
}

/**
 * Tarjeta de consejo descartable (TipKit en iOS). Solo se dibuja si la app da [LocalTips] y el consejo no se descartó;
 * «Entendido» lo descarta para siempre en este dispositivo (Ajustes › Guía los vuelve a mostrar). El texto crece con
 * la fuente (sin alto fijo) y TalkBack lee el título como encabezado.
 */
@Composable
fun TipCard(tip: Tip, modifier: Modifier = Modifier) {
    val tips = LocalTips.current ?: return
    if (!tips.isVisible(tip)) return
    val title = stringResource(tip.titleRes())
    val dismissDescription = stringResource(R.string.n9_tip_dismiss_description, title)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("tip-${tip.id}"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(tip.bodyRes()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(
                onClick = { tips.dismiss(tip) },
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .testTag("tip-${tip.id}-dismiss")
                    .semantics { contentDescription = dismissDescription },
            ) { Text(stringResource(R.string.n9_tip_dismiss)) }
        }
    }
}
