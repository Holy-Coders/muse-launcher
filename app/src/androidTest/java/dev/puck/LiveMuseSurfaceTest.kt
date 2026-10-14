package dev.puck

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in: only run by explicit class selection on the already paired personal phone. */
@RunWith(AndroidJUnit4::class)
class LiveMuseSurfaceTest {
    @Test fun pairedMusePushesAnInformationUpdateIntoTheJournal() = runBlocking {
        assumeTrue("Explicit live-test argument required", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("puckLiveMuse") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue("No paired personal Muse on this test device", MuseVault(app).hasCredentials() && MuseVault(app).enabled)
        val microphoneBefore = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        val model = withContext(Dispatchers.Main) { PuckViewModel(app) }
        withTimeout(60_000) { model.ready.first { it }; model.connection.first { it == ConnectionStatus.MuseConnected } }
        val id = "live-push-${UUID.randomUUID()}"
        val legacy = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("puckLegacyPush") == "true"
        val known = model.tasks.value.map { it.id }.toSet()
        withContext(Dispatchers.Main) {
            model.interactNative(if (legacy) "Test this gadget's existing puck.request command exactly once. Its request argument must be the JSON string {\"type\":\"dev.puck.core.WorkspaceRequest.ContentCard\",\"id\":\"$id\",\"version\":1,\"title\":\"Puck update test\",\"text\":\"Your companion is connected\"}. Do not call other tools or perform actions. Then reply briefly." else "Test Puck's incoming-update capability. Use the connected Puck device command puck.update exactly once with id $id, title Puck update test, text Your companion is connected, and mood cheerful. This is information only. Do not invoke any action command or other external tool. Then reply briefly.")
        }
        try {
            withTimeout(120_000) { model.tasks.first { tasks -> tasks.any {
                it.phase == TaskPhase.COMPLETED && (it.id == id || (it.id !in known && (it.request as? WorkspaceRequest.ContentCard)?.title == "Muse reply"))
            } } }
            withTimeout(5_000) { model.tasks.first { tasks -> tasks.any { it.id == id && it.phase == TaskPhase.COMPLETED } } }
        } finally {
            val counts = withContext(Dispatchers.IO) { MuseRuntime.get(app).linkStats() }
            // A bounded content-free artifact survives instrumentation stdout
            // filtering and can be retrieved with run-as on this debug build.
            withContext(Dispatchers.IO) { app.filesDir.resolve("muse-link-diagnostics.json").writeText(counts) }
            android.util.Log.i("PuckMuseDiagnostics", counts)
            println("Puck control counts: " + counts)
        }
        val stored = TaskRepository(PuckDatabase.get(app).tasks()).get(id)!!
        assertEquals("muse", stored.source)
        assertEquals("Your companion is connected", (stored.request as WorkspaceRequest.ContentCard).text)
        assertNull(stored.action)
        assertEquals(microphoneBefore, app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
    }
}
