package com.joelbermudez.pocketgb.video

import com.joelbermudez.pocketgb.emulator.CoreBridge
import com.joelbermudez.pocketgb.emulator.ScaleMode
import kotlin.math.min

/**
 * Rectángulo donde se dibuja la imagen de 160x144 dentro de la superficie. Espeja `compute_layout` de
 * `native_session.c`: [ScaleMode.INTEGER] usa el mayor múltiplo entero que quepa (si no cabe ninguno, el
 * fraccional que quepa) y [ScaleMode.FILL] el 10:9 que quepa, centrado. [scale] es 0 en Llenar.
 */
data class Viewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val scale: Int,
) {
    companion object {
        fun calculate(width: Int, height: Int, mode: ScaleMode = ScaleMode.INTEGER): Viewport {
            require(width > 0 && height > 0)
            val scale = min(width / CoreBridge.SCREEN_WIDTH, height / CoreBridge.SCREEN_HEIGHT)
            val drawWidth: Int
            val drawHeight: Int
            if (mode == ScaleMode.INTEGER && scale >= 1) {
                drawWidth = CoreBridge.SCREEN_WIDTH * scale
                drawHeight = CoreBridge.SCREEN_HEIGHT * scale
            } else if (width.toLong() * CoreBridge.SCREEN_HEIGHT <= height.toLong() * CoreBridge.SCREEN_WIDTH) {
                drawWidth = width
                drawHeight = (width.toLong() * CoreBridge.SCREEN_HEIGHT / CoreBridge.SCREEN_WIDTH).toInt().coerceAtLeast(1)
            } else {
                drawHeight = height
                drawWidth = (height.toLong() * CoreBridge.SCREEN_WIDTH / CoreBridge.SCREEN_HEIGHT).toInt().coerceAtLeast(1)
            }
            return Viewport(
                left = (width - drawWidth) / 2,
                top = (height - drawHeight) / 2,
                width = drawWidth,
                height = drawHeight,
                scale = if (mode == ScaleMode.INTEGER) scale else 0,
            )
        }
    }
}
