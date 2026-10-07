package com.joelbermudez.pocketgb.game

import android.content.ContentResolver
import android.net.Uri
import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulationOptions
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.library.DocumentReadException
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.ExactContinuation
import com.joelbermudez.pocketgb.saves.FingerprintOwnership
import com.joelbermudez.pocketgb.saves.LaunchMode
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.ResumableCore
import com.joelbermudez.pocketgb.saves.ResumeFailure
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.saves.SaveLoadWarning
import com.joelbermudez.pocketgb.saves.SaveMirror
import com.joelbermudez.pocketgb.saves.SaveOpening
import com.joelbermudez.pocketgb.saves.SaveSizes
import com.joelbermudez.pocketgb.saves.SaveStore
import com.joelbermudez.pocketgb.saves.SavesIndex
import com.joelbermudez.pocketgb.saves.SramFlushPolicy
import com.joelbermudez.pocketgb.saves.StateStore
import com.joelbermudez.pocketgb.saves.saf.MirrorDisabledReason
import com.joelbermudez.pocketgb.saves.saf.SafSaveMirror
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Por qué no se pudo abrir un juego. Compose traduce cada caso a su mensaje y recuperación (SPEC §6). */
sealed interface OpenError {
    /** El archivo ya está marcado como no jugable en la biblioteca (cabecera inválida, demasiado grande...). */
    data class Unplayable(val problem: RomProblem) : OpenError

    /** La carpeta (o el archivo) ya no existe. */
    data object FolderMissing : OpenError

    /** El sistema ya no concede acceso a la carpeta. */
    data object PermissionRevoked : OpenError

    /** El proveedor (nube) aún no tiene el archivo disponible. */
    data object RemotePending : OpenError

    /** El archivo supera los 8 MiB: no es un ROM de Game Boy. */
    data object RomTooLarge : OpenError

    /** El núcleo rechazó el ROM (cabecera, tamaño, MBC no soportado, solo CGB...). */
    data class RomRejected(val error: CoreError) : OpenError

    /** El `.sav` junto a la ROM existe en la nube pero no se pudo leer, y no hay partida local: no se abre. */
    data object MirrorNotDownloaded : OpenError

    /** La partida guardada tiene un tamaño que el cartucho no acepta y no se puede abrir con seguridad. */
    data object SaveIncompatible : OpenError

    /** No se pudo leer o preparar la partida local: abrir empezaría de cero y podría pisarla. */
    data class LocalSaveFailed(val error: Throwable) : OpenError

    /** Esa partida tiene un guardado pendiente de una sesión anterior (huérfana o en reparación): no se abre aún. */
    data object SavePending : OpenError

    /** El archivo no se pudo leer. */
    data object Unreadable : OpenError

    /** Fallo inesperado del núcleo o del sistema. */
    data class Core(val error: Throwable) : OpenError

    /**
     * «Continuar» no pudo retomar el estado automático ([reason]). No se escribió nada: la partida está intacta y se
     * puede «Jugar desde el inicio» (A9, como iOS D8.1).
     */
    data class ResumeFailed(val reason: ResumeFailure, val fingerprint: String) : OpenError
}

sealed interface OpenResult {
    /**
     * [notices]: avisos para mostrar al empezar (p. ej. [GameNotice.HeaderDamaged]); el juego se puede jugar igual.
     * [resumed]: se retomó el estado automático («Continuar» exacto, A9).
     */
    class Opened(val game: GameSession, val notices: List<GameNotice> = emptyList(), val resumed: Boolean = false) : OpenResult {
        val warning: SaveLoadWarning? get() = game.warning
    }

    data class Failed(val error: OpenError) : OpenResult
}

/** Cómo se usa el espejo en esta sesión según lo que dice el proveedor. */
class MirrorSetup(val mirror: SaveMirror, val mode: SaveOpening.MirrorMode)

/** Localiza el espejo `<rom>.sav` junto a la ROM. Se ejecuta fuera del hilo principal. */
fun interface MirrorLocator {
    fun locate(
        entry: RomEntry,
        store: SaveStore,
        validSizes: Set<Int>,
        onDisabled: (MirrorDisabledReason) -> Unit,
    ): MirrorSetup?
}

