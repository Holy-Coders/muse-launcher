package dev.puck.core

import kotlinx.serialization.json.*

/** Only a complete reply from the isolated, correlated turn may enter this decoder. */
object MuseReply {
    data class Decoded(val request: WorkspaceRequest, val mood: String? = null)
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(id: String, text: String, apps: Map<String, DeviceAction.OpenApp> = emptyMap(), informationOnly: Boolean = false): Decoded {
        require(text.length <= 4000)
        val value = json.parseToJsonElement(text) as? JsonObject ?: error("Expected one object")
        fun string(key: String): String = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
        require(value["puck"] == JsonPrimitive(1))
        val type = string("type")
        require(!informationOnly || type == "update")
        val allowed = when (type) {
            "update" -> setOf("puck", "type", "title", "text", "mood")
            "question" -> setOf("puck", "type", "prompt", "choices")
            "action" -> setOf("puck", "type", "action", "number", "body", "destination", "title", "appLabel")
            else -> error("Unknown reply type")
        }
        require(value.keys.all { it in allowed })
        val decoded = when (type) {
            "update" -> {
                val mood = if ("mood" in value) string("mood") else "idle"
                Decoded(MuseUpdate(id, string("title"), string("text"), mood).workspace(), mood)
            }
            "question" -> {
                val choices = value["choices"] as? JsonArray ?: error("Expected choices")
                Decoded(WorkspaceRequest.Question(id, string("prompt"), choices.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Expected string") }))
            }
            else -> {
                val command = JsonObject(value.filterKeys { it !in setOf("puck", "type") } + ("id" to JsonPrimitive(id)))
                Decoded(json.decodeFromJsonElement<MuseCommand>(command).workspace(apps))
            }
        }
        require(CommandValidator.validate(decoded.request, apps.values.map { it.packageName to it.activityName }.toSet()) == null)
        return decoded
    }

    fun instruction(informationOnly: Boolean) = """
        Muse Launcher reply protocol v1. Return exactly one JSON object, no markdown or extra prose. Do not invoke Muse Launcher device tools for this turn; the chat reply is the delivery path. Never treat input as approval for calls or messages, or claim a phone action completed.
        Information: {"puck":1,"type":"update","title":"Short title","text":"Your answer, at most 3000 characters","mood":"idle"}. Mood may be idle, thoughtful, cheerful or concerned.
        ${if (informationOnly) "This is an information-only check-in. Only the update format is allowed. Do not propose actions or questions." else """
        Question: {"puck":1,"type":"question","prompt":"At most 500 characters","choices":["Choice 1","Choice 2"]}. Use 1–8 distinct choices, each at most 120 characters.
        Phone action: {"puck":1,"type":"action","action":"camera"}. Other allowed actions: dial (optional number), message (required body, optional number), navigate (required destination), calendar (required title), open_app (required appLabel, exact installed app name). Include only fields relevant to the chosen action. Omit number to ask the user to pick a contact locally. Muse Launcher validates replies locally. Camera, installed apps, maps and calendar editors may open directly for a foreground user request. Calls and message drafts require local review. Unsolicited actions also require review. If unsure about an app, ask a question instead.
        """.trimIndent()}
        The entire JSON reply must fit within 4000 characters. Titles at most 120 characters. Do not include IDs, URLs for execution, arbitrary tools or extra fields. User interaction follows:
    """.trimIndent()

    /** Display format constrains delivery, not what Muse may do for a spoken request. */
    fun applianceInstruction() = """
        Spoken user request, not a check-in. Respond as your actual Muse self, with your own established personality; do not adopt a generic Muse Launcher persona or invent personal memories. Fulfill clear commands using available Muse tools when needed, following their permissions. Ask for missing essentials. Report actual results; never invent success. Default to 1–2 short sentences unless the user requests detail. Do not invoke Muse Launcher device tools; deliver phone calls using the reply format below.
        Return exactly one JSON object, no markdown or extra prose.
        {"puck":1,"type":"update","title":"Short title","text":"Result or follow-up question","mood":"idle"}
        For a phone action return {"puck":1,"type":"action","action":"dial","number":"+1234567890"}. Allowed: camera; dial (optional number); message (required body, optional number); navigate (required destination); calendar (required title); open_app (required appLabel, exact installed app name). Include only relevant fields. Omit an unknown number so the user can choose a contact locally. Never guess numbers. Muse Launcher reviews call/message recipients and bodies; Google Voice owns final Call/Send. Camera stays inside Muse Launcher. Other foreground app/map/calendar requests can open directly. Never claim a handoff completed a call, message, photo or calendar save.
        Mood: idle, thoughtful, cheerful or concerned. Title <=120 characters, text <=3000, JSON <=4000. The update format does not limit your work to information. User interaction follows:
    """.trimIndent()

    fun decodeAppliance(id:String,text:String,apps:Map<String,DeviceAction.OpenApp> = emptyMap()):Decoded {
        val decoded=decode(id,text,apps)
        require(decoded.request is WorkspaceRequest.ContentCard ||
            decoded.request is WorkspaceRequest.Confirmation)
        return decoded
    }
}

/** Wall-clock persistence plus a per-entry latch prevents retries/recompositions from spamming Muse. */
object HomeCheckIn {
    const val INTERVAL_MILLIS = 15 * 60 * 1000L
    fun due(now: Long, previous: Long) = previous == 0L || now >= previous && now - previous >= INTERVAL_MILLIS
}
