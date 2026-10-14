package dev.puck.appliance

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Reads package names only, never node trees, notification text, key events or audio. */
class NavigationGuard:AccessibilityService() {
    companion object {
        @Volatile var connected=false;private set
        private fun prefs(c:Context)=c.getSharedPreferences("appliance_navigation",Context.MODE_PRIVATE)
        fun enabled(c:Context)=prefs(c).getBoolean("enabled",true)
        fun setEnabled(c:Context,value:Boolean) {prefs(c).edit().putBoolean("enabled",value).commit();clear(c)}
        fun clear(c:Context){prefs(c).edit().remove("packages").remove("expires").commit()}
        fun redirects(c:Context)=prefs(c).getLong("redirects",0)
        fun allow(c:Context,intent:Intent) {
            val packages=if(intent.`package`!=null)setOf(intent.`package`!!) else if(intent.component!=null)setOf(intent.component!!.packageName)
                else c.packageManager.queryIntentActivities(intent,0).map {it.activityInfo.packageName}.toSet()
            prefs(c).edit().putStringSet("packages",packages).putLong("expires",SystemClock.elapsedRealtime()+2*60*60*1000L).commit()
        }
    }
    private var lastRedirect=0L
    private var currentPackage=""
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val redirect=Runnable {checkForeground()}
    override fun onServiceConnected(){connected=true;clear(this)}
    override fun onUnbind(intent:Intent?):Boolean {connected=false;handler.removeCallbacks(redirect);return super.onUnbind(intent)}
    override fun onInterrupt()=Unit
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        val pkg=event.packageName?.toString()?:return
        currentPackage=pkg
        checkForeground()
    }
    private fun checkForeground() {
        val p=prefs(this)
        val now=SystemClock.elapsedRealtime()
        val locked=getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val keyboard=android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore("/")
        if(NavigationGate.allowed(currentPackage,packageName,p.getStringSet("packages",emptySet())!!,now,p.getLong("expires",0),enabled(this),locked,setOfNotNull(keyboard)))return
        if(now-lastRedirect<500) {handler.removeCallbacks(redirect);handler.postDelayed(redirect,500-(now-lastRedirect));return}
        lastRedirect=now
        if(performGlobalAction(GLOBAL_ACTION_HOME))p.edit().putLong("redirects",redirects(this)+1).apply()
    }
}
