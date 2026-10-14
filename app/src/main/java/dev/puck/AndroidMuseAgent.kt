package dev.puck

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import dev.puck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Platform implementation stays behind the same AgentAdapter as FakeAgent. */
class AndroidMuseAgent(private val runtime: MuseRuntime) : AgentAdapter {
    override val connection = runtime.connection
    override val events = runtime.requests.receiveAsFlow().map { AgentEvent.Request(it) }
    override suspend fun connect() { runtime.connect() }
    override suspend fun disconnect() { runtime.disconnect() }
    override fun sendInput(requestId: String, text: String) = sendReply(requestId, text)
    fun sendReply(requestId: String, text: String, informationOnly: Boolean = false, nativeTools: Boolean = false): Flow<AgentEvent> = flow {
        val result = withContext(Dispatchers.IO) { runtime.send(requestId, text, if (nativeTools) "native" else if (informationOnly) "checkin" else "structured") }
        check(result.optBoolean("ok")) { "Muse request failed" }
        val reply = result.optString("text")
        check(reply.isNotBlank())
        val decoded = if (nativeTools) null else runCatching { MuseReply.decode(requestId, reply, runtime.replyApps(), informationOnly) }.getOrNull()
        val request = decoded?.request ?: WorkspaceRequest.ContentCard(requestId, "Muse reply",
            if (!nativeTools && (reply.trimStart().startsWith("{") || reply.trimStart().startsWith("```"))) "Muse returned an unsupported reply. Ask again; no action was proposed." else reply.take(4000))
        decoded?.mood?.let { runtime.mood.value = it }
        val readable = when (request) {
            is WorkspaceRequest.ContentCard -> request.text
            is WorkspaceRequest.Question -> request.prompt
            is WorkspaceRequest.Confirmation -> request.action.summary()
            is WorkspaceRequest.TaskStatus -> request.text
        }
        emit(AgentEvent.ReplyDelta(readable))
        emit(AgentEvent.Request(request))
        emit(AgentEvent.ReplyFinished)
    }.onCompletion { cause -> if (cause is CancellationException) runtime.cancel(requestId) }
    override fun answer(requestId: String, answer: String) = sendInput("$requestId-answer-${java.util.UUID.randomUUID()}", answer)
    // A local handoff is never reported to Muse as a completed external action.
    override suspend fun report(task: PuckTask) { runtime.report(task) }
    override suspend fun cancel(requestId: String) { runtime.cancel(requestId) }
}

