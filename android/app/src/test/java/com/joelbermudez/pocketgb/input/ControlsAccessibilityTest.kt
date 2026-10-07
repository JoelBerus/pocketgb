package com.joelbermudez.pocketgb.input

import com.joelbermudez.pocketgb.settings.DpadStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsAccessibilityTest {
    @Test
    fun withoutTheMenuControlFiveNodesAreVisible() {
        assertEquals(
            listOf(ControlId.DPAD, ControlId.A, ControlId.B, ControlId.START, ControlId.SELECT),
            ControlsAccessibilityModel.visibleControls(showMenu = false),
        )
    }

    @Test
    fun withTheMenuControlSixNodesAreVisible() {
        assertEquals(6, ControlsAccessibilityModel.visibleControls(showMenu = true).size)
        assertTrue(ControlId.MENU in ControlsAccessibilityModel.visibleControls(showMenu = true))
    }

    @Test
    fun buttonsPressTheirOwnBit() {
        assertEquals(GameBoyButton.A.mask, ControlsAccessibilityModel.pressMask(ControlId.A))
        assertEquals(GameBoyButton.B.mask, ControlsAccessibilityModel.pressMask(ControlId.B))
        assertEquals(GameBoyButton.START.mask, ControlsAccessibilityModel.pressMask(ControlId.START))
        assertEquals(GameBoyButton.SELECT.mask, ControlsAccessibilityModel.pressMask(ControlId.SELECT))
    }

    @Test
    fun theDpadAndTheMenuHaveNoPlainPress() {
        assertNull(ControlsAccessibilityModel.pressMask(ControlId.DPAD))
        assertNull(ControlsAccessibilityModel.pressMask(ControlId.MENU))
    }

    @Test
    fun directionsMapToTheirDpadBit() {
        assertEquals(GameBoyButton.UP.mask, ControlsAccessibilityModel.directionMask(DpadDirection.UP))
        assertEquals(GameBoyButton.DOWN.mask, ControlsAccessibilityModel.directionMask(DpadDirection.DOWN))
        assertEquals(GameBoyButton.LEFT.mask, ControlsAccessibilityModel.directionMask(DpadDirection.LEFT))
        assertEquals(GameBoyButton.RIGHT.mask, ControlsAccessibilityModel.directionMask(DpadDirection.RIGHT))
    }

    @Test
    fun theDpadOffersFourDirectionActionsAndNoClick() {
        assertEquals(4, ControlsAccessibilityModel.actionsFor(ControlId.DPAD).size)
        assertEquals(setOf(DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT, DpadDirection.RIGHT),
            ControlsAccessibilityModel.directionsFor(ControlId.DPAD))
        assertTrue(ControlsAccessibilityModel.directionsFor(ControlId.A).isEmpty())
    }

    @Test
    fun onlyTheMenuAndTheRootOfferOpenMenu() {
        assertTrue(ControlsAccessibilityModel.opensMenu(ControlId.MENU))
        assertTrue(!ControlsAccessibilityModel.opensMenu(ControlId.A))
        assertTrue(!ControlsAccessibilityModel.opensMenu(ControlId.DPAD))
    }

    @Test
    fun virtualNodeBoundsAreAtLeast48DpAtTheSmallestScale() {
        val density = 2.625f
        val geometry = ControlGeometry(
            layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT),
            orientation = ControlsOrientation.PORTRAIT,
            area = ControlBounds(0f, 0f, 411f * density, 800f * density),
            density = density,
            sizeScale = 0.6f,
            showMenu = true,
        )
        ControlsAccessibilityModel.visibleControls(showMenu = true).forEach { id ->
            val bounds = ControlsAccessibilityModel.nodeBounds(geometry, id)
            assertTrue("$id ancho ${bounds.width / density} dp", bounds.width / density >= 47.99f)
            assertTrue("$id alto ${bounds.height / density} dp", bounds.height / density >= 47.99f)
        }
    }

    @Test
    fun dpadNodeStaysAtLeast48DpAndInsideTheAreaWithSeparatedArrowsAtEverySeparation() {
        val density = 2.625f
        val area = ControlBounds(0f, 0f, 411f * density, 800f * density)
        for (separation in listOf(0.7f, 1f, 1.5f)) {
            for (scale in listOf(0.6f, 1f, 1.6f)) {
                val geometry = ControlGeometry(
                    layout = ControlLayout.defaults(ControlsOrientation.PORTRAIT)
                        .copy(scales = mapOf(ControlId.DPAD to scale), separation = separation),
                    orientation = ControlsOrientation.PORTRAIT,
                    area = area,
                    density = density,
                    sizeScale = 0.85f,
                    dpadStyle = DpadStyle.ARROWS,
                )
                val bounds = ControlsAccessibilityModel.nodeBounds(geometry, ControlId.DPAD)
                assertTrue("separación $separation escala $scale: ancho ${bounds.width / density} dp", bounds.width / density >= 47.99f)
                assertTrue("separación $separation escala $scale: alto ${bounds.height / density} dp", bounds.height / density >= 47.99f)
                assertTrue(bounds.left >= -0.01f && bounds.right <= area.right + 0.01f && bounds.top >= -0.01f && bounds.bottom <= area.bottom + 0.01f)
                // El nodo envuelve todo el grupo de flechas.
                val frame = geometry.frames.getValue(ControlId.DPAD)
                DpadShape.arrowCircles(frame, geometry.dpadSeparation).values.forEach { circle ->
                    assertTrue(circle.centerX - circle.radius >= bounds.left - 0.01f && circle.centerX + circle.radius <= bounds.right + 0.01f)
                    assertTrue(circle.centerY - circle.radius >= bounds.top - 0.01f && circle.centerY + circle.radius <= bounds.bottom + 0.01f)
                }
            }
        }
    }

    @Test
    fun pressDurationIsOneHundredMilliseconds() {
        assertEquals(100L, ControlsAccessibilityModel.PRESS_MS)
    }
}
