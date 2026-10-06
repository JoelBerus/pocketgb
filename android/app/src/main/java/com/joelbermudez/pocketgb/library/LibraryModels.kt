package com.joelbermudez.pocketgb.library

import com.joelbermudez.pocketgb.emulator.CoreError
import com.joelbermudez.pocketgb.emulator.RomInfo
import java.util.Locale

/** Por qué no se pudo listar la carpeta. Cada caso tiene su propia recuperación en la UI. */
sealed interface LibraryError {
    /** El sistema ya no concede acceso: hay que volver a elegir la carpeta. */
    data object PermissionRevoked : LibraryError

    /** La carpeta se borró o se movió. */
    data object FolderMissing : LibraryError

    /** El proveedor falló al listarla; reintentar puede bastar. */
    data object Unreadable : LibraryError

    /** El sistema no concedió el permiso persistente de la carpeta recién elegida; la anterior sigue intacta. */
    data object AccessNotKept : LibraryError
}

sealed interface LibraryState {
    /** Estado inicial: aún no se sabe si hay carpeta (p. ej. tras morir el proceso). No es `NoFolder`. */
    data object Loading : LibraryState

    data object NoFolder : LibraryState

    /** Escaneando; [previous] es lo último conocido, para no vaciar la pantalla al volver a primer plano. */
    data class Scanning(
        val previous: List<RomEntry>,
        val folderName: String?,
        /** Avance real del escaneo («X de Y»); `total == 0` mientras aún no se conoce. */
        val done: Int = 0,
        val total: Int = 0,
    ) : LibraryState

    data class Ready(val entries: List<RomEntry>, val folderName: String?) : LibraryState

    data class Failed(val error: LibraryError) : LibraryState
}

/** Metadatos del cartucho obtenidos con el núcleo al abrir el detalle. */
data class GameDetails(
    val entry: RomEntry,
    val cartridge: String,
    val romBytes: Int,
    val sramBytes: Int,
    val hasBattery: Boolean,
    val hasRtc: Boolean,
    val headerChecksumOk: Boolean,
    val globalChecksumOk: Boolean,
    /** SHA-256 del ROM en hexadecimal. */
    val fingerprint: String,
) {
    companion object {
        fun from(entry: RomEntry, info: RomInfo) = GameDetails(
            entry = entry,
            cartridge = CartridgeNames.describe(info.cartType),
            romBytes = info.romBytes,
            sramBytes = info.sramBytes,
            hasBattery = info.hasBattery,
            hasRtc = info.hasRtc,
            headerChecksumOk = info.headerChecksumOk,
            globalChecksumOk = info.globalChecksumOk,
            fingerprint = info.fingerprintHex,
        )
    }
}

/** Por qué no se pudieron leer los metadatos de un juego. */
sealed interface DetailsError {
    val message: String

    data object NotFound : DetailsError {
        override val message = "El juego ya no está en la carpeta."
    }

    data class Problem(val problem: RomProblem) : DetailsError {
        override val message get() = problem.message
    }

    data object TooLarge : DetailsError {
        override val message = RomProblem.TOO_LARGE.message
    }

    data object Remote : DetailsError {
        override val message = RomProblem.REMOTE_UNAVAILABLE.message
    }

    data object Unreadable : DetailsError {
        override val message = RomProblem.UNREADABLE.message
    }

    /** El núcleo rechazó el ROM (tamaño declarado, MBC no soportado, solo CGB...). */
    data class CoreRejected(val error: CoreError) : DetailsError {
        override val message get() = error.message ?: "El núcleo rechazó el ROM."
    }
}

sealed interface DetailsLoad {
    data object Loading : DetailsLoad

    data class Loaded(val details: GameDetails) : DetailsLoad

    data class Failed(val error: DetailsError) : DetailsLoad
}

/** Nombre legible del tipo de cartucho (byte 0x147 de la cabecera). */
object CartridgeNames {
    fun describe(code: Int): String = when (code) {
        0x00 -> "Solo ROM"
        0x01 -> "MBC1"
        0x02 -> "MBC1 + RAM"
        0x03 -> "MBC1 + RAM + batería"
        0x05 -> "MBC2"
        0x06 -> "MBC2 + batería"
        0x08 -> "ROM + RAM"
        0x09 -> "ROM + RAM + batería"
        0x0B -> "MMM01"
        0x0C -> "MMM01 + RAM"
        0x0D -> "MMM01 + RAM + batería"
        0x0F -> "MBC3 + reloj + batería"
        0x10 -> "MBC3 + reloj + RAM + batería"
        0x11 -> "MBC3"
        0x12 -> "MBC3 + RAM"
        0x13 -> "MBC3 + RAM + batería"
        0x19 -> "MBC5"
        0x1A -> "MBC5 + RAM"
        0x1B -> "MBC5 + RAM + batería"
        0x1C -> "MBC5 + vibración"
        0x1D -> "MBC5 + vibración + RAM"
        0x1E -> "MBC5 + vibración + RAM + batería"
        0x20 -> "MBC6"
        0x22 -> "MBC7 + sensor + vibración + RAM + batería"
        0xFC -> "Pocket Camera"
        0xFD -> "Bandai TAMA5"
        0xFE -> "HuC3"
        0xFF -> "HuC1 + RAM + batería"
        else -> "Desconocido (0x%02X)".format(Locale.ROOT, code and 0xFF)
    }
}

/** Tamaños de archivo con unidades binarias y coma decimal, igual en cualquier idioma del sistema. */
object ByteFormat {
    fun format(bytes: Long): String {
        val kib = 1024.0
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> trim(bytes / kib) + " KiB"
            else -> trim(bytes / (kib * 1024)) + " MiB"
        }
    }

    private fun trim(value: Double): String {
        val text = "%.1f".format(Locale.ROOT, value)
        return text.removeSuffix(".0").replace('.', ',')
    }
}
