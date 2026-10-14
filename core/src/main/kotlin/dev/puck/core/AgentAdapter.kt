package dev.puck.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object FakeConnected : ConnectionStatus
    data object MuseConnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Blocked(val reason: String) : ConnectionStatus
}
sealed interface AgentEvent {
    data class ReplyDelta(val text: String) : AgentEvent
    data class Request(val request: WorkspaceRequest) : AgentEvent
    data object ReplyFinished : AgentEvent
}
interface AgentAdapter {
    val events: Flow<AgentEvent> get() = emptyFlow()
    val connection: StateFlow<ConnectionStatus>
    suspend fun connect()
    fun sendInput(requestId: String, text: String): Flow<AgentEvent>
    fun answer(requestId: String, answer: String): Flow<AgentEvent>
    suspend fun report(task: PuckTask)
    suspend fun cancel(requestId: String)
    suspend fun disconnect()
}

/** Offline scripted parser, deliberately not presented as AI or a real hardware connection. */
class FakeAgent(private val launchableByLabel: Map<String, DeviceAction.OpenApp> = emptyMap()) : AgentAdapter {
    override val connection = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    private val cancelled = mutableSetOf<String>()
    override suspend fun connect() { connection.value = ConnectionStatus.FakeConnected }
    override suspend fun disconnect() { connection.value = ConnectionStatus.Disconnected }
    override suspend fun cancel(requestId: String) { cancelled += requestId }
    override fun answer(requestId: String, answer: String): Flow<AgentEvent> = flow {
        if (requestId !in cancelled && answer == "Open camera") emit(AgentEvent.Request(WorkspaceRequest.Confirmation("$requestId-camera", DeviceAction.Camera)))
    }
    override suspend fun report(task: PuckTask) = Unit
    override fun sendInput(requestId: String, text: String): Flow<AgentEvent> = flow {
        val input = text.trim()
        val verb = input.substringBefore(' ').lowercase()
        val value = input.substringAfter(' ', "").trim()
        val request: WorkspaceRequest = when (verb) {
            "call", "dial" -> WorkspaceRequest.Confirmation(requestId, DeviceAction.Dial())
            "text", "message", "sms" -> WorkspaceRequest.Confirmation(requestId, DeviceAction.Message(value))
            "navigate", "maps", "go" -> WorkspaceRequest.Confirmation(requestId, DeviceAction.Navigate(value))
            "calendar", "event" -> WorkspaceRequest.Confirmation(requestId, DeviceAction.Calendar(value))
            "open" -> launchableByLabel[value.lowercase()]?.let { WorkspaceRequest.Confirmation(requestId, it) }
                ?: WorkspaceRequest.ContentCard(requestId, "Choose an app", "No unique installed app matches that label. Use Apps to search and choose.")
            "camera", "photo" -> WorkspaceRequest.Confirmation(requestId, DeviceAction.Camera)
            "question" -> WorkspaceRequest.Question(requestId, "How would you like to continue?", listOf("Keep typing", "Open camera"))
            else -> WorkspaceRequest.ContentCard(requestId, "Try a command", "call · text Hello · navigate London · event Lunch · camera\nThis is the offline FakeAgent.")
        }
        for (chunk in listOf("I prepared ", "a card for review.")) {
            delay(80)
            if (requestId in cancelled) return@flow
            emit(AgentEvent.ReplyDelta(chunk))
        }
        if (requestId !in cancelled) { emit(AgentEvent.Request(request)); emit(AgentEvent.ReplyFinished) }
    }
}
