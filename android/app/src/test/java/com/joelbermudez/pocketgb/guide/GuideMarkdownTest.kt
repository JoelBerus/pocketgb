package com.joelbermudez.pocketgb.guide

import com.joelbermudez.pocketgb.tips.InMemoryTipsStorage
import com.joelbermudez.pocketgb.tips.Tip
import com.joelbermudez.pocketgb.tips.TipsState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N9: el Markdown de la guía, su búsqueda y los consejos descartables. */
class GuideMarkdownTest {
    private val guideDir = File("../../docs/guia")

    private fun realSections(): List<GuideSection> = GuideLibrary.load { id ->
        File(guideDir, "$id.md").takeIf { it.isFile }?.readText()
    }

    @Test
    fun parsesHeadingsParagraphsListsTablesCodeAndQuotes() {
        val md = """
            # Momentos y progreso en Android

            Un **momento** es una foto. Ver [carpetas](carpetas-android.md).

            ## Crear un momento
            1. Abre la **pausa**.
            2. Toca `Momentos`
               y sigue.
            - Uno
              - Dentro
            > Nota *importante*.

            | Mando | Game Boy |
            |---|---|
            | L1 | Menú `a|b` |

            ```
            Roms/
              Pokémon/
            ```
        """.trimIndent()
        val section = GuideMarkdown.parse("x", md)
        assertEquals("Momentos y progreso", section.title)
        val blocks = section.blocks
        assertTrue(blocks[0] is GuideBlock.Paragraph)
        val para = (blocks[0] as GuideBlock.Paragraph).text
        assertEquals(GuideSpan.Bold("momento"), para[1])
        assertEquals(GuideSpan.Link("carpetas", "carpetas-android.md"), para[3])
        val heading = blocks[1] as GuideBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("crear-un-momento", heading.anchor)
        val first = blocks[2] as GuideBlock.ListItem
        assertEquals("1.", first.marker)
        val second = blocks[3] as GuideBlock.ListItem
        assertEquals("Toca Momentos y sigue.", second.text.plainText())
        assertEquals(0, (blocks[4] as GuideBlock.ListItem).depth)
        assertEquals(1, (blocks[5] as GuideBlock.ListItem).depth)
        val quote = blocks[6] as GuideBlock.Quote
        assertEquals(GuideSpan.Italic("importante"), quote.text[1])
        val table = blocks[7] as GuideBlock.Table
        assertEquals(listOf("Mando", "Game Boy"), table.header.map { it.plainText() })
        assertEquals(1, table.rows.size)
        assertEquals("Menú a|b", table.rows[0][1].plainText())
        assertEquals("Roms/\n  Pokémon/", (blocks[8] as GuideBlock.Code).text)
        assertEquals(9, blocks.size)
    }

    @Test
    fun boldLinksAndUnclosedMarkersStayReadable() {
        val spans = GuideMarkdown.parseInline("**[Ver](gba-android.md)** y 2 ** sueltos y [roto")
        assertEquals(GuideSpan.Link("Ver", "gba-android.md", bold = true), spans[0])
        assertEquals(" y 2 ** sueltos y [roto", spans.drop(1).plainText())
    }

    @Test
    fun repeatedHeadingsGetDistinctAnchors() {
        val section = GuideMarkdown.parse("x", "# T\n## Uno\n## Uno\n### Pokémon y Kirby")
        assertEquals(listOf("uno", "uno-1", "pokemon-y-kirby"), section.blocks.map { (it as GuideBlock.Heading).anchor })
    }

    @Test
    fun resolveOnlyLeadsToBundledSections() {
        assertEquals("carpetas-android" to null, GuideLibrary.resolve("carpetas-android.md"))
        assertEquals("gba-android" to "la-bios-opcional", GuideLibrary.resolve("gba-android.md#la-bios-opcional"))
        assertNull(GuideLibrary.resolve("../11-biblioteca-carpetas.md"))
        assertNull(GuideLibrary.resolve("../LEEME-PocketGB.txt"))
        assertNull(GuideLibrary.resolve("carpetas.md"))
        assertNull(GuideLibrary.resolve("https://example.com"))
    }

