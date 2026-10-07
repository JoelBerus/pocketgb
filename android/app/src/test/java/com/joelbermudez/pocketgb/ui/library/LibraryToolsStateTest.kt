package com.joelbermudez.pocketgb.ui.library

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N3b · hasta dónde sube un panel de la barra flotante sin tapar el título de sección (px de ventana). */
class LibraryToolsStateTest {
    private fun tools() = LibraryToolsState(mutableStateOf(false), mutableStateOf(null)).apply { viewportTop = 176 }

    private val toolbarTop = 528
    private val panelLeft = 288

    @Test
    fun withThePinnedTitleShownThePanelStopsBelowIt() {
        val tools = tools().apply {
            headerTop = 176
            headerBottom = 256
            titleRight = 470
        }
        assertEquals(256, tools.panelLimitTop(toolbarTop, panelLeft))
    }

    @Test
    fun withTheTitleStillBelowTheToolbarThePanelCanReachTheTopBar() {
        // Arriba del todo, con el carril: el título de sección aún no se ve por encima de la barra flotante.
        val tools = tools().apply {
            headerTop = 600
            headerBottom = 680
            titleRight = 470
        }
        assertEquals(176, tools.panelLimitTop(toolbarTop, panelLeft))
    }

    @Test
    fun aTitleThatIsNotOnScreenDoesNotLimitThePanel() {
        val tools = tools()
        tools.forgetHeader()
        assertEquals(176, tools.panelLimitTop(toolbarTop, panelLeft))
    }

    @Test
    fun aTitleLeftOfThePanelIsNeverCoveredSoItDoesNotLimitIt() {
        // Ventana ancha: el panel (alineado a la derecha) empieza después de donde acaba el texto del título.
        val tools = tools().apply {
            headerTop = 300
            headerBottom = 380
            titleRight = 250
        }
        assertEquals(176, tools.panelLimitTop(toolbarTop, panelLeft))
    }

    @Test
    fun togglingAPanelOpensAndClosesIt() {
        val tools = tools()
        tools.toggle(LibraryPanel.FILTERS)
        assertEquals(LibraryPanel.FILTERS, tools.panel)
        tools.toggle(LibraryPanel.VIEW)
        assertEquals(LibraryPanel.VIEW, tools.panel)
        tools.toggle(LibraryPanel.VIEW)
        assertNull(tools.panel)
    }
}
