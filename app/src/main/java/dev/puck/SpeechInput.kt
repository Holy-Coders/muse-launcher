package dev.puck

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal interface SpeechSession {
    fun setListener(listener: RecognitionListener)
    fun start(intent: Intent)
    fun stop()
    fun cancel()
    fun destroy()
}

private class AndroidSpeechSession(private val recognizer: SpeechRecognizer) : SpeechSession {
    override fun setListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    override fun start(intent: Intent) = recognizer.startListening(intent)
    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

/** Foreground-only capture. There is no wake word, service, or automatic restart. */
class SpeechInput internal constructor(
    private val context: Context,
    private val transcription: (String) -> Unit,
    private val sessionFactory: (Boolean) -> SpeechSession,
    private val captureTimeoutMs: Long = 60_000,
    private val resultTimeoutMs: Long = 15_000,
    private val deviceAvailable: () -> Boolean = { Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) },
) {
    constructor(context: Context, transcription: (String) -> Unit) : this(context, transcription, { offline ->
        AndroidSpeechSession(if (offline && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context))
    })
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    var listening by mutableStateOf(false)
        private set
    var processing by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    private var recognizer: SpeechSession? = null
    private var generation = 0
    var onFinalResult: ((String) -> Unit)? = null
    var onFailure: (() -> Unit)? = null
    val onDeviceAvailable get() = deviceAvailable()
    val available get() = SpeechRecognizer.isRecognitionAvailable(context)
    fun start(networkAllowed: Boolean = false) {
        if (listening || processing) return
        if (!onDeviceAvailable && !networkAllowed) { message = "On-device speech unavailable. Network recognition requires your choice."; return }
        if ((networkAllowed || !onDeviceAvailable) && !available) { message = "No speech recognizer installed. Use typing."; return }
        release()
        val token = ++generation
        try {
            val offline = onDeviceAvailable && !networkAllowed
            recognizer = sessionFactory(offline)
            recognizer!!.setListener(object : RecognitionListener {
                private fun valid() = token == generation
                override fun onReadyForSpeech(params: Bundle?) { if (valid()) { listening = true; message = "Listening — tap Stop when finished" } }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (valid()) { listening = false; processing = true; message = "Transcribing. Review before Finish."; awaitResult(token) } }
                override fun onError(error: Int) { if (valid()) {
                    listening = false; processing = false
                    message = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied. Use typing."
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Retry or type."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network unavailable. Use typing."
                        else -> "Speech unavailable (code $error). Retry or type."
                    }
                    generation++
                    release()
                    onFailure?.invoke()
                } }
                override fun onResults(results: Bundle?) { if (valid()) {
                    listening = false; processing = false
                    val final = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (final.isNotBlank()) transcription(final)
                    message = "Review text, then Finish or Discard."
                    generation++
                    release()
                    onFinalResult?.invoke(final)
                } }
                override fun onPartialResults(partialResults: Bundle?) { if (valid()) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(transcription) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            listening = true
            message = if (offline) "Starting on-device speech" else "Starting system speech; audio may leave this phone"
            armTimeout(token, captureTimeoutMs, "Speech timed out. Review saved text or retry.")
            recognizer!!.start(intent)
        } catch (_: Exception) { interrupt("Could not start speech. Use typing."); onFailure?.invoke() }
    }
    fun stop() { if (listening) { listening = false; processing = true; message = "Transcribing"; awaitResult(generation); recognizer?.stop() } }
    fun interrupt(reason: String = "Listening interrupted. Review saved text or start again.") {
        val active = listening || processing
        generation++ // Late callbacks cannot overwrite a discarded or interrupted draft.
        listening = false; processing = false
        runCatching { recognizer?.cancel() }
        release()
        if (active) message = reason
    }
    private fun awaitResult(token: Int) {
        // Duplicate end callbacks must not extend an already-running result deadline.
        if (!waitingForResult) {
            waitingForResult = true
            armTimeout(token, resultTimeoutMs, "Transcription timed out. Review saved text or retry.")
        }
    }
    private var waitingForResult = false
    private fun armTimeout(token: Int, delayMs: Long, reason: String) {
        watchdog?.let(handler::removeCallbacks)
        watchdog = Runnable {
            if (token == generation && (listening || processing)) {
                interrupt(reason)
                onFailure?.invoke()
            }
        }.also { handler.postDelayed(it, delayMs) }
    }
    private fun release() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
        waitingForResult = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
