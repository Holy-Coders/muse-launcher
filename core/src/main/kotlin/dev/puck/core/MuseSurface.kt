package dev.puck.core

import kotlinx.serialization.Serializable

@Serializable
data class MuseUpdate(val id: String, val title: String, val text: String, val mood: String = "idle") {
    fun workspace(): WorkspaceRequest.ContentCard {
        require(mood in setOf("idle", "thoughtful", "cheerful", "concerned"))
        return WorkspaceRequest.ContentCard(id, title, text).also { require(CommandValidator.validate(it) == null) }
    }
}

@Serializable
data class MuseCommand(
    val id: String, val action: String, val number: String? = null,
    val body: String? = null, val destination: String? = null,
    val title: String? = null, val appLabel: String? = null
) {
    fun workspace(apps: Map<String, DeviceAction.OpenApp>): WorkspaceRequest.Confirmation {
        val typed = when (action) {
            "camera" -> DeviceAction.Camera
            "dial" -> DeviceAction.Dial(number)
            "message" -> DeviceAction.Message(requireNotNull(body), number)
            "navigate" -> DeviceAction.Navigate(requireNotNull(destination))
            "calendar" -> DeviceAction.Calendar(requireNotNull(title))
            "open_app" -> requireNotNull(apps[requireNotNull(appLabel).lowercase()])
            else -> error("Unsupported action")
        }
        require(body == null || action == "message")
        require(destination == null || action == "navigate")
        require(title == null || action == "calendar")
        require(number == null || action in setOf("dial", "message"))
        require(appLabel == null || action == "open_app")
        return WorkspaceRequest.Confirmation(id, typed).also {
            require(CommandValidator.validate(it, apps.values.map { app -> app.packageName to app.activityName }.toSet()) == null)
        }
    }
}
