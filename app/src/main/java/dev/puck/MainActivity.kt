package dev.puck

import android.Manifest
import android.app.role.RoleManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import android.view.KeyEvent
import android.view.Window
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.puck.core.CapabilityResult
import dev.puck.core.HoldEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_VOICE_ENTRY = "dev.puck.OPEN_VOICE"
        const val VOICE_ENTRY_COMPONENT = "dev.puck.VoiceEntry"
        fun isVoiceEntry(intent: Intent) = (intent.component?.className == VOICE_ENTRY_COMPONENT && !intent.hasCategory(Intent.CATEGORY_HOME)) || intent.action == ACTION_VOICE_ENTRY
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) interruptVoice("Screen off. Capture stopped; draft kept.") }
    }
    private val model: PuckViewModel by viewModels()
    private lateinit var capabilities: DeviceCapabilities
    private lateinit var speech: SpeechInput
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var foregroundSession: Any? = null
    private var page by mutableStateOf(Page.HOME)
    private var speechConsent by mutableStateOf(false)
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permission callback never resumes capture after leaving the foreground.
        model.notice.value = if (granted) "Microphone permitted. Tap Start listening when ready." else "Microphone denied. Typing always works."
    }
    private val contact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
        var number: String? = null
        var denied = false
        if (uri != null && uri.scheme == "content" && uri.authority == ContactsContract.AUTHORITY) {
            try {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) number = cursor.getString(0)
                }
            } catch (_: SecurityException) { denied = true }
              catch (_: Exception) { model.notice.value = "Could not read the selected number" }
        }
        model.contactPicked(number, denied)
    }
    private val home = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* System owns consent. */ }
    private val museBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.muse.setupStatus.value = "Bluetooth permission updated. Tap Start Muse pairing again when ready."
    }
    private fun beginMusePairing() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) museBluetooth.launch(permissions)
        else model.muse.startPairing()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InputDiagnostics.recordLaunch(intent, "onCreate")
        capabilities = DeviceCapabilities(this)
        speech = SpeechInput(this, model::editVoice)
        model.muse.restoreBluetoothNameIfIdle()
        // Observe the public Window callback and preserve Android/Compose's normal key dispatch.
        val nativeCallback = window.callback
        window.callback = object : Window.Callback by nativeCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                if (handleHardwareKey(event)) true else nativeCallback.dispatchKeyEvent(event)
        }
        page = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        if (savedInstanceState == null && isVoiceEntry(intent)) page = Page.VOICE
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Audio-mode monitoring needs no Phone/private-call permission. Ring/call modes cancel capture.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    if ((speech.listening || speech.processing) && getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) interruptVoice("Call/audio interruption. Capture stopped; draft kept.")
                    delay(200)
                }
            }
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = tts?.setLanguage(Locale.getDefault())?.let { it >= 0 } == true
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                for (reply in model.spokenReplies) speak(reply)
            }
        }
        setContent {
            val ready by model.ready.collectAsState()
            val connection by model.connection.collectAsState()
            DisposableEffect(page, ready, connection) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && page == Page.HOME) model.homeEntered { foregroundEligible() && page == Page.HOME && !speech.listening && !speech.processing }
                }
                lifecycle.addObserver(observer)
                if (page == Page.HOME) model.homeEntered { foregroundEligible() && page == Page.HOME && !speech.listening && !speech.processing }
                onDispose { lifecycle.removeObserver(observer) }
            }
            PuckUi(model, speech, page, { next ->
                if (page == Page.VOICE && next != Page.VOICE) interruptVoice()
                if (next == Page.DIAGNOSTICS) { model.holdInput.disarm(); model.captureTestInput.disarm(); InputDiagnostics.configuredKey.value = null; InputDiagnostics.captureTestKey.value = null }
                page = next
            }, capabilities, ::chooseContact, ::makeHome, ::homeSettings, ::systemSettings, ::playStore,
                { if (speech.onDeviceAvailable) beginSpeech(false) else speechConsent = true }, ::speak, ::beginMusePairing, { speechConsent = true })
            if (speechConsent) androidx.compose.material3.AlertDialog(
                onDismissRequest = { speechConsent = false },
                title = { androidx.compose.material3.Text("Network speech?") },
                text = { androidx.compose.material3.Text("The system recognizer may send audio to its provider. Use it for this recording? Typing remains available.") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { speechConsent = false; beginSpeech(true) }) { androidx.compose.material3.Text("Use system speech") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { speechConsent = false; page = Page.INPUT }) { androidx.compose.material3.Text("Type") } }
            )
        }
    }
    private fun beginSpeech(networkAllowed: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) {
            model.notice.value = "Speech requires an unlocked, foreground phone without a call/audio interruption."
            return
        }
        model.holdInput.interrupt()
        model.captureTestInput.interrupt()
        speech.onFinalResult = null
        speech.onFailure = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start(networkAllowed)
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun chooseContact(id: String) { lifecycleScope.launch {
        if (!model.beginContact(id)) return@launch
        try { contact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
        catch (_: android.content.ActivityNotFoundException) { model.contactPicked(null); model.notice.value = "No contact picker installed. Cancel this task or install Contacts." }
        catch (_: SecurityException) { model.contactPicked(null, true) }
    } }
    private fun makeHome() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try { home.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            catch (_: Exception) { homeSettings() }
        } else homeSettings()
    }
    private fun homeSettings() = openSystem(Intent(Settings.ACTION_HOME_SETTINGS))
    private fun systemSettings() = openSystem(Intent(Settings.ACTION_SETTINGS))
    private fun playStore() {
        val intent = packageManager.getLaunchIntentForPackage("com.android.vending")
        if (intent == null) model.notice.value = "Play Store is not installed or available"
        else openSystem(intent)
    }
    private fun openSystem(intent: Intent) {
        val result = capabilities.launch(intent)
        if (result != CapabilityResult.HandedOff) model.notice.value = "This system screen is unavailable"
    }
    private fun speak(text: String) {
        if (!ttsReady) { model.notice.value = "Text-to-speech unavailable. Replies remain visible."; return }
        // Avoid network TTS voices. A local voice must exist before speaking private replies.
        val localVoice = tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
        if (localVoice == null) { model.notice.value = "No offline TTS voice installed. Use Android speech settings."; return }
        tts?.voice = localVoice
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "puck-reply")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        InputDiagnostics.recordLaunch(intent, "onNewIntent")
        val voiceEntry = isVoiceEntry(intent)
        // Keep the original activity intent stable; route this new entry directly and save page state separately.
        if (voiceEntry) page = Page.VOICE // Launching alone never starts the microphone.
        else { interruptVoice(); page = Page.HOME }
    }
    private fun foregroundEligible() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(PowerManager::class.java).isInteractive &&
        getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL
    private fun handleHardwareKey(event: KeyEvent): Boolean {
        InputDiagnostics.recordKey(event, page == Page.DIAGNOSTICS)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val permitted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val eligible = foregroundEligible() && permitted && speech.onDeviceAvailable && model.ready.value &&
            (event.action == KeyEvent.ACTION_UP || (!speech.listening && !speech.processing && model.voiceReview.value.isBlank()))
        val captureTest = InputDiagnostics.available && InputDiagnostics.captureTestKey.value == event.keyCode
        val controller = if (captureTest) model.captureTestInput else model.holdInput
        val effect = controller.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, eligible)
        if (effect == HoldEffect.Ignore) return false
        if (event.action == KeyEvent.ACTION_UP && !eligible) interruptVoice("Capture no longer eligible. Draft kept; not submitted.")
        if (effect == HoldEffect.Blocked) {
            model.notice.value = "Hold-to-talk unavailable now. Release, then use the voice controls. Existing drafts are kept."
            page = Page.VOICE
            if (!permitted && foregroundEligible()) microphone.launch(Manifest.permission.RECORD_AUDIO)
        } else applyHoldEffect(effect, captureTest)
        return true
    }
    private fun applyHoldEffect(effect: HoldEffect, captureTest: Boolean = false) {
        when (effect) {
            is HoldEffect.Start -> {
                page = Page.VOICE
                val controller = if (captureTest) model.captureTestInput else model.holdInput
                speech.onFailure = { controller.interrupt() }
                speech.onFinalResult = { text -> applyHoldEffect(controller.transcript(effect.utteranceId, text), captureTest) }
                speech.start() // Verified foreground key flow uses on-device speech only.
            }
            is HoldEffect.Stop -> speech.stop()
            is HoldEffect.Review -> {
                model.notice.value = "Capture test complete. Review text; nothing was sent."
                page = Page.VOICE
            }
            is HoldEffect.Submit -> { model.submitVoiceUtterance(effect.utteranceId, effect.text); page = Page.HOME }
            else -> Unit
        }
    }
    private fun interruptVoice(reason: String = "Capture interrupted. Review the draft or start again.") {
        model.holdInput.interrupt()
        model.captureTestInput.interrupt()
        if (::speech.isInitialized) speech.interrupt(reason)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page.name); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        DebugScreen.apply(this)
        val session = Any()
        foregroundSession = session
        model.bindForeground(session) { id -> model.openRequested(id, capabilities) { foregroundSession === session && foregroundEligible() && !speech.listening && !speech.processing } }
    }
    override fun onPause() {
        foregroundSession?.let(model::unbindForeground)
        foregroundSession = null
        interruptVoice()
        super.onPause()
    }
    override fun onStop() { interruptVoice(); speechConsent = false; tts?.stop(); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::speech.isInitialized) interruptVoice()
        if (hasFocus && ::speech.isInitialized && page == Page.HOME) model.homeEntered { foregroundEligible() && page == Page.HOME && !speech.listening && !speech.processing }
    }
    override fun onDestroy() { interruptVoice(); unregisterReceiver(screenOff); tts?.shutdown(); super.onDestroy() }
}
