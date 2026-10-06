package com.joelbermudez.pocketgb.game

import android.content.ContentResolver
import android.net.Uri
import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.library.DocumentReadException
import com.joelbermudez.pocketgb.library.FolderStore
import com.joelbermudez.pocketgb.library.LibraryScanner
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomProblem
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
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

    /** El archivo no se pudo leer. */
    data object Unreadable : OpenError

    /** Fallo inesperado del núcleo o del sistema. */
    data class Core(val error: Throwable) : OpenError
}

sealed interface OpenResult {
    class Opened(val game: GameSession) : OpenResult {
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
) {
    suspend fun open(entry: RomEntry): OpenResult = withContext(io + NonCancellable) { openBlocking(entry) }

    fun openBlocking(entry: RomEntry): OpenResult {
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

        val session = try {
            newSession()
        } catch (error: CoreError) {
            return OpenResult.Failed(OpenError.Core(error))
        }
        var handedOver = false
        try {
            val info = try {
                session.load(rom, now() / 1000)
            } catch (error: CoreError) {
                return OpenResult.Failed(OpenError.RomRejected(error))
            }
            val fingerprint = info.fingerprintHex
            val states = StateStore(statesRoot, fingerprint, fileOps)
            val events = GameEvents()

            var target: com.joelbermudez.pocketgb.saves.SaveTarget? = null
            var warning: SaveLoadWarning? = null
            var baseline: ByteArray? = null
            if (info.hasBattery && session.sramSaveSize > 0) {
                val validSizes = SaveSizes.validSizes(info.hasRtc, info.sramBytes)
                val store = SaveStore(savesDirectory, fingerprint, fileOps)
                val outcome = try {
                    store.recoverOrphans(validSizes)
                    val setup = mirrors.locate(entry, store, validSizes) { events.post(GameEvent.MirrorDisabled(it)) }
                    val snapshot = if (setup == null || setup.mode == SaveOpening.MirrorMode.Shared) {
                        SaveMirror.Snapshot.Absent
                    } else {
                        setup.mirror.snapshot()
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
            SavesIndex(savesDirectory, fileOps).record(fingerprint, info.title, entry.fileName)

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
            )
            handedOver = true
            return OpenResult.Opened(game)
        } catch (error: CoreError) {
            return OpenResult.Failed(OpenError.Core(error))
        } catch (error: RuntimeException) {
            return OpenResult.Failed(OpenError.Core(error))
        } finally {
            if (!handedOver) session.close()
        }
    }
}
