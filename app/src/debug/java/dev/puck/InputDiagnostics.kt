package dev.puck

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug build only. Bounded memory, no logcat/file logging, no extras/data/transcripts. */
object InputDiagnostics {
    const val available = true
    val armed = MutableStateFlow(false)
    val lines = MutableStateFlow<List<String>>(emptyList())
    val pairs = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val captureTestKey = MutableStateFlow<Int?>(null)
    val configuredKey = MutableStateFlow<Int?>(null)
    private val down = mutableSetOf<Int>()
    fun setArmed(value: Boolean) { armed.value = value; down.clear() }
    fun clear() { lines.value = emptyList(); pairs.value = emptyMap(); down.clear() }
    fun recordKey(event: KeyEvent, isolatedDiagnosticPage: Boolean) {
        if (!armed.value) return
        // Never collect printable keyboard events alongside typed input or editable transcription.
        if (event.isPrintingKey && !isolatedDiagnosticPage) return
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        append("key code=${event.keyCode} action=$action repeat=${event.repeatCount}")
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) down += event.keyCode
        if (event.action == KeyEvent.ACTION_UP && down.remove(event.keyCode) && !event.isPrintingKey && event.keyCode !in setOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH)) pairs.value = pairs.value + (event.keyCode to ((pairs.value[event.keyCode] ?: 0) + 1))
    }
    fun recordLaunch(intent: Intent, source: String) {
        val action = when (intent.action) {
            Intent.ACTION_MAIN -> "MAIN"
            MainActivity.ACTION_VOICE_ENTRY -> "PUCK_VOICE_ENTRY"
            null -> "none"
            else -> "other(redacted)"
        }
        val entry = if (intent.component?.className == MainActivity.VOICE_ENTRY_COMPONENT) "PuckTalk" else "Puck"
        val categories = intent.categories.orEmpty().filter { it in setOf(Intent.CATEGORY_HOME, Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_DEFAULT) }.map { it.substringAfterLast('.') }
        append("launch $source entry=$entry action=$action flags=0x${intent.flags.toString(16)} categories=$categories extrasPresent=${intent.extras != null} dataPresent=${intent.data != null}")
    }
    private fun append(line: String) { lines.value = (lines.value + "${SystemClock.elapsedRealtime()} $line").takeLast(120) }
}
