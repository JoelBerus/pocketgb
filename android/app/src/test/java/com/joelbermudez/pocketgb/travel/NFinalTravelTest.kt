package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveLineage
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavesIndex
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.travel.CrossVectors.B
import com.joelbermudez.pocketgb.travel.CrossVectors.ROM
import com.joelbermudez.pocketgb.travel.CrossVectors.ROM_HEX
import com.joelbermudez.pocketgb.travel.CrossVectors.S1
import com.joelbermudez.pocketgb.travel.CrossVectors.S2
import com.joelbermudez.pocketgb.travel.CrossVectors.T1
import com.joelbermudez.pocketgb.travel.CrossVectors.T2
import com.joelbermudez.pocketgb.travel.CrossVectors.TARGET
import com.joelbermudez.pocketgb.travel.CrossVectors.build
import com.joelbermudez.pocketgb.travel.CrossVectors.sha
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Auditoría conjunta final del nivel N (docs/auditorias/N-final-respuesta-android.md): H6, H8 (importación), H13 y la
 * paridad menor con iOS (ALREADY_CURRENT, «Continuar donde lo dejaste en <equipo>», ND20 j). JVM con [ReferencePgbmCodec].
 */
class NFinalTravelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var saves: File
    private lateinit var states: File
    private lateinit var moments: File
    private val ownership = FingerprintOwnership()
    private val codec = ReferencePgbmCodec
    private val thumb = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val ownAuto = byteArrayOf(0x50, 0x47, 0x42, 0x53, 7)

    @Before fun setUp() {
        root = tmp.newFolder()
        saves = File(root, "saves"); states = File(root, "states"); moments = File(root, "moments")
    }

    private fun importer(ops: SaveFileOps = PosixSaveFileOps) = SaveImporter(saves, states, moments, codec, ops, ownership)
    private fun store() = SaveStore(saves, TARGET.fingerprint)
    private fun momentStore() = MomentStore(moments, TARGET.fingerprint)
    private fun autoStore() = StateStore(states, TARGET.fingerprint)

    /** Como X2 (avance de S1 a S2 en el iPhone), pero con el estado de S2 y su miniatura. */
    private fun x2WithState(): ByteArray = codec.encode(
        PgbmPackage(
            ROM,
            PgbmMeta(ROM_HEX, sha(S2), sha(S1), "ios", "iPhone de prueba", 1790007200000, "gb", "1.0.0", stateOfSavSha256 = sha(S2)).toJson(),
            S2, T2, thumb,
        ),
    )

    // MARK: H6

    @Test fun keepLocalConflictMomentCarriesThePackageStateAndThumbnail() {
        store().save(B)
        val r = importer().importPackage(x2WithState(), TARGET, SaveImporter.Choice.KEEP_LOCAL) as SaveImporter.Result.Done
        assertArrayEquals(B, store().load())
        val m = momentStore().snapshot().moments.single { it.id == r.conflictMomentId }
        assertTrue(m.hasState && m.hasSram && m.hasThumbnail)
        assertArrayEquals(T2, momentStore().loadState(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(S2, momentStore().loadSram(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(thumb, momentStore().thumbnail(MomentStore.Kind.MOMENT, m.id))
    }

    @Test fun rejectingTheStateReplacementKeepsTheIncomingState() {
        importer().importPackage(build("X1", codec), TARGET)
        autoStore().save(ownAuto, null, StateSlot.AUTO) // se jugó aquí sin guardar
        assertEquals(
            SaveImporter.Result.NeedsChoice(SaveImporter.Ask.REPLACE_STATE, "Pixel de prueba"),
            importer().importPackage(build("X1", codec), TARGET),
        )
        val r = importer().importPackage(build("X1", codec), TARGET, SaveImporter.Choice.KEEP_LOCAL) as SaveImporter.Result.Done
        assertEquals(SaveLineage.Incoming.ALREADY_CURRENT, r.lineage)
        assertNull(r.continueFrom)
        assertArrayEquals("el de aquí se queda", ownAuto, autoStore().load(StateSlot.AUTO))
        val m = momentStore().snapshot().moments.single { it.id == r.conflictMomentId }
        assertArrayEquals("el del paquete no se descarta", T1, momentStore().loadState(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(S1, momentStore().loadSram(MomentStore.Kind.MOMENT, m.id))
        assertArrayEquals(S1, store().load())
    }

    /** Paridad con iOS: si el AUTO de aquí es idéntico (mismo SHA-256) al estado del paquete, no se pregunta. */
    @Test fun anIdenticalAutoIsNotAskedAbout() {
        importer().importPackage(build("X1", codec), TARGET) // el AUTO de aquí ya es T1
        val r = importer().importPackage(build("X1", codec), TARGET)
        assertTrue("no pregunta: $r", r is SaveImporter.Result.Done)
        assertEquals(SaveLineage.Incoming.ALREADY_CURRENT, (r as SaveImporter.Result.Done).lineage)
        assertEquals("Pixel de prueba", r.continueFrom)
        assertArrayEquals(T1, autoStore().load(StateSlot.AUTO))
        assertTrue("no se duplica en el anillo", momentStore().snapshot().beforeLoad.none { it.hasState })
        // Con uno distinto sí se pregunta.
        autoStore().save(ownAuto, null, StateSlot.AUTO)
        assertEquals(
            SaveImporter.Result.NeedsChoice(SaveImporter.Ask.REPLACE_STATE, "Pixel de prueba"),
            importer().importPackage(build("X1", codec), TARGET),
        )
    }

    // MARK: H8 · si el anillo falla, no se instala

    @Test fun aRingFailureAbortsTheInstall() {
        store().save(B)
        val failingRing = object : SaveFileOps by PosixSaveFileOps {
            override fun writeSynced(file: File, data: ByteArray) {
                if (file.name.startsWith("b-")) throw IOException("anillo lleno de errores")
                PosixSaveFileOps.writeSynced(file, data)
            }
        }
        try {
            importer(failingRing).importPackage(build("X1", codec), TARGET, SaveImporter.Choice.USE_INCOMING)
            fail("debía fallar")
        } catch (_: IOException) {
        }
        assertArrayEquals("la partida no se sustituye sin su copia", B, store().load())
        assertTrue(autoStore().let { !it.stateFile(StateSlot.AUTO).exists() })
    }

    // MARK: H13 · etiquetas en code points

    @Test fun tagsAreMeasuredInCodePoints() {
        val emoji = "🎮" // 🎮: 1 code point, 2 unidades UTF-16
        fun meta(tag: String) = PgbmMeta(ROM_HEX, sha(S1), null, "ios", "iPhone", 1, "gb", "1", tags = listOf(tag)).toJson()
        assertEquals(listOf(emoji.repeat(64)), PgbmMeta.parse(meta(emoji.repeat(64))).tags)
        try {
            PgbmMeta.parse(meta(emoji.repeat(65)))
            fail("65 code points no caben")
        } catch (_: PgbmMeta.Invalid) {
        }
        assertEquals(emoji.repeat(64), (emoji.repeat(70)).takeCodePoints(64))
        assertEquals("ab", "ab".takeCodePoints(64))
    }

    // MARK: paridad menor

    @Test fun alreadyCurrentSetsTheCurrentAsideAndRecordsItAsReceived() {
        store().save(S1)
        val r = importer().importRawSave(S1, TARGET) as SaveImporter.Result.Done
        assertEquals(SaveLineage.Incoming.ALREADY_CURRENT, r.lineage)
        assertTrue(store().setAside().any { File(store().backupsDirectory, it.name).readBytes().contentEquals(S1) })
        assertEquals(sha(S1), store().lineageBase())
    }

    @Test fun detailContinuesFromTheOtherDeviceWhileItsStateIsTheAuto() {
        importer().importPackage(build("X1", codec), TARGET)
        val status = SaveStatus.read(saves, TARGET.fingerprint, statesRoot = states)
        assertEquals("Pixel de prueba", status?.device)
        assertEquals("Pixel de prueba", status?.continueFrom)
        assertNull("sin estados no se sabe", SaveStatus.read(saves, TARGET.fingerprint)?.continueFrom)
        autoStore().save(ownAuto, null, StateSlot.AUTO) // se jugó aquí
        assertNull(SaveStatus.read(saves, TARGET.fingerprint, statesRoot = states)?.continueFrom)
        assertNotNull(SaveStatus.read(saves, TARGET.fingerprint, statesRoot = states)?.device)
    }

    @Test fun headerSizesWinOverTheIndex() {
        val indexed = SavesIndex.Record(title = "X", fileName = "x.gba", validSizes = listOf(32768))
        assertEquals(true to setOf(65536, 131072), ImportTarget.sizing(setOf(65536, 131072), indexed))
        assertEquals("sin batería según la cabecera", false to null, ImportTarget.sizing(emptySet(), indexed))
        assertEquals("cabecera ilegible: el índice", true to setOf(32768), ImportTarget.sizing(null, indexed))
        assertEquals(false to null, ImportTarget.sizing(null, SavesIndex.Record(title = "X", fileName = "x.gb", validSizes = null)))
        assertEquals(true to null, ImportTarget.sizing(null, null))
    }
}
