package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.MomentStore
import com.joelbermudez.pocketgb.saves.SavePendingException
import com.joelbermudez.pocketgb.saves.SaveLineage
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.travel.CrossVectors.B
import com.joelbermudez.pocketgb.travel.CrossVectors.S1
import com.joelbermudez.pocketgb.travel.CrossVectors.S2
import com.joelbermudez.pocketgb.travel.CrossVectors.T1
import com.joelbermudez.pocketgb.travel.CrossVectors.TARGET
import com.joelbermudez.pocketgb.travel.CrossVectors.build
import com.joelbermudez.pocketgb.travel.CrossVectors.sha
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N7b · exportar e importar `.pgbm` y `.sav` (JVM, con [ReferencePgbmCodec]; el C se prueba igual en
 * `PgbmNativeTest`). Las cargas son sintéticas (`pat`), generadas aquí.
 */
class SaveTravelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var saves: File
    private lateinit var states: File
    private lateinit var moments: File
    private val ownership = FingerprintOwnership()
    private val codec = ReferencePgbmCodec

    @Before fun setUp() {
        root = tmp.newFolder()
        saves = File(root, "saves"); states = File(root, "states"); moments = File(root, "moments")
    }

    private fun importer() = SaveImporter(saves, states, moments, codec, ownership = ownership)
    private fun store() = SaveStore(saves, TARGET.fingerprint)
    private fun snapshotTree(): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to sha(it.readBytes()) }

    // MARK: vectores

    @Test fun crossVectorsHaveTheDocumentedSha() {
        for ((name, expected) in CrossVectors.EXPECTED_SHA) assertEquals(name, expected, sha(build(name, codec)))
    }

    @Test fun x1MetaIsByteForByteTheDocumentedOne() {
        val meta = codec.parse(build("X1", codec)).meta!!
        assertEquals(536, meta.size)
        val parsed = PgbmMeta.parse(meta)
        assertTrue(parsed.stateMatchesSave)
        assertArrayEquals(meta, parsed.toJson())
    }

    // MARK: importación de los vectores

    @Test fun x1InstallsAndOffersExactContinuation() {
        val r = importer().importPackage(build("X1", codec), TARGET) as SaveImporter.Result.Done
        assertEquals(SaveImporter.Result.Done(SaveLineage.Incoming.INSTALL, installed = true, continueFrom = "Pixel de prueba"), r.copy(meta = null))
        assertArrayEquals(S1, store().load())
        assertArrayEquals(T1, StateStore(states, TARGET.fingerprint).load(StateSlot.AUTO))
        assertEquals("Pixel de prueba", store().origin(sha(S1))?.deviceName)
    }

    @Test fun x2AfterX1IsAnAdvanceWithoutContinuation() {
        importer().importPackage(build("X1", codec), TARGET)
        val r = importer().importPackage(build("X2", codec), TARGET) as SaveImporter.Result.Done
        assertEquals(SaveLineage.Incoming.ADVANCE, r.lineage)
        assertNull("el estado es de otra partida", r.continueFrom)
        assertArrayEquals(S2, store().load())
        assertArrayEquals("lo actual queda respaldado", S1, store().backupFile(1).readBytes())
        assertArrayEquals("el AUTO no se pisa con un estado ajeno", T1, StateStore(states, TARGET.fingerprint).load(StateSlot.AUTO))
    }

    @Test fun x2OverAnotherLocalIsADivergenceThatTouchesNothingUntilChosen() {
        store().save(B)
        val before = snapshotTree()
        assertEquals(
            SaveImporter.Result.NeedsChoice(SaveImporter.Ask.DIVERGENCE, "iPhone de prueba"),
            importer().importPackage(build("X2", codec), TARGET),
        )
        assertEquals(before, snapshotTree())
        // Se queda la de aquí: la otra va a un momento «Conflicto», a backup y apartada.
        val keep = importer().importPackage(build("X2", codec), TARGET, SaveImporter.Choice.KEEP_LOCAL) as SaveImporter.Result.Done
        assertFalse(keep.installed)
        assertArrayEquals(B, store().load())
        val m = MomentStore(moments, TARGET.fingerprint).snapshot().moments.single()
        assertTrue(m.name.contains("iPhone de prueba"))
        assertArrayEquals(S2, MomentStore(moments, TARGET.fingerprint).loadSram(MomentStore.Kind.MOMENT, m.id))
        // Ahora se usa la que llegó: la de aquí no se pierde.
        val use = importer().importPackage(build("X2", codec), TARGET, SaveImporter.Choice.USE_INCOMING) as SaveImporter.Result.Done
        assertTrue(use.installed)
        assertArrayEquals(S2, store().load())
        assertArrayEquals(B, store().backupFile(1).readBytes())
    }

    @Test fun emptyOrWrongSizedSaveNeverTouchesTheSaveAndLeavesABackup() {
        for (name in listOf("X3", "X4")) {
            store().save(S1)
            val sav = store().saveFile.readBytes()
            assertEquals(name, SaveImporter.Result.SaveSizeMismatch, importer().importPackage(build(name, codec), TARGET))
            assertArrayEquals(name, sav, store().load())
            assertTrue(name, store().backups().any { store().backupFile(it.index).readBytes().contentEquals(S1) })
        }
    }

    @Test fun invalidMetaOrCriticalSectionIsRejectedWithoutTouchingAnything() {
        store().save(B)
        val before = snapshotTree()
        val expected = mapOf(
            "X5" to SaveImporter.Rejection.NEWER_APP,
            "X6" to SaveImporter.Rejection.INVALID_META,
            "X7" to SaveImporter.Rejection.INVALID_META,
            "G4" to SaveImporter.Rejection.NEWER_APP,
            "G2" to SaveImporter.Rejection.INVALID_META,
        )
        for ((name, reason) in expected) {
            assertEquals(name, SaveImporter.Result.Rejected(reason), importer().importPackage(build(name, codec), TARGET))
            assertEquals(name, before, snapshotTree())
        }
    }

    @Test fun damagedPackagesAreRejectedWithoutTouchingAnything() {
        store().save(B)
        val before = snapshotTree()
        val good = build("X1", codec)
        val cases = listOf(good.copyOf(good.size - 1), good.copyOf().also { it[100] = (it[100] + 1).toByte() }, "PGB".toByteArray())
        for (bad in cases) {
            val r = importer().importPackage(bad, TARGET)
            assertTrue(r.toString(), r is SaveImporter.Result.Rejected)
            assertEquals(before, snapshotTree())
        }
    }

    @Test fun anotherGameOrConsoleIsRejected() {
        val other = ImportTarget("ab".repeat(32), "gb", true, setOf(32768))
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.OTHER_GAME), importer().importPackage(build("X1", codec), other))
        val gba = ImportTarget(TARGET.fingerprint, "gba", true, setOf(32768))
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.OTHER_GAME), importer().importPackage(build("X1", codec), gba))
        val unknown = ImportTarget(TARGET.fingerprint, "gb", true, null)
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.UNKNOWN_SIZES), importer().importPackage(build("X1", codec), unknown))
    }

    @Test fun importRespectsTheFingerprintExclusion() {
        store().save(B)
        val before = snapshotTree()
        ownership.tryAcquire(TARGET.fingerprint, "sesión")!!.use {
            try {
                importer().importPackage(build("X1", codec), TARGET)
                fail("con el juego abierto no se importa")
            } catch (_: SavePendingException) {
            }
            try {
                importer().importRawSave(S1, TARGET)
                fail("tampoco un .sav")
            } catch (_: SavePendingException) {
            }
        }
        assertEquals(before, snapshotTree())
    }

    // MARK: .sav crudo

    @Test fun rawSaveIsValidatedByExactSizeAndAlwaysConfirmed() {
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.WRONG_SIZE), importer().importRawSave(ByteArray(32767), TARGET))
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.NOT_A_PACKAGE), importer().importRawSave(build("X1", codec), TARGET))
        // ND20 (e): aunque no haya partida, se confirma nombrando el juego; sin confirmar no se toca nada.
        assertEquals(SaveImporter.Result.NeedsChoice(SaveImporter.Ask.RAW_CONFIRM, null), importer().importRawSave(B, TARGET))
        assertNull(store().load())
        val done = importer().importRawSave(B, TARGET, confirmed = setOf(SaveImporter.Ask.RAW_CONFIRM)) as SaveImporter.Result.Done
        assertEquals(SaveLineage.Incoming.INSTALL, done.lineage)
        assertEquals(SaveImporter.Ask.DIVERGENCE, (importer().importRawSave(S1, TARGET) as SaveImporter.Result.NeedsChoice).ask)
        importer().importRawSave(S1, TARGET, SaveImporter.Choice.USE_INCOMING)
        assertArrayEquals(S1, store().load())
        assertArrayEquals(B, store().backupFile(1).readBytes())
        assertEquals("momento «Conflicto» con la de aquí", 1, MomentStore(moments, TARGET.fingerprint).snapshot().moments.size)
    }

    @Test fun peekValidatesByHeaderNotByName() {
        assertEquals(SaveImporter.Peek.RawSave, importer().peek(S1))
        val p = importer().peek(build("X1", codec)) as SaveImporter.Peek.Package
        assertEquals(TARGET.fingerprint, p.romFingerprint)
        assertEquals(SaveImporter.Peek.Rejected(SaveImporter.Rejection.NEWER_APP), importer().peek(build("G4", codec)))
    }

    // MARK: ida y vuelta

    @Test fun exportThenImportOnAnotherDeviceRoundTripsWithContinuation() {
        val a = File(root, "a")
        SaveStore(File(a, "saves"), TARGET.fingerprint).save(S1)
        StateStore(File(a, "states"), TARGET.fingerprint).save(byteArrayOf(0x50, 0x47, 0x42, 0x53, 1, 2, 3), null, StateSlot.AUTO)
        val exporter = SaveExporter(File(a, "saves"), File(a, "states"), codec)
        val info = SaveExporter.Info(TARGET.fingerprint, "gb", "Pixel", "1.0.0", title = "Rojo", alias = "Mi Rojo", tags = listOf("rpg"))
        val pkg = exporter.buildPackage(info, nowMs = 5L)
        assertArrayEquals("determinista", pkg, exporter.buildPackage(info, nowMs = 5L))
        val meta = PgbmMeta.parse(codec.parse(pkg).meta!!)
        assertEquals("android", meta.devicePlatform)
        assertEquals("Mi Rojo", meta.alias)
        assertTrue(meta.stateMatchesSave)
        val r = importer().importPackage(pkg, TARGET) as SaveImporter.Result.Done
        assertEquals("Pixel", r.continueFrom)
        assertArrayEquals(S1, store().load())
        assertArrayEquals(S1, exporter.rawSave(TARGET.fingerprint))
    }

    @Test fun anAutoStateOlderThanTheSaveIsNotExported() {
        val st = StateStore(states, TARGET.fingerprint)
        st.save(byteArrayOf(0x50, 0x47, 0x42, 0x53), null, StateSlot.AUTO)
        assertTrue(st.stateFile(StateSlot.AUTO).setLastModified(1_000L))
        store().save(S1)
        val pkg = SaveExporter(saves, states, codec).buildPackage(SaveExporter.Info(TARGET.fingerprint, "gb", "Pixel", "1"))
        assertNull(codec.parse(pkg).state)
        assertNull(PgbmMeta.parse(codec.parse(pkg).meta!!).stateOfSavSha256)
    }

    @Test fun exportCarriesTheLineageBase() {
        store().save(B)
        store().recordReceived(B)
        store().save(S1)
        val pkg = SaveExporter(saves, states, codec).buildPackage(SaveExporter.Info(TARGET.fingerprint, "gb", "Pixel", "1"))
        assertEquals(sha(B), PgbmMeta.parse(codec.parse(pkg).meta!!).baseSavSha256)
    }

    // MARK: META v1

    @Test fun metaSchemaRejectsWrongTypesAndLimits() {
        val good = String(codec.parse(build("X1", codec)).meta!!)
        val bad = listOf(
            "", "[]", "{}", good.replace("\"format\":1", "\"format\":1.0"), good.replace("\"format\":1", "\"format\":\"1\""),
            good.replace("\"platform\":\"android\"", "\"platform\":\"pc\""), good.replace("\"name\":\"gb\"", "\"name\":\"nes\""),
            good.replace("\"created_ms\":1790003600000", "\"created_ms\":-1"),
            good.replace("\"created_ms\":1790003600000", "\"created_ms\":9007199254740992"),
            good.replace("\"tags\":[\"cruzado\"]", "\"tags\":[1]"), good.replace("\"title\":\"Prueba cruzada\"", "\"title\":null"),
            good.replace("\"rom_sha256\":\"10355a7f", "\"rom_sha256\":\"zz355a7f"),
            "﻿" + good,
        )
        for (b in bad) {
            try {
                PgbmMeta.parse(b.toByteArray())
                fail("debía rechazarse: $b")
            } catch (_: PgbmMeta.Invalid) {
            }
        }
        // Claves desconocidas se ignoran y las huellas en mayúsculas se aceptan.
        val ok = PgbmMeta.parse(good.replace("{\"format\":1,", "{\"format\":1,\"futuro\":{\"x\":[1]},").uppercaseHashes().toByteArray())
        assertEquals(sha(S1), ok.savSha256)
    }

    // MARK: ND20 / auditoría

    @Test fun x8DuplicateKeyIsRejectedWithoutTouchingAnything() {
        store().save(B)
        val before = snapshotTree()
        assertEquals(SaveImporter.Result.Rejected(SaveImporter.Rejection.INVALID_META), importer().importPackage(build("X8", codec), TARGET))
        assertEquals(before, snapshotTree())
    }

    @Test fun duplicateKeysAreDetectedAfterDecodingAndAtAnyDepth() {
        assertTrue(PgbmMeta.hasDuplicateKeys("""{"a":1,"\u0061":2}"""))
        assertTrue(PgbmMeta.hasDuplicateKeys("""{"x":{"b":[{"c":1,"c":2}]}}"""))
        assertFalse(PgbmMeta.hasDuplicateKeys("""{"a":{"a":1},"b":["a","a"],"c":"{\"a\":1,\"a\":2}"}"""))
    }

    @Test fun configIsTypedAndExported() {
        val good = String(codec.parse(build("X1", codec)).meta!!)
        for (bad in listOf(""""config":{"model":"nes"}""", """"config":{"gba_bios":"true"}""", """"config":{"gba_rtc":1}""", """"config":[]""")) {
            try {
                PgbmMeta.parse(good.replace("\"core\":", "$bad,\"core\":").toByteArray())
                fail("config inválida: $bad")
            } catch (_: PgbmMeta.Invalid) {
            }
        }
        val ok = PgbmMeta.parse(good.replace("\"core\":", "\"config\":{\"model\":\"cgb\",\"futuro\":3},\"core\":").toByteArray())
        assertEquals(PgbmConfig(model = "cgb"), ok.config)
        store().save(S1)
        val info = SaveExporter.Info(TARGET.fingerprint, "gb", "Pixel", "1", config = PgbmConfig(model = "dmg", compatPalette = "3"))
        val meta = PgbmMeta.parse(codec.parse(SaveExporter(saves, states, codec).buildPackage(info)).meta!!)
        assertEquals(PgbmConfig(model = "dmg", compatPalette = "3"), meta.config)
    }

    @Test fun aStateFromAnotherConfigurationIsAnnouncedBeforeBeingDiscarded() {
        val a = File(root, "a")
        SaveStore(File(a, "saves"), TARGET.fingerprint).save(S1)
        StateStore(File(a, "states"), TARGET.fingerprint).save(byteArrayOf(0x50, 0x47, 0x42, 0x53), null, StateSlot.AUTO)
        val pkg = SaveExporter(File(a, "saves"), File(a, "states"), codec)
            .buildPackage(SaveExporter.Info(TARGET.fingerprint, "gb", "iPhone", "1", config = PgbmConfig(model = "cgb")))
        val dmg = ImportTarget(TARGET.fingerprint, "gb", true, TARGET.validSizes, config = PgbmConfig(model = "dmg"))
        assertEquals(SaveImporter.Result.NeedsChoice(SaveImporter.Ask.CONFIG_MISMATCH, "iPhone"), importer().importPackage(pkg, dmg))
        assertNull(store().load())
        val done = importer().importPackage(pkg, dmg, confirmed = setOf(SaveImporter.Ask.CONFIG_MISMATCH)) as SaveImporter.Result.Done
        assertNull("sin estado", done.continueFrom)
        assertArrayEquals(S1, store().load())
    }

    @Test fun aKnownSaveAsksAndKeepingLocalLeavesItInTheCopies() {
        store().save(S1)
        store().save(B) // S1 queda en `.1`: ya conocida
        val raw = importer().importRawSave(S1, TARGET)
        assertEquals(SaveImporter.Ask.KNOWN, (raw as SaveImporter.Result.NeedsChoice).ask)
        val keep = importer().importRawSave(S1, TARGET, SaveImporter.Choice.KEEP_LOCAL) as SaveImporter.Result.Done
        assertEquals(SaveLineage.Incoming.STALE, keep.lineage)
        assertArrayEquals(B, store().load())
        assertTrue(store().setAside().any { File(store().backupsDirectory, it.name).readBytes().contentEquals(S1) })
    }

    @Test fun installingPutsTheCurrentSaveWithItsAutoInTheBeforeImportRingAndSetsItAside() {
        store().save(B)
        val st = StateStore(states, TARGET.fingerprint)
        st.save(byteArrayOf(0x50, 0x47, 0x42, 0x53, 9), byteArrayOf(1, 2, 3), StateSlot.AUTO)
        importer().importPackage(build("X1", codec), TARGET, SaveImporter.Choice.USE_INCOMING)
        val ring = MomentStore(moments, TARGET.fingerprint).snapshot().beforeLoad
        val entry = ring.single { it.name == "Antes de importar" }
        assertTrue(entry.hasState && entry.hasSram && entry.hasThumbnail)
        assertArrayEquals(B, MomentStore(moments, TARGET.fingerprint).loadSram(MomentStore.Kind.BEFORE_LOAD, entry.id))
        assertTrue(store().setAside().any { File(store().backupsDirectory, it.name).readBytes().contentEquals(B) })
        assertArrayEquals(T1, st.load(StateSlot.AUTO))
    }

    @Test fun theSameSaveWithAStateAsksBeforeReplacingTheAuto() {
        importer().importPackage(build("X1", codec), TARGET)
        val st = StateStore(states, TARGET.fingerprint)
        st.save(byteArrayOf(0x50, 0x47, 0x42, 0x53, 7), null, StateSlot.AUTO) // se jugó un rato sin guardar
        assertEquals(SaveImporter.Result.NeedsChoice(SaveImporter.Ask.REPLACE_STATE, "Pixel de prueba"), importer().importPackage(build("X1", codec), TARGET))
        assertArrayEquals(byteArrayOf(0x50, 0x47, 0x42, 0x53, 7), st.load(StateSlot.AUTO))
        importer().importPackage(build("X1", codec), TARGET, confirmed = setOf(SaveImporter.Ask.REPLACE_STATE))
        assertArrayEquals(T1, st.load(StateSlot.AUTO))
        val ring = MomentStore(moments, TARGET.fingerprint).snapshot().beforeLoad
        assertTrue("el AUTO de aquí no se pierde", ring.any { it.hasState && it.name == "Antes de importar" })
    }

    @Test fun anUnreadableLocalIsNeverTreatedAsAbsent() {
        store().saveFile.parentFile!!.mkdirs()
        store().saveFile.mkdirs() // existe pero no se puede leer como archivo
        assertEquals(
            SaveImporter.Result.Rejected(SaveImporter.Rejection.LOCAL_UNREADABLE),
            importer().importPackage(build("X1", codec), TARGET),
        )
    }

    @Test fun anOversizeLocalIsQuarantinedBeforeInstalling() {
        saves.mkdirs()
        store().saveFile.writeBytes(ByteArray(SaveStore.MAX_SAVE_BYTES + 1))
        val r = importer().importPackage(build("X1", codec), TARGET) as SaveImporter.Result.Done
        assertTrue(r.installed)
        assertArrayEquals(S1, store().load())
        assertTrue(store().backupsDirectory.list()!!.any { it.contains("wrong-size") })
    }

    @Test fun aWrongSizedIncomingSaveIsSetAside() {
        store().save(S1)
        importer().importPackage(build("X4", codec), TARGET)
        assertTrue(store().setAside().any { File(store().backupsDirectory, it.name).readBytes().contentEquals(CrossVectors.S4) })
    }

    @Test fun metadataIsMergedNotOverwritten() {
        val local = com.joelbermudez.pocketgb.progress.GameProgress(
            playTimeMs = 5_000,
            milestones = listOf(
                com.joelbermudez.pocketgb.progress.Milestone("a", "Uno", done = false),
                com.joelbermudez.pocketgb.progress.Milestone("b", "Dos", done = true),
            ),
        )
        val meta = PgbmMeta(
            "0".repeat(64), "0".repeat(64), null, "ios", "iPhone", 1, "gb", "1", alias = "Del iPhone", tags = listOf("RPG", "nuevo"),
            playTimeMs = 9_000, milestones = listOf(PgbmMeta.Milestone("a", "Uno", true), PgbmMeta.Milestone("c", "Tres", false)),
        )
        val m = MetadataMerge.merge("Mi Rojo", listOf("rpg"), local, meta)
        assertEquals("Mi Rojo", m.alias)
        assertEquals(listOf("rpg", "nuevo"), m.tags)
        assertEquals(9_000, m.playTimeMs)
        assertEquals(listOf("a" to true, "b" to true, "c" to false), m.milestones.map { it.id to it.done })
        assertEquals("Del iPhone", MetadataMerge.merge(null, emptyList(), local, meta).alias)
    }

    private fun String.uppercaseHashes() = replace(sha(S1), sha(S1).uppercase())
}
