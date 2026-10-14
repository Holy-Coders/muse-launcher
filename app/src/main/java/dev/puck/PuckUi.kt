package dev.puck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.puck.core.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Page { HOME, INPUT, APPS, VOICE, SETTINGS, HISTORY, TASK, DIAGNOSTICS }
private val colors = darkColorScheme(primary = Color(0xFFA8DCD0), onPrimary = Color(0xFF063C32), surface = Color(0xFF080B0E), background = Color(0xFF080B0E), surfaceVariant = Color(0xFF19232B), onSurface = Color(0xFFF0F3F5), onSurfaceVariant = Color(0xFFB7C6CE))

@Composable
fun PuckUi(
    model: PuckViewModel, speech: SpeechInput, page: Page, navigate: (Page) -> Unit,
    capabilities: DeviceCapabilities, chooseContact: (String) -> Unit, makeHome: () -> Unit,
    homeSettings: () -> Unit, systemSettings: () -> Unit, playStore: () -> Unit,
    beginSpeech: () -> Unit, speak: (String) -> Unit, beginMusePairing: () -> Unit,
    systemSpeech: () -> Unit = {}
) {
    var selectedTaskId by rememberSaveable { mutableStateOf("") }
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val voice by model.voiceReview.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val reply by model.reply.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val speakReplies by model.speakReplies.collectAsStateWithLifecycle()
    val usingMuse by model.usingMuse.collectAsStateWithLifecycle()
    val thinking by model.thinking.collectAsStateWithLifecycle()
    val motion by model.motion.collectAsStateWithLifecycle()
    val googleVoice by model.googleVoice.collectAsStateWithLifecycle()
    val homeCheckIns by model.homeCheckIns.collectAsStateWithLifecycle()
    val mood by model.muse.mood.collectAsStateWithLifecycle()
    val dismissed by model.dismissedUpdates.collectAsStateWithLifecycle()
    val read by model.readUpdates.collectAsStateWithLifecycle()
    val time by produceState(initialValue = Date()) { while (true) { value = Date(); delay(30000) } }
    BackHandler(page != Page.HOME) { navigate(Page.HOME) }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().then(if (page == Page.HISTORY) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page != Page.HOME) TextButton(onClick = { navigate(Page.HOME) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Home") }
                    if (notice.isNotBlank()) {
                        Card { Column(Modifier.padding(12.dp)) { Text(notice); TextButton(onClick = { model.notice.value = "" }) { Text("Dismiss") } } }
                    }
                    when (page) {
                        Page.HOME -> {
                            CompanionHome(SimpleDateFormat("HH:mm", Locale.getDefault()).format(time), connection, usingMuse,
                                thinking, speech.listening, mood, motion, tasks, read, ready,
                                review = { task -> selectedTaskId = task.id; model.markRead(task.id); navigate(Page.TASK) },
                                ask = { navigate(Page.INPUT) }, talk = { navigate(Page.VOICE) },
                                apps = { navigate(Page.APPS) }, updates = { navigate(Page.HISTORY) }, settings = { navigate(Page.SETTINGS) },
                                checkIn = { model.interact("I tapped your companion avatar. Give me a brief friendly check-in and one useful next step. Do not perform external actions.") },
                                dismissed = dismissed, dismiss = { model.dismissUpdate(it.id) })
                        }
                        Page.TASK -> {
                            Title("Review task")
                            val task = tasks.firstOrNull { it.id == selectedTaskId } ?: tasks.firstOrNull()
                            if (task == null) Text("No task selected")
                            else {
                                if (googleVoice && (task.action is DeviceAction.Dial || task.action is DeviceAction.Message)) Text(if (task.action is DeviceAction.Dial) "Opens Google Voice. You place the call over Wi-Fi." else "Shares this text to Google Voice. Choose and verify the recipient again there, then tap Send.")
                                TaskCard(task, { model.approve(task.id, capabilities) }, { model.cancel(task.id) }, { chooseContact(task.id) }, { model.resetContact(task.id) }, { model.markDone(task.id) }, { model.answer(task.id, it) })
                            }
                            if (task?.source == "muse" && task.request is WorkspaceRequest.ContentCard) {
                                FullButton("Ask Muse about this", ready && !thinking) { model.respondTo(task); navigate(Page.HOME) }
                                FullButton("Read aloud") { speak((task.request as WorkspaceRequest.ContentCard).text) }
                            } else if (reply.isNotBlank()) { Text(reply); FullButton("Read reply") { speak(reply) } }
                        }
                        Page.INPUT -> {
                            Title("Ask Muse")
                            Text(if (usingMuse) "Clear commands stay on Muse Launcher. Everything else goes to Muse." else "Offline commands: call, text Hello, navigate London, event Lunch, camera, open Settings")
                            OutlinedTextField(value = draft, onValueChange = model::edit, label = { Text("Your request") }, enabled = ready, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                            Text(model.routeLabel(draft), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            FullButton(if (usingMuse) "Send" else "Send to FakeAgent", ready && draft.isNotBlank() && !thinking) { model.submit(); navigate(Page.HOME) }
                            FullButton("Search apps") { navigate(Page.APPS) }
                            if (reply.isNotBlank()) { Text(reply); OutlinedButton(onClick = { speak(reply) }) { Text("Read reply") } }
                        }
                        Page.APPS -> {
                            Title("Apps")
                            var query by remember { mutableStateOf("") }
                            val apps = remember(page) { capabilities.apps() }
                            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val matches = apps.filter { it.label.contains(query, ignoreCase = true) }
                            if (matches.isEmpty()) Text("No launchable apps found")
                            matches.forEach { app -> FullButton(app.label) { model.proposeApp(app); navigate(Page.HOME) } }
                            FullButton("Play Store", action = playStore)
                            FullButton("Android Settings", action = systemSettings)
                        }
                        Page.VOICE -> {
                            Title("Talk")
                            val captureKey by InputDiagnostics.captureTestKey.collectAsStateWithLifecycle()
                            if (captureKey != null) Text("Button capture test: hold to record, release to review. Nothing is sent automatically.")
                            val physicalKey by InputDiagnostics.configuredKey.collectAsStateWithLifecycle()
                            Text(if (physicalKey != null) "Physical hold enabled for this foreground session. Release sends the utterance; calls and messages still need review." else "Assign Muse Launcher Talk to the side-button shortcut. Opens this screen; hold-to-talk is unverified.", style = MaterialTheme.typography.bodyMedium)
                            Text(if (speech.onDeviceAvailable) "On-device speech service detected" else if (speech.available) "System speech may use the network" else "Speech unavailable. Use typing.")
                            if (speech.message.isNotBlank()) Text(speech.message)
                            if (speech.listening) FullButton("Stop listening") { speech.stop() }
                            else FullButton("Start listening", ready && !speech.processing && voice.isBlank() && (speech.onDeviceAvailable || speech.available)) { beginSpeech() }
                            if (speech.available) FullButton("Use system speech", ready && !speech.listening && !speech.processing && voice.isBlank(), action = systemSpeech)
                            OutlinedTextField(value = voice, onValueChange = model::editVoice, label = { Text("Review transcription") }, enabled = !speech.listening && !speech.processing, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                            FullButton("Finish", voice.isNotBlank() && !speech.listening && !speech.processing) { speech.interrupt(); model.finishVoice(); navigate(Page.INPUT) }
                            FullButton("Discard") { speech.interrupt(); model.discardVoice(); navigate(Page.HOME) }
                            FullButton("Type instead") { speech.interrupt(); navigate(Page.INPUT) }
                            Text("Finish copies text to your draft. You still choose Send. Capture never resumes by itself.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Page.SETTINGS -> {
                            Title("Muse Launcher settings")
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = homeCheckIns, onCheckedChange = model::setHomeCheckIns); Text("Muse check-in on Home", Modifier.padding(start = 8.dp)) }
                            Text("An information-only chat when you return, at most once every 15 minutes. Uses your Muse account; pauses while a review or draft is pending.", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = motion, onCheckedChange = model::setMotion); Text("Companion motion", Modifier.padding(start = 8.dp)) }
                            Text("Animation pauses off screen and respects Android animation removal.", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = googleVoice, onCheckedChange = model::setGoogleVoice); Text("Google Voice for calls and messages", Modifier.padding(start = 8.dp)) }
                            Text("Requires a signed-in Voice number and Prefer Wi-Fi and mobile data in Voice. Calls open its dialer. Messages share reviewed text; choose and verify the recipient again in Voice. You place calls and send messages there.", style = MaterialTheme.typography.bodySmall)
                            MuseSettings(model, beginMusePairing)
                            FullButton("Make Muse Launcher HOME", action = makeHome)
                            FullButton("Change HOME", action = homeSettings)
                            Text("To restore the stock launcher, choose it in Android’s Home app settings. Phone, Messages and the lock screen stay managed by Android.")
                            FullButton("Android Settings", action = systemSettings)
                            FullButton("Play Store", action = playStore)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = speakReplies, onCheckedChange = model::setSpeak); Text("Speak replies", Modifier.padding(start = 8.dp)) }
                            Text("Uses an installed offline Android TTS voice. Replies remain visible if no voice is available.")
                            if (InputDiagnostics.available) FullButton("Button diagnostics") { navigate(Page.DIAGNOSTICS) }
                            FullButton("Task history") { navigate(Page.HISTORY) }
                            Text("External screen hardware support is unverified. Layout adapts to the current window; no docking connection is claimed.")
                        }
                        Page.DIAGNOSTICS -> {
                            if (InputDiagnostics.available) ButtonDiagnostics(model, navigate) else Text("Diagnostics are debug-only")
                        }
                        Page.HISTORY -> {
                            Title("Updates & tasks")
                            if (tasks.isEmpty()) Text("Your updates will appear here")
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(tasks, key = { it.id }) { task ->
                                    if (task.source == "muse" && task.id !in read) Text("New from Muse", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    TaskCard(task, { model.approve(task.id, capabilities) }, { model.cancel(task.id) }, { chooseContact(task.id) }, { model.resetContact(task.id) }, { model.markDone(task.id) }, { model.answer(task.id, it) })
                                    if (task.source == "muse") TextButton(onClick = { selectedTaskId = task.id; model.markRead(task.id); navigate(Page.TASK) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read / respond") }
                                }
                                item {
                                    var clearConfirm by remember { mutableStateOf(false) }
                                    FullButton("Clear history") { clearConfirm = true }
                                    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Delete local history?") }, text = { Text("This hides local cards. Request IDs remain to prevent replay. It cannot undo actions in other apps.") }, confirmButton = { TextButton(onClick = { model.clearHistory(); clearConfirm = false }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Keep") } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Title(text: String) { Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium) }
@Composable private fun FullButton(text: String, enabled: Boolean = true, action: () -> Unit) { Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) } }

@Composable
fun TaskCard(task: PuckTask, approve: () -> Unit, cancel: () -> Unit, chooseContact: () -> Unit, resetContact: () -> Unit, done: () -> Unit, answer: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (task.phase) {
                TaskPhase.PREPARING -> "Preparing"
                TaskPhase.AWAITING_APPROVAL -> "Awaiting approval"
                TaskPhase.DISPATCHING -> "Opening app"
                TaskPhase.HANDED_OFF -> "Handed off"
                TaskPhase.COMPLETED -> "Completed"
                TaskPhase.FAILED -> "Failed"
                TaskPhase.CANCELLED -> "Cancelled"
            }, fontWeight = FontWeight.Bold)
            when (val request = task.request) {
                is WorkspaceRequest.Confirmation -> Text(request.action.summary())
                is WorkspaceRequest.ContentCard -> { Text(request.title, fontWeight = FontWeight.Medium); Text(request.text) }
                is WorkspaceRequest.Question -> Text(request.prompt)
                is WorkspaceRequest.TaskStatus -> Text(request.text)
            }
            if (task.userInput.isNotBlank()) Text("Your request: ${task.userInput}", style = MaterialTheme.typography.bodyMedium)
            Text(task.note, style = MaterialTheme.typography.bodyMedium)
            task.agentNote?.let { Text("Agent update: $it", style = MaterialTheme.typography.bodyMedium) }
            if (task.phase == TaskPhase.AWAITING_APPROVAL) {
                val action = task.action
                if (action != null) {
                    if (CommandValidator.needsContact(action)) {
                        FullButton(if (task.contactPicking) "Choosing contact…" else "Choose contact", enabled = !task.contactPicking, action = chooseContact)
                        if (task.contactPicking) Text("If the picker was interrupted, cancel this task and make a new request.")
                    } else {
                        FullButton("Approve & open", action = approve)
                        if (action is DeviceAction.Dial || action is DeviceAction.Message) FullButton("Change contact", action = resetContact)
                    }
                }
                val question = task.request as? WorkspaceRequest.Question
                question?.choices?.forEach { choice -> FullButton(choice) { answer(choice) } }
            }
            if (TaskMachine.canCancel(task)) OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
            if (task.phase == TaskPhase.HANDED_OFF) {
                Text("No Undo available. Muse Launcher cannot cancel work already handed to another app.", style = MaterialTheme.typography.bodyMedium)
                FullButton("I completed it", action = done)
            }
        }
    }
}

