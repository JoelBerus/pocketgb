package com.joelbermudez.pocketgb.emulator

import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeLibraryTest {
    @Test
    fun createsAndDestroysCore() {
        val handle = NativeLibrary.nativeCreate()

        assertNotEquals(0L, handle)
        NativeLibrary.nativeDestroy(handle)
    }
}
