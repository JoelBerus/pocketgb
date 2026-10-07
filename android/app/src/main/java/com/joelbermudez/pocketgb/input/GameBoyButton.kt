package com.joelbermudez.pocketgb.input

enum class GameBoyButton(val mask: Int) {
    A(1 shl 0),
    B(1 shl 1),
    SELECT(1 shl 2),
    START(1 shl 3),
    RIGHT(1 shl 4),
    LEFT(1 shl 5),
    UP(1 shl 6),
    DOWN(1 shl 7),
}
