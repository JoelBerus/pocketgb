package com.joelbermudez.pocketgb.ui.gameplay

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R

/** Avance rápido cíclico ×1 → ×2 → ×4 → ×1 (K13). Una velocidad desconocida vuelve a la normal. */
object SpeedCycle {
    fun next(current: Int): Int = when (current) {
        1 -> 2
        2 -> 4
        else -> 1
    }
}

private val HudScrim = Color(0x73000000)
private val FastForwardActive = Color(0xFFFFA04D)

/** Confirmación táctil del botón de avance rápido: `CONFIRM` (API 30+) o un toque de tecla. */
fun View.confirmHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
    )
}

/**
 * HUD del juego (K13): un botón de pausa que abre «Pausa y opciones» y un botón que cicla el avance rápido, con texto
 * además del símbolo y valor accesible. Fijo: solo los controles se desvanecen. Sobre fondo oscuro siempre (K4).
 */
@Composable
fun GameplayHud(
    speed: Int,
    onPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: Boolean = true,
) {
    val view = LocalView.current
    val pauseLabel = stringResource(R.string.hud_pause)
    val speedLabel = stringResource(R.string.hud_speed)
    val speedState = if (speed > 1) stringResource(R.string.hud_speed_value, speed) else stringResource(R.string.hud_speed_off)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(HudScrim)
                .clickable(role = Role.Button, onClick = onPause)
                .semantics { contentDescription = pauseLabel }
                .testTag("hud-pause"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Row(
            Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(HudScrim)
                .clickable(role = Role.Button) {
                    if (haptics) view.confirmHaptic()
                    onCycleSpeed()
                }
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = speedLabel
                    stateDescription = speedState
                }
                .testTag("hud-speed"),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (speed > 1) FastForwardActive else Color.White
            Icon(
                if (speed > 1) Icons.Filled.FastForward else Icons.Outlined.FastForward,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            if (speed > 1) {
                Text(stringResource(R.string.hud_speed_value, speed), color = tint, fontWeight = FontWeight.Bold)
            }
        }
    }
}
