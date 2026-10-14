package dev.puck.appliance

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Local notification previews only; no source actions or command decoding. */
class MuseNotifications:NotificationListenerService() {
    companion object {
        const val PACKAGE="com.facebook.aura"
        @Volatile var connected=false;private set
        @Volatile internal var live:MuseNotifications?=null
        fun allowed(c:Context)=c.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(c,MuseNotifications::class.java))
    }
    override fun onListenerConnected() {
        connected=true;live=this
        // Import active previews without replaying sounds or asking Muse for another turn.
        runCatching {
            val active=activeNotifications.filter {it.packageName!=packageName}
            MuseNotificationInbox.get(this).retain(active.map {it.key})
            active.sortedBy {it.postTime}.forEach {accept(it,false)}
        }
    }
    override fun onListenerDisconnected(){connected=false;live=null}
    override fun onDestroy(){connected=false;live=null;super.onDestroy()}
    override fun onNotificationPosted(sbn:StatusBarNotification){runCatching {accept(sbn,true)}}
    override fun onNotificationRemoved(sbn:StatusBarNotification){removed(sbn,0)}
    override fun onNotificationRemoved(sbn:StatusBarNotification,rankingMap:RankingMap,reason:Int){removed(sbn,reason)}
    private fun removed(sbn:StatusBarNotification,reason:Int) {
        if(sbn.packageName!=packageName)MuseNotificationInbox.get(this).sourceRemoved(sbn.key,sbn.postTime,reason==REASON_LISTENER_CANCEL)
    }
    fun mirrorActiveNotifications() {
        runCatching {activeNotifications.forEach {accept(it,false)}}
    }
    fun dismissPreview(id:String) {
        MuseNotificationInbox.get(this).dismiss(id)
        runCatching {activeNotifications.firstOrNull {MuseNotificationInbox.matches(id,it.key) && it.isClearable}?.let {cancelNotification(it.key)}}
    }
    fun clearPreviews() {
        val current=runCatching {activeNotifications.filter {it.packageName!=packageName && it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY==0}}.getOrDefault(emptyList())
        MuseNotificationInbox.get(this).clearAll()
        current.forEach {runCatching {cancelNotification(it.key)}}
    }
    internal fun accept(sbn:StatusBarNotification,alert:Boolean) {
        if(sbn.packageName==packageName)return
        val n=sbn.notification
        if(n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT)!=0)return
        val extras=n.extras
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(120)
        val body=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf {it.isNotBlank()}
            ?:extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf {it.isNotBlank()}
            ?:extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {Notification.MessagingStyle.Message.getMessagesFromBundleArray(it).lastOrNull()?.text?.toString()}
            ?:extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()).take(2000)
        if(body.isBlank() && title.isBlank())return
        val app=runCatching {packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString()}.getOrDefault(sbn.packageName).take(80)
        val inbox=MuseNotificationInbox.get(this)
        if(inbox.post(sbn.key,title,body.ifBlank {title},sbn.postTime,sbn.packageName,app) && alert)ApplianceEngine.get(this).notificationArrived()
        // Commit ownership before cancellation: its callback must retain the private bubble.
        // Calls, alarms, full-screen alerts and protected notifications keep their system UI.
        if(inbox.hideSystem && sbn.isClearable && n.category!=Notification.CATEGORY_CALL && n.category!=Notification.CATEGORY_ALARM && n.fullScreenIntent==null && inbox.holdMirror(sbn.key,sbn.postTime)) {
            runCatching {cancelNotification(sbn.key)}.onFailure {inbox.releaseMirror(sbn.key,sbn.postTime)}
        }
    }
}

