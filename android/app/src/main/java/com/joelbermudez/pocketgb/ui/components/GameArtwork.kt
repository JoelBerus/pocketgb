package com.joelbermudez.pocketgb.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.ArtworkStore
import com.joelbermudez.pocketgb.library.artwork.CoverKind
import com.joelbermudez.pocketgb.library.artwork.CoverRepository
import com.joelbermudez.pocketgb.library.artwork.ShownCover
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Almacén de portadas de la UI. `null` = el compartido del proceso; las pruebas Compose inyectan el suyo. */
val LocalArtworkStore = compositionLocalOf<ArtworkStore?> { null }

@Composable
fun rememberArtworkStore(): ArtworkStore {
    val provided = LocalArtworkStore.current
    val context = LocalContext.current.applicationContext
    return provided ?: remember(context) { ArtworkStore.shared(context) }
}

/** N5: repositorio de portadas de la UI. `null` = el compartido (o uno sobre [LocalArtworkStore] si lo hay). */
val LocalCoverRepository = compositionLocalOf<CoverRepository?> { null }

@Composable
fun rememberCoverRepository(): CoverRepository {
    LocalCoverRepository.current?.let { return it }
    val provided = LocalArtworkStore.current
    val context = LocalContext.current.applicationContext
    // Catálogo y pruebas de antes de N5: solo capturas, elección Automática.
    return remember(context, provided) { if (provided != null) CoverRepository(provided) else CoverRepository.shared(context) }
}

/** Cambia cada vez que cambia algo que afecta a las portadas (capturas, fijadas, importadas, elección). */
@Composable
private fun coverVersion(repository: CoverRepository): Any {
    val captures by repository.captures.version.collectAsState()
    val pinned = repository.pinned?.version?.collectAsState()?.value
    val imported = repository.imported?.version?.collectAsState()?.value
    val own by repository.version.collectAsState()
    val settings by repository.settingsState.collectAsState()
    return listOf(captures, pinned, imported, own, settings)
}

/** Huellas con portada propia (captura o imagen importada): carril «Continuar jugando» (K10). */
@Composable
fun rememberArtworkFingerprints(): Set<String> {
    val repository = rememberCoverRepository()
    val version = coverVersion(repository)
    val set by produceState(emptySet<String>(), repository, version) {
        value = withContext(Dispatchers.IO) { repository.fingerprintsWithCover() }
    }
    return set
}

/** Portada que se ve de [entry] (N5): la primera fuente que se lea bien, o la generada. */
@Composable
fun rememberCover(entry: RomEntry, fingerprint: String?): ShownCover {
    val repository = rememberCoverRepository()
    val version = coverVersion(repository)
    val cover by produceState(ShownCover.GENERATED, repository, fingerprint, entry.coverUri, entry.coverStamp, version) {
        value = withContext(Dispatchers.IO) { repository.load(entry, fingerprint) }
    }
    return cover
}

/** Portada decodificada de [fingerprint] según su elección, o `null` si se ve la generada. */
@Composable
fun rememberArtwork(entry: RomEntry, fingerprint: String?): ImageBitmap? = rememberCover(entry, fingerprint).image

/**
 * Portada de un juego (K9 + K11 + N5): imagen importada, imagen de la carpeta, captura o la generada, según la elección
 * del juego y la preferencia global. **Encaje (N5):** el marco (10:9 en tarjetas y filas; la proporción de la consola en
 * el detalle) no cambia nunca; en tarjetas la imagen lo rellena centrada (se recorta lo que sobra) y con [fit] (detalle)
 * se ve entera, con bandas del color de superficie. Las capturas se dibujan sin suavizado (pixel art); las imágenes, con
 * filtrado. Con problema se atenúa.
 */
@Composable
fun GameArtwork(
    entry: RomEntry,
    fingerprint: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shape: Shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
    /** `true` si quien la contiene ya describe el juego (tarjeta, fila): la portada no se anuncia aparte. */
    decorative: Boolean = false,
    /** N3a: ancho / alto del marco; el detalle pasa la proporción de la consola (`screenAspectRatio`). */
    aspectRatio: Float = ARTWORK_RATIO,
    /** N5: la imagen entera dentro del marco (detalle) en vez de rellenarlo. */
    fit: Boolean = false,
) {
    val cover = rememberCover(entry, fingerprint)
    val image = cover.image
    val dim = if (entry.problem == null) 1f else 0.45f
    Box(
        modifier
            .aspectRatio(aspectRatio)
            .clip(shape)
            .alpha(dim)
            .then(if (decorative) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        // N5: la fuente que se ve, para las pruebas (en tarjetas decorativas no llega al árbol de semántica).
        Box(Modifier.matchParentSize().testTag("cover-${cover.kind.name.lowercase()}"))
        if (image != null) {
            val description = stringResource(R.string.game_artwork_description, entry.displayTitle)
            Image(
                bitmap = image,
                contentDescription = description,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (fit) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier),
                // N8: una captura de GBA (3:2) en un marco de 10:9 se recorta centrada en vez de deformarse; en el
                // marco de su proporción (detalle) cabe exacta. N5: en el detalle, una imagen se ve entera.
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                filterQuality = if (cover.kind == CoverKind.CAPTURE) FilterQuality.None else FilterQuality.Medium,
            )
        } else {
            GamePlaceholder(
                seed = fingerprint ?: entry.id,
                title = entry.displayTitle,
                console = entry.console,
                modifier = Modifier.fillMaxSize(),
                compact = compact,
            )
        }
    }
}

/** 160×144 de la pantalla de Game Boy. */
const val ARTWORK_RATIO = 10f / 9f

/** Ancho de la miniatura de las filas de lista. */
val ThumbnailWidth: Dp = 56.dp
