package com.joelbermudez.pocketgb.emulator

import java.security.MessageDigest

/** Estado de la BIOS de GBA de la biblioteca (= iOS `BIOSFile.Status`). */
enum class GbaBiosStatus {
    /** No hay `gba_bios.bin`: BIOS emulada (HLE). */
    ABSENT,

    /** Volcado oficial verificado: se usa. */
    VALID,

    /** Hay archivo pero no es la BIOS oficial (tamaño o SHA-256): se ignora y se usa la emulada. */
    INVALID,
}

/**
 * BIOS opcional de Game Boy Advance: el volcado propio del usuario, `gba_bios.bin` en la carpeta de la biblioteca
 * (nunca en el repo ni en la app, regla dura 1). Solo vale si su SHA-256 es el de la BIOS oficial (el mismo que
 * valida iOS). El puente nativo vuelve a comprobarlo antes de cargarla: una BIOS que no lo sea nunca llega al núcleo.
 */
object GbaBios {
    const val FILE_NAME = "gba_bios.bin"
    const val SIZE_BYTES = 16 * 1024

    /** SHA-256 de la BIOS oficial (GBA, GBA SP, Micro y Game Boy Player) = `NATIVE_GBA_BIOS_SHA256`. */
    const val SHA256 = "fd2547724b505f487e6dcb29ec2ecff3af35a841a77ab2e85fd87350abd36570"

    fun isOfficial(data: ByteArray): Boolean = data.size == SIZE_BYTES && sha256Hex(data) == SHA256

    internal fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    /** Estado de un archivo leído ([data] null = no existe). */
    fun status(data: ByteArray?): GbaBiosStatus = when {
        data == null -> GbaBiosStatus.ABSENT
        isOfficial(data) -> GbaBiosStatus.VALID
        else -> GbaBiosStatus.INVALID
    }
}