    @Test
    fun everyBundledSectionExistsAndParsesWithoutLeftoverMarkdown() {
        assertTrue("falta docs/guia (ejecutar desde android/app)", guideDir.isDirectory)
        val sections = realSections()
        assertEquals(GuideLibrary.SECTION_IDS, sections.map { it.id })
        // La tarea de Gradle empaqueta *-android.md y la guía común: todas deben estar en la guía de la app.
        val packaged = guideDir.listFiles()!!.map { it.name }
            .filter { it.endsWith("-android.md") || it == "partidas-continuar-y-renombrar.md" }
            .map { it.removeSuffix(".md") }.toSet()
        assertEquals(packaged, GuideLibrary.SECTION_IDS.toSet())
        sections.forEach { section ->
            assertFalse("título vacío en ${section.id}", section.title.isBlank())
            assertFalse("título con «Android» en ${section.id}: ${section.title}", section.title.contains("Android"))
            assertTrue("sección vacía: ${section.id}", section.blocks.size > 3)
            section.blocks.filterNot { it is GuideBlock.Code }.forEach { block ->
                val text = GuideSearch.blockText(block)
                assertFalse("Markdown sin interpretar en ${section.id}: «$text»", "**" in text || "](" in text || text.startsWith("#"))
                assertFalse("milestone interno en ${section.id}: «$text»", Regex("\\bN[0-9]\\b").containsMatchIn(text))
            }
        }
    }

    @Test
    fun linksBetweenSectionsShowTheTargetTitle() {
        val sections = realSections()
        val titles = sections.associate { it.id to it.title }
        val links = sections.flatMap { s -> s.blocks.flatMap { spansOf(it) } }.filterIsInstance<GuideSpan.Link>()
        val internal = links.filter { GuideLibrary.resolve(it.target) != null }
        assertTrue(internal.isNotEmpty())
        internal.forEach { assertEquals(titles[GuideLibrary.resolve(it.target)!!.first], it.text) }
    }

    @Test
    fun searchIgnoresAccentsAndCaseAndPointsToTheSubsection() {
        val sections = realSections()
        val hits = GuideSearch.search(sections, "bios OFICIAL")
        assertTrue(hits.isNotEmpty())
        val bios = hits.first { it.sectionId == "gba-android" }
        assertEquals("La BIOS (opcional)", bios.heading)
        assertEquals("la-bios-opcional", bios.anchor)
        assertTrue(bios.snippet.contains("BIOS", ignoreCase = true))
        assertEquals(hits.map { it.sectionId to it.anchor }, GuideSearch.search(sections, "BÍOS oficial").map { it.sectionId to it.anchor })
        assertTrue(GuideSearch.search(sections, "pokemon").isNotEmpty())
        assertTrue(GuideSearch.search(sections, "intercambio").any { it.sectionId == "viajar-android" })
        assertTrue(GuideSearch.search(sections, "zzzz-no-existe").isEmpty())
        assertTrue(GuideSearch.search(sections, "   ").isEmpty())
        assertTrue(GuideSearch.search(sections, "a").size <= GuideSearch.MAX_HITS)
    }

    @Test
    fun tipsStayDismissedAcrossRestartsAndCanBeShownAgain() {
        val storage = InMemoryTipsStorage()
        val tips = TipsState(storage)
        Tip.entries.forEach { assertTrue(tips.isVisible(it)) }
        tips.dismiss(Tip.MOMENTS)
        assertFalse(tips.isVisible(Tip.MOMENTS))
        assertTrue(tips.isVisible(Tip.SEND))
        val restarted = TipsState(storage)
        assertFalse(restarted.isVisible(Tip.MOMENTS))
        assertTrue(restarted.anyDismissed)
        restarted.resetAll()
        assertTrue(TipsState(storage).isVisible(Tip.MOMENTS))
        assertEquals(Tip.entries.size, Tip.entries.map { it.id }.toSet().size)
    }

    @Test
    fun aFailingStorageNeverBreaksTheTips() {
        val tips = TipsState(object : com.joelbermudez.pocketgb.tips.TipsStorage {
            override fun dismissed() = emptySet<String>()
            override fun save(dismissed: Set<String>) = throw java.io.IOException("disco lleno")
        })
        tips.dismiss(Tip.ARROWS)
        assertFalse(tips.isVisible(Tip.ARROWS))
        assertNotNull(tips)
    }

    private fun spansOf(block: GuideBlock): List<GuideSpan> = when (block) {
        is GuideBlock.Heading -> block.text
        is GuideBlock.Paragraph -> block.text
        is GuideBlock.ListItem -> block.text
        is GuideBlock.Quote -> block.text
        is GuideBlock.Table -> (listOf(block.header) + block.rows).flatten().flatten()
        is GuideBlock.Code -> emptyList()
    }
}
