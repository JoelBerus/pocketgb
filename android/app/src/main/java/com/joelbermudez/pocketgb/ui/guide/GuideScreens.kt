package com.joelbermudez.pocketgb.ui.guide

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.guide.GuideBlock
import com.joelbermudez.pocketgb.guide.GuideHit
import com.joelbermudez.pocketgb.guide.GuideLibrary
import com.joelbermudez.pocketgb.guide.GuideSearch
import com.joelbermudez.pocketgb.guide.GuideSection
import com.joelbermudez.pocketgb.guide.GuideSpan
import com.joelbermudez.pocketgb.guide.plainText
import com.joelbermudez.pocketgb.tips.LocalTips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Caché del proceso: la guía empaquetada no cambia mientras la app corre. */
private object GuideCache {
    @Volatile var sections: List<GuideSection>? = null

    fun load(context: Context): List<GuideSection> = sections ?: GuideLibrary.load { id ->
        runCatching {
            context.assets.open("${GuideLibrary.ASSET_DIR}/$id.md").bufferedReader().use { it.readText() }
        }.getOrNull()
    }.also { sections = it }
}

/** Las secciones de la guía (`null` mientras se leen, fuera del hilo principal). */
@Composable
fun rememberGuideSections(): List<GuideSection>? {
    val context = LocalContext.current.applicationContext
    val sections by produceState(GuideCache.sections, context) {
        if (value == null) value = withContext(Dispatchers.IO) { GuideCache.load(context) }
    }
    return sections
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideScaffold(title: String, onBack: () -> Unit, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        content = content,
    )
}

/** Ajustes › Guía: buscador y lista de secciones; con texto en el buscador, los apartados que lo contienen. */
@Composable
fun GuideScreen(onOpenSection: (String, String?) -> Unit, onBack: () -> Unit) {
    GuideContent(rememberGuideSections(), onOpenSection, onBack)
}

@Composable
fun GuideContent(
    sections: List<GuideSection>?,
    onOpenSection: (String, String?) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val hits = remember(sections, query) { sections?.let { GuideSearch.search(it, query) }.orEmpty() }
    val tips = LocalTips.current
    var tipsReset by remember { mutableStateOf(false) }
    GuideScaffold(stringResource(R.string.n9_guide_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("guide-list"), contentPadding = padding) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.n9_guide_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.n9_guide_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.testTag("guide-search-clear")) {
                                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.n9_guide_search_clear))
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().testTag("guide-search"),
                    )
                }
            }
            when {
                sections == null -> Unit
                sections.isEmpty() -> item(key = "unavailable") { GuideNote(stringResource(R.string.n9_guide_unavailable)) }
                query.isBlank() -> {
                    item(key = "sections-header") { GroupHeader(stringResource(R.string.n9_guide_sections)) }
                    itemsIndexed(sections, key = { _, s -> "section-${s.id}" }) { _, section ->
                        ListItem(
                            headlineContent = { Text(section.title) },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            modifier = Modifier
                                .heightIn(min = 56.dp)
                                .clickable { onOpenSection(section.id, null) }
                                .testTag("guide-section-${section.id}"),
                        )
                    }
                    if (tips != null) {
                        item(key = "tips") {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            GroupHeader(stringResource(R.string.n9_guide_tips_header))
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.n9_guide_tips_reset)) },
                                supportingContent = {
                                    Text(
                                        stringResource(if (tipsReset) R.string.n9_guide_tips_reset_done else R.string.n9_guide_tips_reset_summary),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                },
                                leadingContent = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
                                modifier = Modifier
                                    .heightIn(min = 56.dp)
                                    .clickable {
                                        tips.resetAll()
                                        tipsReset = true
                                    }
                                    .testTag("guide-tips-reset"),
                            )
                        }
                    }
                }
                hits.isEmpty() -> item(key = "no-results") {
                    GuideNote(stringResource(R.string.n9_guide_no_results, query.trim()), Modifier.testTag("guide-no-results"))
                }
                else -> {
                    item(key = "results-header") {
                        GroupHeader(
                            pluralStringResource(R.plurals.n9_guide_results_count, hits.size, hits.size),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    itemsIndexed(hits, key = { index, _ -> "hit-$index" }) { index, hit -> HitRow(hit, index, onOpenSection) }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: GuideHit, index: Int, onOpenSection: (String, String?) -> Unit) {
    ListItem(
        overlineContent = if (hit.heading != null) ({ Text(hit.sectionTitle) }) else null,
        headlineContent = { Text(hit.heading ?: hit.sectionTitle) },
        supportingContent = { Text(hit.snippet, maxLines = 4) },
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clickable { onOpenSection(hit.sectionId, hit.anchor) }
            .testTag("guide-hit-$index"),
    )
}

@Composable
private fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun GuideNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(16.dp),
    )
}

