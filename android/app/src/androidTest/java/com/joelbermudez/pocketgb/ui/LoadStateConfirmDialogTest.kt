package com.joelbermudez.pocketgb.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.saves.StateSlot
import com.joelbermudez.pocketgb.ui.gameplay.LoadStateConfirmDialog
import com.joelbermudez.pocketgb.ui.theme.PocketGBTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A6-H6: cada ranura muestra su propio cuerpo; la de rescate recupera su texto explicativo. */
@RunWith(AndroidJUnit4::class)
class LoadStateConfirmDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(slot: StateSlot) = compose.setContent {
        PocketGBTheme { LoadStateConfirmDialog(slot, "ranura", {}, {}, {}) }
    }

    @Test
    fun rescueSlotHasItsOwnBody() {
        show(StateSlot.RESCUE)
        compose.onNodeWithText(context.getString(R.string.state_load_body_rescue)).assertIsDisplayed()
    }

    @Test
    fun manualAndAutoSlotsKeepTheirBodies() {
        show(StateSlot.AUTO)
        compose.onNodeWithText(context.getString(R.string.gameplay_load_body_auto)).assertIsDisplayed()
    }

    @Test
    fun manualSlotUsesTheGenericBody() {
        show(StateSlot.MANUAL1)
        compose.onNodeWithText(context.getString(R.string.gameplay_load_body)).assertIsDisplayed()
    }
}