@Composable
private fun CompactTaskCard(task: PuckTask, review: () -> Unit) {
    val title = when (val request = task.request) {
        is WorkspaceRequest.Confirmation -> when (request.action) {
            is DeviceAction.Dial -> "Call"
            is DeviceAction.Message -> "Message"
            is DeviceAction.Navigate -> "Maps"
            is DeviceAction.Calendar -> "Calendar"
            DeviceAction.Camera -> "Camera"
            is DeviceAction.OpenApp -> "Open app"
        }
        is WorkspaceRequest.ContentCard -> request.title
        is WorkspaceRequest.Question -> "Question"
        is WorkspaceRequest.TaskStatus -> "Task update"
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (task.phase) {
                    TaskPhase.PREPARING -> "Preparing"
                    TaskPhase.AWAITING_APPROVAL -> "Awaiting approval"
                    TaskPhase.DISPATCHING -> "Opening app"
                    TaskPhase.HANDED_OFF -> "Handed off"
                    TaskPhase.COMPLETED -> "Completed"
                    TaskPhase.FAILED -> "Failed"
                    TaskPhase.CANCELLED -> "Cancelled"
                }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            TextButton(onClick = review, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review") }
        }
    }
}

@Composable
private fun ButtonDiagnostics(model: PuckViewModel, navigate: (Page) -> Unit) {
    val lines by InputDiagnostics.lines.collectAsStateWithLifecycle()
    val collecting by InputDiagnostics.armed.collectAsStateWithLifecycle()
    val pairs by InputDiagnostics.pairs.collectAsStateWithLifecycle()
    val captureKey by InputDiagnostics.captureTestKey.collectAsStateWithLifecycle()
    val configured by InputDiagnostics.configuredKey.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    val context = LocalContext.current
    var releaseOptIn by remember { mutableStateOf(false) }
    Title("Button diagnostics")
    Text("Debug only. Launch metadata and observed key codes; no speech, intent extras, data URIs or text drafts. 120 entries in memory. Printable keys are excluded outside this isolated screen. Do not type here.")
    FullButton(if (collecting) "Stop collecting" else "Collect keys") { InputDiagnostics.setArmed(!collecting) }
    FullButton("Clear diagnostic data") { InputDiagnostics.clear(); releaseOptIn = false }
    FullButton("Copy diagnostics") {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Muse Launcher input diagnostics", lines.joinToString("\n")))
    }
    Text(if (configured == null) "Release-to-send: disabled" else "Hold gate: enabled for observed key $configured in this session only")
    if (captureKey != null) Text("Capture test active for key $captureKey; release never sends.")
    if (configured != null || captureKey != null) FullButton("Disable hold") {
        model.holdInput.disarm(); model.captureTestInput.disarm()
        InputDiagnostics.configuredKey.value = null; InputDiagnostics.captureTestKey.value = null
    }
    Text("Enable after observing at least three physical red-button press/release pairs on this firmware. Foreground only, with on-device speech. Release sends successful final text once; interruption cancels sending. Calls and messages still require review. This opt-in does not mark the full physical checklist complete.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = releaseOptIn, onCheckedChange = { releaseOptIn = it })
        Text("Enable automatic send when I release", Modifier.weight(1f))
    }
    if (pairs.isEmpty()) Text("No eligible down/up pairs observed. Use the launch shortcut and on-screen controls.")
    pairs.forEach { (key, count) ->
        Text("Observed key $key: $count complete pairs")
        FullButton("Test capture with key $key", count >= 3 && !collecting) {
            model.holdInput.disarm(); InputDiagnostics.configuredKey.value = null
            model.captureTestInput.armObservedKey(key)
            InputDiagnostics.captureTestKey.value = key
            navigate(Page.VOICE)
        }
        FullButton("Enable release-to-send for key $key", releaseOptIn && count >= 3 && !collecting) {
            model.captureTestInput.disarm(); InputDiagnostics.captureTestKey.value = null
            model.holdInput.armObservedKey(key)
            InputDiagnostics.configuredKey.value = key
            model.notice.value = "Hold to talk enabled for this process. Release sends successful final text once; calls and messages still need review."
        }
    }
    Text("Recorded events")
    lines.takeLast(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}
