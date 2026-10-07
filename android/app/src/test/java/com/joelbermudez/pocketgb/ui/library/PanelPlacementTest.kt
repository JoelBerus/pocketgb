package com.joelbermudez.pocketgb.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N3b (respuesta a la auditoría, H1/H2/H6): dónde va un panel de la biblioteca en horizontal sin tapar el título de
 * sección. En reposo se abre **hacia abajo** desde los iconos de la barra superior; al desplazar, **hacia arriba** desde
 * la barra flotante. Px de ventana; teléfono girado de 360 dp a 2 px/dp: lista de 176 a 672, separación 16, mínimo 224.
 */
class PanelPlacementTest {
    private val gap = 16
    private val min = 224
    private val viewportTop = 176
    private val viewportBottom = 672

    private fun overlaps(span: PanelSpan, header: HeaderBounds) =
        span.top < header.bottom && span.top + span.maxHeight > header.top

    // ---- En reposo: bajo la barra superior ----

    @Test
    fun withoutAVisibleTitleThePanelUsesTheWholeListHeight() {
        val span = PanelPlacement.below(anchorBottom = 160, viewportBottom = viewportBottom, header = null, gap = gap, minHeight = min)
        assertEquals(176, span.top)
        assertEquals(viewportBottom - gap - 176, span.maxHeight)
    }

    @Test
    fun withTheTitleLowerDownThePanelStopsAboveIt() {
        // Arriba del todo con el carril: el título de sección está en 500–580.
        val header = HeaderBounds(top = 500, bottom = 580)
        val span = PanelPlacement.below(160, viewportBottom, header, gap, min)
        assertEquals(176, span.top)
        assertEquals(500 - gap - 176, span.maxHeight)
        assertFalse(overlaps(span, header))
    }

    @Test
    fun withTheTitlePinnedUnderTheBarThePanelStartsBelowIt() {
        // La barra superior volvió al subir (enterAlways) con el título fijado justo debajo.
        val header = HeaderBounds(top = 176, bottom = 256)
        val span = PanelPlacement.below(160, viewportBottom, header, gap, min)
        assertEquals(256 + gap, span.top)
        assertEquals(viewportBottom - gap - (256 + gap), span.maxHeight)
        assertFalse(overlaps(span, header))
    }

    @Test
    fun withTheTitleInTheMiddleThePanelPicksTheSideWhereItFits() {
        // Encima del título solo caben 104 px (< 224): va debajo, donde caben 280.
        val header = HeaderBounds(top = 296, bottom = 360)
        val span = PanelPlacement.below(160, viewportBottom, header, gap, min)
        assertEquals(360 + gap, span.top)
        assertFalse(overlaps(span, header))
    }

    @Test
    fun aTitleScrolledAboveThePanelDoesNotMoveIt() {
        val header = HeaderBounds(top = 40, bottom = 120)
        val span = PanelPlacement.below(160, viewportBottom, header, gap, min)
        assertEquals(176, span.top)
    }

    // ---- Al desplazar: sobre la barra flotante ----

    @Test
    fun aboveTheFloatingToolbarThePanelStopsBelowThePinnedTitle() {
        // Barra superior plegada: título fijado en 48–128; barra flotante desde 560.
        val header = HeaderBounds(top = 48, bottom = 128)
        val available = PanelPlacement.above(anchorTop = 560, viewportTop = 48, header = header, gap = gap)
        assertEquals(560 - gap - (128 + gap), available)
        assertTrue(available >= min)
        assertFalse(PanelPlacement.needsRoom(available, min))
    }

    @Test
    fun withAHalfCollapsedBarAndLargeFontThereIsNoRoomUntilTheBarFolds() {
        // Fuente al 200 %: título de 208 px bajo una barra a medio plegar (lista desde 112), barra flotante en 528.
        val header = HeaderBounds(top = 112, bottom = 320)
        val available = PanelPlacement.above(anchorTop = 528, viewportTop = 112, header = header, gap = gap)
        assertTrue("caso intermedio: $available", PanelPlacement.needsRoom(available, min))
        // Con la barra plegada del todo el título sube 64 px y ya cabe.
        val folded = PanelPlacement.above(anchorTop = 528, viewportTop = 48, header = HeaderBounds(48, 256), gap = gap)
        assertFalse("plegada: $folded", PanelPlacement.needsRoom(folded, min))
    }

    @Test
    fun withoutAVisibleTitleTheFloatingPanelReachesTheTopOfTheList() {
        val available = PanelPlacement.above(anchorTop = 560, viewportTop = 48, header = null, gap = gap)
        assertEquals(560 - gap - (48 + gap), available)
    }

    @Test
    fun aTitleBelowTheFloatingToolbarDoesNotLimitThePanel() {
        val available = PanelPlacement.above(anchorTop = 560, viewportTop = 48, header = HeaderBounds(600, 680), gap = gap)
        assertEquals(560 - gap - (48 + gap), available)
    }

    // ---- Cuándo aparece la barra flotante (H1) ----

    @Test
    fun theFloatingToolbarOnlyShowsOnceScrolledWithThePinnedTitle() {
        assertFalse("en reposo", showFloatingToolbar(landscape = true, searching = false, collapsedFraction = 0f, titlePinned = true))
        assertFalse("barra a menos de la mitad", showFloatingToolbar(true, false, 0.49f, true))
        assertFalse("título aún no fijado", showFloatingToolbar(true, false, 1f, false))
        assertTrue(showFloatingToolbar(true, false, 0.5f, true))
        assertFalse("buscando", showFloatingToolbar(true, true, 1f, true))
        assertFalse("vertical", showFloatingToolbar(false, false, 1f, true))
    }

    @Test
    fun theTitleIsPinnedOnceTheRailHasScrolledAway() {
        assertTrue("sin carril, el título es lo primero", isTitlePinned(railShown = false, firstVisibleItemIndex = 0))
        assertFalse(isTitlePinned(railShown = true, firstVisibleItemIndex = 0))
        assertTrue(isTitlePinned(railShown = true, firstVisibleItemIndex = 1))
    }
}
