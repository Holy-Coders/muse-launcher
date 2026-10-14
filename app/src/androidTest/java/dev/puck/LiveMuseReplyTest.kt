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

/** Separate from the native command delivery probe: chat fallback must not mask it. */
@RunWith(AndroidJUnit4::class)
class LiveMuseReplyTest {
    @Test fun correlatedRepliesCreateInformationAndReviewedActions() = runBlocking {
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("puckLiveMuse") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(MuseVault(app).hasCredentials() && MuseVault(app).enabled)
        val microphoneBefore = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        val model = withContext(Dispatchers.Main) { PuckViewModel(app) }
        withTimeout(60_000) { model.ready.first { it }; model.connection.first { it == ConnectionStatus.MuseConnected } }
        val known = model.tasks.value.map { it.id }.toSet()
        withContext(Dispatchers.Main) { model.interact("""This is a protocol test. Return exactly this object: {"puck":1,"type":"update","title":"Chat fallback test","text":"Your companion is connected","mood":"cheerful"}. No tools or external actions.""") }
        val info = withTimeout(120_000) { model.tasks.first { it.any { t -> t.id !in known && t.phase == TaskPhase.COMPLETED } }.first { it.id !in known && it.phase == TaskPhase.COMPLETED } }
        assertEquals("Chat fallback test", (info.request as WorkspaceRequest.ContentCard).title)
        assertEquals("Your companion is connected", (info.request as WorkspaceRequest.ContentCard).text)
        assertEquals("muse", info.source)
        assertNull(info.action)
        withTimeout(10_000) { model.thinking.first { !it } }
        val checkIn = AndroidMuseAgent(MuseRuntime.get(app)).sendReply("check-in-${java.util.UUID.randomUUID()}",
            """Return exactly {"puck":1,"type":"update","title":"Check-in test","text":"No fresh news","mood":"idle"}.""", informationOnly = true).first { it is AgentEvent.Request } as AgentEvent.Request
        assertEquals("Check-in test", (checkIn.request as WorkspaceRequest.ContentCard).title)
        assertNull((checkIn.request as? WorkspaceRequest.Confirmation)?.action)
        val before = model.tasks.value.map { it.id }.toSet()
        withContext(Dispatchers.Main) { model.interact("Prepare a Puck camera action proposal using the action JSON format. Do not invoke tools or open anything. I will review it locally later.") }
        val proposal = withTimeout(120_000) { model.tasks.first { it.any { t -> t.id !in before && t.phase != TaskPhase.PREPARING } }.first { it.id !in before && it.phase != TaskPhase.PREPARING } }
        try {
            assertEquals(TaskPhase.AWAITING_APPROVAL, proposal.phase)
            assertEquals(DeviceAction.Camera, proposal.action)
            assertEquals("muse", proposal.source)
            delay(1000)
            assertEquals(TaskPhase.AWAITING_APPROVAL, TaskRepository(PuckDatabase.get(app).tasks()).get(proposal.id)?.phase)
            assertEquals(microphoneBefore, app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        } finally {
            // Remove only this test's proposal; never approve a physical handoff.
            withContext(Dispatchers.Main) { model.cancel(proposal.id) }
            withTimeout(10_000) { model.tasks.first { it.any { t -> t.id == proposal.id && t.phase == TaskPhase.CANCELLED } } }
        }
    }
}
