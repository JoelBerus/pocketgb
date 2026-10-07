package com.joelbermudez.pocketgb.library.artwork

import com.joelbermudez.pocketgb.library.TreeNode

/**
 * N5 · portadas. Todo lo de este archivo es puro (sin Android) y se prueba en JVM.
 *
 * Fuentes de una portada, por huella:
 * - [CoverKind.IMPORTED]: imagen importada en la app (Photo Picker u `OpenDocument`), guardada reducida (≤ 1024 px);
 * - [CoverKind.SIDECAR]: imagen junto al ROM en la carpeta (`<nombre del ROM>.png|jpg|jpeg|webp`, o `portada.*` /
 *   `cover.*` si la carpeta tiene un solo juego);
 * - [CoverKind.CAPTURE]: captura del juego (la fijada con «Usar como portada» desde la pausa o, si no, el último
 *   fotograma al cerrar, K9);
 * - [CoverKind.GENERATED]: la portada generada (color y glifo de la huella). Siempre está y es el último recurso.
 */
enum class CoverKind { IMPORTED, SIDECAR, CAPTURE, GENERATED }

/** Elección por juego (centro de ajustes › Portada). */
enum class CoverChoice { AUTO, IMAGE, CAPTURE, GENERATED }

/** Elección global (Ajustes › Biblioteca › Portadas): qué gana en «Automática» cuando hay imagen y captura. */
enum class CoverPreference { IMAGES, CAPTURES }

/** Qué fuentes tiene un juego ahora mismo (sin decodificar nada). */
data class CoverAvailability(
    val imported: Boolean = false,
    val sidecar: Boolean = false,
    val capture: Boolean = false,
) {
    val hasImage: Boolean get() = imported || sidecar
}

object CoverResolver {
    /**
     * Fuentes a probar, en orden; la última es siempre [CoverKind.GENERATED]. Quien dibuja prueba cada una y, si una
     * falla al leerse o decodificarse, pasa a la siguiente: ante cualquier fallo se acaba en la generada.
     * - Imagen: la importada gana a la de la carpeta (es un gesto explícito en la app).
     * - Una elección explícita (Imagen o Captura) sin esa fuente da la generada: nunca muestra otra fuente que el
     *   usuario no eligió.
     * - Automática sigue la preferencia global.
     */
    fun candidates(choice: CoverChoice, preference: CoverPreference, available: CoverAvailability): List<CoverKind> {
        val images = buildList {
            if (available.imported) add(CoverKind.IMPORTED)
            if (available.sidecar) add(CoverKind.SIDECAR)
        }
        val captures = if (available.capture) listOf(CoverKind.CAPTURE) else emptyList()
        val ordered = when (choice) {
            CoverChoice.GENERATED -> emptyList()
            CoverChoice.IMAGE -> images
            CoverChoice.CAPTURE -> captures
            CoverChoice.AUTO -> if (preference == CoverPreference.IMAGES) images + captures else captures + images
        }
        return ordered + CoverKind.GENERATED
    }

    /** La fuente que se verá si todas las candidatas se leen bien. */
    fun resolve(choice: CoverChoice, preference: CoverPreference, available: CoverAvailability): CoverKind =
        candidates(choice, preference, available).first()
}

/** Imagen junto al ROM (N5): `<nombre del ROM>.<ext>` o, si la carpeta tiene un solo juego, `portada.*`/`cover.*`. */
object SidecarCover {
    /** Extensiones por prioridad. El contenido se valida después por sus bytes: la extensión no basta. */
    val EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
    private val FOLDER_NAMES = listOf("portada", "cover")

    /**
     * Busca la portada de [romName] entre [siblings] (la misma carpeta), sin distinguir mayúsculas. [romsInFolder] es
     * cuántos ROMs hay en esa carpeta: `portada.*`/`cover.*` solo valen con uno. Ignora carpetas, ocultos (`.`) y
     * documentos virtuales o vacíos. Desempate estable: extensión por prioridad y luego nombre.
     */
    fun find(romName: String, siblings: List<TreeNode>, romsInFolder: Int): TreeNode? {
        val base = romName.substringBeforeLast('.')
        val files = siblings.filter { !it.isDirectory && !it.isVirtual && !it.name.startsWith(".") && it.sizeBytes != 0L }
        fun pick(stem: String): TreeNode? = EXTENSIONS.firstNotNullOfOrNull { ext ->
            files.filter { it.name.equals("$stem.$ext", ignoreCase = true) }.minByOrNull { it.name + "\u0000" + it.id }
        }
        pick(base)?.let { return it }
        if (romsInFolder != 1) return null
        return FOLDER_NAMES.firstNotNullOfOrNull(::pick)
    }

    /** Sello de una imagen de la carpeta: si cambia (otra imagen, editada), se vuelve a leer. */
    fun stamp(node: TreeNode): String = "${node.id}|${node.sizeBytes}|${node.lastModified ?: 0}"
}

/** Formatos de imagen aceptados, reconocidos por sus primeros bytes (nunca por la extensión). */
enum class CoverFormat { PNG, JPEG, WEBP }

/**
 * Reglas de la imagen como entrada no confiable (N5): tope de bytes, formato por firma, dimensiones máximas y
 * submuestreo para no reservar nunca un mapa de bits enorme.
 */
object CoverImageRules {
    /** Tope de lo que se lee de una imagen (importada o de la carpeta). */
    const val MAX_BYTES: Int = 15 * 1024 * 1024

    /** Lado máximo de la copia guardada. */
    const val TARGET_SIDE: Int = 1024

    /** Lado máximo que se acepta en la cabecera (más es hostil o absurdo para una portada). */
    const val MAX_SIDE: Int = 16_384

    /** Píxeles máximos de la cabecera (≈ 100 MP). */
    const val MAX_PIXELS: Long = 100_000_000L

    fun sniff(bytes: ByteArray): CoverFormat? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        if (bytes.size >= 8 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
            at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A
        ) return CoverFormat.PNG
        if (bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return CoverFormat.JPEG
        if (bytes.size >= 12 && at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
            at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code
        ) return CoverFormat.WEBP
        return null
    }

    /** `true` si las dimensiones de la cabecera son aceptables. */
    fun dimensionsOk(width: Int, height: Int): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

    /**
     * `inSampleSize` (potencia de 2) para decodificar sin pasar de 2 × [target] en el lado mayor: la reducción final a
     * [target] se hace después con buen filtrado.
     */
    fun sampleSize(width: Int, height: Int, target: Int = TARGET_SIDE): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Tamaño final con el lado mayor ≤ [target], conservando la proporción (nunca 0). */
    fun scaledSize(width: Int, height: Int, target: Int = TARGET_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= target) return width to height
        val scale = target.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
