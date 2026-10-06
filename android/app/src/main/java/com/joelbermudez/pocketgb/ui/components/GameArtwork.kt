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

/** Huellas con portada capturada (carril «Continuar jugando», K10); se actualiza al guardar o borrar portadas. */
@Composable
fun rememberArtworkFingerprints(): Set<String> {
    val store = rememberArtworkStore()
    val version by store.version.collectAsState()
    val set by produceState(emptySet<String>(), store, version) {
        value = withContext(Dispatchers.IO) { store.fingerprints() }
    }
    return set
}

/** Portada decodificada de [fingerprint], o `null` si no hay captura. */
@Composable
fun rememberArtwork(fingerprint: String?): ImageBitmap? {
    val store = rememberArtworkStore()
    val version by store.version.collectAsState()
    val image by produceState<ImageBitmap?>(null, store, fingerprint, version) {
        value = if (fingerprint == null) null else withContext(Dispatchers.IO) { store.load(fingerprint) }
    }
    return image
}

/**
 * Portada de un juego (K9 + K11): la última captura al cerrarlo, o el placeholder generado de forma determinista
 * si aún no hay. Proporción 10:9 (la pantalla de Game Boy); con problema se atenúa. Sin suavizado: es pixel art.
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
) {
    val image = rememberArtwork(fingerprint)
    val dim = if (entry.problem == null) 1f else 0.45f
    Box(
        modifier
            .aspectRatio(ARTWORK_RATIO)
            .clip(shape)
            .alpha(dim)
            .then(if (decorative) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        if (image != null) {
            val description = stringResource(R.string.game_artwork_description, entry.title)
            Image(
                bitmap = image,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
            )
        } else {
            GamePlaceholder(
                seed = fingerprint ?: entry.id,
                title = entry.title,
                isColor = entry.isColor,
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
