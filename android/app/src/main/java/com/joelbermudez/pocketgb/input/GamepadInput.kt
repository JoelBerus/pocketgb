package com.joelbermudez.pocketgb.input

import android.view.InputDevice
import android.view.KeyEvent
import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.GbaButtonBits
import com.joelbermudez.pocketgb.settings.ControllerMappingData
import com.joelbermudez.pocketgb.settings.DiagonalMode
import kotlin.math.hypot

/** Lo que un botón del mando puede hacer: un botón de Game Boy o una acción de la app. */
enum class PadAction { A, B, START, SELECT, MENU, FAST_FORWARD }

/** [mask] = botones de Game Boy pulsados ahora; [actions] = acciones de la app que se disparan con este evento. */
data class PadOutput(val mask: Int, val actions: Set<PadAction> = emptySet())

/** `true` si el dispositivo con estas `sources` es un mando (gamepad o joystick), no un teclado. */
fun isGamepadSources(sources: Int): Boolean =
    (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

/**
 * `true` si un evento de ejes viene de un mando: su fuente es de mando, o su dispositivo ([deviceSources]) lo es aunque
 * emita el hat con `SOURCE_DPAD` (DS-H1). Un teclado o mando a distancia con solo `SOURCE_DPAD` no cuenta.
 */
fun isPadMotion(eventSource: Int, deviceSources: Int): Boolean =
    isGamepadSources(eventSource) || isGamepadSources(deviceSources)

/**
 * Estado puro de un mando: traduce teclas y ejes a la máscara de Game Boy y a acciones de la app (R1–R4).
 * Botones, cruceta digital, hat y stick izquierdo se combinan con OR y después se anulan los opuestos. Las acciones
 * de la app (menú, avance rápido) solo se disparan al pasar de suelto a pulsado: una repetición no las repite.
 *
 * **Game Boy Advance (N8, como iOS: hombros = L/R):** con [console] = GBA, L1 y R1 son SIEMPRE L y R del juego, sea
 * cual sea el mapeo; lo que el mapeo tenga en L1 o R1 (de fábrica, el menú y el avance rápido) pasa a L2 o R2, si
 * están libres ([GamepadMapping.forConsole]). El botón MODE (Guía) sigue abriendo el menú.
 */
class GamepadState(mapping: ControllerMappingData? = null, console: Console = Console.GB) {
    private val keys: Map<Int, PadAction> = GamepadMapping.forConsole((mapping ?: ControllerMappingData.DEFAULT).resolved(), console)
    private val shoulders: Map<Int, Int> = if (console == Console.GBA) GamepadMapping.GBA_SHOULDERS else emptyMap()
    private val held = HashSet<Int>()
    private var dpadKeys = 0
    private var shoulderKeys = 0
    private var hat = 0
    private var stick = 0

    /** `true` si esta tecla es de cruceta, de L/R (GBA) o está asignada a algo. */
    fun handles(keyCode: Int): Boolean = keyCode in keys || keyCode in shoulders || dpadBit(keyCode) != 0

    fun onKey(keyCode: Int, down: Boolean): PadOutput {
        val bit = dpadBit(keyCode)
        if (bit != 0) {
            dpadKeys = if (down) dpadKeys or bit else dpadKeys and bit.inv()
            return output()
        }
        shoulders[keyCode]?.let { shoulder ->
            shoulderKeys = if (down) shoulderKeys or shoulder else shoulderKeys and shoulder.inv()
            return output()
        }
        val action = keys[keyCode] ?: return output()
        val fired = if (down) held.add(keyCode) else { held.remove(keyCode); false }
        val actions = if (fired && action.isAppAction) setOf(action) else emptySet()
        return output(actions)
    }

    /** [hatX]/[hatY] del hat (−1, 0, 1) y [x]/[y] del stick izquierdo (−1..1, Y crece hacia abajo). */
    fun onAxes(hatX: Float, hatY: Float, x: Float, y: Float): PadOutput {
        // El ajuste «Diagonales» es de la cruceta táctil: un stick físico sigue con ocho sectores de 45° (y su propio umbral).
        hat = if (hypot(hatX, hatY) < HAT_THRESHOLD) 0 else ControlGeometry.dpadMask(hatX, hatY, 1f, DiagonalMode.NORMAL)
        stick = if (hypot(x, y) < STICK_THRESHOLD) 0 else ControlGeometry.dpadMask(x, y, 1f, DiagonalMode.NORMAL)
        return output()
    }

    /** Suelta todo (desconexión, pérdida de foco): máscara 0. */
    fun reset(): PadOutput {
        held.clear()
        dpadKeys = 0
        shoulderKeys = 0
        hat = 0
        stick = 0
        return PadOutput(0)
    }

    private fun output(actions: Set<PadAction> = emptySet()): PadOutput {
        var buttons = 0
        for (code in held) {
            when (keys[code]) {
                PadAction.A -> buttons = buttons or GameBoyButton.A.mask
                PadAction.B -> buttons = buttons or GameBoyButton.B.mask
                PadAction.START -> buttons = buttons or GameBoyButton.START.mask
                PadAction.SELECT -> buttons = buttons or GameBoyButton.SELECT.mask
                else -> Unit
            }
        }
        return PadOutput(buttons or shoulderKeys or withoutOpposites(dpadKeys or hat or stick), actions)
    }

    private val PadAction.isAppAction get() = this == PadAction.MENU || this == PadAction.FAST_FORWARD

    private fun dpadBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> GameBoyButton.UP.mask
        KeyEvent.KEYCODE_DPAD_DOWN -> GameBoyButton.DOWN.mask
        KeyEvent.KEYCODE_DPAD_LEFT -> GameBoyButton.LEFT.mask
        KeyEvent.KEYCODE_DPAD_RIGHT -> GameBoyButton.RIGHT.mask
        else -> 0
    }

    private companion object {
        const val HAT_THRESHOLD = 0.5f
        const val STICK_THRESHOLD = 0.5f
    }
}

/**
 * Mapeo del mando por consola (N8). En Game Boy, el de [ControllerMappingData]. En Game Boy Advance los hombros L1/R1
 * son L y R del juego (= iOS `GamepadMapping`: `leftShoulder`/`rightShoulder`), así que lo que tuvieran asignado se
 * reasigna: L1 → L2 y R1 → R2 (de fábrica: menú en L2 y avance rápido en R2), salvo que L2/R2 ya tengan otra acción,
 * en cuyo caso esa acción queda solo en sus otras teclas (el menú sigue en MODE).
 */
object GamepadMapping {
    /** L1 → L (bit 9) y R1 → R (bit 8) en GBA. */
    val GBA_SHOULDERS: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to GbaButtonBits.L,
        KeyEvent.KEYCODE_BUTTON_R1 to GbaButtonBits.R,
    )

    /** Dónde va en GBA lo que estaba en un hombro. */
    private val MOVED_TO = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R2,
    )

    /** Tecla → acción efectiva en [console] a partir de [resolved] (el mapeo ya resuelto de Game Boy). */
    fun forConsole(resolved: Map<Int, PadAction>, console: Console): Map<Int, PadAction> {
        if (console != Console.GBA) return resolved
        val result = LinkedHashMap(resolved)
        for ((shoulder, target) in MOVED_TO) {
            val action = result.remove(shoulder) ?: continue
            result.putIfAbsent(target, action)
        }
        return result
    }
}
