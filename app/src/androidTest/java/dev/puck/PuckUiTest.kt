package dev.puck

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PuckUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun messageRequiresApprovalAndDoesNotClaimSending() {
        var approved = false
        val task = PuckTask("message-1", WorkspaceRequest.Confirmation("message-1", DeviceAction.Message("Hello", "+123456789")), TaskPhase.AWAITING_APPROVAL)
        compose.setContent { MaterialTheme { TaskCard(task, { approved = true }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Awaiting approval").assertIsDisplayed()
        compose.onNodeWithText("You send it.", substring = true).assertIsDisplayed()
        assertFalse(approved)
        compose.onNodeWithText("Approve & open").performClick()
        assertTrue(approved)
    }
    @Test fun handoffOffersNoCancelOrUndo() {
        val task = PuckTask("camera-1", WorkspaceRequest.Confirmation("camera-1", DeviceAction.Camera), TaskPhase.HANDED_OFF)
        compose.setContent { MaterialTheme { TaskCard(task, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Approve & open").assertDoesNotExist()
        compose.onNodeWithText("Handed off").assertIsDisplayed()
    }
}
