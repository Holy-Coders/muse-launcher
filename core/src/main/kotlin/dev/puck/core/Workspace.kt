package dev.puck.core

import kotlinx.serialization.Serializable

/** Meaning-based workspace API. Version mismatches fail closed, never reinterpret coordinates. */
const val WORKSPACE_VERSION = 1

@Serializable
sealed interface DeviceAction {
    @Serializable data class Dial(val number: String? = null) : DeviceAction
    @Serializable data class Message(val body: String, val number: String? = null) : DeviceAction
    @Serializable data class Navigate(val destination: String) : DeviceAction
    @Serializable data class Calendar(val title: String, val startMillis: Long? = null, val endMillis: Long? = null) : DeviceAction
    @Serializable data object Camera : DeviceAction
    @Serializable data class OpenApp(val packageName: String, val activityName: String) : DeviceAction
}

@Serializable
sealed interface WorkspaceRequest {
    val id: String
    val version: Int
    @Serializable data class Confirmation(override val id: String, val action: DeviceAction, override val version: Int = WORKSPACE_VERSION) : WorkspaceRequest
    @Serializable data class ContentCard(override val id: String, val title: String, val text: String, override val version: Int = WORKSPACE_VERSION) : WorkspaceRequest
    @Serializable data class Question(override val id: String, val prompt: String, val choices: List<String>, override val version: Int = WORKSPACE_VERSION) : WorkspaceRequest
    @Serializable data class TaskStatus(override val id: String, val targetTaskId: String, val text: String, override val version: Int = WORKSPACE_VERSION) : WorkspaceRequest
}

@Serializable
enum class TaskPhase { PREPARING, AWAITING_APPROVAL, DISPATCHING, HANDED_OFF, COMPLETED, FAILED, CANCELLED }

@Serializable
data class PuckTask(
    val id: String,
    val request: WorkspaceRequest,
    val phase: TaskPhase = TaskPhase.PREPARING,
    val note: String = "Preparing",
    val createdAt: Long = 0,
    val contactPicking: Boolean = false,
    val agentNote: String? = null,
    val userInput: String = "",
    val source: String = "local"
) {
    val action: DeviceAction? get() = (request as? WorkspaceRequest.Confirmation)?.action
}

object TaskMachine {
    fun move(task: PuckTask, next: TaskPhase, note: String): PuckTask {
        val allowed = when (task.phase) {
            TaskPhase.PREPARING -> setOf(TaskPhase.AWAITING_APPROVAL, TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.CANCELLED)
            TaskPhase.AWAITING_APPROVAL -> setOf(TaskPhase.DISPATCHING, TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.CANCELLED)
            TaskPhase.DISPATCHING -> setOf(TaskPhase.HANDED_OFF, TaskPhase.FAILED)
            TaskPhase.HANDED_OFF -> setOf(TaskPhase.COMPLETED) // User reports completion; Android editor result is unknown.
            else -> emptySet()
        }
        require(next in allowed) { "Invalid task transition" }
        return task.copy(phase = next, note = note, contactPicking = false)
    }
    /** Agent status is informational; it cannot approve, execute or certify a device handoff. */
    fun agentStatus(task: PuckTask, text: String) = task.copy(agentNote = text)
    fun canCancel(task: PuckTask) = task.phase in setOf(TaskPhase.PREPARING, TaskPhase.AWAITING_APPROVAL)
    fun canDispatch(task: PuckTask) = task.phase == TaskPhase.AWAITING_APPROVAL && task.action != null && !CommandValidator.needsContact(task.action!!) && !task.contactPicking
}

