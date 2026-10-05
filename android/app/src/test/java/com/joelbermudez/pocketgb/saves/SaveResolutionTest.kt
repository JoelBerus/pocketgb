package com.joelbermudez.pocketgb.saves

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisión pura de qué partida cargar (port de SaveMirrorTests/SaveResolution de iOS, con RTC). */
class SaveResolutionTest {
    private fun c(data: ByteArray, date: Long?) = SaveResolution.Candidate(data, date)
    private val valid: (Int) -> Boolean = { it == 4 }
    private val old = 1_000L
    private val new = 2_000L
    private val a = bytes(1, 1, 1, 1)
    private val b = bytes(2, 2, 2, 2)

    private fun load(
        data: ByteArray, backupOther: ByteArray? = null, installLocal: Boolean = false, updateMirror: Boolean = false,
        mirrorIgnored: Boolean = false, quarantineLocal: Boolean = false,
    ) = SaveResolution.Load(data, backupOther, installLocal, updateMirror, mirrorIgnored, quarantineLocal)

    private fun resolve(local: SaveResolution.Candidate?, mirror: SaveResolution.Candidate?, owned: Boolean = false,
                        isValid: (Int) -> Boolean = valid) =
        SaveResolution.resolve(local, mirror, owned, isValid)

    @Test fun nothingSaved() = assertEquals(SaveResolution.None, resolve(null, null))

    @Test fun localOnlyIsUsedAndMirrorCreated() =
        assertEquals(load(a, updateMirror = true), resolve(c(a, old), null))

    @Test fun mirrorOnlyIsImportedIntoLocal() =
        assertEquals(load(b, installLocal = true), resolve(null, c(b, old)))

    @Test fun newerMirrorWinsAndLocalIsKeptByTheInstall() =
        assertEquals(load(b, installLocal = true), resolve(c(a, old), c(b, new)))

    @Test fun newerLocalWinsAndMirrorIsBackedUp() =
        assertEquals(load(a, backupOther = b, updateMirror = true), resolve(c(a, new), c(b, old)))

    @Test fun missingDatesPreferLocal() =
        assertEquals(load(a, backupOther = b, updateMirror = true), resolve(c(a, null), c(b, null)))

    @Test fun tieOnDatePrefersLocal() =
        assertEquals(load(a, backupOther = b, updateMirror = true), resolve(c(a, old), c(b, old)))

    @Test fun mirrorWithDateBeatsLocalWithoutDate() =
        assertEquals(load(b, installLocal = true), resolve(c(a, null), c(b, old)))

    @Test fun localWithDateBeatsMirrorWithoutDate() =
        assertEquals(load(a, backupOther = b, updateMirror = true), resolve(c(a, old), c(b, null)))

    @Test fun identicalCopiesChangeNothing() =
        assertEquals(load(a), resolve(c(a, old), c(a.copyOf(), new)))

    @Test fun ownedStaleMirrorLosesWithoutBackupEvenIfNewer() =
        // Fecha del espejo posterior, pero es una escritura propia terminada tarde: gana la local, sin backup.
        assertEquals(load(a, updateMirror = true), resolve(c(a, old), c(b, new), owned = true))

    @Test fun wrongSizeMirrorOnlyIsNeverLoadedNorTouched() =
        assertEquals(SaveResolution.WrongSize(fromMirror = true), resolve(null, c(bytes(9), new)))

    @Test fun wrongSizeMirrorWithValidLocalIsIgnoredEvenIfNewer() =
        assertEquals(load(a, mirrorIgnored = true), resolve(c(a, old), c(bytes(9), new)))

    @Test fun wrongSizeLocalIsNotLoaded() =
        assertEquals(SaveResolution.WrongSize(fromMirror = false), resolve(c(bytes(9), new), null))

    @Test fun wrongSizeLocalWithValidMirrorIsQuarantined() =
        assertEquals(load(b, installLocal = true, quarantineLocal = true), resolve(c(bytes(9), new), c(b, old)))

    @Test fun bothWrongSizeKeepsTheLocalUntouched() =
        assertEquals(SaveResolution.WrongSize(fromMirror = false), resolve(c(bytes(9), old), c(bytes(8), new)))

    @Test fun ownedFlagDoesNotRescueAWrongSizeMirror() =
        assertEquals(load(a, mirrorIgnored = true), resolve(c(a, old), c(bytes(9), new), owned = true))

    // RTC: sram 16 -> {16, 64, 60}

    private val rtcValid: (Int) -> Boolean = SaveSizes.validSizes(hasRtc = true, sramBytes = 16)::contains
    private fun sram(n: Int, size: Int) = ByteArray(size) { n.toByte() }

    @Test fun rtcSizesAreAllAccepted() {
        for (size in listOf(16, 64, 60)) {
            assertEquals(load(sram(1, size), updateMirror = true), resolve(c(sram(1, size), old), null, isValid = rtcValid))
        }
    }

    @Test fun rtcLocalWithBlockAndMirrorWithoutItResolveByDate() {
        val withRtc = sram(1, 64)
        val without = sram(2, 16)
        assertEquals(load(without, installLocal = true), resolve(c(withRtc, old), c(without, new), isValid = rtcValid))
        assertEquals(load(withRtc, backupOther = without, updateMirror = true),
            resolve(c(withRtc, new), c(without, old), isValid = rtcValid))
    }

    @Test fun rtcSizeBetweenValidOnesIsWrong() {
        assertEquals(SaveResolution.WrongSize(false), resolve(c(sram(1, 50), old), null, isValid = rtcValid))
        assertEquals(SaveResolution.WrongSize(true), resolve(null, c(sram(1, 61), old), isValid = rtcValid))
    }

    @Test fun loadComparesByContent() {
        assertEquals(load(a, backupOther = b), load(a.copyOf(), backupOther = b.copyOf()))
        assertTrue(load(a) != load(b))
        assertArrayEquals(a, (resolve(c(a, 1), null) as SaveResolution.Load).data)
    }
}
