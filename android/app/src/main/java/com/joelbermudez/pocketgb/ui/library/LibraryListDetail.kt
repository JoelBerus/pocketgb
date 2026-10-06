package com.joelbermudez.pocketgb.ui.library

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.joelbermudez.pocketgb.R
import kotlinx.coroutines.launch

/**
 * Lista-detalle de la biblioteca en anchura expanded (A7 R14). Está detrás de este flag: la estructura existe y se
 * compila, pero no se enchufa a la navegación hasta pulir el panel doble (queda para A8 o una decisión de Joel).
 */
const val ENABLE_LIST_DETAIL = false

/** [list] recibe la acción «abrir detalle»; [detail] recibe el id del juego elegido. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LibraryListDetail(
    list: @Composable (openDetails: (String) -> Unit) -> Unit,
    detail: @Composable (gameId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        listPane = {
            AnimatedPane {
                list { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } }
            }
        },
        detailPane = {
            AnimatedPane {
                val id = navigator.currentDestination?.contentKey
                if (id != null) detail(id) else Text(stringResource(R.string.a7_list_detail_empty))
            }
        },
    )
}