/** Una sección de la guía, pintada de forma nativa. Con [anchor], abre desplazada a ese apartado. */
@Composable
fun GuideSectionScreen(sectionId: String, anchor: String?, onOpenSection: (String, String?) -> Unit, onBack: () -> Unit) {
    val sections = rememberGuideSections()
    val section = sections?.firstOrNull { it.id == sectionId }
    GuideSectionContent(section, anchor, onOpenSection, onBack, unavailable = sections != null && section == null)
}

@Composable
fun GuideSectionContent(
    section: GuideSection?,
    anchor: String?,
    onOpenSection: (String, String?) -> Unit,
    onBack: () -> Unit,
    unavailable: Boolean = false,
) {
    GuideScaffold(section?.title ?: stringResource(R.string.n9_guide_title), onBack) { padding ->
        if (section == null) {
            if (unavailable) Column(Modifier.padding(padding)) { GuideNote(stringResource(R.string.n9_guide_unavailable)) }
            return@GuideScaffold
        }
        val listState = rememberLazyListState()
        LaunchedEffect(section.id, anchor) {
            val index = section.blocks.indexOfFirst { it is GuideBlock.Heading && it.anchor == anchor }
            if (index >= 0) listState.scrollToItem(index)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("guide-section-body"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(section.blocks, key = { index, _ -> index }) { _, block -> GuideBlockView(block, onOpenSection) }
        }
    }
}

@Composable
private fun GuideBlockView(block: GuideBlock, onOpenSection: (String, String?) -> Unit) {
    when (block) {
        is GuideBlock.Heading -> Text(
            rich(block.text, onOpenSection),
            style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = if (block.level <= 2) 12.dp else 4.dp).semantics { heading() }
                .testTag("guide-heading-${block.anchor}"),
        )
        is GuideBlock.Paragraph -> Text(rich(block.text, onOpenSection), style = MaterialTheme.typography.bodyLarge)
        is GuideBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // La viñeta es decorativa: TalkBack ya lee cada elemento por separado.
            Text(
                block.marker ?: "•",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(rich(block.text, onOpenSection), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        is GuideBlock.Quote -> Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(rich(block.text, onOpenSection), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
        is GuideBlock.Code -> Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        is GuideBlock.Table -> GuideTable(block, onOpenSection)
    }
}

/**
 * Las tablas se pintan como fichas, una por fila: la primera celda es el título y las demás van con su cabecera
 * («Game Boy: Menú de pausa»). Así no hay desplazamiento horizontal y con la fuente al 200 % nada se corta.
 */
@Composable
private fun GuideTable(table: GuideBlock.Table, onOpenSection: (String, String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        table.rows.forEach { row ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("guide-table-row"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        rich(row.firstOrNull().orEmpty(), onOpenSection),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    row.drop(1).forEachIndexed { index, cell ->
                        val header = table.header.getOrNull(index + 1)?.plainText().orEmpty()
                        if (cell.plainText().isNotBlank()) {
                            Text(
                                buildAnnotatedString {
                                    if (header.isNotBlank() && table.header.size > 2) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(header) }
                                        append(": ")
                                    }
                                    append(rich(cell, onOpenSection))
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Texto con estilos; los enlaces a otra sección de la guía son enlaces de verdad (TalkBack los anuncia). */
@Composable
private fun rich(spans: List<GuideSpan>, onOpenSection: (String, String?) -> Unit): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(spans, linkColor, codeBackground) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span) {
                    is GuideSpan.Plain -> append(span.text)
                    is GuideSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                    is GuideSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                    is GuideSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(span.text) }
                    is GuideSpan.Link -> {
                        val destination = GuideLibrary.resolve(span.target)
                        val weight = if (span.bold) FontWeight.Bold else null
                        if (destination == null) {
                            // Documento que no está en la app: se lee como texto.
                            withStyle(SpanStyle(fontWeight = weight)) { append(span.text) }
                        } else {
                            val link = LinkAnnotation.Clickable(
                                tag = span.target,
                                styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = weight, textDecoration = TextDecoration.Underline)),
                            ) { onOpenSection(destination.first, destination.second) }
                            withLink(link) { append(span.text) }
                        }
                    }
                }
            }
        }
    }
}