class MuseRuntime private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: MuseRuntime? = null
        fun get(context: Context): MuseRuntime = instance ?: synchronized(this) {
            instance ?: MuseRuntime(context.applicationContext).also { instance = it }
        }
    }
    val vault = MuseVault(context)
    val connection = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val setupStatus = MutableStateFlow("Muse is not paired with Muse Launcher")
    val pairingOpen = MutableStateFlow(false)
    val mood = MutableStateFlow("idle")
    val requests = Channel<WorkspaceRequest>(32)
    val applianceUpdates = MutableSharedFlow<WorkspaceRequest.ContentCard>(extraBufferCapacity = 32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packets = Channel<ByteArray>(128)
    @Volatile var bluetooth: MusePairingService? = null
    private val decoder = Json { ignoreUnknownKeys = false }
    private val python: PyObject by lazy {
        synchronized(Python::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        }
        Python.getInstance().getModule("puck_muse").callAttr("Runtime", this, vault.mac)
    }
    init {
        scope.launch {
            for (value in packets) {
                try { python.callAttr("packet", android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)) }
                catch (_: Exception) { setupStatus.value = "Pairing packet rejected"; disconnectPeer() }
            }
        }
    }
    fun saveSdk(token: String) {
        scope.launch {
            try {
                val clean = token.trim()
                check(python.callAttr("validate_sdk", clean).toBoolean())
                check(vault.save("sdk", clean))
                setupStatus.value = "SDK token saved securely on this phone"
            } catch (_: Exception) { setupStatus.value = "Could not save SDK token. Copy it again." }
        }
    }
    fun startPairing() {
        try {
            check(vault.read("sdk") != null) { "SDK token required" }
            val intent = Intent(context, MusePairingService::class.java).setAction(MusePairingService.PAIR)
            context.startForegroundService(intent)
        } catch (_: Exception) { setupStatus.value = "Save an SDK token and grant Bluetooth access before pairing." }
    }
    fun stopPairing() { bluetooth?.stopPairing() }
    fun activatePairing() {
        scope.launch {
            try {
                python.callAttr("start_pairing", vault.read("sdk") ?: error("SDK token required"))
                bluetooth?.advertise()
            } catch (_: Exception) {
                setupStatus.value = "Pairing could not start. Check SDK token and Bluetooth."
                bluetooth?.stopPairing()
            }
        }
    }
    fun packet(value: ByteArray) {
        if (!packets.trySend(value).isSuccess) disconnectPeer()
    }
    fun peerDisconnected() { scope.launch { try { python.callAttr("peer_disconnected") } catch (_: Exception) {} } }
    fun pairingStopped() {
        scope.launch { try { python.callAttr("stop_pairing") } catch (_: Exception) {} }
        pairingOpen.value = false
        if (setupStatus.value.startsWith("Pairing open:")) setupStatus.value = "Pairing window closed. Start again when ready."
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun restoreBluetoothNameIfIdle() {
        if (bluetooth != null) return
        if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val name = vault.originalBluetoothName() ?: return
        try {
            if (context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.setName(name) == true) vault.clearedBluetoothName()
        } catch (_: Exception) { /* Retain the original name for another restoration attempt. */ }
    }
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                val credentials = vault.read("credentials")
                if (credentials == null) { connection.value = ConnectionStatus.Blocked("Pair Muse with Muse Launcher first"); return@withContext }
                python.callAttr("connect", credentials)
            } catch (_: Exception) { connection.value = ConnectionStatus.Blocked("Muse credentials unavailable. Pair again.") }
        }
    }
    fun reconnect() { scope.launch { connect() } }
    fun disconnect() { scope.launch { try { python.callAttr("disconnect") } catch (_: Exception) {}; connection.value = ConnectionStatus.Disconnected } }
    fun forget() { disconnect(); stopPairing(); vault.forgetSecrets(); setupStatus.value = "Secrets removed from this phone. Remove Muse Launcher in Muse Devices too." }
    internal fun linkStats(): String = python.callAttr("link_stats").toString()
    val turnProgress=MutableSharedFlow<Pair<String,String>>(extraBufferCapacity=32)
    fun onTurnProgress(id:String,phase:String) {
        if(phase in setOf("uploading","thinking","replying"))turnProgress.tryEmit(id to phase)
    }
    fun send(id: String, text: String, mode: String = "native", image: String = "", audio: String = "", video: String = ""): JSONObject = JSONObject(python.callAttr("send", id, text, vault.chatId,
        when (mode) {
            "native" -> ""
            "appliance" -> MuseReply.applianceInstruction()+"\nInstalled app labels: "+replyApps().keys.sorted().joinToString(", ").take(2000)
            "portrait" -> dev.puck.appliance.MusePortrait.INSTRUCTION
            else -> MuseReply.instruction(mode == "checkin")
        }, image, audio, video).toString())
    fun replyApps() = appCommands()
    fun cancel(id: String) { scope.launch { try { python.callAttr("cancel", id) } catch (_: Exception) {} } }
    // Public bridge callbacks; none log credentials, transcripts or command payloads.
    fun onState(state: String, message: String) {
        connection.value = when (state) {
            "connected" -> ConnectionStatus.MuseConnected
            "connecting" -> ConnectionStatus.Connecting
            "blocked" -> ConnectionStatus.Blocked(message)
            else -> ConnectionStatus.Disconnected
        }
        setupStatus.value = message
    }
    fun onRequestFailed(message: String) { setupStatus.value = message.take(200) }
    fun saveCredentials(value: String): Boolean = try { vault.save("credentials", value) } catch (_: Exception) { false }
    fun clearCredentials() { vault.forgetCredentials() }
    fun onPaired() {
        setupStatus.value = "Muse paired with Muse Launcher. Choose Use Muse to connect."
        stopPairing()
    }
    private fun appCommands():Map<String,DeviceAction.OpenApp> {
        val apps=DeviceCapabilities.launchableApps(context)
        val commands=apps.groupBy {it.label.lowercase()}.filterValues {it.size==1}
            .mapValues {(_,matches)->DeviceAction.OpenApp(matches.single().packageName,matches.single().activityName)}.toMutableMap()
        apps.singleOrNull {it.packageName=="com.google.android.apps.googlevoice"}?.let {
            commands.putIfAbsent("google voice",DeviceAction.OpenApp(it.packageName,it.activityName))
        }
        return commands
    }
    fun onUpdate(value: String): Boolean = try {
        val update = decoder.decodeFromString<MuseUpdate>(value)
        val card = update.workspace()
        onWorkspace(Json.encodeToString(WorkspaceRequest.serializer(), card)).also { accepted -> if (accepted) mood.value = update.mood }
    } catch (_: Exception) { false }
    fun onCommand(value: String): Boolean = try {
        val command = decoder.decodeFromString<MuseCommand>(value).workspace(appCommands())
        onWorkspace(Json.encodeToString(WorkspaceRequest.serializer(), command))
    } catch (_: Exception) { false }
    fun report(task: PuckTask) {
        if (!vault.enabled) return
        val event = JSONObject().put("id", task.id).put("phase", task.phase.name)
            .put("note", task.note).put("action", task.action?.summary() ?: "Information only")
            .put("external_completion_verified", false)
        scope.launch {
            try { python.callAttr("notify", event.toString(), vault.chatId) }
            catch (_: Exception) { setupStatus.value = "Muse event was not sent. It will not be replayed." }
        }
    }
    fun onWorkspace(value: String): Boolean {
        if (!vault.enabled) return false
        return try {
        val request = decoder.decodeFromString<WorkspaceRequest>(value)
        val allowlist = DeviceCapabilities.launchableApps(context).map { it.packageName to it.activityName }.toSet()
        if (CommandValidator.validate(request, allowlist) != null) false else {
            if (request is WorkspaceRequest.ContentCard) applianceUpdates.tryEmit(request)
            requests.trySend(request).isSuccess
        }
        } catch (_: Exception) { false }
    }
    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    fun getMtu() = bluetooth?.mtu ?: 23
    fun sendPacket(value: String): Boolean = bluetooth?.sendPacket(android.util.Base64.decode(value, android.util.Base64.NO_WRAP)) ?: false
    fun disconnectPeer() { bluetooth?.disconnectPeer() }
}
