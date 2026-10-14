package dev.puck

import android.app.Activity
import android.view.WindowManager

/** Only debug builds keep the foreground screen awake; sleep tests can opt out. */
object DebugScreen {
    fun enabled(activity: Activity): Boolean = BuildConfig.DEBUG &&
        activity.getSharedPreferences("debug", 0).getBoolean("keep_screen_awake", true)
    fun apply(activity: Activity) {
        if (enabled(activity)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun set(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences("debug", 0).edit().putBoolean("keep_screen_awake", enabled).apply()
        apply(activity)
    }
}
