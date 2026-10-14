package dev.puck

import android.content.Intent
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow

/** Release stub: no input recorder or configurable hardware mapping. */
object InputDiagnostics {
    const val available = false
    val armed = MutableStateFlow(false)
    val lines = MutableStateFlow<List<String>>(emptyList())
    val pairs = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val captureTestKey = MutableStateFlow<Int?>(null)
    val configuredKey = MutableStateFlow<Int?>(null)
    fun setArmed(value: Boolean) = Unit
    fun clear() = Unit
    fun recordKey(event: KeyEvent, isolatedDiagnosticPage: Boolean) = Unit
    fun recordLaunch(intent: Intent, source: String) = Unit
}
