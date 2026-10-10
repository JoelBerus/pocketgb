package com.joelbermudez.pocketgb.ui.library

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N3b · estado de las herramientas en horizontal: título de sección medido y paneles por origen. */
class LibraryToolsStateTest {
    private fun tools() = LibraryToolsState(mutableStateOf(false), mutableStateOf(null))

    @Test
    fun theHeaderIsKnownOnlyWhileItIsOnScreen() {
        val tools = tools()
        assertNull("sin medir", tools.header)
        tools.headerTop = 176
        tools.headerBottom = 256
        assertEquals(HeaderBounds(176, 256), tools.header)
        tools.forgetHeader()
        assertNull("fuera de la lista", tools.header)
    }

    @Test
    fun anEmptyHeaderIsNotAHeader() {
        val tools = tools().apply {
            headerTop = 200
            headerBottom = 200
        }
        assertNull(tools.header)
    }

    @Test
    fun togglingAPanelOpensAndClosesItFromTheSameSource() {
        val tools = tools()
        tools.toggle(LibraryPanel.FILTERS, PanelSource.BAR)
        assertEquals(LibraryPanel.FILTERS, tools.panel)
        assertEquals(PanelSource.BAR, tools.panelSource)
        tools.toggle(LibraryPanel.VIEW, PanelSource.BAR)
        assertEquals(LibraryPanel.VIEW, tools.panel)
        tools.toggle(LibraryPanel.VIEW, PanelSource.BAR)
        assertNull(tools.panel)
    }

    @Test
    fun theSamePanelFromTheOtherToolbarMovesInsteadOfClosing() {
        val tools = tools()
        tools.toggle(LibraryPanel.CATEGORIES, PanelSource.BAR)
        tools.toggle(LibraryPanel.CATEGORIES, PanelSource.FLOATING)
        assertEquals(LibraryPanel.CATEGORIES, tools.panel)
        assertEquals(PanelSource.FLOATING, tools.panelSource)
    }
}
