package dev.puck.core

/** Foreground requests may open reversible Android surfaces; communication still gets review. */
object ActionReview {
    fun mayOpenImmediately(action: DeviceAction, userInitiated: Boolean): Boolean = userInitiated && when (action) {
        DeviceAction.Camera, is DeviceAction.OpenApp, is DeviceAction.Navigate, is DeviceAction.Calendar -> true
        is DeviceAction.Dial, is DeviceAction.Message -> false
    }
}
