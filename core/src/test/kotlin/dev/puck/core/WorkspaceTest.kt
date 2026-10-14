package dev.puck.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    private fun approved(id: String = "request-1", action: DeviceAction = DeviceAction.Camera) =
        TaskMachine.move(PuckTask(id, WorkspaceRequest.Confirmation(id, action)), TaskPhase.AWAITING_APPROVAL, "Review")

    @Test fun validatesVersionIdsAndBoundaries() {
        assertNotNull(CommandValidator.validate(WorkspaceRequest.Confirmation("a", DeviceAction.Camera, version = 2)))
        assertNotNull(CommandValidator.validate(WorkspaceRequest.Confirmation("../../oops", DeviceAction.Camera)))
        assertNull(CommandValidator.validate(WorkspaceRequest.Confirmation("a", DeviceAction.Navigate("A&B / Paris?"))))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Navigate("")))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Message("x".repeat(2001), "+123")))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Message("Hello", "123;smsto:evil")))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Dial("*123#"))) // USSD is outside the capability.
        assertNotNull(CommandValidator.validateAction(DeviceAction.Dial("---")))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Calendar("Event", 100, 50)))
        assertNotNull(CommandValidator.validateAction(DeviceAction.Calendar("Event", null, 50)))
        assertNull(CommandValidator.validateAction(DeviceAction.Dial("+1 (202) 555-0123")))
    }
    @Test fun contactChoiceIsRequiredBeforeDispatch() {
        assertFalse(TaskMachine.canDispatch(approved(action = DeviceAction.Dial())))
        assertFalse(TaskMachine.canDispatch(approved(action = DeviceAction.Message("Hello"))))
        assertTrue(TaskMachine.canDispatch(approved(action = DeviceAction.Message("Hello", "+12345"))))
    }
    @Test fun appMustBeInLocalLaunchableList() {
        val action = DeviceAction.OpenApp("dev.example", "dev.example.Main")
        assertNotNull(CommandValidator.validateAction(action))
        assertNull(CommandValidator.validateAction(action, setOf("dev.example" to "dev.example.Main")))
        assertNotNull(CommandValidator.validateAction(action.copy(activityName = "SecretActivity"), setOf("dev.example" to "dev.example.Main")))
    }
    @Test fun duplicateIdCannotReplaceActionOrExecuteTwice() {
        val ledger = RequestLedger()
        assertTrue(ledger.accept(approved()))
        assertFalse(ledger.accept(approved(action = DeviceAction.Navigate("elsewhere"))))
        assertEquals(DeviceAction.Camera, ledger.get("request-1")!!.action)
        assertTrue(ledger.claim("request-1"))
        assertFalse(ledger.claim("request-1"))
        ledger.finish("request-1", CapabilityResult.HandedOff)
        assertFalse(ledger.claim("request-1"))
        assertEquals(TaskPhase.HANDED_OFF, ledger.get("request-1")!!.phase)
    }
    @Test fun cancellationBeforeExecutionStopsAction() {
        val ledger = RequestLedger()
        ledger.accept(approved())
        assertTrue(ledger.cancel("request-1"))
        assertFalse(ledger.claim("request-1"))
        assertFalse(ledger.cancel("request-1"))
    }
    @Test fun committedHandoffCannotBeCancelledOrPretendToUndo() {
        val ledger = RequestLedger()
        ledger.accept(approved())
        ledger.claim("request-1")
        assertFalse(ledger.cancel("request-1"))
        ledger.finish("request-1", CapabilityResult.HandedOff)
        assertFalse(ledger.cancel("request-1"))
    }
    @Test fun permissionDenialAndMissingAppAreFailures() {
        listOf(CapabilityResult.PermissionDenied, CapabilityResult.Unavailable, CapabilityResult.Invalid).forEach { result ->
            val ledger = RequestLedger()
            ledger.accept(approved())
            ledger.claim("request-1")
            ledger.finish("request-1", result)
            assertEquals(TaskPhase.FAILED, ledger.get("request-1")!!.phase)
            assertFalse(ledger.claim("request-1"))
        }
    }
    @Test fun preparingCannotSkipUserApproval() {
        assertThrows(IllegalArgumentException::class.java) { TaskMachine.move(PuckTask("a", WorkspaceRequest.Confirmation("a", DeviceAction.Camera)), TaskPhase.DISPATCHING, "unsafe") }
        assertThrows(IllegalArgumentException::class.java) { TaskMachine.move(approved(), TaskPhase.HANDED_OFF, "unsafe") }
    }
    @Test fun roundTripPreservesDraftActionAndPhase() {
        val task = approved(action = DeviceAction.Message("Private draft", "+123456789"))
        assertEquals(task, Json.decodeFromString<PuckTask>(Json.encodeToString(task)))
    }
    @Test fun fakeAgentIsDeterministicAndRepliesAreEvents() = runTest {
        val agent = FakeAgent()
        agent.connect()
        val events = agent.sendInput("id-1", "navigate Paris").toList()
        assertEquals(ConnectionStatus.FakeConnected, agent.connection.value)
        assertEquals(WorkspaceRequest.Confirmation("id-1", DeviceAction.Navigate("Paris")), (events.filterIsInstance<AgentEvent.Request>().single()).request)
        assertEquals(AgentEvent.ReplyFinished, events.last())
        assertEquals(events, agent.sendInput("id-1", "navigate Paris").toList())
    }
    @Test fun fakeQuestionAnswerProducesSeparateConfirmation() = runTest {
        val agent = FakeAgent()
        val events = agent.answer("q", "Open camera").toList()
        assertEquals(WorkspaceRequest.Confirmation("q-camera", DeviceAction.Camera), (events.single() as AgentEvent.Request).request)
        agent.cancel("q")
        assertTrue(agent.answer("q", "Open camera").toList().isEmpty())
    }
    @Test fun fakeOpensOnlyConfiguredUnambiguousAppLabels() = runTest {
        val action = DeviceAction.OpenApp("test.settings", "test.settings.Main")
        val agent = FakeAgent(mapOf("settings" to action))
        val events = agent.sendInput("open-1", "open Settings").toList()
        assertEquals(WorkspaceRequest.Confirmation("open-1", action), events.filterIsInstance<AgentEvent.Request>().single().request)
    }
    @Test fun fakeCancellationStopsReplyAndAction() = runTest {
        val agent = FakeAgent()
        agent.cancel("cancel-me")
        assertTrue(agent.sendInput("cancel-me", "camera").toList().isEmpty())
    }
    @Test fun statusUpdatesCannotApproveExecuteOrCertifyDeviceTasks() {
        val task = approved()
        val changed = TaskMachine.agentStatus(task, "I think this is done")
        assertEquals(TaskPhase.AWAITING_APPROVAL, changed.phase)
        assertEquals(task.action, changed.action)
        assertEquals("I think this is done", changed.agentNote)
        assertNull(CommandValidator.validate(WorkspaceRequest.TaskStatus("update-1", "request-1", "Preparing")))
        assertNotNull(CommandValidator.validate(WorkspaceRequest.TaskStatus("update-1", "../bad", "Preparing")))
    }
    @Test fun questionsHaveBoundedDistinctChoices() {
        assertNotNull(CommandValidator.validate(WorkspaceRequest.Question("q", "Pick", emptyList())))
        assertNotNull(CommandValidator.validate(WorkspaceRequest.Question("q", "Pick", listOf("a", "a"))))
        assertNull(CommandValidator.validate(WorkspaceRequest.Question("q", "Pick", listOf("a", "b"))))
    }
}
