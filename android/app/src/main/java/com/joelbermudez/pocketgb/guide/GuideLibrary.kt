package com.joelbermudez.pocketgb.guide

/**
 * Las secciones de Ajustes › Guía, en orden. Salen de `docs/guia/` (una sola fuente: la tarea `copyGuideAssets` de
 * Gradle las empaqueta como `assets/guide/<id>.md`). Nada es remoto.
 */
object GuideLibrary {
    const val ASSET_DIR = "guide"

    val SECTION_IDS = listOf(
        "biblioteca-android",
        "carpetas-android",
        "categorias-android",
        "portadas-android",
        "partidas-continuar-y-renombrar",
        "momentos-android",
        "viajar-android",
        "controles-android",
        "gba-android",
    )

    /** Lee y analiza todas las secciones con [read] (id → Markdown); una que falte se salta, nunca rompe la guía. */
    fun load(read: (String) -> String?): List<GuideSection> {
        val sections = SECTION_IDS.mapNotNull { id -> read(id)?.let { GuideMarkdown.parse(id, it) } }
        val titles = sections.associate { it.id to it.title }
        return sections.map { it.copy(blocks = it.blocks.map { block -> retitleLinks(block, titles) }) }
    }

    /** Un enlace cuyo texto es el nombre del archivo («carpetas-android.md») muestra el título de esa sección. */
    private fun retitleLinks(block: GuideBlock, titles: Map<String, String>): GuideBlock {
        fun fix(spans: List<GuideSpan>) = spans.map { span ->
            if (span is GuideSpan.Link && span.text.endsWith(".md")) {
                resolve(span.target)?.let { titles[it.first] }?.let { span.copy(text = it) } ?: span
            } else {
                span
            }
        }
        return when (block) {
            is GuideBlock.Heading -> block.copy(text = fix(block.text))
            is GuideBlock.Paragraph -> block.copy(text = fix(block.text))
            is GuideBlock.ListItem -> block.copy(text = fix(block.text))
            is GuideBlock.Quote -> block.copy(text = fix(block.text))
            is GuideBlock.Table -> block.copy(header = block.header.map(::fix), rows = block.rows.map { row -> row.map(::fix) })
            is GuideBlock.Code -> block
        }
    }

    /**
     * Sección a la que lleva un enlace de la guía («carpetas-android.md», «carpetas-android.md#las-reglas»), o `null`
     * si el destino no está en la app (por ejemplo, un documento del proyecto).
     */
    fun resolve(target: String): Pair<String, String?>? {
        val file = target.substringBefore('#')
        val anchor = target.substringAfter('#', "").ifBlank { null }
        if ('/' in file || !file.endsWith(".md")) return null
        val id = file.removeSuffix(".md")
        return if (id in SECTION_IDS) id to anchor else null
    }
}