/** Localizador real: SAF sobre la carpeta de la biblioteca. */
class SafMirrorLocator(
    private val resolver: ContentResolver,
    private val folders: FolderStore,
) : MirrorLocator {
    override fun locate(
        entry: RomEntry,
        store: SaveStore,
        validSizes: Set<Int>,
        onDisabled: (MirrorDisabledReason) -> Unit,
    ): MirrorSetup? {
        val folderId = entry.folderDocumentId ?: return null
        val tree = folders.currentUri()?.let(Uri::parse) ?: return null
        val writeGranted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        val mirror = SafSaveMirror(resolver, tree, folderId, entry.fileName, store, validSizes, writeGranted, onDisabled)
        return MirrorSetup(mirror, mirror.mirrorMode())
    }
}

/**
 * Abre un juego de la biblioteca (SPEC §2.2): lee la ROM, crea el núcleo, recupera temporales locales,
 * resuelve la partida local frente al espejo, la carga y deja la sesión lista (sin arrancar). Todo el I/O
 * ocurre en [io]; la apertura no se cancela a medias (un hilo suelto dejaría un handle nativo sin dueño).
 */
class GameLauncher(
    private val roms: RomSource,
    private val savesDirectory: File,
    private val statesRoot: File,
    private val mirrors: MirrorLocator = MirrorLocator { _, _, _, _ -> null },
    private val hasFolderPermission: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val fileOps: SaveFileOps = PosixSaveFileOps,
    private val registry: MirrorChannelRegistry = MirrorChannelRegistry.shared,
    private val newSession: () -> EmulatorSession = ::EmulatorSession,
    private val policy: () -> SramFlushPolicy = { SramFlushPolicy({ System.nanoTime() / 1_000_000 }) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Cuánto espera la apertura a que se vacíe el canal del espejo de esta huella antes de darlo por no disponible. */
    private val mirrorIdleWaitMs: Long = 5_000,
    /** Propiedad exclusiva por huella: una partida solo la tiene una sesión, huérfana, reparación o restauración (A5V3-H1). */
    private val ownership: FingerprintOwnership = FingerprintOwnership.shared,
    /**
     * Opciones de emulación según los ajustes (A6-H1): recibe la huella SHA-256 del ROM y si su byte `0x143`
     * marca un cartucho CGB. La app real la alimenta con `GameplaySettingsRepository`; por defecto, AUTO y paleta 0.
     */
    private val emulationFor: (fingerprint: String, isCgbRom: Boolean) -> EmulationOptions = { _, _ -> EmulationOptions() },
) {
    /**
     * Las opciones (modelo y paleta) se fijan al abrir. Si [options] es `null` se resuelven con [emulationFor] a partir
     * de la huella y del byte `0x143` del ROM leído; volumen, escala y paleta en caliente los aplica el ViewModel.
     */
    suspend fun open(entry: RomEntry, options: EmulationOptions? = null, mode: LaunchMode = LaunchMode.FRESH): OpenResult =
        withContext(io + NonCancellable) { openBlocking(entry, options, mode) }

    /**
     * Con [mode] = [LaunchMode.RESUME] («Continuar», A9) retoma además el estado automático si sigue siendo el de esta
     * partida ([ExactContinuation]), con la partida ya abierta y la huella ya adquirida, antes de arrancar. Si no vale
     * devuelve [OpenError.ResumeFailed] y cierra la sesión sin haber escrito nada.
     */
    fun openBlocking(entry: RomEntry, options: EmulationOptions? = null, mode: LaunchMode = LaunchMode.FRESH): OpenResult {
        entry.problem?.let { return OpenResult.Failed(OpenError.Unplayable(it)) }
        if (!hasFolderPermission()) return OpenResult.Failed(OpenError.PermissionRevoked)
        val rom = try {
            roms.read(entry.uri, LibraryScanner.MAX_ROM_BYTES.toInt() + 1)
        } catch (error: DocumentReadException) {
            return OpenResult.Failed(
                when {
                    error.remote -> OpenError.RemotePending
                    error.cause is FileNotFoundException -> OpenError.FolderMissing
                    error.cause is SecurityException -> OpenError.PermissionRevoked
                    else -> OpenError.Unreadable
                },
            )
        } catch (_: SecurityException) {
            return OpenResult.Failed(OpenError.PermissionRevoked)
        } catch (_: FileNotFoundException) {
            return OpenResult.Failed(OpenError.FolderMissing)
        } catch (_: IOException) {
            return OpenResult.Failed(OpenError.Unreadable)
        } catch (error: RuntimeException) {
            return OpenResult.Failed(OpenError.Core(error))
        }
        if (rom.size > LibraryScanner.MAX_ROM_BYTES) return OpenResult.Failed(OpenError.RomTooLarge)

        val resolved = options ?: resolveOptions(rom)
        val session = try {
            newSession()
        } catch (error: CoreError) {
            return OpenResult.Failed(OpenError.Core(error))
        }
        var handedOver = false
        var lease: FingerprintOwnership.Lease? = null
        try {
            val info = try {
                session.load(rom, now() / 1000, resolved)
            } catch (error: CoreError) {
                return OpenResult.Failed(OpenError.RomRejected(error))
            }
            val fingerprint = info.fingerprintHex
            // Propiedad exclusiva ATÓMICA antes de tocar NINGÚN archivo (recoverOrphans borra temporales que otra sesión
            // puede estar usando). Se conserva toda la vida de la sesión (la posee GameSession); si la apertura falla,
            // se libera en el `finally`. Si ya tiene dueño (sesión abierta, huérfana, reparación, restauración): no se abre.
            lease = ownership.tryAcquire(fingerprint, "sesión") ?: return OpenResult.Failed(OpenError.SavePending)
            val states = StateStore(statesRoot, fingerprint, fileOps)
            val events = GameEvents()
            // Temporales huérfanos de una escritura interrumpida (estados, capturas, índice): mejor esfuerzo.
            try { states.recoverOrphans() } catch (_: IOException) {}
            val index = SavesIndex(savesDirectory, fileOps)
            index.recoverOrphans()

            var target: com.joelbermudez.pocketgb.saves.SaveTarget? = null
            var saveStore: SaveStore? = null
            var warning: SaveLoadWarning? = null
            var baseline: ByteArray? = null
            var validSizesForIndex: Set<Int>? = null
            if (info.hasBattery && session.sramSaveSize > 0) {
                val validSizes = SaveSizes.validSizes(info.hasRtc, info.sramBytes)
                validSizesForIndex = validSizes
                val store = SaveStore(savesDirectory, fingerprint, fileOps)
                saveStore = store
                val outcome = try {
                    store.recoverOrphans(validSizes)
                    val setup = mirrors.locate(entry, store, validSizes) { events.post(GameEvent.MirrorDisabled(it)) }
                    val snapshot = if (setup == null || setup.mode == SaveOpening.MirrorMode.Shared) {
                        SaveMirror.Snapshot.Absent
                    } else {
                        // Solo con el canal de esta huella en reposo (una escritura SAF en vuelo dejaría un .sav
                        // parcial); si no se vacía a tiempo, el espejo es Unavailable: nunca se lee parcial.
                        SaveOpening.snapshotWhenIdle(setup.mirror, registry.channel(fingerprint), mirrorIdleWaitMs)
                    }
                    SaveOpening.prepare(
                        store = store,
                        mirror = setup?.mirror,
                        snapshot = snapshot,
                        validSizes = validSizes,
                        mirrorMode = setup?.mode ?: SaveOpening.MirrorMode.ReadWrite,
                        registry = registry,
                    )
                } catch (_: SaveOpening.Refusal) {
                    return OpenResult.Failed(OpenError.MirrorNotDownloaded)
                } catch (error: IOException) {
                    return OpenResult.Failed(OpenError.LocalSaveFailed(error))
                } catch (error: RuntimeException) {
                    return OpenResult.Failed(OpenError.LocalSaveFailed(error))
                }
                outcome.data?.let {
                    try {
                        session.loadSram(it)
                    } catch (_: CoreError.SramSize) {
                        return OpenResult.Failed(OpenError.SaveIncompatible)
                    } catch (error: CoreError) {
                        return OpenResult.Failed(OpenError.Core(error))
                    }
                }
                target = outcome.target
                warning = outcome.warning
                // Lo que hay ahora en el núcleo es lo que está en disco: punto de partida de los guardados.
                if (target != null) baseline = session.copySram()
            }
            index.record(fingerprint, info.title, entry.fileName, validSizesForIndex)

            // A9 · «Continuar» exacto: DESPUÉS de resolver la partida (la apertura pudo instalar un espejo más nuevo) y
            // antes de crear el guardado y el hilo. Nunca escribe: si el estado vale, su RAM ya es la de disco.
            var resumed = false
            if (mode == LaunchMode.RESUME) {
                val core = SessionResumableCore(session, info.sramBytes) { now() / 1000 }
                val outcome = try {
                    ExactContinuation.resume(core, states, saveStore?.modificationDateMs)
                } catch (_: RuntimeException) {
                    // Un fallo inesperado del núcleo al copiar o comparar: la sesión se cierra sin guardar y se ofrece
                    // «Jugar desde el inicio» igual que con cualquier otro estado que no vale.
                    ExactContinuation.Outcome.Rejected(ResumeFailure.UNREADABLE)
                }
                if (outcome is ExactContinuation.Outcome.Rejected) {
                    return OpenResult.Failed(OpenError.ResumeFailed(outcome.reason, fingerprint))
                }
                resumed = true
            }

            val game = GameSession(
                session = session,
                info = info,
                states = states,
                target = target,
                baseline = baseline,
                warning = warning,
                entryId = entry.id,
                events = events,
                policy = policy(),
                lease = lease,
                title = entry.alias ?: info.title,
            )
            handedOver = true
            // Cabecera con checksum incorrecto (K15): se abre igual y se avisa al empezar.
            val notices = if (info.headerChecksumOk) emptyList() else listOf(GameNotice.HeaderDamaged)
            return OpenResult.Opened(game, notices, resumed)
        } catch (error: CoreError) {
            return OpenResult.Failed(OpenError.Core(error))
        } catch (error: RuntimeException) {
            return OpenResult.Failed(OpenError.Core(error))
        } finally {
            if (!handedOver) {
                try { session.close() } finally { lease?.close() }
            }
        }
    }

    /**
     * «Continuar» de la biblioteca (A9): de [fingerprints], las que tienen un estado automático con firma y no anterior
     * a su partida local, con la fecha del estado. Solo lee fechas y 4 bytes por juego (nunca miniaturas); el contenido
     * se valida al abrir. Bloquea: llamar fuera del hilo principal.
     */
    fun continuations(fingerprints: Set<String>): Map<String, Long> = buildMap {
        for (fingerprint in fingerprints) {
            if (!FINGERPRINT.matches(fingerprint)) continue // nunca una ruta fuera de `states/`
            try {
                val saveDate = SaveStore(savesDirectory, fingerprint, fileOps).modificationDateMs
                StateStore(statesRoot, fingerprint, fileOps).automaticEntry(saveDate)?.let { put(fingerprint, it.dateMs) }
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
        }
    }

    /** Huella (SHA-256 del ROM completo) y marca CGB (`0x143` con el bit 7) para pedir las opciones a los ajustes. */
    private fun resolveOptions(rom: ByteArray): EmulationOptions {
        if (rom.size <= CGB_FLAG_OFFSET) return EmulationOptions()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(rom).joinToString("") { "%02x".format(it) }
        val isCgbRom = (rom[CGB_FLAG_OFFSET].toInt() and 0x80) != 0
        return emulationFor(fingerprint, isCgbRom)
    }

    private companion object {
        const val CGB_FLAG_OFFSET = 0x143
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

/**
 * El núcleo de una sesión recién abierta (cargada y SIN arrancar) visto por [ExactContinuation]. La RAM del cartucho
 * son los primeros [ramBytes] de la SRAM, sin el pie del RTC (iOS `ramBytes()`).
 */
internal class SessionResumableCore(
    private val session: EmulatorSession,
    private val ramBytes: Int,
    private val nowSeconds: () -> Long,
) : ResumableCore {
    override fun cartridgeRam(): ByteArray {
        val sram = session.copySram()
        return sram.copyOf(minOf(ramBytes, sram.size))
    }

    override fun captureState(): ByteArray = session.saveStateParked()

    override fun restoreState(state: ByteArray) = session.loadStateParked(state)

    override fun syncClockToNow() = session.syncRtc(nowSeconds())
}
