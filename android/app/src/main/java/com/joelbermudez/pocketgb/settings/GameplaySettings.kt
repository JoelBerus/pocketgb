package com.joelbermudez.pocketgb.settings

import com.joelbermudez.pocketgb.emulator.EmulationOptions
import com.joelbermudez.pocketgb.emulator.GbModel
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlLayout
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.input.NormalizedPoint
import com.joelbermudez.pocketgb.input.PadAction
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Máximo id de paleta de compatibilidad (`GB_COMPAT_PALETTES` en `core/include/pocketgb.h`); 0 = automática. */
const val MAX_COMPAT_PALETTE = 12

val OPACITY_CHOICES = listOf(30, 50, 70, 100)
val SIZE_SCALE_CHOICES = listOf(0.85f, 1f, 1.15f)
const val MIN_CONTROL_SCALE = 0.6f
const val MAX_CONTROL_SCALE = 1.6f
const val MIN_SIZE_SCALE = 0.85f
const val MAX_SIZE_SCALE = 1.15f

private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

fun isValidFingerprint(value: String): Boolean = FINGERPRINT.matches(value)

@Serializable
enum class ControlsVisibility { ALWAYS, ON_TOUCH, HIDDEN }

@Serializable
enum class DpadStyle { CROSS, ARROWS }

/**
 * Mapeo del mando físico (A7): `bindings` va de `PadAction.name` a `KeyEvent.KEYCODE_*`. Lo que no esté en `bindings`
 * conserva su botón por defecto (ver [resolved]); `controllerMapping == null` o [DEFAULT] = todo por defecto.
 */
@Serializable
data class ControllerMappingData(val bindings: Map<String, Int> = emptyMap()) {
    /** Descarta acciones desconocidas, teclas inválidas (≤ 0, cruceta, atrás) y duplicados (gana la última). */
    fun sanitized(): ControllerMappingData {
        val clean = LinkedHashMap<String, Int>()
        for ((name, code) in bindings) {
            if (PadAction.entries.none { it.name == name } || !isAssignableKey(code)) continue
            clean.values.removeAll { it == code }
            clean.remove(name)
            clean[name] = code
        }
        return ControllerMappingData(clean)
    }

    /** Tecla → acción efectiva: lo personalizado, más los valores por defecto de las acciones sin personalizar. */
    fun resolved(): Map<Int, PadAction> {
        val custom = sanitized().bindings
        val result = LinkedHashMap<Int, PadAction>()
        for ((name, code) in custom) result[code] = PadAction.valueOf(name)
        for ((code, action) in DEFAULT_PAIRS) {
            if (action.name !in custom && code !in result) result[code] = action
        }
        return result
    }

    companion object {
        /** Todo por defecto. */
        val DEFAULT = ControllerMappingData()

        /** Por defecto (R1/R3): por posición, A de Game Boy en el botón derecho y B en el inferior. */
        val DEFAULT_PAIRS: List<Pair<Int, PadAction>> = listOf(
            KeyEvent.KEYCODE_BUTTON_B to PadAction.A,
            KeyEvent.KEYCODE_BUTTON_A to PadAction.B,
            KeyEvent.KEYCODE_BUTTON_START to PadAction.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadAction.SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE to PadAction.MENU,
            KeyEvent.KEYCODE_BUTTON_L1 to PadAction.MENU,
            KeyEvent.KEYCODE_BUTTON_R1 to PadAction.FAST_FORWARD,
        )

        fun isAssignableKey(code: Int): Boolean =
            code > 0 && code != KeyEvent.KEYCODE_BACK && code !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER

        /** Asigna [code] a [action] partiendo de lo que hoy vale [current]; quien tuviera esa tecla la pierde. */
        fun assign(current: ControllerMappingData?, action: PadAction, code: Int): ControllerMappingData {
            val base = LinkedHashMap<String, Int>()
            for ((key, owner) in (current ?: DEFAULT).resolved()) base.putIfAbsent(owner.name, key)
            base.values.removeAll { it == code }
            base.remove(action.name)
            base[action.name] = code
            return ControllerMappingData(base).sanitized()
        }
    }
}

/** Espejo serializable de `ControlLayout`: posiciones 0..1 relativas a la zona de controles y escalas 0,6..1,6. */
@Serializable
data class StoredControlLayout(
    val positions: Map<ControlId, NormalizedPoint> = emptyMap(),
    val scales: Map<ControlId, Float> = emptyMap(),
)

/** Ajustes de un juego; `null` = usa el ajuste global. */
@Serializable
data class GameOverrides(
    val colorForGameBoy: Boolean? = null,
    /** 0 = automática; 1..12. */
    val compatPalette: Int? = null,
) {
    val isEmpty get() = colorForGameBoy == null && compatPalette == null
}

enum class SelectedModel { AUTO, DMG, CGB }

/** Resultado de resolver global + por juego; [toOptions] lo traduce a las opciones del núcleo (GbModel + paleta). */
data class EmulationSelection(val model: SelectedModel, val compatPalette: Int) {
    fun toOptions(): EmulationOptions = EmulationOptions(
        model = when (model) {
            SelectedModel.AUTO -> GbModel.AUTO
            SelectedModel.DMG -> GbModel.DMG
            SelectedModel.CGB -> GbModel.CGB
        },
        compatPalette = compatPalette.coerceIn(0, MAX_COMPAT_PALETTE),
    )
}

