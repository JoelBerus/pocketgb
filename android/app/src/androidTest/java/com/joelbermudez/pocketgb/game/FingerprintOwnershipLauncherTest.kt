package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.testing.SyntheticRom
import com.joelbermudez.pocketgb.testing.tempDir
import com.joelbermudez.pocketgb.testing.waitUntil
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A5V3-H1: propiedad exclusiva atómica por huella en el lanzador. Pruebas deterministas con latch que intercalan
 * exactamente DESPUÉS de que la primera apertura haya pasado el punto en el que antes se "comprobaba" el bloqueo.
 */
class FingerprintOwnershipLauncherTest {
    private lateinit var root: File
    private val pool = Executors.newFixedThreadPool(3)

    @Before fun setUp() { root = tempDir("ownership") }

    @After fun tearDown() {
        pool.shutdownNow()
        root.deleteRecursively()
    }

    private fun fingerprintOf(): String = GameplayTestHost.launcher(root).let { launcher ->
        val opened = launcher.openBlocking(GameplayTestHost.entry) as OpenResult.Opened
        val fp = opened.game.fingerprint
        opened.game.close()
        fp
    }

    /**
     * (b) Dos aperturas simultáneas de la misma huella: la primera queda dentro de la apertura (con el lease ya
     * adquirido, en el localizador del espejo) y la segunda debe fallar con SavePending; con "comprobar y luego abrir"
     * las dos pasaban la comprobación y abrían la misma partida a la vez.
     */
    @Test
    fun twoSimultaneousOpensOfTheSameFingerprintOnlyOneWins() {
        val ownership = FingerprintOwnership()
        val inside = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val first = GameplayTestHost.launcher(
            root,
            mirrors = MirrorLocator { _, _, _, _ ->
                inside.countDown()
                proceed.await()
                null
            },
            ownership = ownership,
        )
        val second = GameplayTestHost.launcher(root, ownership = ownership)

        val firstResult = pool.submit<OpenResult> { first.openBlocking(GameplayTestHost.entry) }
        assertTrue("la primera apertura ya tocó la partida", inside.await(10, TimeUnit.SECONDS))

        val secondResult = second.openBlocking(GameplayTestHost.entry)
        assertEquals(OpenResult.Failed(OpenError.SavePending), secondResult)

        proceed.countDown()
        val opened = firstResult.get(15, TimeUnit.SECONDS) as OpenResult.Opened
        assertTrue("la ganadora conserva el lease toda la vida de la sesión", opened.game.holdsLease)
        assertEquals(OpenResult.Failed(OpenError.SavePending), second.openBlocking(GameplayTestHost.entry))

        opened.game.close()
        assertTrue("al cerrarse la sesión se libera", waitUntil { !ownership.isOwned(opened.game.fingerprint) })
        val third = second.openBlocking(GameplayTestHost.entry)
        assertTrue("y ya se puede abrir de nuevo: $third", third is OpenResult.Opened)
        (third as OpenResult.Opened).game.close()
    }

    /** (d) El lease se libera tras un fallo de apertura (error del espejo/disco) y tras una excepción inesperada. */
    @Test
    fun theLeaseIsReleasedWhenTheOpenFailsOrThrows() {
        val ownership = FingerprintOwnership()
        val fp = fingerprintOf()

        var seenOwned = false
        val failing = GameplayTestHost.launcher(
            root,
            mirrors = MirrorLocator { _, _, _, _ ->
                seenOwned = ownership.isOwned(fp) // durante la apertura la huella es nuestra
                throw IllegalStateException("fallo inyectado del localizador")
            },
            ownership = ownership,
        )
        val result = failing.openBlocking(GameplayTestHost.entry)
        assertTrue("falló la apertura: $result", result is OpenResult.Failed)
        assertTrue("durante la apertura tenía dueño", seenOwned)
        assertFalse("tras el fallo, libre", ownership.isOwned(fp))

        val refusing = GameplayTestHost.launcher(
            root,
            mirrors = MirrorLocator { _, _, _, _ -> throw SaveOpening.Refusal.MirrorNotDownloaded },
            ownership = ownership,
        )
        assertEquals(OpenResult.Failed(OpenError.MirrorNotDownloaded), refusing.openBlocking(GameplayTestHost.entry))
        assertFalse(ownership.isOwned(fp))

        val ok = GameplayTestHost.launcher(root, ownership = ownership).openBlocking(GameplayTestHost.entry)
        assertTrue(ok is OpenResult.Opened)
        assertTrue((ok as OpenResult.Opened).game.holdsLease)
        ok.game.close()
        assertFalse("tras cerrar la sesión, libre", ownership.isOwned(fp))
    }

    /** (d) Si el ViewModel falla tras abrir (p. ej. `recordPlayed` lanza), cierra la sesión y la huella queda libre. */
    @Test
    fun aViewModelThatFailsAfterOpeningStillReleasesTheLease() {
        val ownership = FingerprintOwnership()
        val vm = GameplayViewModel(
            GameplayTestHost.launcher(root, ownership = ownership),
            recordPlayed = { _, _, _ -> throw IllegalStateException("biblioteca rota") },
        )
        vm.open(GameplayTestHost.entry)
        assertTrue(waitUntil(15_000) { vm.dialog.value != null })
        assertNull(vm.game.value)
        val fp = fingerprintOfRom()
        assertTrue("la huella queda libre", waitUntil(10_000) { !ownership.isOwned(fp) })
    }

    private fun fingerprintOfRom(): String = fingerprintOf()
}
