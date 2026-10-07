package com.joelbermudez.pocketgb.input

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.emulator.GbaButtonBits
import com.joelbermudez.pocketgb.settings.ControlsVisibility
import kotlin.math.hypot

enum class DpadDirection { UP, DOWN, LEFT, RIGHT }

/** Acciones que TalkBack ofrece sobre un control (R7). */
enum class ControlA11yAction { CLICK, UP, DOWN, LEFT, RIGHT, OPEN_MENU }

/** Lógica pura del árbol accesible de los controles: qué nodos hay, qué máscara pulsa cada acción. */
internal object ControlsAccessibilityModel {
    /** Duración de una pulsación accesible (R7). */
    const val PRESS_MS = 100L

    /** Controles que existen para TalkBack: todos salvo MENU cuando no se dibuja (K13) y L/R fuera de GBA (N8). */
    fun visibleControls(showMenu: Boolean, shoulders: Boolean = false): List<ControlId> =
        ControlId.entries.filter { (it != ControlId.MENU || showMenu) && (shoulders || !it.isShoulder) }

    /** Máscara de `ACTION_CLICK` sobre [id]; `null` si el clic no pulsa nada del juego (cruceta y menú). */
    fun pressMask(id: ControlId): Int? = when (id) {
        ControlId.A -> GameBoyButton.A.mask
        ControlId.B -> GameBoyButton.B.mask
        ControlId.START -> GameBoyButton.START.mask
        ControlId.SELECT -> GameBoyButton.SELECT.mask
        ControlId.L -> GbaButtonBits.L
        ControlId.R -> GbaButtonBits.R
        ControlId.DPAD, ControlId.MENU -> null
    }

    fun directionMask(direction: DpadDirection): Int = when (direction) {
        DpadDirection.UP -> GameBoyButton.UP.mask
        DpadDirection.DOWN -> GameBoyButton.DOWN.mask
        DpadDirection.LEFT -> GameBoyButton.LEFT.mask
        DpadDirection.RIGHT -> GameBoyButton.RIGHT.mask
    }

    fun directionsFor(id: ControlId): Set<DpadDirection> =
        if (id == ControlId.DPAD) DpadDirection.entries.toSet() else emptySet()

    fun opensMenu(id: ControlId): Boolean = id == ControlId.MENU

    fun actionsFor(id: ControlId): List<ControlA11yAction> = when (id) {
        ControlId.DPAD -> listOf(ControlA11yAction.UP, ControlA11yAction.DOWN, ControlA11yAction.LEFT, ControlA11yAction.RIGHT)
        ControlId.MENU -> listOf(ControlA11yAction.CLICK, ControlA11yAction.OPEN_MENU)
        else -> listOf(ControlA11yAction.CLICK)
    }

    /** Límites del nodo: el área táctil real, que nunca baja de 48 dp (R16). */
    fun nodeBounds(geometry: ControlGeometry, id: ControlId): ControlBounds = geometry.touchFrame(id)
}

/**
 * TalkBack en [GameControlsView] (R7): un nodo virtual por control con los límites de la geometría real. Los botones
 * se pulsan con `ACTION_CLICK` (100 ms); la cruceta ofrece una acción por dirección; el nodo raíz y MENU ofrecen
 * «Abrir menú». Los toques normales siguen en `onTouchEvent`; el helper solo recibe la exploración (`dispatchHoverEvent`).
 */
class ControlsAccessibilityHelper(private val view: GameControlsView) : ExploreByTouchHelper(view) {
    override fun getVirtualViewAt(x: Float, y: Float): Int {
        if (!view.hasGeometry) return INVALID_ID
        val geometry = view.controlGeometry
        val id = when (val hit = geometry.hit(ControlPoint(x, y))) {
            is ControlHit.Single -> hit.id
            ControlHit.AB -> nearerOfAB(geometry, x, y)
            null -> null
        }
        return if (id != null && id in visible()) id.ordinal else INVALID_ID
    }

    /** Para las pruebas instrumentadas: [getVirtualViewAt] es protegido. */
    internal fun probeVirtualViewAt(x: Float, y: Float): Int = getVirtualViewAt(x, y)

    private fun nearerOfAB(geometry: ControlGeometry, x: Float, y: Float): ControlId {
        val a = geometry.frames.getValue(ControlId.A)
        val b = geometry.frames.getValue(ControlId.B)
        return if (hypot(x - a.centerX, y - a.centerY) <= hypot(x - b.centerX, y - b.centerY)) ControlId.A else ControlId.B
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        if (!view.hasGeometry) return
        visible().forEach { virtualViewIds += it.ordinal }
    }

