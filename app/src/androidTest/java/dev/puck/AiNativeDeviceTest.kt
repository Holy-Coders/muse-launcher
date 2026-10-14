package dev.puck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Injects the transport callbacks, then checks real validation/journaling/UI, not the live server. */
@RunWith(AndroidJUnit4::class)
class AiNativeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun incomingUpdatePersistsAndDuplicateCannotReplaceApproval() {
        val model = ViewModelProvider(compose.activity)[PuckViewModel::class.java]
        compose.waitUntil(60_000) { model.ready.value }
        val original = model.usingMuse.value
        val microphoneBefore = compose.activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        val suffix = UUID.randomUUID().toString()
        val update = "update-$suffix"
        val action = "action-$suffix"
        try {
            // Network-independent fixture: enable only the receive guard.
            compose.runOnIdle { model.muse.vault.enabled = true; model.usingMuse.value = true }
            assertTrue(model.muse.onUpdate("""{"id":"$update","title":"Device test update","text":"A retained update","mood":"cheerful"}"""))
            compose.waitUntil(10_000) { model.tasks.value.any { it.id == update && it.phase == TaskPhase.COMPLETED } }
            val retained = runBlocking { TaskRepository(PuckDatabase.get(compose.activity).tasks()).get(update) }
            assertEquals("muse", retained?.source)
            assertEquals("A retained update", (retained?.request as WorkspaceRequest.ContentCard).text)
            compose.runOnIdle { model.markRead(update) }
            compose.waitUntil(10_000) { update in model.readUpdates.value }
            assertTrue(model.muse.onCommand("""{"id":"$action","action":"camera"}"""))
            compose.waitUntil(10_000) { model.tasks.value.any { it.id == action && it.phase == TaskPhase.AWAITING_APPROVAL } }
            assertTrue(model.muse.onUpdate("""{"id":"$action","title":"Replacement","text":"Must not erase approval"}"""))
            compose.waitForIdle()
            assertTrue(runBlocking { TaskRepository(PuckDatabase.get(compose.activity).tasks()).get(action) }?.request is WorkspaceRequest.Confirmation)
            assertEquals(TaskPhase.AWAITING_APPROVAL, model.tasks.value.first { it.id == action }.phase)
            assertFalse(model.muse.onCommand("""{"id":"bad-$suffix","action":"dial","number":"*123#"}"""))
            assertFalse(model.muse.onCommand("""{"id":"bad-$suffix","action":"camera","execute":true}"""))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Review an action").assertIsDisplayed()
            assertEquals(microphoneBefore, compose.activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        } finally {
            compose.runOnIdle { model.usingMuse.value = false; model.muse.vault.enabled = false; model.cancel(action); model.useMuse(original) }
        }
    }
}
