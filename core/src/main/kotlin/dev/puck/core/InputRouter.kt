package dev.puck.core

/** Recognize a narrow explicit command grammar; never guess natural-language intent. */
object InputRouter {
    fun isLocal(text: String, appLabels: Set<String> = emptySet()): Boolean {
        val input = text.trim()
        val lower = input.lowercase()
        if (lower in setOf("camera", "photo", "call", "dial")) return true
        val verb = lower.substringBefore(' ')
        val value = input.substringAfter(' ', "").trim()
        return when (verb) {
            "text", "sms" -> value.isNotBlank() // explicit composer command, recipient chosen locally
            "navigate", "maps" -> value.isNotBlank()
            "event", "calendar" -> value.isNotBlank()
            "open" -> value.lowercase() in appLabels
            else -> false
        }
    }
}
