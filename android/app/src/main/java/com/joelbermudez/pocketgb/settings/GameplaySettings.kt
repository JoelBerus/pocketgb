package com.joelbermudez.pocketgb.settings

import com.joelbermudez.pocketgb.emulator.Console
import com.joelbermudez.pocketgb.emulator.EmulationOptions
import com.joelbermudez.pocketgb.emulator.GbModel
import com.joelbermudez.pocketgb.emulator.GbaOptions
import com.joelbermudez.pocketgb.emulator.GbaRtc
import com.joelbermudez.pocketgb.emulator.GbaSaveType
import com.joelbermudez.pocketgb.input.ControlId
import com.joelbermudez.pocketgb.input.ControlLayout
import com.joelbermudez.pocketgb.input.ControlsOrientation
import com.joelbermudez.pocketgb.input.NormalizedPoint
import com.joelbermudez.pocketgb.input.PadAction
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Máximo id de paleta de compatibilidad (`GB_COMPAT_PALETTES` en `core/include/pocketgb.h`); 0 = automática. */
const val MAX_COMPAT_PALETTE = 12

val OPACITY_CHOICES = listOf(30, 50, 70, 100)
val SIZE_SCALE_CHOICES = listOf(0.85f, 1f, 1.15f)
const val MIN_CONTROL_SCALE = 0.6f
const val MAX_CONTROL_SCALE = 1.6f
const val MIN_SIZE_SCALE = 0.85f
const val MAX_SIZE_SCALE = 1.15f

/** Separación de las flechas separadas (N2, ND10): 1,0 = la distribución de fábrica; se guarda por orientación. */
const val MIN_DPAD_SEPARATION = 0.7f
const val MAX_DPAD_SEPARATION = 1.5f
const val DPAD_SEPARATION_STEP = 0.1f

private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

fun isValidFingerprint(value: String): Boolean = FINGERPRINT.matches(value)

@Serializable
enum class ControlsVisibility { ALWAYS, ON_TOUCH, HIDDEN }

@Serializable
enum class DpadStyle { CROSS, ARROWS }

/**
 * Cuánto cuentan las diagonales de la cruceta (N2). [NORMAL]: ocho sectores de 45° (las diagonales ocupan la mitad del
 * ángulo). [REDUCED]: la diagonal solo vale a ±15° de los 45°. [DISABLED]: solo cuatro direcciones.
 */
@Serializable
enum class DiagonalMode { NORMAL, REDUCED, DISABLED }

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

/**
 * Espejo serializable de `ControlLayout`: posiciones 0..1 relativas a la zona de controles, escalas 0,6..1,6 y la
 * separación de las flechas separadas 0,7..1,5 (N2; los archivos anteriores no la traen y valen 1,0).
 */
@Serializable
data class StoredControlLayout(
    val positions: Map<ControlId, NormalizedPoint> = emptyMap(),
    val scales: Map<ControlId, Float> = emptyMap(),
    val separation: Float = 1f,
)

