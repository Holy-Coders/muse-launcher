package dev.puck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionHomeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeTextKeepsControlsReachableAndAvatarTapIsExplicitInteraction() {
        var taps = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        CompanionHome("12:34", ConnectionStatus.MuseConnected, true, false, false, "idle", false,
                            listOf(PuckTask("update", WorkspaceRequest.ContentCard("update", "A useful update", "Full information remains in the review screen"), TaskPhase.COMPLETED, source = "muse")), emptySet(), true,
                            review = {}, ask = {}, talk = {}, apps = {}, updates = {}, settings = {}, checkIn = { taps++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Muse companion, New from Muse").performScrollTo().performClick()
        compose.waitUntil(3_000) { taps == 1 }
        assertEquals(1, taps)
        // Compose semantics alone can pass while an AndroidView is absent from
        // TalkBack's platform tree. Check the real native node and touch path.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun findAvatar(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "Muse companion, New from Muse") return node
            for (index in 0 until node.childCount) findAvatar(node.getChild(index))?.let { return it }
            return null
        }
        var nativeAvatar: android.view.accessibility.AccessibilityNodeInfo? = null
        compose.waitUntil(3_000) { nativeAvatar = findAvatar(automation.rootInActiveWindow); nativeAvatar != null }
        val avatarNode = requireNotNull(nativeAvatar)
        // The platform's unmerged tree exposes the label as a child of its
        // clickable group, just as it does for Compose's ordinary buttons.
        var activationNode = avatarNode
        while (!activationNode.isClickable && activationNode.parent != null) activationNode = activationNode.parent
        assertTrue(activationNode.isClickable)
        assertTrue(activationNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(3_000) { taps == 2 }
        assertEquals(2, taps)
        for (label in listOf("Talk", "More")) {
            val node = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(label, bounds.bottom - bounds.top >= 48.dp)
        }
        compose.onNodeWithText("Apps").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("More").performScrollTo().performClick()
        for (label in listOf("Type a request", "Apps", "Updates (1)", "Settings")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }

    }
    @Test fun dismissClearsPreviewWithoutResurfacingHistoryAndNewUpdateCanAppear() {
        val old = PuckTask("old", WorkspaceRequest.ContentCard("old", "Older reply", "History"), TaskPhase.COMPLETED, source = "muse")
        val first = PuckTask("first", WorkspaceRequest.ContentCard("first", "Current reply", "Information"), TaskPhase.COMPLETED, source = "muse")
        var updates by mutableStateOf(listOf(first, old))
        var hidden by mutableStateOf(emptySet<String>())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    CompanionHome("12:34", ConnectionStatus.MuseConnected, true, false, false, "idle", false,
                        updates, emptySet(), true, {}, {}, {}, {}, {}, {}, {},
                        dismissed = hidden, dismiss = { hidden = hidden + it.id })
                }
            }
        }
        compose.onNodeWithText("Current reply").performScrollTo().performTouchInput { swipeLeft() }
        compose.waitUntil(3_000) { "first" in hidden }
        compose.onNodeWithText("Current reply").assertDoesNotExist()
        compose.onNodeWithText("Older reply").assertDoesNotExist()
        compose.runOnIdle {
            updates = listOf(PuckTask("second", WorkspaceRequest.ContentCard("second", "Fresh reply", "Information"), TaskPhase.COMPLETED, source = "muse")) + updates
        }
        compose.onNodeWithText("Fresh reply").performScrollTo().assertIsDisplayed().performTouchInput { swipeRight() }
        compose.waitUntil(3_000) { "second" in hidden }
        compose.onNodeWithText("Older reply").assertDoesNotExist()
        compose.runOnIdle {
            updates = listOf(PuckTask("third", WorkspaceRequest.ContentCard("third", "Newest reply", "Information"), TaskPhase.COMPLETED, source = "muse")) + updates
        }
        compose.onNodeWithContentDescription("Dismiss update").performScrollTo().performClick()
        compose.waitUntil(3_000) { "third" in hidden }
        compose.onNodeWithText("Newest reply").assertDoesNotExist()
        assertEquals(4, updates.size) // History source is untouched.
    }
    @Test fun swipeCannotHidePendingReview() {
        var dismissed = false
        val task = PuckTask("pending", WorkspaceRequest.Confirmation("pending", DeviceAction.Camera), TaskPhase.AWAITING_APPROVAL, source = "muse")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    CompanionHome("12:34", ConnectionStatus.MuseConnected, true, false, false, "idle", false,
                        listOf(task), emptySet(), true, {}, {}, {}, {}, {}, {}, {}, dismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("Review an action").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Review an action").assertIsDisplayed()
        assertFalse(dismissed)
    }

}