@Serializable
data class GameplaySettingsData(
    val schema: Int = 1,
    /** {30, 50, 70, 100}. Solo visual: el área táctil no cambia. */
    val opacity: Int = 70,
    val visibility: ControlsVisibility = ControlsVisibility.ALWAYS,
    val haptics: Boolean = true,
    /** 0,85..1,15. */
    val sizeScale: Float = 1f,
    val portraitLayout: StoredControlLayout = StoredControlLayout(),
    val landscapeLayout: StoredControlLayout = StoredControlLayout(),
    val integerScaleLandscape: Boolean = true,
    val dpadStyle: DpadStyle = DpadStyle.CROSS,
    /** 0..1, ganancia lineal. */
    val volume: Float = 1f,
    val colorForGameBoy: Boolean = false,
    val compatPalette: Int = 0,
    /** Clave: huella SHA-256 hex minúscula de 64 caracteres. Nunca por ruta. */
    val perGame: Map<String, GameOverrides> = emptyMap(),
    val controllerMapping: ControllerMappingData? = null,
    /** Con un mando conectado los controles táctiles se ocultan salvo que esto sea `true`. */
    val showTouchControlsWithController: Boolean = false,
) {
    /** Normaliza lo que haya venido del disco o de un cambio: ningún valor fuera de rango sale de aquí. */
    fun sanitized(): GameplaySettingsData = copy(
        opacity = if (opacity in OPACITY_CHOICES) opacity else 70,
        sizeScale = sizeScale.finiteOr(1f).coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE),
        portraitLayout = portraitLayout.sanitized(),
        landscapeLayout = landscapeLayout.sanitized(),
        volume = volume.finiteOr(1f).coerceIn(0f, 1f),
        compatPalette = if (compatPalette in 0..MAX_COMPAT_PALETTE) compatPalette else 0,
        controllerMapping = controllerMapping?.sanitized(),
        perGame = perGame.mapNotNull { (key, value) ->
            if (!isValidFingerprint(key)) return@mapNotNull null
            val clean = value.sanitized()
            if (clean.isEmpty) null else key to clean
        }.toMap(),
    )

    fun update(change: (GameplaySettingsData) -> GameplaySettingsData): GameplaySettingsData =
        change(this).sanitized()

    fun layout(orientation: ControlsOrientation): StoredControlLayout =
        if (orientation == ControlsOrientation.PORTRAIT) portraitLayout else landscapeLayout

    private fun withLayout(orientation: ControlsOrientation, layout: StoredControlLayout) =
        if (orientation == ControlsOrientation.PORTRAIT) copy(portraitLayout = layout) else copy(landscapeLayout = layout)

    /** Guarda un control movido, solo en esa orientación. */
    fun move(orientation: ControlsOrientation, id: ControlId, point: NormalizedPoint): GameplaySettingsData {
        val clamped = NormalizedPoint(point.x.finiteOr(0.5f).coerceIn(0f, 1f), point.y.finiteOr(0.5f).coerceIn(0f, 1f))
        val current = layout(orientation)
        return withLayout(orientation, current.copy(positions = current.positions + (id to clamped)))
    }

    /** Cambia el tamaño de un control en pasos de 0,1 (recorte 0,6..1,6), solo en esa orientación. */
    fun resize(orientation: ControlsOrientation, id: ControlId, delta: Float): GameplaySettingsData {
        val current = layout(orientation)
        val base = current.scales[id] ?: 1f
        val next = (((base + delta) * 10f).roundToInt() / 10f).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)
        return withLayout(orientation, current.copy(scales = current.scales + (id to next)))
    }

    fun resetLayout(orientation: ControlsOrientation): GameplaySettingsData =
        withLayout(orientation, StoredControlLayout())

    /** `true` si la disposición de esa orientación equivale a la de fábrica (aunque haya entradas guardadas). */
    fun isFactoryLayout(orientation: ControlsOrientation): Boolean {
        val stored = layout(orientation)
        val defaults = ControlLayout.defaults(orientation)
        val positionsMatch = stored.positions.all { (id, point) ->
            val factory = defaults.centers[id]
            factory != null && abs(factory.x - point.x) < EPSILON && abs(factory.y - point.y) < EPSILON
        }
        return positionsMatch && stored.scales.all { (_, scale) -> abs(scale - 1f) < EPSILON }
    }

    /** Ajustes de un juego; al quedar todo en «Global» se borra la entrada. */
    fun setOverrides(fingerprint: String, overrides: GameOverrides): GameplaySettingsData {
        require(isValidFingerprint(fingerprint)) { "Huella inválida" }
        val clean = overrides.sanitized()
        return copy(perGame = if (clean.isEmpty) perGame - fingerprint else perGame + (fingerprint to clean))
    }

    /**
     * Global + lo personalizado del juego. Un ROM GBC va siempre en color (`AUTO`); uno DMG usa `CGB` con color
     * efectivo y `DMG` sin él. [fingerprint] `null` (nunca abierto) solo usa lo global.
     */
    fun emulation(fingerprint: String?, isCgbRom: Boolean): EmulationSelection {
        val override = fingerprint?.let { perGame[it] }
        val color = override?.colorForGameBoy ?: colorForGameBoy
        val palette = override?.compatPalette ?: compatPalette
        val model = when {
            isCgbRom -> SelectedModel.AUTO
            color -> SelectedModel.CGB
            else -> SelectedModel.DMG
        }
        return EmulationSelection(model, palette)
    }

}

private const val EPSILON = 1e-4f

private fun Float.finiteOr(default: Float) = if (isFinite()) this else default

private fun GameOverrides.sanitized() =
    copy(compatPalette = compatPalette?.takeIf { it in 0..MAX_COMPAT_PALETTE })

private fun StoredControlLayout.sanitized() = StoredControlLayout(
    positions = positions.filterValues { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f },
    scales = scales.filterValues { it.isFinite() }.mapValues { it.value.coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE) },
)
