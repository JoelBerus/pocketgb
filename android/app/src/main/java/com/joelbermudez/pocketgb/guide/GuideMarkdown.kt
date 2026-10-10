package com.joelbermudez.pocketgb.guide

import java.text.Normalizer

/**
 * N9 · Guía en la app: el subconjunto de Markdown que usan los `.md` de `docs/guia/` (títulos, párrafos, listas, tablas,
 * bloques de código, citas y, dentro de una línea, negrita, código y enlaces). Lógica pura: se prueba en la JVM y la
 * pinta Compose de forma nativa (sin WebView ni nada remoto).
 */
sealed interface GuideBlock {
    data class Heading(val level: Int, val text: List<GuideSpan>, val anchor: String) : GuideBlock
    data class Paragraph(val text: List<GuideSpan>) : GuideBlock
    /** Elemento de lista; [marker] es `null` en las de viñetas y «3.» en las numeradas. */
    data class ListItem(val depth: Int, val marker: String?, val text: List<GuideSpan>) : GuideBlock
    data class Table(val header: List<List<GuideSpan>>, val rows: List<List<List<GuideSpan>>>) : GuideBlock
    data class Code(val text: String) : GuideBlock
    data class Quote(val text: List<GuideSpan>) : GuideBlock
}

sealed interface GuideSpan {
    val text: String
    data class Plain(override val text: String) : GuideSpan
    data class Bold(override val text: String) : GuideSpan
    data class Italic(override val text: String) : GuideSpan
    data class Code(override val text: String) : GuideSpan
    /** [target] es el destino tal cual («carpetas-android.md», «../11-…md»); la UI decide si lleva a otra sección. */
    data class Link(override val text: String, val target: String, val bold: Boolean = false) : GuideSpan
}

data class GuideSection(val id: String, val title: String, val blocks: List<GuideBlock>)

fun List<GuideSpan>.plainText(): String = joinToString("") { it.text }

object GuideMarkdown {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^(\\s*)[-*]\\s+(.*)$")
    private val numberedRegex = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$")
    private val tableSeparator = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$")

