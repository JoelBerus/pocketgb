package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.ui.gameplay.SpeedCycle
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedCycleTest {
    @Test
    fun cyclesOneTwoFourAndBack() {
        assertEquals(2, SpeedCycle.next(1))
        assertEquals(4, SpeedCycle.next(2))
        assertEquals(1, SpeedCycle.next(4))
    }

    @Test
    fun unknownSpeedFallsBackToNormal() {
        assertEquals(1, SpeedCycle.next(3))
        assertEquals(1, SpeedCycle.next(0))
    }
}
