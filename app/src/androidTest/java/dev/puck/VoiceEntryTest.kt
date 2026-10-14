package dev.puck

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.puck.core.TaskPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun repeatedVoiceEntryReusesLauncherAndPreservesPendingTaskWithoutRecording() {
        val original = compose.activity
        val microphoneBefore = original.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val model = ViewModelProvider(original)[PuckViewModel::class.java]
        compose.waitUntil(60_000) { model.ready.value }
        val priorIds = runBlocking { PuckDatabase.get(original).tasks().all().map { it.id }.toSet() }
        assertEquals(MainActivity.VOICE_ENTRY_COMPONENT, original.packageManager.getLaunchIntentForPackage(original.packageName)?.component?.className)
        compose.runOnIdle { model.edit("text Hello"); model.submit() }
        compose.waitUntil(60_000) { model.tasks.value.any { it.phase == TaskPhase.AWAITING_APPROVAL && it.id !in priorIds } }
        val id = model.tasks.value.first { it.phase == TaskPhase.AWAITING_APPROVAL && it.id !in priorIds }.id
        repeat(3) {
            compose.runOnIdle {
                original.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(original.packageName, MainActivity.VOICE_ENTRY_COMPONENT)))
            }
        }
        compose.onNodeWithText("Start listening").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(original), ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>())
            assertSame(model, ViewModelProvider(original)[PuckViewModel::class.java])
            assertEquals(TaskPhase.AWAITING_APPROVAL, model.tasks.value.first { it.id == id }.phase)
            assertEquals(microphoneBefore, original.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        }
    }
}