    /** Interpreta una sección. El primer `#` es el título (sin «(Android)» ni «en Android», que sobran en la app). */
    fun parse(id: String, markdown: String): GuideSection {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<GuideBlock>()
        var title: String? = null
        val paragraph = mutableListOf<String>()
        val anchors = mutableMapOf<String, Int>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += GuideBlock.Paragraph(parseInline(paragraph.joinToString(" ") { it.trim() }))
                paragraph.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    blocks += GuideBlock.Code(code.joinToString("\n").trimEnd())
                }
                trimmed.isEmpty() -> flushParagraph()
                headingRegex.matches(trimmed) -> {
                    flushParagraph()
                    val match = headingRegex.find(trimmed)!!
                    val level = match.groupValues[1].length
                    val text = match.groupValues[2].trim()
                    if (level == 1 && title == null) {
                        title = cleanTitle(text)
                    } else {
                        val base = slug(text)
                        val seen = anchors.getOrDefault(base, 0)
                        anchors[base] = seen + 1
                        blocks += GuideBlock.Heading(level, parseInline(text), if (seen == 0) base else "$base-$seen")
                    }
                }
                trimmed.startsWith("|") -> {
                    flushParagraph()
                    val table = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) table += lines[i++].trim()
                    i--
                    blocks += parseTable(table)
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    i--
                    blocks += GuideBlock.Quote(parseInline(quote.joinToString(" ")))
                }
                bulletRegex.matches(line) || numberedRegex.matches(line) -> {
                    flushParagraph()
                    val bullet = bulletRegex.find(line)
                    val (indent, marker, body) = if (bullet != null) {
                        Triple(bullet.groupValues[1], null, bullet.groupValues[2])
                    } else {
                        val n = numberedRegex.find(line)!!
                        Triple(n.groupValues[1], "${n.groupValues[2]}.", n.groupValues[3])
                    }
                    // Las líneas que siguen sin marcador ni línea en blanco continúan el mismo elemento.
                    val more = mutableListOf(body.trim())
                    while (i + 1 < lines.size && isContinuation(lines[i + 1])) more += lines[++i].trim()
                    blocks += GuideBlock.ListItem(depth = indentDepth(indent), marker = marker, text = parseInline(more.joinToString(" ")))
                }
                else -> paragraph += line
            }
            i++
        }
        flushParagraph()
        return GuideSection(id = id, title = title ?: id, blocks = blocks)
    }

    private fun isContinuation(next: String): Boolean {
        val t = next.trim()
        if (t.isEmpty() || !next.startsWith(" ")) return false
        return !(bulletRegex.matches(next) || numberedRegex.matches(next) || t.startsWith("|") || t.startsWith("#") ||
            t.startsWith("```") || t.startsWith(">"))
    }

    private fun indentDepth(indent: String): Int = indent.replace("\t", "    ").length / 2

    private fun parseTable(lines: List<String>): GuideBlock.Table {
        val cells = lines.filterNot { tableSeparator.matches(it) }.map { splitRow(it).map(::parseInline) }
        val header = cells.firstOrNull().orEmpty()
        return GuideBlock.Table(header, cells.drop(1).map { row -> List(header.size) { row.getOrElse(it) { emptyList() } } })
    }

    /** Separa una fila por `|`, sin cortar dentro de `código` (allí una barra es texto). */
    private fun splitRow(row: String): List<String> {
        val body = row.trim().removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        for (c in body) {
            when {
                c == '`' -> { inCode = !inCode; current.append(c) }
                c == '|' && !inCode -> { cells += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    /** Negrita `**…**`, cursiva `*…*`, código `` `…` `` y enlaces `[texto](destino)` (también en negrita). El resto, texto. */
    fun parseInline(text: String): List<GuideSpan> {
        val spans = mutableListOf<GuideSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                spans += GuideSpan.Plain(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { plain.append(c); i++; continue }
                    flush()
                    spans += GuideSpan.Code(text.substring(i + 1, end))
                    i = end + 1
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) { plain.append("**"); i += 2; continue }
                    flush()
                    val inner = text.substring(i + 2, end)
                    val link = linkAt(inner, 0)
                    if (link != null && link.second == inner.length) {
                        spans += link.first.copy(bold = true)
                    } else {
                        spans += GuideSpan.Bold(inner)
                    }
                    i = end + 2
                }
                c == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end < 0) { plain.append(c); i++; continue }
                    flush()
                    spans += GuideSpan.Italic(text.substring(i + 1, end))
                    i = end + 1
                }
                c == '[' -> {
                    val link = linkAt(text, i)
                    if (link == null) { plain.append(c); i++; continue }
                    flush()
                    spans += link.first
                    i = link.second
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return spans
    }

    /** Enlace que empieza en [start] y la posición siguiente a su `)`, o `null`. */
    private fun linkAt(text: String, start: Int): Pair<GuideSpan.Link, Int>? {
        if (start >= text.length || text[start] != '[') return null
        val close = text.indexOf("](", start)
        if (close < 0) return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val label = text.substring(start + 1, close)
        if ('[' in label || ']' in label) return null
        return GuideSpan.Link(label.replace("`", ""), text.substring(close + 2, end)) to end + 1
    }

    private fun cleanTitle(text: String): String = text
        .replace(Regex("\\s*\\((Android|iOS y Android)\\)\\s*$"), "")
        .replace(Regex("\\s+en Android$"), "")
        .trim()

    /** Ancla estable de un título: minúsculas, sin acentos, palabras unidas por «-». */
    fun slug(text: String): String = normalize(parseInline(text).plainText())
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Para comparar al buscar: sin acentos ni mayúsculas («Pokémon» = «pokemon»). */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
}

/** Un resultado de búsqueda: la sección, el apartado (título `##`/`###` más cercano) y un fragmento con el término. */
data class GuideHit(val sectionId: String, val sectionTitle: String, val heading: String?, val anchor: String?, val snippet: String)

object GuideSearch {
    const val MAX_HITS = 40
    private const val SNIPPET_RADIUS = 60

    /**
     * Búsqueda simple: todas las palabras de [query] (sin acentos ni mayúsculas) en el mismo apartado. Un resultado por
     * apartado, en el orden de la guía; el fragmento rodea la primera palabra encontrada.
     */
    fun search(sections: List<GuideSection>, query: String): List<GuideHit> {
        val words = GuideMarkdown.normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val hits = mutableListOf<GuideHit>()
        for (section in sections) {
            for (part in parts(section)) {
                val title = GuideMarkdown.normalize(part.title)
                val body = GuideMarkdown.normalize(part.body)
                if (words.all { it in title || it in body }) {
                    val word = words.firstOrNull { it in body }
                    val snippet = if (word != null) snippet(part.body, body, word) else snippet(part.body, body, "")
                    hits += GuideHit(section.id, section.title, part.heading, part.anchor, snippet)
                    if (hits.size >= MAX_HITS) return hits
                }
            }
        }
        return hits
    }

    /** Un apartado: [title] (el de la sección o su `##`) cuenta para buscar; el fragmento sale de [body]. */
    private class Part(val heading: String?, val anchor: String?, val title: String, val body: String)

    private fun parts(section: GuideSection): List<Part> {
        val result = mutableListOf<Part>()
        var heading: String? = null
        var anchor: String? = null
        val body = StringBuilder()
        fun close() {
            if (body.isNotBlank() || heading != null) result += Part(heading, anchor, heading ?: section.title, body.toString().trim())
            body.clear()
        }
        for (block in section.blocks) {
            if (block is GuideBlock.Heading) {
                close()
                heading = block.text.plainText()
                anchor = block.anchor
            } else {
                body.append(blockText(block)).append('\n')
            }
        }
        close()
        return result
    }

    fun blockText(block: GuideBlock): String = when (block) {
        is GuideBlock.Heading -> block.text.plainText()
        is GuideBlock.Paragraph -> block.text.plainText()
        is GuideBlock.ListItem -> block.text.plainText()
        is GuideBlock.Quote -> block.text.plainText()
        is GuideBlock.Code -> block.text
        is GuideBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString(" · ") { it.plainText() } }
    }

    /** El normalizado conserva la longitud en los textos de la guía (acentos compuestos), así que las posiciones valen. */
    private fun snippet(text: String, haystack: String, word: String): String {
        val flat = text.replace('\n', ' ')
        val at = haystack.indexOf(word).coerceAtLeast(0).coerceAtMost(flat.length)
        val start = (at - SNIPPET_RADIUS).coerceAtLeast(0).let { s -> flat.lastIndexOf(' ', s).takeIf { it >= 0 && s > 0 }?.plus(1) ?: s }
        val end = (at + word.length + SNIPPET_RADIUS).coerceAtMost(flat.length).let { e -> flat.indexOf(' ', e).takeIf { it >= 0 } ?: flat.length }
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}
