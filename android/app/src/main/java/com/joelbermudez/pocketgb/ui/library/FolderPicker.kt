package com.joelbermudez.pocketgb.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/** El selector de carpetas del sistema, pidiendo un permiso que se pueda conservar. */
private class PersistableOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
}

/** Devuelve una función que abre el selector; llama a [onPicked] con la URI del árbol elegido. */
@Composable
fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(PersistableOpenDocumentTree()) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return { launcher.launch(null) }
}
