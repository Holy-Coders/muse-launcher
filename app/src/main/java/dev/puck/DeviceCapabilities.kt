package dev.puck

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import dev.puck.core.*
import androidx.core.net.toUri

/** No agent-supplied intents, extras, URIs, class names outside the local launchable allowlist. */
data class LaunchableApp(val label: String, val packageName: String, val activityName: String)
class DeviceCapabilities(private val activity: Activity) {
    companion object {
        fun launchableApps(context: Context): List<LaunchableApp> = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != context.packageName }.distinctBy { it.packageName to it.activityName }.sortedBy { it.label.lowercase() }
    }
    fun apps() = launchableApps(activity)
    fun allowlist() = apps().map { it.packageName to it.activityName }.toSet()
    fun open(action: DeviceAction, googleVoice: Boolean = false): CapabilityResult {
        if (CommandValidator.validateAction(action, allowlist()) != null || CommandValidator.needsContact(action)) return CapabilityResult.Invalid
        val intent = when (action) {
            is DeviceAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.number, null))
            is DeviceAction.Message -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.number, null)).putExtra("sms_body", action.body)
            is DeviceAction.Navigate -> Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(action.destination)}".toUri())
            is DeviceAction.Calendar -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, action.title).apply {
                    action.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                    action.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                }
            DeviceAction.Camera -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            is DeviceAction.OpenApp -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(action.packageName, action.activityName))
        }
        if (googleVoice) googleVoiceIntent(action)?.let { return launch(it) }
        return launch(intent)
    }
    fun launch(intent: Intent): CapabilityResult = try {
        if(activity is dev.puck.appliance.ApplianceActivity)dev.puck.appliance.NavigationGuard.allow(activity,intent)
        // Attempt directly: resolveActivity would require extra visibility queries for each handler.
        activity.startActivity(intent)
        CapabilityResult.HandedOff
    } catch (_: android.content.ActivityNotFoundException) { CapabilityResult.Unavailable }
      catch (_: SecurityException) { CapabilityResult.PermissionDenied }
}

/** Public dial/share handoffs only. Voice owns the final call/send controls. */
internal fun googleVoiceIntent(action: DeviceAction): Intent? = when (action) {
    is DeviceAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.number, null))
        .setPackage("com.google.android.apps.googlevoice")
    // This installed Voice app has no SENDTO receiver. Do not assume a
    // recipient extra works: explicitly require selection again in Voice.
    is DeviceAction.Message -> Intent(Intent.ACTION_SEND).setType("text/plain")
        .setPackage("com.google.android.apps.googlevoice").putExtra(Intent.EXTRA_TEXT, action.body)
    else -> null
}
