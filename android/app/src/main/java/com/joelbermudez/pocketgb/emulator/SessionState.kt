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
}
