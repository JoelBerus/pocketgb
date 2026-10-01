package com.joelbermudez.pocketgb.emulator

import android.view.Surface
import com.joelbermudez.pocketgb.audio.AudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmulatorSession : AutoCloseable {
    private var handle = NativeLibrary.nativeSessionCreate().also {
        if (it == 0L) throw CoreError.OutOfMemory()
    }
    private val mutableState = MutableStateFlow<SessionState>(SessionState.New)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val frameCount: Long
        get() = NativeLibrary.nativeSessionFrameCount(requireHandle())

    val requestedButtons: Int
        get() = NativeLibrary.nativeSessionRequestedButtons(requireHandle())

    val appliedButtons: Int
        get() = NativeLibrary.nativeSessionAppliedButtons(requireHandle())

    val speed: Int
        get() = NativeLibrary.nativeSessionSpeed(requireHandle())

    val audioState: AudioState
        get() = AudioState.fromNative(NativeLibrary.nativeSessionAudioState(requireHandle()))

    val audioFramesProduced: Long
        get() = NativeLibrary.nativeSessionAudioFramesProduced(requireHandle())

    val audioFramesConsumed: Long
        get() = NativeLibrary.nativeSessionAudioFramesConsumed(requireHandle())

    fun load(rom: ByteArray) {
        requireState("cargar", SessionState.New)
        if (rom.size < CoreBridge.MIN_ROM_BYTES) throw CoreError.RomTooSmall()
        if (rom.size > CoreBridge.MAX_ROM_BYTES) throw CoreError.RomTooLarge()
        CoreError.fromResult(NativeLibrary.nativeSessionLoad(requireHandle(), rom))?.let { throw it }
        mutableState.value = SessionState.Ready
    }

    fun start() {
        requireState("iniciar", SessionState.Ready)
        checkNativeControl("iniciar", NativeLibrary.nativeSessionStart(requireHandle()))
        mutableState.value = SessionState.Running
    }

    fun pause() {
        requireState("pausar", SessionState.Running)
        checkNativeControl("pausar", NativeLibrary.nativeSessionPause(requireHandle()))
        mutableState.value = SessionState.Paused
    }

    fun resume() {
        requireState("reanudar", SessionState.Paused)
        checkNativeControl("reanudar", NativeLibrary.nativeSessionResume(requireHandle()))
        mutableState.value = SessionState.Running
    }

    fun stop() {
        val current = mutableState.value
        if (current == SessionState.Stopped) return
        if (current !in listOf(SessionState.Ready, SessionState.Running, SessionState.Paused)) {
            throw SessionError.InvalidTransition("detener", current)
        }
        checkNativeControl("detener", NativeLibrary.nativeSessionStop(requireHandle()))
        mutableState.value = SessionState.Stopped
    }

    fun attachSurface(surface: Surface) {
        NativeLibrary.nativeSessionAttachSurface(requireHandle(), surface)
    }

    fun detachSurface() {
        val nativeHandle = handle
        if (nativeHandle == 0L) return
        NativeLibrary.nativeSessionDetachSurface(nativeHandle)
    }

    fun setTouchButtons(mask: Int) {
        NativeLibrary.nativeSessionSetTouchButtons(requireHandle(), mask and 0xFF)
    }

    fun setPhysicalButtons(mask: Int) {
        NativeLibrary.nativeSessionSetPhysicalButtons(requireHandle(), mask and 0xFF)
    }

    fun setSpeed(factor: Int) {
        NativeLibrary.nativeSessionSetSpeed(requireHandle(), factor)
    }

    override fun close() {
        val nativeHandle = handle
        if (nativeHandle == 0L) return
        NativeLibrary.nativeSessionDestroy(nativeHandle)
        handle = 0L
        mutableState.value = SessionState.Closed
    }

    private fun checkNativeControl(action: String, result: Int) {
        when (result) {
            0 -> Unit
            1 -> throw SessionError.InvalidTransition(action, mutableState.value)
            else -> throw SessionError.NativeThread()
        }
    }

    private fun requireState(action: String, expected: SessionState) {
        if (mutableState.value != expected) {
            throw SessionError.InvalidTransition(action, mutableState.value)
        }
    }

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: throw CoreError.Closed()
}
