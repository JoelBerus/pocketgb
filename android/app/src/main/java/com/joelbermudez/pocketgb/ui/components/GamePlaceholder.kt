package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.RomConsole
import kotlin.math.min

/**
 * Semilla estable de un juego (su huella o, si no se conoce, su id). FNV-1a de 32 bits: no cambia entre
 * arranques ni entre dispositivos, a diferencia de `hashCode()`.
 */
object PlaceholderSeed {
    private const val OFFSET_BASIS = 0x811C9DC5L
    private const val PRIME = 0x01000193L

    fun value(key: String): Long {
        var hash = OFFSET_BASIS
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * PRIME) and 0xFFFFFFFFL
        }
        return hash
    }

    /** Índice del color de fondo (4 colores). */
    fun colorIndex(key: String): Int = (value(key) % 4).toInt()

    /** Glifo abstracto 5×5 simétrico en horizontal; la celda central siempre está encendida. */
    fun glyph(key: String): List<List<Boolean>> {
        val bits = value(key) shr 8
        val cells = Array(5) { BooleanArray(5) }
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val on = (bits shr (row * 3 + col)) and 1L == 1L
                cells[row][col] = on
                cells[row][4 - col] = on
            }
        }
        cells[2][2] = true
        return cells.map { it.toList() }
    }

    /** Iniciales de hasta dos palabras («DMG-ACID2» → «DA»). */
    fun initials(title: String): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val letters = words.take(2).map { it.first().uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }
}

private val placeholderColors = listOf(
    Color(0xFF2E7D6B),
    Color(0xFF5B5BD6),
    Color(0xFFB5564B),
    Color(0xFF8A6D1F),
)

/**
 * Portada generada (SPEC §11): color y glifo derivados de la huella, iniciales y chip GB/GBC/GBA. No imita arte
 * comercial. Colores fijos (no dependen del tema); se dibuja sin suavizado. El llamador fija la proporción.
 */
@Composable
fun GamePlaceholder(
    seed: String,
    title: String,
    console: RomConsole,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val glyph = PlaceholderSeed.glyph(seed)
    val description = stringResource(R.string.placeholder_description, title)
    Box(
        modifier
            .background(placeholderColors[PlaceholderSeed.colorIndex(seed)])
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = min(size.width, size.height) * if (compact) 0.6f else 0.42f
            val cell = side / 5f
            val origin = Offset(
                (size.width - side) / 2f,
                (size.height - side) / 2f - if (compact) 0f else size.height * 0.08f,
            )
            val inset = cell * 0.08f
            for (row in 0 until 5) {
                for (col in 0 until 5) {
                    if (!glyph[row][col]) continue
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.85f),
                        topLeft = Offset(origin.x + col * cell + inset, origin.y + row * cell + inset),
                        size = Size(cell - 2 * inset, cell - 2 * inset),
                        cornerRadius = CornerRadius(cell * 0.2f),
                    )
                }
            }
        }
        if (!compact) {
            Text(
                PlaceholderSeed.initials(title),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            Text(
                console.shortName,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
