package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.emulator.GbaButtonBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N8: L y R táctiles con disposición GBA propia por orientación (= iOS `ControlsLayout.defaults(_, shoulders: true)`). */
class ControlsGbaLayoutTest {
    private val portraitArea = ControlBounds(0f, 0f, 400f, 560f)
    private val landscapeArea = ControlBounds(48f, 0f, 852f, 400f)

    private fun geometry(orientation: ControlsOrientation, area: ControlBounds, shoulders: Boolean = true) =
        ControlGeometry(ControlLayout.defaults(orientation, shoulders), orientation, area, shoulders = shoulders)

    @Test
    fun gbaDefaultsMatchIos() {
        val portrait = ControlLayout.defaults(ControlsOrientation.PORTRAIT, shoulders = true)
        assertEquals(NormalizedPoint(0.17f, 0.14f), portrait.centers[ControlId.L])
        assertEquals(NormalizedPoint(0.83f, 0.14f), portrait.centers[ControlId.R])
        assertEquals("el resto, como en Game Boy", ControlLayout.defaults(ControlsOrientation.PORTRAIT).centers, portrait.centers - ControlId.L - ControlId.R)
        val landscape = ControlLayout.defaults(ControlsOrientation.LANDSCAPE, shoulders = true)
        // iOS los pone a y = 0,1; en Android bajan a 0,26 para no quedar bajo el HUD (pausa y velocidad, arriba a la derecha).
        assertEquals(NormalizedPoint(0.065f, 0.26f), landscape.centers[ControlId.L])
        assertEquals(NormalizedPoint(0.94f, 0.26f), landscape.centers[ControlId.R])
        assertEquals(0.6f, landscape.scale(ControlId.DPAD), 0f)
        assertEquals(0.9f, landscape.scale(ControlId.L), 0f)
        assertFalse(ControlId.L in ControlLayout.defaults(ControlsOrientation.LANDSCAPE).centers)
    }

    @Test
    fun shouldersExistOnlyInGbaAndStayInsideTheAreaWithTouchTargets() {
        assertEquals(listOf(ControlId.DPAD, ControlId.A, ControlId.B, ControlId.START, ControlId.SELECT, ControlId.MENU),
            geometry(ControlsOrientation.PORTRAIT, portraitArea, shoulders = false).controls)
        for ((orientation, area) in listOf(ControlsOrientation.PORTRAIT to portraitArea, ControlsOrientation.LANDSCAPE to landscapeArea)) {
            val g = geometry(orientation, area)
            assertTrue(ControlId.L in g.frames && ControlId.R in g.frames)
            g.controls.forEach { id ->
                val f = g.frames.getValue(id)
                assertTrue("$id $orientation", f.left >= area.left && f.right <= area.right && f.top >= area.top && f.bottom <= area.bottom)
                assertTrue("$id ≥ 48 dp", g.touchFrame(id).width >= 48f && g.touchFrame(id).height >= 48f)
            }
            val l = g.frames.getValue(ControlId.L)
            assertEquals("cápsula 92×40", 92f * (if (orientation == ControlsOrientation.LANDSCAPE) 0.9f else 1f), l.width, 0.01f)
            // L a la izquierda y R a la derecha, arriba.
            assertTrue(l.centerX < g.frames.getValue(ControlId.R).centerX)
            assertTrue(l.centerY < g.frames.getValue(ControlId.A).centerY)
        }
    }

    @Test
    fun inLandscapeRStaysClearOfTheHudAndOfA() {
        // Teléfono horizontal de 360 dp de alto: el HUD (48 dp + 8 de margen) ocupa la esquina superior derecha.
        val area = ControlBounds(0f, 0f, 800f, 360f)
        val g = geometry(ControlsOrientation.LANDSCAPE, area)
        val r = g.frames.getValue(ControlId.R)
        assertTrue("R bajo el HUD (${r.top})", r.top >= 64f)
        assertTrue("R sobre A", r.bottom <= g.frames.getValue(ControlId.A).top)
        assertTrue("L sobre la cruceta", g.frames.getValue(ControlId.L).bottom <= g.frames.getValue(ControlId.DPAD).top)
    }

    @Test
    fun theShouldersWinTheHitAndPressLAndR() {
        val g = geometry(ControlsOrientation.LANDSCAPE, landscapeArea)
        val l = g.frames.getValue(ControlId.L)
        val r = g.frames.getValue(ControlId.R)
        assertEquals(ControlHit.Single(ControlId.L), g.hit(ControlPoint(l.centerX, l.centerY)))
        assertEquals(ControlHit.Single(ControlId.R), g.hit(ControlPoint(r.right + 4f, r.centerY)))
        val engine = TouchInputEngine(g)
        engine.pointerDown(1, ControlPoint(l.centerX, l.centerY))
        engine.pointerDown(2, ControlPoint(r.centerX, r.centerY))
        assertEquals(GbaButtonBits.L or GbaButtonBits.R, engine.mask)
        assertEquals(setOf(ControlId.L, ControlId.R), engine.pressed)
        engine.pointerUp(1)
        assertEquals(GbaButtonBits.R, engine.mask)
        // En Game Boy el mismo punto no es ningún hombro.
        val gb = geometry(ControlsOrientation.LANDSCAPE, landscapeArea, shoulders = false)
        assertFalse(gb.hit(ControlPoint(l.centerX, l.centerY)) == ControlHit.Single(ControlId.L))
    }

    @Test
    fun accessibilityHasNodesForLAndRInGbaOnly() {
        assertEquals(5, ControlsAccessibilityModel.visibleControls(showMenu = false).size)
        assertEquals(listOf(ControlId.DPAD, ControlId.A, ControlId.B, ControlId.START, ControlId.SELECT, ControlId.L, ControlId.R),
            ControlsAccessibilityModel.visibleControls(showMenu = false, shoulders = true))
        assertEquals(GbaButtonBits.L, ControlsAccessibilityModel.pressMask(ControlId.L))
        assertEquals(GbaButtonBits.R, ControlsAccessibilityModel.pressMask(ControlId.R))
        assertEquals(listOf(ControlA11yAction.CLICK), ControlsAccessibilityModel.actionsFor(ControlId.L))
        // Los ids virtuales de los controles de antes no cambian (L y R van al final).
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), ControlId.entries.map { it.ordinal })
        assertEquals(ControlId.MENU, ControlId.entries[5])
    }

    @Test
    fun aStoredGameBoyLayoutNeverAddsShoulders() {
        val stored = com.joelbermudez.pocketgb.settings.StoredControlLayout(positions = mapOf(ControlId.L to NormalizedPoint(0.5f, 0.5f)))
        assertFalse(ControlId.L in ControlLayout.from(stored, ControlsOrientation.PORTRAIT).centers)
        assertEquals(NormalizedPoint(0.5f, 0.5f), ControlLayout.from(stored, ControlsOrientation.PORTRAIT, shoulders = true).centers[ControlId.L])
    }
}
