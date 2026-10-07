package com.joelbermudez.pocketgb.ui.details

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.joelbermudez.pocketgb.library.RomEntry
import com.joelbermudez.pocketgb.library.artwork.CoverAvailability
import com.joelbermudez.pocketgb.library.artwork.CoverKind
import com.joelbermudez.pocketgb.library.artwork.CoverRepository
import com.joelbermudez.pocketgb.ui.components.rememberCover
import com.joelbermudez.pocketgb.ui.components.rememberCoverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tipos que se piden a `OpenDocument` (el contenido se valida igualmente por su firma). */
private val IMAGE_TYPES = arrayOf("image/png", "image/jpeg", "image/webp")

/**
 * N5 · estado y acciones de «Portada» en el centro de ajustes. Importar usa el Photo Picker (sin permisos; en Android
 * sin el selector nuevo cae solo en `ACTION_OPEN_DOCUMENT`) o `OpenDocument`; la imagen se lee con tope, se valida y se
 * guarda reducida por huella, y el juego pasa a «Imagen». [fingerprint] `null` = huella sin confirmar: la fila se ve
 * pero «Cambiar» está deshabilitado (el centro lo indica).
 */
@Composable
fun rememberCoverCenter(entry: RomEntry, fingerprint: String?): CoverCenterState {
    val repository = rememberCoverRepository()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable(entry.id) { mutableStateOf(false) }
    var importing by remember(entry.id) { mutableStateOf(false) }
    var importFailed by remember(entry.id) { mutableStateOf(false) }
    val settings by repository.settingsState.collectAsState()
    val ownVersion by repository.version.collectAsState()
    val shown = rememberCover(entry, fingerprint)
    val available by produceState(CoverAvailability(), repository, entry, fingerprint, shown, ownVersion, settings) {
        value = withContext(Dispatchers.IO) { repository.availability(entry, fingerprint) }
    }
    val pinned by produceState(false, repository, fingerprint, shown) {
        value = fingerprint != null && withContext(Dispatchers.IO) { repository.hasPinned(fingerprint) }
    }
    val import: (Uri?) -> Unit = { uri ->
        if (uri != null && fingerprint != null) {
            scope.launch {
                importing = true
                importFailed = false
                val ok = withContext(Dispatchers.IO) {
                    val bytes = CoverRepository.readUri(context, uri.toString())
                    bytes != null && repository.importImage(fingerprint, bytes)
                }
                importing = false
                importFailed = !ok
            }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), import)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), import)
    return CoverCenterState(
        choice = settings.choiceFor(fingerprint),
        shown = shown.kind.takeIf { shown.image != null } ?: CoverKind.GENERATED,
        available = available,
        hasPinned = pinned,
        showDialog = showDialog && fingerprint != null,
        onShowDialog = { open ->
            showDialog = open
            if (!open) importFailed = false
        },
        onChoose = { choice -> fingerprint?.let { fp -> scope.launch(Dispatchers.IO) { repository.setChoice(fp, choice) } } },
        onImportPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onImportFile = { files.launch(IMAGE_TYPES) },
        onRemoveImported = { fingerprint?.let { fp -> scope.launch(Dispatchers.IO) { repository.removeImported(entry, fp) } } },
        onUnpin = { fingerprint?.let { fp -> scope.launch(Dispatchers.IO) { repository.unpinCapture(fp) } } },
        importing = importing,
        importFailed = importFailed,
    )
}