object CommandValidator {
    private val requestId = Regex("[A-Za-z0-9._:-]{1,128}")
    private val phone = Regex("\\+?[0-9 ()-]{3,32}")
    private fun safeText(s: String, max: Int) = s.isNotBlank() && s.length <= max && s.none { it.code < 32 && it != '\n' && it != '\t' }
    fun validate(request: WorkspaceRequest, launchable: Set<Pair<String, String>> = emptySet()): String? {
        if (request.version != WORKSPACE_VERSION) return "Unsupported workspace version"
        if (!requestId.matches(request.id)) return "Invalid request ID"
        return when (request) {
            is WorkspaceRequest.Confirmation -> validateAction(request.action, launchable)
            is WorkspaceRequest.ContentCard -> if (safeText(request.title, 120) && safeText(request.text, 4000)) null else "Invalid card"
            is WorkspaceRequest.Question -> if (safeText(request.prompt, 500) && request.choices.size in 1..8 && request.choices.all { safeText(it, 120) } && request.choices.distinct().size == request.choices.size) null else "Invalid choices"
            is WorkspaceRequest.TaskStatus -> if (requestId.matches(request.targetTaskId) && request.targetTaskId != request.id && safeText(request.text, 500)) null else "Invalid status"
        }
    }
    fun validateAction(action: DeviceAction, launchable: Set<Pair<String, String>> = emptySet()): String? = when (action) {
        is DeviceAction.Dial -> if (action.number == null || phone.matches(action.number) && action.number.count { it.isDigit() } >= 3) null else "Choose a valid telephone number"
        is DeviceAction.Message -> if (action.number != null && (!phone.matches(action.number) || action.number.count { it.isDigit() } < 3)) "Choose a valid recipient" else if (!safeText(action.body, 2000)) "Message must contain 1–2000 characters" else null
        is DeviceAction.Navigate -> if (safeText(action.destination, 500)) null else "Enter a destination"
        is DeviceAction.Calendar -> if (!safeText(action.title, 200)) "Enter an event title" else if ((action.startMillis == null) != (action.endMillis == null) || (action.startMillis != null && (action.startMillis < 0 || action.endMillis!! <= action.startMillis))) "Invalid event time" else null
        DeviceAction.Camera -> null
        is DeviceAction.OpenApp -> if (action.packageName to action.activityName in launchable) null else "App is not launchable"
    }
    fun needsContact(action: DeviceAction) = when (action) {
        is DeviceAction.Dial -> action.number == null
        is DeviceAction.Message -> action.number == null
        else -> false
    }
}

fun DeviceAction.summary(): String = when (this) {
    is DeviceAction.Dial -> "Open dialer${number?.let { " for $it" } ?: " after choosing a contact"}. You place the call."
    is DeviceAction.Message -> "Open message composer${number?.let { " for $it" } ?: " after choosing a contact"}:\n$body\nYou send it."
    is DeviceAction.Navigate -> "Open maps for $destination. Choose a route there."
    is DeviceAction.Calendar -> "Open calendar editor: $title. Set or review the time and save there."
    DeviceAction.Camera -> "Open camera. Take and save a photo there."
    is DeviceAction.OpenApp -> "Open $packageName"
}

sealed interface CapabilityResult {
    data object HandedOff : CapabilityResult
    data object PermissionDenied : CapabilityResult
    data object Unavailable : CapabilityResult
    data object Invalid : CapabilityResult
}
fun CapabilityResult.note() = when (this) {
    CapabilityResult.HandedOff -> "Handed off. Completion in the other app is unknown."
    CapabilityResult.PermissionDenied -> "Permission denied. Nothing was executed."
    CapabilityResult.Unavailable -> "No compatible app is available."
    CapabilityResult.Invalid -> "Action failed local validation."
}

/** In-memory reference ledger used in domain tests; production uses durable Room CAS. */
class RequestLedger {
    private val tasks = mutableMapOf<String, PuckTask>()
    fun accept(task: PuckTask): Boolean = if (tasks.containsKey(task.id)) false else { tasks[task.id] = task; true }
    fun get(id: String) = tasks[id]
    fun cancel(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (!TaskMachine.canCancel(task)) return false
        tasks[id] = TaskMachine.move(task, TaskPhase.CANCELLED, "Cancelled before handoff")
        return true
    }
    fun claim(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (!TaskMachine.canDispatch(task)) return false
        tasks[id] = TaskMachine.move(task, TaskPhase.DISPATCHING, "Opening app")
        return true
    }
    fun finish(id: String, result: CapabilityResult) {
        val task = tasks[id] ?: return
        tasks[id] = TaskMachine.move(task, if (result == CapabilityResult.HandedOff) TaskPhase.HANDED_OFF else TaskPhase.FAILED, result.note())
    }
}
