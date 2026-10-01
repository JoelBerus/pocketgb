package com.joelbermudez.pocketgb.video

import com.joelbermudez.pocketgb.emulator.CoreBridge
import kotlin.math.min

data class IntegerViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val scale: Int,
) {
    companion object {
        fun calculate(width: Int, height: Int): IntegerViewport {
            require(width > 0 && height > 0)
            val scale = min(width / CoreBridge.SCREEN_WIDTH, height / CoreBridge.SCREEN_HEIGHT)
            val drawWidth: Int
            val drawHeight: Int
            if (scale >= 1) {
                drawWidth = CoreBridge.SCREEN_WIDTH * scale
                drawHeight = CoreBridge.SCREEN_HEIGHT * scale
            } else if (width.toLong() * CoreBridge.SCREEN_HEIGHT <= height.toLong() * CoreBridge.SCREEN_WIDTH) {
                drawWidth = width
                drawHeight = (width * CoreBridge.SCREEN_HEIGHT / CoreBridge.SCREEN_WIDTH).coerceAtLeast(1)
            } else {
                drawHeight = height
                drawWidth = (height * CoreBridge.SCREEN_WIDTH / CoreBridge.SCREEN_HEIGHT).coerceAtLeast(1)
            }
            return IntegerViewport(
                left = (width - drawWidth) / 2,
                top = (height - drawHeight) / 2,
                width = drawWidth,
                height = drawHeight,
                scale = scale,
            )
        }
    }
}
