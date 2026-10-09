package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.travel.CrossVectors.S1
import com.joelbermudez.pocketgb.travel.CrossVectors.TARGET
import com.joelbermudez.pocketgb.travel.CrossVectors.build
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N7c · bandeja de intercambio, estado de la partida en el detalle y continuación tras importar. */
class SaveTravelN7cTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun inboxOffersOnlyNewPackagesNotSentFromHere() {
        val inbox = ExchangeInbox(File(tmp.newFolder(), "seen.json"))
        val a = ExchangeFolder.Item("a", "Rojo · iPhone.pgbm", 10, 100)
        val b = ExchangeFolder.Item("b", "Rojo · Pixel.pgbm", 20, 100)
        assertEquals(listOf("b", "a"), inbox.pending(listOf(a, b)).map { it.documentId })
        inbox.markSent("b")
        inbox.markSeen(a)
        assertTrue(inbox.pending(listOf(a, b)).isEmpty())
        // El mismo documento reescrito (otro envío con el mismo nombre) vuelve a ser nuevo.
        val a2 = ExchangeFolder.Item("a", "Rojo · iPhone.pgbm", 30, 120)
        assertEquals(listOf("a"), inbox.pending(listOf(a2)).map { it.documentId })
    }

    @Test fun aCorruptInboxFileOffersEverythingAgainWithoutFailing() {
        val f = File(tmp.newFolder(), "seen.json").apply { writeText("{roto") }
        assertEquals(1, ExchangeInbox(f).pending(listOf(ExchangeFolder.Item("x", "x.pgbm", 1, 1))).size)
    }

    @Test fun exchangeFileNamesAreUniqueAndSafe() {
        val n = ExchangeFolder.fileName("Pokémon: Rojo/Azul", "Pixel 9", 1_790_000_000_000)
        assertTrue(n.endsWith(".pgbm"))
        assertFalse(n.contains('/') || n.contains(':'))
        assertTrue(n.startsWith("Pokémon  Rojo Azul · Pixel 9 · "))
    }

    @Test fun saveStatusSaysWhereTheCurrentSaveComesFrom() {
        val root = tmp.newFolder()
        val saves = File(root, "saves")
        assertNull(SaveStatus.read(saves, TARGET.fingerprint))
        SaveImporter(saves, File(root, "states"), File(root, "moments"), ReferencePgbmCodec, ownership = FingerprintOwnership())
            .importPackage(build("X1", ReferencePgbmCodec), TARGET)
        val fromPixel = SaveStatus.read(saves, TARGET.fingerprint)!!
        assertEquals("Pixel de prueba", fromPixel.device)
        assertEquals(1790003600000, fromPixel.atMs)
        // Se juega aquí y la partida cambia: ya es «Este teléfono».
        SaveStore(saves, TARGET.fingerprint).save(S1.copyOf().also { it[0] = 9 })
        val here = SaveStatus.read(saves, TARGET.fingerprint)!!
        assertNull(here.device)
        assertTrue(here.atMs > 0)
    }
}
