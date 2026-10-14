package dev.puck.core

/** Input-only controller. It knows no Android vendor codes, agents, permissions, or device actions. */
sealed interface HoldEffect {
    data object Ignore : HoldEffect
    data object Consume : HoldEffect
    data object Blocked : HoldEffect
    data class Start(val utteranceId: String) : HoldEffect
    data class Stop(val utteranceId: String) : HoldEffect
    data class Review(val utteranceId: String, val text: String) : HoldEffect
    data class Submit(val utteranceId: String, val text: String) : HoldEffect
}
class HoldToTalk(private val reviewOnly: Boolean = false, private val newId: () -> String) {
    var verifiedKey: Int? = null
        private set
    private var pressed = false
    private var mustRelease = false
    private var utterance: String? = null
    private var finalText: String? = null
    private var waiting = false

    /** Caller must have physically verified this exact key on the current firmware. Never auto-learn. */
    fun armObservedKey(key: Int) { require(key > 0); interrupt(); verifiedKey = key }
    fun disarm() { interrupt(); verifiedKey = null }
    fun onKey(key: Int, down: Boolean, repeat: Int, eligible: Boolean): HoldEffect {
        if (verifiedKey != key) return HoldEffect.Ignore
        if (down && repeat > 0) return HoldEffect.Consume
        if (mustRelease) {
            if (!down) { mustRelease = false; pressed = false }
            return HoldEffect.Consume
        }
        if (down) {
            if (pressed || waiting) return HoldEffect.Consume
            pressed = true
            if (!eligible) { mustRelease = true; return HoldEffect.Blocked }
            utterance = newId()
            finalText = null
            return HoldEffect.Start(utterance!!)
        }
        if (!pressed) return HoldEffect.Consume // Orphan/duplicate release never submits.
        pressed = false
        val id = utterance ?: return HoldEffect.Consume
        if (!eligible) { interrupt(); return HoldEffect.Consume }
        waiting = true
        return finalText?.let { submit(id, it) } ?: HoldEffect.Stop(id)
    }
    fun transcript(id: String, text: String): HoldEffect {
        if (utterance != id || mustRelease) return HoldEffect.Ignore
        finalText = text.trim().take(4000)
        return if (waiting) submit(id, finalText!!) else HoldEffect.Consume // VAD may finish before key up.
    }
    private fun submit(id: String, text: String): HoldEffect {
        utterance = null; finalText = null; waiting = false
        return when {
            text.isBlank() -> HoldEffect.Consume
            reviewOnly -> HoldEffect.Review(id, text)
            else -> HoldEffect.Submit(id, text)
        }
    }
    fun interrupt() {
        if (pressed) mustRelease = true
        utterance = null; finalText = null; waiting = false
    }
}