/** Ajustes de un juego; `null` = usa el ajuste global (o lo detectado, en GBA). */
@Serializable
data class GameOverrides(
    val colorForGameBoy: Boolean? = null,
    /** 0 = automática; 1..12. */
    val compatPalette: Int? = null,
    /**
     * N8 (GBA, = iOS `gbaSaveType`): medio forzado con el valor nativo de [GbaSaveType] (1 «Sin partida» … 6 «EEPROM
     * 8 KiB»); `null` = «Detectado». Se guarda como número para que un valor desconocido no tumbe el archivo.
     */
    val gbaSaveType: Int? = null,
    /** N8 (GBA, = iOS `gbaRTC`): 1 «Con reloj», 2 «Sin reloj»; `null` = «Detectado». */
    val gbaRtc: Int? = null,
    /** N8 (GBA, = iOS `gbaUseBIOS`): `false` = «Emulada»; `null` = «Global» (la del usuario si existe y es la oficial). */
    val gbaUseBios: Boolean? = null,
) {
    val isEmpty get() = colorForGameBoy == null && compatPalette == null && gbaSaveType == null && gbaRtc == null &&
        gbaUseBios == null

    /** GBA: hay tipo de partida o reloj forzado (iOS `GameSettingsSaveWarning.check(forced:)`). */
    val gbaForced: Boolean get() = gbaSaveType != null || gbaRtc != null

    /** Las opciones del núcleo de GBA: lo forzado o AUTO, y la BIOS del usuario salvo «Emulada». */
    fun gbaOptions(): GbaOptions = GbaOptions(
        saveType = gbaSaveType?.let { value -> GbaSaveType.entries.firstOrNull { it.native == value } } ?: GbaSaveType.AUTO,
        rtc = gbaRtc?.let { value -> GbaRtc.entries.firstOrNull { it.native == value } } ?: GbaRtc.AUTO,
        useBios = gbaUseBios != false,
    )
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
    /**
     * N8: disposición propia de los juegos de GBA por orientación (con L y R; = iOS `ControlsLayout.defaults(_,
     * shoulders:)`). Se guarda aparte de la de Game Boy: consola × orientación.
     */
    val gbaPortraitLayout: StoredControlLayout = StoredControlLayout(),
    val gbaLandscapeLayout: StoredControlLayout = StoredControlLayout(),
    val integerScaleLandscape: Boolean = true,
    val dpadStyle: DpadStyle = DpadStyle.CROSS,
    /** Diagonales de la cruceta (N2). Por defecto «Reducidas»: la diagonal solo vale cerca de los 45°. */
    val diagonalMode: DiagonalMode = DiagonalMode.REDUCED,
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
        gbaPortraitLayout = gbaPortraitLayout.sanitized(),
        gbaLandscapeLayout = gbaLandscapeLayout.sanitized(),
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

    /** La disposición guardada de [console] (N8: GB y GBA por separado) en [orientation]. */
    fun layout(orientation: ControlsOrientation, console: Console = Console.GB): StoredControlLayout = when (console) {
        Console.GB -> if (orientation == ControlsOrientation.PORTRAIT) portraitLayout else landscapeLayout
        Console.GBA -> if (orientation == ControlsOrientation.PORTRAIT) gbaPortraitLayout else gbaLandscapeLayout
    }

    /** La disposición resuelta (guardada sobre la de fábrica de esa consola) que dibujan los controles. */
    fun controlLayout(orientation: ControlsOrientation, console: Console = Console.GB): ControlLayout =
        ControlLayout.from(layout(orientation, console), orientation, shoulders = console == Console.GBA)

    private fun withLayout(orientation: ControlsOrientation, console: Console, layout: StoredControlLayout) = when (console) {
        Console.GB -> if (orientation == ControlsOrientation.PORTRAIT) copy(portraitLayout = layout) else copy(landscapeLayout = layout)
        Console.GBA ->
            if (orientation == ControlsOrientation.PORTRAIT) copy(gbaPortraitLayout = layout) else copy(gbaLandscapeLayout = layout)
    }

    /** Guarda un control movido, solo en esa orientación y consola. */
    fun move(
        orientation: ControlsOrientation,
        id: ControlId,
        point: NormalizedPoint,
        console: Console = Console.GB,
    ): GameplaySettingsData {
        val clamped = NormalizedPoint(point.x.finiteOr(0.5f).coerceIn(0f, 1f), point.y.finiteOr(0.5f).coerceIn(0f, 1f))
        val current = layout(orientation, console)
        return withLayout(orientation, console, current.copy(positions = current.positions + (id to clamped)))
    }

    /**
     * Cambia el tamaño de un control en pasos de 0,1 (recorte 0,6..1,6), solo en esa orientación y consola. Sin escala
     * guardada parte de la de fábrica de esa consola (en GBA horizontal la cruceta nace al 0,6 y L/R al 0,9).
     */
    fun resize(orientation: ControlsOrientation, id: ControlId, delta: Float, console: Console = Console.GB): GameplaySettingsData {
        val current = layout(orientation, console)
        val base = current.scales[id] ?: ControlLayout.defaults(orientation, console == Console.GBA).scales[id] ?: 1f
        val next = (((base + delta) * 10f).roundToInt() / 10f).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)
        return withLayout(orientation, console, current.copy(scales = current.scales + (id to next)))
    }

    /** Cambia la separación de las flechas separadas en pasos de 0,1 (recorte 0,7..1,5), solo en esa orientación y consola. */
    fun adjustSeparation(orientation: ControlsOrientation, delta: Float, console: Console = Console.GB): GameplaySettingsData {
        val current = layout(orientation, console)
        val next = (((current.separation.finiteOr(1f) + delta) * 10f).roundToInt() / 10f)
            .coerceIn(MIN_DPAD_SEPARATION, MAX_DPAD_SEPARATION)
        return withLayout(orientation, console, current.copy(separation = next))
    }

    /** Devuelve la disposición de esa orientación y consola a la de fábrica: posiciones, tamaños y separación (1,0). */
    fun resetLayout(orientation: ControlsOrientation, console: Console = Console.GB): GameplaySettingsData =
        withLayout(orientation, console, StoredControlLayout())

    /** `true` si la disposición de esa orientación y consola equivale a la de fábrica (aunque haya entradas guardadas). */
    fun isFactoryLayout(orientation: ControlsOrientation, console: Console = Console.GB): Boolean {
        val stored = layout(orientation, console)
        val defaults = ControlLayout.defaults(orientation, console == Console.GBA)
        val positionsMatch = stored.positions.all { (id, point) ->
            val factory = defaults.centers[id]
            factory != null && abs(factory.x - point.x) < EPSILON && abs(factory.y - point.y) < EPSILON
        }
        return positionsMatch && stored.scales.all { (id, scale) -> abs(scale - (defaults.scales[id] ?: 1f)) < EPSILON } &&
            abs(stored.separation - 1f) < EPSILON
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

    /** N8: opciones de un juego de GBA (tipo de partida, reloj y BIOS) por huella; sin ajustes, todo detectado. */
    fun gbaOptions(fingerprint: String?): GbaOptions = (fingerprint?.let { perGame[it] } ?: GameOverrides()).gbaOptions()
}

