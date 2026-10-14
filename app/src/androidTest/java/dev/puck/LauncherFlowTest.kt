package dev.puck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import dev.puck.core.TaskPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual installed Camera app, not an intent mock. Other device handlers are manual checks. */
@RunWith(AndroidJUnit4::class)
class LauncherFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun backgroundReplyStaysReviewableAfterReturning() {
        val model = ViewModelProvider(compose.activity)[PuckViewModel::class.java]
        compose.waitUntil(60_000) { model.ready.value }
        compose.runOnIdle { model.useMuse(false) }
        compose.waitUntil(10_000) { !model.usingMuse.value }
        val known = model.tasks.value.map { it.id }.toSet()
        compose.runOnIdle { model.edit("camera"); model.submit() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { model.tasks.value.any { it.id !in known && it.phase == TaskPhase.AWAITING_APPROVAL } }
        val task = model.tasks.value.first { it.id !in known && it.phase == TaskPhase.AWAITING_APPROVAL }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(TaskPhase.AWAITING_APPROVAL, model.tasks.value.first { it.id == task.id }.phase)
        assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        compose.runOnIdle { model.cancel(task.id) }
        compose.waitUntil(10_000) { model.tasks.value.first { it.id == task.id }.phase == TaskPhase.CANCELLED }
    }
    @Test fun typedCameraRequestSurvivesDraftRecreationAndOpensWithoutExtraApproval() {
        val model = ViewModelProvider(compose.activity)[PuckViewModel::class.java]
        compose.waitUntil(60_000) { model.ready.value }
        val priorIds = runBlocking { PuckDatabase.get(compose.activity).tasks().all().map { it.id }.toSet() }
        compose.runOnIdle { model.useMuse(false) }
        compose.waitUntil(10_000) { !model.usingMuse.value }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Type a request").performClick()
        val input = "camera"
        compose.onNodeWithText("Your request").performTextReplacement(input)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Your request").assertTextContains(input)
        compose.onNodeWithText("Send to FakeAgent").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(60_000) { model.tasks.value.any { it.phase == TaskPhase.HANDED_OFF && it.id !in priorIds } }
        val task = model.tasks.value.first { it.phase == TaskPhase.HANDED_OFF && it.id !in priorIds }
        assertEquals(input, task.userInput)
        assertEquals(TaskPhase.HANDED_OFF, task.phase)
        // The launcher is no longer resumed; Android accepted the real cross-app handoff.
        val launcher = compose.activity
        compose.waitUntil(60_000) { !launcher.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }
}
