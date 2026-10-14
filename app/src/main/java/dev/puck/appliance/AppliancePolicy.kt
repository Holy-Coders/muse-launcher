package dev.puck.appliance

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager

class ApplianceAdmin : DeviceAdminReceiver()
object AppliancePolicy {
    fun owner(c: Context) = c.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(c.packageName)
    fun companionEntry(c:Context,enabled:Boolean) {
        c.getSharedPreferences("appliance",Context.MODE_PRIVATE).edit().putBoolean("companion_entry",enabled).commit()
        c.packageManager.setComponentEnabledSetting(ComponentName(c.packageName,"dev.puck.VoiceEntry"),
            if(enabled)android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
    }
    fun configure(c: Context) {
        val dpm=c.getSystemService(DevicePolicyManager::class.java)
        check(owner(c)) { "Provision Muse Launcher as device owner first" }
        val admin=ComponentName(c,ApplianceAdmin::class.java)
        dpm.setLockTaskPackages(admin,arrayOf(c.packageName))
        dpm.setLockTaskFeatures(admin,DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        dpm.addPersistentPreferredActivity(admin,IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME);addCategory(Intent.CATEGORY_DEFAULT)
        },ComponentName(c,ApplianceActivity::class.java))
        dpm.addUserRestriction(admin,UserManager.DISALLOW_CREATE_WINDOWS)
        dpm.addUserRestriction(admin,UserManager.DISALLOW_INSTALL_APPS)
        for(name in listOf("dev.puck.MainActivity","dev.puck.VoiceEntry")) c.packageManager.setComponentEnabledSetting(ComponentName(c.packageName,name),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
        // Do not disable factory recovery, USB debugging or the existing PIN.
        // Lock-task is activated only when recovery credentials are configured.
    }
    fun recover(c:Context) {
        check(owner(c))
        val dpm=c.getSystemService(DevicePolicyManager::class.java)
        val admin=ComponentName(c,ApplianceAdmin::class.java)
        dpm.setLockTaskPackages(admin,emptyArray())
        companionEntry(c,false)
        dpm.clearPackagePersistentPreferredActivities(admin,c.packageName)
        dpm.clearUserRestriction(admin,UserManager.DISALLOW_CREATE_WINDOWS)
        dpm.clearUserRestriction(admin,UserManager.DISALLOW_INSTALL_APPS)
        for(name in listOf("dev.puck.MainActivity","dev.puck.VoiceEntry")) c.packageManager.setComponentEnabledSetting(ComponentName(c.packageName,name),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,android.content.pm.PackageManager.DONT_KILL_APP)
    }
}
