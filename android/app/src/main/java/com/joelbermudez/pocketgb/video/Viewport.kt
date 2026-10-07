package com.joelbermudez.pocketgb.video

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.ScaleMode
import com.joelbermudez.pocketgb.emulator.ScreenSize
import kotlin.math.min

/**
 * Rectángulo donde se dibuja la imagen de la consola ([ScreenSize]: 160×144 en GB, 240×160 en GBA) dentro de la
 * superficie. Espeja `compute_layout` de `native_session.c` con la misma geometría que el blit (N8): [ScaleMode.INTEGER]
 * usa el mayor múltiplo entero que quepa (si no cabe ninguno, el fraccional que quepa) y [ScaleMode.FILL] la proporción
 * de la consola (10:9 o 3:2) que quepa, centrado. [scale] es 0 en Llenar.
 */
data class Viewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val scale: Int,
) {
    companion object {
        fun calculate(
            width: Int,
            height: Int,
            mode: ScaleMode = ScaleMode.INTEGER,
            screen: ScreenSize = Console.GB.screen,
        ): Viewport {
            require(width > 0 && height > 0)
            val screenWidth = screen.width
            val screenHeight = screen.height
            val scale = min(width / screenWidth, height / screenHeight)
            var drawWidth: Int
            var drawHeight: Int
            if (mode == ScaleMode.INTEGER && scale >= 1) {
                drawWidth = screenWidth * scale
                drawHeight = screenHeight * scale
            } else if (width.toLong() * screenHeight <= height.toLong() * screenWidth) {
                drawWidth = width
                drawHeight = (width.toLong() * screenHeight / screenWidth).toInt()
            } else {
                drawHeight = height
                drawWidth = (height.toLong() * screenWidth / screenHeight).toInt()
            }
            // Como el nativo: el mínimo de 1 px se aplica después de elegir el caso.
            if (drawWidth < 1) drawWidth = 1
            if (drawHeight < 1) drawHeight = 1
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
