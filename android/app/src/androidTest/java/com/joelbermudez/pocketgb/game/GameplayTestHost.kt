package com.joelbermudez.pocketgb.game

import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.RomSource
import com.joelbermudez.pocketgb.saves.BlockedFingerprints
import com.joelbermudez.pocketgb.saves.MirrorChannelRegistry
import com.joelbermudez.pocketgb.saves.PosixSaveFileOps
import com.joelbermudez.pocketgb.saves.SaveFileOps
import com.joelbermudez.pocketgb.testing.SyntheticRom
import java.io.File

/** Datos y lanzador de las pruebas del juego (ROM sintética en memoria; nada de proveedores). */
object GameplayTestHost {
    val entry = RomEntry(
        id = "Contador.gb",
        uri = "content://prueba/Contador.gb",
        fileName = "Contador.gb",
        title = "CONTADOR",
        isColor = false,
        sizeBytes = 32L * 1024,
        headerChecksumOk = true,
        problem = null,
    )

    fun launcher(
        root: File,
        ops: SaveFileOps = PosixSaveFileOps,
        rom: ByteArray = SyntheticRom.sramCounter(),
        mirrors: MirrorLocator = MirrorLocator { _, _, _, _ -> null },
        blocked: BlockedFingerprints = BlockedFingerprints(),
    ) = GameLauncher(
        roms = RomSource { _, _ -> rom },
        savesDirectory = File(root, "saves"),
        statesRoot = File(root, "states"),
        mirrors = mirrors,
        fileOps = ops,
        registry = MirrorChannelRegistry(),
        blocked = blocked,
    )
}