    private fun visible(): List<ControlId> =
        ControlsAccessibilityModel.visibleControls(view.renderOptions.showMenu, view.controlGeometry.shoulders)

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val id = ControlId.entries.getOrNull(virtualViewId)
        if (id == null || !view.hasGeometry || id !in view.controlGeometry.frames) {
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        val bounds = ControlsAccessibilityModel.nodeBounds(view.controlGeometry, id)
        node.contentDescription = label(id)
        node.className = "android.widget.Button"
        node.setBoundsInParent(
            Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt().coerceAtLeast(bounds.left.toInt() + 1), bounds.bottom.toInt().coerceAtLeast(bounds.top.toInt() + 1)),
        )
        // Controles ocultos (mando o ajuste): los nodos siguen accesibles, pero TalkBack avisa de que están ocultos (A7-H4).
        if (view.controlsVisibility == ControlsVisibility.HIDDEN && !view.editing) {
            node.stateDescription = view.context.getString(R.string.controls_a11y_hidden)
        }
        if (view.editing) return // en el editor se arrastra con el dedo: no hay acciones de juego
        ControlsAccessibilityModel.actionsFor(id).forEach { action ->
            when (action) {
                ControlA11yAction.CLICK -> node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                ControlA11yAction.OPEN_MENU -> node.addAction(AccessibilityActionCompat(R.id.a11y_action_open_menu, view.context.getString(R.string.controls_a11y_open_menu)))
                else -> directionOf(action)?.let { direction ->
                    node.addAction(AccessibilityActionCompat(directionActionId(direction), directionLabel(direction)))
                }
            }
        }
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        if (!view.editing) {
            node.addAction(AccessibilityActionCompat(R.id.a11y_action_open_menu, view.context.getString(R.string.controls_a11y_open_menu)))
        }
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val id = ControlId.entries.getOrNull(virtualViewId) ?: return false
        if (view.editing || !view.hasGeometry || id !in view.controlGeometry.frames) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> when {
                ControlsAccessibilityModel.opensMenu(id) -> view.openMenuFromAccessibility()
                else -> ControlsAccessibilityModel.pressMask(id)?.let { view.pressFor(it, ControlsAccessibilityModel.PRESS_MS) } ?: false
            }
            R.id.a11y_action_open_menu -> ControlsAccessibilityModel.opensMenu(id) && view.openMenuFromAccessibility()
            else -> directionForAction(action)
                ?.takeIf { it in ControlsAccessibilityModel.directionsFor(id) }
                ?.let { view.pressFor(ControlsAccessibilityModel.directionMask(it), ControlsAccessibilityModel.PRESS_MS) } ?: false
        }
    }

    /** Acciones sobre el nodo raíz (la vista): «Abrir menú». */
    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        if (action == R.id.a11y_action_open_menu && !view.editing) return view.openMenuFromAccessibility()
        return super.performAccessibilityAction(host, action, args)
    }

    private fun label(id: ControlId): String = view.context.getString(
        when (id) {
            ControlId.DPAD -> R.string.controls_a11y_dpad
            ControlId.A -> R.string.controls_a11y_a
            ControlId.B -> R.string.controls_a11y_b
            ControlId.START -> R.string.controls_a11y_start
            ControlId.SELECT -> R.string.controls_a11y_select
            ControlId.MENU -> R.string.controls_a11y_menu
            ControlId.L -> R.string.n8_controls_a11y_l
            ControlId.R -> R.string.n8_controls_a11y_r
        },
    )

    private fun directionOf(action: ControlA11yAction): DpadDirection? = when (action) {
        ControlA11yAction.UP -> DpadDirection.UP
        ControlA11yAction.DOWN -> DpadDirection.DOWN
        ControlA11yAction.LEFT -> DpadDirection.LEFT
        ControlA11yAction.RIGHT -> DpadDirection.RIGHT
        else -> null
    }

    private fun directionActionId(direction: DpadDirection): Int = when (direction) {
        DpadDirection.UP -> R.id.a11y_action_up
        DpadDirection.DOWN -> R.id.a11y_action_down
        DpadDirection.LEFT -> R.id.a11y_action_left
        DpadDirection.RIGHT -> R.id.a11y_action_right
    }

    private fun directionForAction(action: Int): DpadDirection? = DpadDirection.entries.firstOrNull { directionActionId(it) == action }

    private fun directionLabel(direction: DpadDirection): String = view.context.getString(
        when (direction) {
            DpadDirection.UP -> R.string.controls_a11y_up
            DpadDirection.DOWN -> R.string.controls_a11y_down
            DpadDirection.LEFT -> R.string.controls_a11y_left
            DpadDirection.RIGHT -> R.string.controls_a11y_right
        },
    )
}
