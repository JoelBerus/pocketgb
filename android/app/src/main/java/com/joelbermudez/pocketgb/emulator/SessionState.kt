package com.joelbermudez.pocketgb.emulator

sealed interface SessionState {
    data object New : SessionState
    data object Ready : SessionState
    data object Running : SessionState
    data object Paused : SessionState
    data object Stopped : SessionState
    data object Closed : SessionState
}

sealed class SessionError(message: String) : IllegalStateException(message) {
    class InvalidTransition(action: String, state: SessionState) :
        SessionError("No se puede $action desde $state")

    class NativeThread : SessionError("No se pudo iniciar el hilo nativo de emulación.")

    /** La operación exige la sesión aparcada (en pausa, o cargada sin arrancar) y estaba corriendo. */
    class NotParked(action: String, state: SessionState) :
        SessionError("No se puede $action con la sesión en $state: debe estar en pausa")

    /** La operación es de la otra consola (p. ej. cargar un ROM de GB en una sesión de GBA). */
    class WrongConsole(action: String, console: Console) :
        SessionError("No se puede $action en una sesión de ${console.shortName}")

    /** El hilo nativo no entregó la instantánea de la SRAM en el plazo (1 s). */
    class SnapshotTimeout : SessionError("El hilo de emulación no entregó la partida a tiempo.")
}