/**
 * Cambios de paleta (global o del juego [fingerprint]) posteriores al valor actual, sin repetidos (A6-H2). Un `StateFlow`
 * no es estado de snapshot de Compose: se observa como flujo, no con `snapshotFlow`.
 */
fun Flow<GameplaySettingsData>.compatPaletteChanges(fingerprint: String): Flow<Int> =
    map { it.emulation(fingerprint, false).compatPalette }.distinctUntilChanged().drop(1)

private const val EPSILON = 1e-4f

private fun Float.finiteOr(default: Float) = if (isFinite()) this else default

private fun GameOverrides.sanitized() = copy(
    compatPalette = compatPalette?.takeIf { it in 0..MAX_COMPAT_PALETTE },
    gbaSaveType = gbaSaveType?.takeIf { value -> value != GbaSaveType.AUTO.native && GbaSaveType.entries.any { it.native == value } },
    gbaRtc = gbaRtc?.takeIf { it == GbaRtc.ON.native || it == GbaRtc.OFF.native },
    gbaUseBios = gbaUseBios?.takeIf { !it },
)

private fun StoredControlLayout.sanitized() = StoredControlLayout(
    positions = positions.filterValues { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f },
    scales = scales.filterValues { it.isFinite() }.mapValues { it.value.coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE) },
    separation = separation.finiteOr(1f).coerceIn(MIN_DPAD_SEPARATION, MAX_DPAD_SEPARATION),
)
