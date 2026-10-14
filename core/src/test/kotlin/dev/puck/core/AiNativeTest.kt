package dev.puck.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AiNativeTest {
    @Test fun ambiguousLanguageCannotBecomeADialerOrCameraCommand() {
        listOf("call Mom tomorrow", "camera advice", "take a photo", "go home", "open something fun", "what should I do", "question").forEach {
            assertFalse(it, InputRouter.isLocal(it, setOf("settings")))
        }
        listOf("camera", " CALL ", "text Hello", "navigate London", "event Lunch", "open Settings").forEach {
            assertTrue(it, InputRouter.isLocal(it, setOf("settings")))
        }
    }
    @Test fun museCommandOnlyCreatesAReviewableProposal() {
        val request = MuseCommand("remote-camera", "camera").workspace(emptyMap())
        val task = PuckTask(request.id, request)
        assertFalse(TaskMachine.canDispatch(task))
        val reviewed = TaskMachine.move(task, TaskPhase.AWAITING_APPROVAL, "Review")
        assertTrue(TaskMachine.canDispatch(reviewed))
        assertFalse(TaskMachine.canDispatch(TaskMachine.move(reviewed, TaskPhase.DISPATCHING, "Opening")))
    }
    @Test fun museCannotChooseArbitraryPackagesOrUssd() {
        assertThrows(IllegalArgumentException::class.java) { MuseCommand("remote", "open_app", appLabel = "unknown").workspace(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { MuseCommand("remote", "dial", number = "*123#").workspace(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { MuseCommand("remote", "camera", body = "hidden extra instruction").workspace(emptyMap()) }
        assertThrows(IllegalStateException::class.java) { MuseCommand("remote", "shell").workspace(emptyMap()) }
    }
    @Test fun unknownJsonFieldsAndUpdateMoodsFailClosed() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) { Json.decodeFromString<MuseCommand>("""{"id":"x","action":"camera","execute":true}""") }
        assertThrows(IllegalArgumentException::class.java) { MuseUpdate("x", "Title", "Text", "execute").workspace() }
        assertEquals("Text", MuseUpdate("x", "Title", "Text", "cheerful").workspace().text)
    }
    @Test fun oldJournalTasksDecodeWithLocalOrigin() {
        val old = """{"id":"legacy","request":{"type":"dev.puck.core.WorkspaceRequest.ContentCard","id":"legacy","title":"Old","text":"Retained"}}"""
        assertEquals("local", Json.decodeFromString<PuckTask>(old).source)
    }
}
