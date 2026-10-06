package com.joelbermudez.pocketgb.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Si hay un mando conectado. Interfaz mínima para poder inyectar un mando «conectado» en las pruebas. */
interface GamepadConnection {
    val connected: StateFlow<Boolean>
}

/** Conexión fija, para pruebas y vistas previas. */
class FakeGamepadConnection(initial: Boolean = false) : GamepadConnection {
    private val state = MutableStateFlow(initial)
    override val connected: StateFlow<Boolean> = state.asStateFlow()
    fun set(value: Boolean) { state.value = value }
}

/** Sigue los dispositivos de entrada con `InputDeviceListener` (R6). [start] y [stop] los llama quien lo usa. */
class GamepadMonitor(context: Context) : GamepadConnection, InputManager.InputDeviceListener {
    private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val state = MutableStateFlow(anyGamepad())
    override val connected: StateFlow<Boolean> = state.asStateFlow()

    fun start() {
        state.value = anyGamepad()
        manager.registerInputDeviceListener(this, null)
    }

    fun stop() = manager.unregisterInputDeviceListener(this)

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        state.value = anyGamepad()
    }

    private fun anyGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id)
        device != null && !device.isVirtual && isGamepadSources(device.sources)
    }
}
