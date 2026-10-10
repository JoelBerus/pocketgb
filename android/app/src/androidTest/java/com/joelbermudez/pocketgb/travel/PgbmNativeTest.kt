package com.joelbermudez.pocketgb.travel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.SaveLineage
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.travel.CrossVectors.TARGET
import com.joelbermudez.pocketgb.travel.CrossVectors.build
import com.joelbermudez.pocketgb.travel.CrossVectors.sha
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N7b · el parser C por JNI ([NativePgbmCodec]) con los vectores cruzados: los bytes que produce la app en Android son
 * los documentados (y los mismos que el códec de referencia de los tests JVM), y el importador real los trata igual.
 */
@RunWith(AndroidJUnit4::class)
class PgbmNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeEncoderProducesTheDocumentedCrossVectors() {
        for ((name, expected) in CrossVectors.EXPECTED_SHA) {
            val bytes = build(name, NativePgbmCodec)
            assertEquals(name, expected, sha(bytes))
            if (name != "G4") assertArrayEquals(name, build(name, ReferencePgbmCodec), bytes)
        }
    }

    @Test fun nativeParserReadsAndRejectsLikeTheContract() {
        val x1 = NativePgbmCodec.parse(build("X1", NativePgbmCodec))
        assertArrayEquals(CrossVectors.S1, x1.sav)
        assertArrayEquals(CrossVectors.T1, x1.state)
        assertEquals(536, x1.meta!!.size)
        assertEquals(0, NativePgbmCodec.parse(build("X3", NativePgbmCodec)).sav.size)
        val cases = listOf(
            build("G4", NativePgbmCodec) to PgbmResult.CRITICAL,
            build("X1", NativePgbmCodec).let { it.copyOf(it.size - 1) } to PgbmResult.TRUNCATED,
            build("X1", NativePgbmCodec).also { it[200] = (it[200] + 1).toByte() } to PgbmResult.CRC,
            byteArrayOf(1, 2, 3, 4) to PgbmResult.MAGIC,
        )
        for ((bytes, code) in cases) {
            try {
                NativePgbmCodec.parse(bytes)
                fail("debía rechazarse con $code")
            } catch (e: PgbmException) {
                assertEquals(code, e.code)
            }
        }
    }

    @Test fun realImporterWithTheNativeParser() {
        val root = File(context.cacheDir, "pgbm-native-${System.nanoTime()}").apply { mkdirs() }
        try {
            val importer = SaveImporter(
                File(root, "saves"), File(root, "states"), File(root, "moments"), NativePgbmCodec, ownership = FingerprintOwnership(),
            )
            assertEquals(
                SaveImporter.Result.Done(SaveLineage.Incoming.INSTALL, installed = true, continueFrom = "Pixel de prueba"),
                (importer.importPackage(build("X1", NativePgbmCodec), TARGET) as SaveImporter.Result.Done).copy(meta = null),
            )
            assertEquals(
                SaveLineage.Incoming.ADVANCE,
                (importer.importPackage(build("X2", NativePgbmCodec), TARGET) as SaveImporter.Result.Done).lineage,
            )
            assertEquals(SaveImporter.Result.SaveSizeMismatch, importer.importPackage(build("X3", NativePgbmCodec), TARGET))
            assertEquals(SaveImporter.Result.SaveSizeMismatch, importer.importPackage(build("X4", NativePgbmCodec), TARGET))
            for (name in listOf("X5", "G4")) {
                assertEquals(name, SaveImporter.Result.Rejected(SaveImporter.Rejection.NEWER_APP), importer.importPackage(build(name, NativePgbmCodec), TARGET))
            }
            for (name in listOf("X6", "X7", "X8")) {
                assertEquals(name, SaveImporter.Result.Rejected(SaveImporter.Rejection.INVALID_META), importer.importPackage(build(name, NativePgbmCodec), TARGET))
            }
            assertArrayEquals(CrossVectors.S2, SaveStore(File(root, "saves"), TARGET.fingerprint).load())
        } finally {
            root.deleteRecursively()
        }
    }
}
