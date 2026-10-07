package com.joelbermudez.pocketgb.saves

import com.joelbermudez.pocketgb.emulator.CoreError

/**
 * Cómo se abre un juego (iOS `GameLaunchMode`, A9/ND6). [FRESH] = «Jugar» o «Jugar desde el inicio»: solo la partida
 * (`.sav`). [RESUME] = «Continuar»: además retoma el estado automático si sigue siendo el de esa partida.
 */
enum class LaunchMode { FRESH, RESUME }

/** Por qué «Continuar» no retomó el estado automático. En todos los casos la partida (`.sav`) queda intacta. */
enum class ResumeFailure {
    /** No hay estado automático (nunca se guardó o ya se retiró). */
    MISSING,

    /** No se pudo leer el archivo del estado (disco). */
    UNREADABLE,

    /**
     * El juego guardó la partida después del estado (SRAM más nueva por fecha) o la partida del estado ya no es la
     * vigente (otro contenido): cargarlo devolvería una partida vieja. El estado se retira (iOS D81V2-H2).
     */
    NOT_CURRENT,

    /** El estado es de otro modelo o configuración (DMG, CGB, compatibilidad) o de otro ROM. Se conserva. */
    INCOMPATIBLE,

    /** Firma, CRC, longitud o versión que esta app no lee (dañado o truncado). Se conserva. */
    CORRUPT,
}

/**
 * Lo mínimo del núcleo, aparcado y SIN arrancar, que necesita la continuación exacta. Interfaz para probar la
 * decisión en JVM; en la app la implementa la sesión nativa.
 */
interface ResumableCore {
    /** RAM del cartucho SIN el pie del RTC: lo que cuenta como «la partida» (iOS `ramBytes()`, INT-H1). */
    fun cartridgeRam(): ByteArray

    /** Estado completo actual, para poder volver atrás. */
    fun captureState(): ByteArray

    /** Aplica un estado. Lanza [CoreError] si lo rechaza; el núcleo valida todo antes de tocar nada. */
    fun restoreState(state: ByteArray)

    /** Lleva el reloj del MBC3 a la hora real (el estado trae la del momento en que se guardó; iOS D81-H5). */
    fun syncClockToNow()
}

/**
 * Continuación exacta (A9; cambia J8 por ND6), con la semántica transaccional de iOS D8.1 (`StateStore.automaticEntry`
 * y `EmulatorSession.start(restoring:)`):
 *
 * 1. **Fecha**: un estado automático anterior a la partida local no vale (el juego guardó después, o se restauró un
 *    backup): [isFreshByDate].
 * 2. **Contenido** ([apply]): con la partida ya abierta (la apertura pudo instalar un espejo más nuevo), un estado
 *    legítimo lleva la MISMA RAM del cartucho que la partida, porque la salida y el segundo plano vacían la SRAM antes
 *    de guardarlo. Si difiere, se revierte el núcleo y se rechaza.
 *
 * Nunca se escribe nada: si el estado vale, su RAM ya es la de disco (no hay que persistir); si no vale, el núcleo
 * vuelve a como estaba y quien llama cierra la sesión sin guardar. Por eso caer a la partida nunca pierde nada.
 */
object ExactContinuation {
    sealed interface Outcome {
        data object Resumed : Outcome

        data class Rejected(val reason: ResumeFailure) : Outcome
    }

    /** `false` si el estado es anterior a la partida local (el juego guardó después). Sin partida local, vale. */
    fun isFreshByDate(autoDateMs: Long, saveDateMs: Long?): Boolean = saveDateMs == null || autoDateMs >= saveDateMs

    /**
     * Aplica [state] sobre [core] solo si conserva la partida actual. En [Outcome.Rejected] el núcleo queda como
     * estaba (mejor esfuerzo) y nunca se ha escrito ninguna copia de la partida.
     */
    fun apply(core: ResumableCore, state: ByteArray): Outcome {
        val before = core.cartridgeRam()
        val previous = core.captureState()
        try {
            core.restoreState(state)
        } catch (error: CoreError) {
            // El núcleo rechaza antes de aplicar; aun así se repone el anterior por si acaso.
            try { core.restoreState(previous) } catch (_: RuntimeException) {}
            return Outcome.Rejected(reasonFor(error))
        }
        if (!core.cartridgeRam().contentEquals(before)) {
            try { core.restoreState(previous) } catch (_: RuntimeException) {}
            return Outcome.Rejected(ResumeFailure.NOT_CURRENT)
        }
        core.syncClockToNow()
        return Outcome.Resumed
    }

    /**
     * Paso completo de la apertura con «Continuar»: lee el estado automático de [states] (fecha y firma primero, como
     * la biblioteca), comprueba que no es anterior a la partida local ([saveDateMs]) y lo aplica con [apply]. Un estado
     * obsoleto ([ResumeFailure.NOT_CURRENT]) se retira para que «Continuar» deje de ofrecerse; la partida no se toca.
     * Debe llamarse con la propiedad exclusiva de la huella ya adquirida y la sesión sin arrancar.
     */
    fun resume(core: ResumableCore, states: StateStore, saveDateMs: Long?): Outcome {
        val entry = try {
            states.entry(StateSlot.AUTO, withThumbnail = false)
        } catch (_: java.io.IOException) {
            return Outcome.Rejected(ResumeFailure.UNREADABLE)
        } ?: return Outcome.Rejected(ResumeFailure.MISSING)
        val outcome = when {
            entry.corrupt -> Outcome.Rejected(ResumeFailure.CORRUPT)
            !isFreshByDate(entry.dateMs, saveDateMs) -> Outcome.Rejected(ResumeFailure.NOT_CURRENT)
            else -> {
                val data = try {
                    states.load(StateSlot.AUTO)
                } catch (_: java.io.IOException) {
                    return Outcome.Rejected(ResumeFailure.UNREADABLE)
                }
                apply(core, data)
            }
        }
        if (outcome is Outcome.Rejected && discardsTheState(outcome.reason)) {
            try { states.delete(StateSlot.AUTO) } catch (_: java.io.IOException) {}
        }
        return outcome
    }

    fun reasonFor(error: CoreError): ResumeFailure = when (error) {
        is CoreError.StateRomMismatch -> ResumeFailure.INCOMPATIBLE
        is CoreError.StateMagic, is CoreError.StateVersion, is CoreError.StateCorrupt, is CoreError.BufferTooSmall ->
            ResumeFailure.CORRUPT
        else -> ResumeFailure.UNREADABLE
    }

    /** Solo un estado obsoleto se retira: uno de otro modelo o dañado se conserva (puede volver a servir o revisarse). */
    fun discardsTheState(reason: ResumeFailure): Boolean = reason == ResumeFailure.NOT_CURRENT
}
