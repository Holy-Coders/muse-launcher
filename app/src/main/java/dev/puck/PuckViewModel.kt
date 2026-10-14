package dev.puck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.puck.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PuckViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = TaskRepository(PuckDatabase.get(application).tasks())
    private val drafts = DraftStore(application)
    private val appCommands = DeviceCapabilities.launchableApps(application).groupBy { it.label.lowercase() }
        .filterValues { it.size == 1 }.mapValues { (_, apps) -> DeviceAction.OpenApp(apps.single().packageName, apps.single().activityName) }
    private val fake: AgentAdapter = FakeAgent(appCommands)
    val muse = MuseRuntime.get(application)
    private val museAgent = AndroidMuseAgent(muse)
    val usingMuse = MutableStateFlow(muse.vault.enabled)
    private val selected = MutableStateFlow(if (usingMuse.value) museAgent else fake)
    private val agent: AgentAdapter get() = selected.value
    val captureTestInput = HoldToTalk(reviewOnly = true) { "capture-test-${UUID.randomUUID()}" }
    val holdInput = HoldToTalk { "voice-${UUID.randomUUID()}" }
    private val mutation = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private var foregroundOwner: Any? = null
    private var foregroundOpen: ((String) -> Unit)? = null
    fun bindForeground(owner: Any, open: (String) -> Unit) { foregroundOwner = owner; foregroundOpen = open }
    fun unbindForeground(owner: Any) { if (foregroundOwner === owner) { foregroundOwner = null; foregroundOpen = null } }
    private var persistingTypedInput = false
    val thinking = MutableStateFlow(false)
    val tasks = repo.tasks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connection = selected.flatMapLatest { it.connection }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionStatus.Disconnected)
    val draft = MutableStateFlow("")
    val voiceReview = MutableStateFlow("")
    val speakReplies = drafts.speak.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val reply = MutableStateFlow("")
    val motion = drafts.motion.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dismissedUpdates = drafts.dismissedUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val readUpdates = drafts.readUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val googleVoice = drafts.googleVoice.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setGoogleVoice(value: Boolean) { viewModelScope.launch { drafts.setGoogleVoice(value) } }
    val homeCheckIns = drafts.homeCheckIns.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var claimingCheckIn = false
    fun setHomeCheckIns(value: Boolean) { viewModelScope.launch { drafts.setHomeCheckIns(value) } }
    private fun canCheckIn(eligible: () -> Boolean) = eligible() && ready.value && usingMuse.value && !thinking.value &&
        connection.value == ConnectionStatus.MuseConnected && draft.value.isBlank() && voiceReview.value.isBlank() &&
        tasks.value.none { it.phase in setOf(TaskPhase.PREPARING, TaskPhase.AWAITING_APPROVAL, TaskPhase.DISPATCHING) }
    fun homeEntered(eligible: () -> Boolean) {
        if (claimingCheckIn || !canCheckIn(eligible)) return
        claimingCheckIn = true
        viewModelScope.launch {
            try {
                if (drafts.claimHomeCheckIn(System.currentTimeMillis()) && canCheckIn(eligible)) {
                    send(UUID.randomUUID().toString(), "I returned to Muse Launcher Home. Give a brief useful check-in. If you have no fresh information, say so; do not invent updates.", forceMuse = true, informationOnly = true)
                }
            } finally { claimingCheckIn = false }
        }
    }
    // Diagnostic native delivery remains independently testable.
    internal fun interactNative(text: String) {
        if (ready.value && !thinking.value) send(UUID.randomUUID().toString(), text, forceMuse = true, nativeTools = true)
    }
    fun setMotion(value: Boolean) { viewModelScope.launch { drafts.setMotion(value) } }
    fun dismissUpdate(id: String) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.source != "muse" || task.phase != TaskPhase.COMPLETED || task.request !is WorkspaceRequest.ContentCard) return
        viewModelScope.launch { drafts.dismissUpdate(id) }
    }
    fun markRead(id: String) { viewModelScope.launch { drafts.markRead(id) } }
    fun routeLabel(text: String) = if (InputRouter.isLocal(text, appCommands.keys)) "Local command" else if (usingMuse.value) "Ask Muse" else "Offline demo"
    fun interact(text: String) {
        if (!ready.value || thinking.value) return
        send(UUID.randomUUID().toString(), text.trim().take(4000), forceMuse = true)
    }
    fun respondTo(task: PuckTask) {
        markRead(task.id)
        val card = task.request as? WorkspaceRequest.ContentCard ?: return
        interact("About this update from Muse: ${card.title}\n${card.text}\nWhat should I do next? Do not perform external actions without asking.")
    }
    val ready = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    private val inputWrites = Channel<String>(Channel.UNLIMITED)
    private val voiceWrites = Channel<String>(Channel.UNLIMITED)
    val spokenReplies = Channel<String>(Channel.BUFFERED)
    init {
        viewModelScope.launch {
            museAgent.events.collect { event ->
                if (usingMuse.value && event is AgentEvent.Request) receive(event.request)
            }
        }
        viewModelScope.launch {
            draft.value = drafts.input.first()
            voiceReview.value = drafts.voiceReview.first()
            repo.recoverInterrupted()
            agent.connect()
            ready.value = true
            for (text in inputWrites) drafts.setInput(text)
        }
        viewModelScope.launch { for (text in voiceWrites) drafts.setVoice(text) }
    }
    fun useMuse(value: Boolean) {
        viewModelScope.launch {
            if (jobs.isNotEmpty()) {
                notice.value = "Finish or cancel the active request before switching agents."
                return@launch
            }
            agent.disconnect()
            muse.vault.enabled = value
            usingMuse.value = value
            selected.value = if (value) museAgent else fake
            agent.connect()
        }
    }
    fun forgetMuse() {
        viewModelScope.launch {
            if (jobs.isNotEmpty()) { notice.value = "Finish or cancel the active request before forgetting Muse."; return@launch }
            agent.disconnect()
            muse.vault.enabled = false
            usingMuse.value = false
            selected.value = fake
            muse.forget()
            fake.connect()
        }
    }
    fun edit(text: String) { if (ready.value) { draft.value = text.take(4000); inputWrites.trySend(draft.value) } }
    fun editVoice(text: String) { voiceReview.value = text.take(4000); voiceWrites.trySend(voiceReview.value) }
    fun finishVoice() { edit(voiceReview.value); editVoice("") }
    fun discardVoice() = editVoice("")
    fun setSpeak(value: Boolean) { viewModelScope.launch { drafts.setSpeak(value) } }
    fun submit() {
        val original = draft.value
        val input = original.trim()
        if (!ready.value || input.isEmpty() || persistingTypedInput || thinking.value) return
        persistingTypedInput = true
        val id = UUID.randomUUID().toString()
        send(id, input, persisted = {
            if (draft.value == original) edit("")
            persistingTypedInput = false
        }, finished = { persistingTypedInput = false })
    }
    fun submitVoiceUtterance(id: String, text: String) {
        val input = text.trim().take(4000)
        if (!ready.value || input.isEmpty() || thinking.value) return
        val original = voiceReview.value
        // Same agent path as typing; preserve the separate typed draft and persist before clearing review.
        send(id, input, persisted = { if (voiceReview.value == original) editVoice("") })
    }
    private fun send(id: String, input: String, persisted: () -> Unit = {}, finished: () -> Unit = {}, forceMuse: Boolean = false, informationOnly: Boolean = false, nativeTools: Boolean = false) {
        val local = !forceMuse && InputRouter.isLocal(input, appCommands.keys)
        val sender = if (local) fake else agent
        val requestOwner = foregroundOwner
        thinking.value = true
        reply.value = ""
        jobs[id] = viewModelScope.launch {
            try {
                val initial = PuckTask(id, WorkspaceRequest.ContentCard(id, "Working", "Preparing request"), createdAt = System.currentTimeMillis(), userInput = input, source = if (local || !usingMuse.value) "local" else "muse")
                if (!repo.insert(initial)) return@launch
                persisted()
                (if (sender === museAgent) museAgent.sendReply(id, input, informationOnly, nativeTools) else sender.sendInput(id, input)).collect { event -> when (event) {
                    is AgentEvent.ReplyDelta -> reply.value = (reply.value + event.text).take(4000)
                    is AgentEvent.Request -> receive(event.request, if (local || !usingMuse.value) "local" else "muse",
                        if (!nativeTools && !informationOnly && event.request.id == id) requestOwner else null)
                    AgentEvent.ReplyFinished -> if (speakReplies.value) spokenReplies.send(reply.value)
                } }
            } catch (_: kotlinx.coroutines.CancellationException) { /* User cancellation is persisted first. */ }
              catch (_: Exception) { notice.value = "Request failed. Unsubmitted drafts are kept."; fail(id, "Request failed. Try again.") }
            finally { finished(); jobs.remove(id); thinking.value = jobs.isNotEmpty() }
        }
    }
    private suspend fun receive(request: WorkspaceRequest, source: String = "muse", immediateOwner: Any? = null) {
        // No Android dependency in the adapter. Agent OpenApp proposals must later pass local allowlist validation.
        var invalid = CommandValidator.validate(request, DeviceCapabilities.launchableApps(getApplication()).map { it.packageName to it.activityName }.toSet())
        if (request is WorkspaceRequest.TaskStatus && repo.get(request.targetTaskId) == null) invalid = "Status references an unknown task"
        val existing = repo.get(request.id)
        val initial = existing ?: PuckTask(request.id, request, createdAt = System.currentTimeMillis(), source = source).also { if (!repo.insert(it)) return }
        if (initial.phase != TaskPhase.PREPARING) return
        val accepted = repo.update(request.id) { task ->
            if (task.phase != TaskPhase.PREPARING) null else {
                val prepared = task.copy(request = request)
                if (invalid != null) TaskMachine.move(prepared, TaskPhase.FAILED, invalid)
                else when (request) {
                    is WorkspaceRequest.Confirmation -> TaskMachine.move(prepared, TaskPhase.AWAITING_APPROVAL, "Review before opening another app")
                    is WorkspaceRequest.Question -> TaskMachine.move(prepared, TaskPhase.AWAITING_APPROVAL, "Choose an answer")
                    is WorkspaceRequest.ContentCard -> TaskMachine.move(prepared, TaskPhase.COMPLETED, "Information only")
                    is WorkspaceRequest.TaskStatus -> TaskMachine.move(prepared, TaskPhase.COMPLETED, "Agent status; no device action")
                }
            }
        }
        if (accepted != null && invalid == null && accepted.action?.let { ActionReview.mayOpenImmediately(it, immediateOwner != null) } == true && immediateOwner === foregroundOwner) {
            foregroundOpen?.invoke(request.id)
        }
        if (accepted != null && invalid == null && request is WorkspaceRequest.TaskStatus) {
            repo.update(request.targetTaskId) { TaskMachine.agentStatus(it, request.text) }
        }
    }
    fun proposeApp(app: LaunchableApp) {
        if (!ready.value) return
        val owner = foregroundOwner
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            receive(WorkspaceRequest.Confirmation(id, DeviceAction.OpenApp(app.packageName, app.activityName)), "local", owner)
        }
    }
    fun cancel(id: String) { viewModelScope.launch { mutation.withLock {
        val task = repo.update(id) { if (TaskMachine.canCancel(it)) TaskMachine.move(it, TaskPhase.CANCELLED, "Cancelled before handoff") else null }
        if (task != null) { jobs.remove(id)?.cancel(); fake.cancel(id); agent.cancel(id)
                if (usingMuse.value) museAgent.report(task) }
    } } }
    fun approve(id: String, capabilities: DeviceCapabilities) = dispatch(id, capabilities)
    fun openRequested(id: String, capabilities: DeviceCapabilities, eligible: () -> Boolean) = dispatch(id, capabilities, eligible)
    private fun dispatch(id: String, capabilities: DeviceCapabilities, immediateEligibility: (() -> Boolean)? = null) { viewModelScope.launch { mutation.withLock {
        val current = repo.get(id) ?: return@withLock
        val action = current.action ?: return@withLock
        if (immediateEligibility != null && (!immediateEligibility() || !ActionReview.mayOpenImmediately(action, true))) return@withLock
        if (!TaskMachine.canDispatch(current)) return@withLock
        val invalid = CommandValidator.validate(current.request, capabilities.allowlist())
        if (invalid != null || CommandValidator.needsContact(action)) { notice.value = invalid ?: "Choose a contact first"; return@withLock }
        val claimed = repo.update(id) { if (TaskMachine.canDispatch(it)) TaskMachine.move(it, TaskPhase.DISPATCHING, "Opening app; will not replay") else null } ?: return@withLock
        // The durable claim precedes every manual or immediate handoff. Never automatically replay DISPATCHING.
        val result = capabilities.open(claimed.action!!, googleVoice.value)
        val updated = repo.update(id) { TaskMachine.move(it, if (result == CapabilityResult.HandedOff) TaskPhase.HANDED_OFF else TaskPhase.FAILED, result.note()) }
        updated?.let { agent.report(it) }
    } } }
    suspend fun beginContact(id: String): Boolean = mutation.withLock {
        if (repo.pendingContact() != null) return@withLock false
        repo.update(id) {
            if (it.phase == TaskPhase.AWAITING_APPROVAL && it.action?.let(CommandValidator::needsContact) == true && !it.contactPicking) it.copy(contactPicking = true, note = "Choose one telephone number in Contacts") else null
        } != null
    }
    fun contactPicked(number: String?, denied: Boolean = false) { viewModelScope.launch { mutation.withLock {
        val task = repo.pendingContact() ?: return@withLock
        repo.update(task.id) {
            if (!it.contactPicking || it.phase != TaskPhase.AWAITING_APPROVAL) null
            else if (number == null) it.copy(contactPicking = false, note = if (denied) "Contact access denied. Try again or cancel." else "No contact selected. Try again or cancel.")
            else {
                val action = when (val old = it.action) {
                    is DeviceAction.Dial -> old.copy(number = number)
                    is DeviceAction.Message -> old.copy(number = number)
                    else -> return@update null
                }
                val invalid = CommandValidator.validateAction(action)
                if (invalid != null) it.copy(contactPicking = false, note = invalid)
                else it.copy(request = WorkspaceRequest.Confirmation(it.id, action), contactPicking = false, note = "Review the selected number before opening")
            }
        }
    } } }
    fun resetContact(id: String) { viewModelScope.launch { repo.update(id) {
        if (it.phase != TaskPhase.AWAITING_APPROVAL) null else when (val action = it.action) {
            is DeviceAction.Dial -> it.copy(request = WorkspaceRequest.Confirmation(id, action.copy(number = null)), contactPicking = false)
            is DeviceAction.Message -> it.copy(request = WorkspaceRequest.Confirmation(id, action.copy(number = null)), contactPicking = false)
            else -> null
        }
    } } }
    fun answer(id: String, choice: String) { viewModelScope.launch {
        val task = repo.update(id) {
            val question = it.request as? WorkspaceRequest.Question
            if (it.phase == TaskPhase.AWAITING_APPROVAL && question != null && choice in question.choices) TaskMachine.move(it, TaskPhase.COMPLETED, "Answered: $choice") else null
        }
        if (task != null) {
            val owner = foregroundOwner
            (if (task.source == "local" || !usingMuse.value) fake else museAgent).answer(id, if (usingMuse.value && task.source == "muse") "Question: ${(task.request as WorkspaceRequest.Question).prompt}\nSelected answer: $choice" else choice).collect { event ->
                if (event is AgentEvent.Request) receive(event.request, task.source, owner)
            }
        }
    } }
    fun markDone(id: String) { viewModelScope.launch { val updated = repo.update(id) {
        if (it.phase == TaskPhase.HANDED_OFF) TaskMachine.move(it, TaskPhase.COMPLETED, "Marked completed by you; not verified by Android") else null
    }; updated?.let { agent.report(it) } } }
    private suspend fun fail(id: String, text: String) { repo.update(id) {
        if (it.phase in setOf(TaskPhase.PREPARING, TaskPhase.AWAITING_APPROVAL)) TaskMachine.move(it, TaskPhase.FAILED, text) else null
    } }
    fun clearHistory() { viewModelScope.launch { mutation.withLock {
        if (tasks.value.any { it.phase in setOf(TaskPhase.PREPARING, TaskPhase.AWAITING_APPROVAL, TaskPhase.DISPATCHING) }) notice.value = "Finish or cancel pending tasks first"
        else repo.clear()
    } } }
}