/** Bounded, private preview inbox. Same notification content never reappears after dismissal. */
class MuseNotificationInbox internal constructor(c:Context,prefsName:String="muse_notifications") {
    data class Entry(val id:String,val title:String,val text:String,val posted:Long,val unread:Boolean=true,val packageName:String=MuseNotifications.PACKAGE,val appName:String="Muse",val mirroredOnly:Boolean=false) {
        val display:String get()=if(title.isBlank() || title.equals("Muse",true) || title==text)text else "$title\n$text"
    }
    companion object {
        @Volatile private var instance:MuseNotificationInbox?=null
        fun get(c:Context)=instance?:synchronized(this){instance?:MuseNotificationInbox(c.applicationContext).also {instance=it}}
        internal fun matches(id:String,key:String)=id==hash(key)
        private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    }
    private val prefs=c.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
    val entries=MutableStateFlow<List<Entry>>(runCatching {
        val array=JSONArray(prefs.getString("entries","[]"));(0 until array.length()).map {i->array.getJSONObject(i).let {Entry(it.getString("id"),it.getString("title"),it.getString("text"),it.getLong("posted"),it.optBoolean("unread",true),it.optString("package",MuseNotifications.PACKAGE),it.optString("app","Muse"),it.optBoolean("mirrored_only",false))}}.take(100)
    }.getOrDefault(emptyList()))
    var hideSystem:Boolean
        get()=prefs.getBoolean("hide_system",false)
        set(value){check(prefs.edit().putBoolean("hide_system",value).commit())}
    private val seen=runCatching {val a=JSONArray(prefs.getString("seen","[]"));(0 until a.length()).map {a.getString(it)}.toMutableList()}.getOrDefault(mutableListOf())
    @Synchronized fun post(key:String,title:String,text:String,posted:Long,packageName:String=MuseNotifications.PACKAGE,appName:String="Muse"):Boolean {
        if(text.isBlank())return false
        val id=hash(key);val cleanTitle=title.take(120);val cleanText=text.take(2000)
        val fingerprint=hash("$id\n$posted\n$cleanTitle\n$cleanText")
        if(fingerprint in seen)return false
        seen.add(fingerprint);while(seen.size>500)seen.removeAt(0)
        entries.value=(listOf(Entry(id,cleanTitle,cleanText,posted,true,packageName,appName.take(80)))+entries.value.filter {it.id!=id}).take(100)
        save();return true
    }
    @Synchronized fun dismiss(id:String){entries.value=entries.value.filter {it.id!=id};save()}
    @Synchronized fun clearAll(){entries.value=emptyList();save()}
    @Synchronized fun read(id:String){entries.value=entries.value.map {if(it.id==id)it.copy(unread=false) else it};save()}
    @Synchronized fun remove(key:String){dismiss(hash(key))}
    @Synchronized fun holdMirror(key:String,posted:Long):Boolean {
        val row=entries.value.firstOrNull {it.id==hash(key) && it.posted==posted}?:return false
        entries.value=entries.value.map {if(it==row)it.copy(mirroredOnly=true) else it};save();return true
    }
    @Synchronized fun releaseMirror(key:String,posted:Long){entries.value=entries.value.map {if(it.id==hash(key) && it.posted==posted)it.copy(mirroredOnly=false) else it};save()}
    @Synchronized fun sourceRemoved(key:String,posted:Long,listenerCancelled:Boolean) {
        // An old cancellation can arrive after a new message reuses the same Android key.
        entries.value=entries.value.filter {it.id!=hash(key) || it.posted!=posted || (listenerCancelled && it.mirroredOnly)};save()
    }
    @Synchronized fun retain(keys:List<String>){val ids=keys.map(::hash).toSet();entries.value=entries.value.filter {it.id in ids || it.mirroredOnly};save()}
    private fun save(){
        val array=JSONArray();entries.value.forEach {array.put(JSONObject().put("id",it.id).put("title",it.title).put("text",it.text).put("posted",it.posted).put("unread",it.unread).put("package",it.packageName).put("app",it.appName).put("mirrored_only",it.mirroredOnly))}
        prefs.edit().putString("entries",array.toString()).putString("seen",JSONArray(seen).toString()).commit()
    }
}
