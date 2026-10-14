package dev.puck.core

import org.junit.Assert.*
import org.junit.Test

class MuseReplyTest {
    @Test fun companionSupportsValidatedPhoneProposalsAndUpdates() {
        assertTrue(MuseReply.decodeAppliance("turn", """{"puck":1,"type":"action","action":"dial"}""").request is WorkspaceRequest.Confirmation)
        val known=MuseReply.decodeAppliance("turn", """{"puck":1,"type":"action","action":"dial","number":"+12025550100"}""").request as WorkspaceRequest.Confirmation
        assertEquals(DeviceAction.Dial("+12025550100"),known.action)
        for(reply in listOf(
            """{"puck":1,"type":"action","action":"dial","number":"*123#"}""",
            """{"puck":1,"type":"action","action":"open_app","appLabel":"Unknown"}""",
            """{"puck":1,"type":"action","action":"message","body":""}""",
            """{"puck":1,"type":"question","prompt":"Which?","choices":["A"]}"""
        ))assertThrows(Exception::class.java) {MuseReply.decodeAppliance("turn",reply)}
        for(reply in listOf(
            """{"puck":1,"type":"action","action":"camera"}""",
            """{"puck":1,"type":"action","action":"message","body":"Hello"}""",
            """{"puck":1,"type":"action","action":"navigate","destination":"London"}""",
            """{"puck":1,"type":"action","action":"calendar","title":"Lunch"}"""
        ))assertTrue(MuseReply.decodeAppliance("turn",reply).request is WorkspaceRequest.Confirmation)
        val app=DeviceAction.OpenApp("dev.fixture","dev.fixture.Main")
        assertEquals(app,(MuseReply.decodeAppliance("turn","""{"puck":1,"type":"action","action":"open_app","appLabel":"Fixture"}""",mapOf("fixture" to app)).request as WorkspaceRequest.Confirmation).action)
    }
    private fun decode(text: String, info: Boolean = false) = MuseReply.decode("local-turn", text, informationOnly = info)
    @Test fun updateBindsToLocalTurnAndMood() {
        val result = decode("""{"puck":1,"type":"update","title":"Hello","text":"Ready","mood":"cheerful"}""")
        assertEquals("local-turn", result.request.id)
        assertEquals("cheerful", result.mood)
        assertTrue(result.request is WorkspaceRequest.ContentCard)
    }
    @Test fun actionsNeedTheExistingReviewTransition() {
        val request = decode("""{"puck":1,"type":"action","action":"camera"}""").request
        val task = PuckTask(request.id, request)
        assertFalse(TaskMachine.canDispatch(task))
        val reviewed = TaskMachine.move(task, TaskPhase.AWAITING_APPROVAL, "Review")
        assertEquals(DeviceAction.Camera, reviewed.action)
        assertTrue(TaskMachine.canDispatch(reviewed))
    }
    @Test fun contactPickingAndAppAllowlistStillApply() {
        val request = decode("""{"puck":1,"type":"action","action":"message","body":"Hello"}""").request as WorkspaceRequest.Confirmation
        assertTrue(CommandValidator.needsContact(request.action))
        assertThrows(Exception::class.java) { decode("""{"puck":1,"type":"action","action":"open_app","appLabel":"Unknown"}""") }
        val app = DeviceAction.OpenApp("dev.test", "dev.test.Main")
        assertEquals(app, (MuseReply.decode("local-turn", """{"puck":1,"type":"action","action":"open_app","appLabel":"Settings"}""", mapOf("settings" to app)).request as WorkspaceRequest.Confirmation).action)
    }
    @Test fun questionsAreBoundedDistinctStrings() {
        assertTrue(decode("""{"puck":1,"type":"question","prompt":"Which?","choices":["A","B"]}""").request is WorkspaceRequest.Question)
        for (choices in listOf("[]", "[1]", "[\"A\",\"A\"]")) {
            assertThrows(Exception::class.java) { decode("""{"puck":1,"type":"question","prompt":"Which?","choices":$choices}""") }
        }
    }
    @Test fun malformedMixedUnsupportedAndModelIdsAreInert() {
        for (text in listOf(
            "Here is {\"puck\":1,\"type\":\"action\",\"action\":\"camera\"}",
            "```json\n{\"puck\":1,\"type\":\"action\",\"action\":\"camera\"}\n```",
            """{"puck":2,"type":"action","action":"camera"}""",
            """{"puck":"1","type":"action","action":"camera"}""",
            """{"puck":1,"type":"action","action":"camera","id":"pending-task"}""",
            """{"puck":1,"type":"action","action":"camera","body":"inappropriate"}""",
            """{"puck":1,"type":"action","action":"shell"}""",
            """[{"puck":1,"type":"action","action":"camera"}]""",
            """{"puck":1,"type":"update","title":"Hi","text":"Ready","mood":"unknown"}"""
        )) assertThrows(Exception::class.java) { decode(text) }
        assertThrows(Exception::class.java) { decode("x".repeat(4001)) }
    }
    @Test fun checkInsCannotProposeActionsOrQuestions() {
        assertTrue(decode("""{"puck":1,"type":"update","title":"Hi","text":"Ready"}""", true).request is WorkspaceRequest.ContentCard)
        for (text in listOf("""{"puck":1,"type":"action","action":"camera"}""", """{"puck":1,"type":"question","prompt":"Which?","choices":["A"]}""")) {
            assertThrows(Exception::class.java) { decode(text, true) }
        }
    }
    @Test fun checkInCooldownSurvivesClockRollback() {
        assertTrue(HomeCheckIn.due(100, 0))
        assertFalse(HomeCheckIn.due(99, 100))
        assertFalse(HomeCheckIn.due(100 + HomeCheckIn.INTERVAL_MILLIS - 1, 100))
        assertTrue(HomeCheckIn.due(100 + HomeCheckIn.INTERVAL_MILLIS, 100))
    }
}
