package com.joelbermudez.pocketgb.audio

enum class AudioState {
    Stopped,
    Priming,
    Live,
    ClockFallback;

    companion object {
        fun fromNative(value: Int): AudioState = entries.getOrElse(value) { ClockFallback }
    }
}
