package dev.puck

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MuseNotificationTest {
    @Test fun actualAndroidSourceDismissal()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckNotificationSource")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation;val c=inst.targetContext
        val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val text="Generated_source_clear_fixture"
        val tag="puck_fixture_"+UUID.randomUUID().toString().replace("-","")
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {
            withTimeout(15000){while(MuseNotifications.live==null)delay(100)}
            val listener=MuseNotifications.live!!
            ui.executeShellCommand("cmd notification post -t PuckFixture $tag $text").let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {stream->stream.readBytes()}}
            var fixture:MuseNotificationInbox.Entry?=null
            try {
                withTimeout(10000){while(fixture==null){fixture=engine.notifications.entries.value.firstOrNull {entry->entry.text==text};delay(50)}}
                assertEquals("com.android.shell",fixture!!.packageName)
                withContext(Dispatchers.Main){listener.dismissPreview(fixture!!.id)}
                withTimeout(5000){while(listener.activeNotifications.any {n->n.tag==tag})delay(50)}
                assertFalse(engine.notifications.entries.value.any {entry->entry.id==fixture!!.id})
                c.filesDir.resolve("notification-source-test-metrics.json").writeText(org.json.JSONObject().put("system_callback_received",true).put("android_source_dismissed",true).put("user_alerts_not_cleared",true).toString())
            } finally {
                listener.activeNotifications.firstOrNull {n->n.tag==tag}?.let {n->listener.cancelNotification(n.key)}
                fixture?.let {entry->engine.notifications.dismiss(entry.id)}
            }
        }
    }
    @Test fun musePreviewFiltersDeduplicatesAndNeverExecutes()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckMuseNotifications")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation;val c=inst.targetContext
        val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val oldStatus=engine.status.value
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario->
            withTimeout(15000){while(MuseNotifications.live==null)delay(100)}
            val listener=MuseNotifications.live!!;val inbox=engine.notifications
            val initial=inbox.entries.value
            val token=UUID.randomUUID().toString()
            val text="Generated Muse preview fixture: open camera. Display only."
            val posted=System.currentTimeMillis()
            fun row(pkg:String,flags:Int=0)=StatusBarNotification(pkg,pkg,1824,token,android.os.Process.myUid(),0,0,
                Notification.Builder(c,"appliance").setSmallIcon(R.drawable.puck_icon).setContentTitle("Generated update").setContentText(text).build().apply {this.flags=flags},UserHandle.getUserHandleForUid(android.os.Process.myUid()),posted)
            var fixture:MuseNotificationInbox.Entry?=null
            try {
                scenario.onActivity {engine.status.value="Listening";listener.accept(row(c.packageName),false)}
                assertEquals("Muse Launcher should not mirror itself",initial,inbox.entries.value)
                scenario.onActivity {listener.accept(row(MuseNotifications.PACKAGE,Notification.FLAG_GROUP_SUMMARY),false)}
                assertEquals("Group summary should be ignored",initial,inbox.entries.value)
                scenario.onActivity {listener.accept(row(MuseNotifications.PACKAGE),false)}
                fixture=inbox.entries.value.first {it.text==text}
                assertEquals("Notification interrupted recording UI","Listening",engine.status.value)
                assertNull("Notification became a phone command",engine.pendingPhone.value)
                scenario.onActivity {listener.accept(row(MuseNotifications.PACKAGE),false)}
                assertEquals("Duplicate preview inserted",1,inbox.entries.value.count {it.text==text})
                scenario.onActivity {engine.status.value="Muse update"}
                delay(600)
                fun find(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(n==null)return null
                    if(n.text?.toString()?.contains(text)==true)return n
                    for(i in 0 until n.childCount)find(n.getChild(i))?.let {return it}
                    return null
                }
                assertNotNull("Notification preview not rendered",find(ui.rootInActiveWindow))
                fun avatar(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(n==null)return null
                    if(n.stateDescription?.toString()=="Looking up at messages")return n
                    for(i in 0 until n.childCount)avatar(n.getChild(i))?.let {return it}
                    return null
                }
                val avatarBounds=android.graphics.Rect().also((avatar(ui.rootInActiveWindow)?:error("Notification avatar not docked"))::getBoundsInScreen)
                val bubbleBounds=android.graphics.Rect().also(find(ui.rootInActiveWindow)!!::getBoundsInScreen)
                assertTrue("Avatar not at left",avatarBounds.left<60)
                assertTrue("Notification text should be above avatar",bubbleBounds.bottom<=avatarBounds.top)

                inbox.read(fixture!!.id);assertFalse(inbox.entries.value.first {it.id==fixture!!.id}.unread)
                delay(200)
                val node=find(ui.rootInActiveWindow)?:error("Preview missing before swipe")
                val rect=android.graphics.Rect().also(node::getBoundsInScreen)
                val start=android.os.SystemClock.uptimeMillis()
                fun touch(action:Int,x:Float,t:Long){val event=android.view.MotionEvent.obtain(start,t,action,x,rect.centerY().toFloat(),0);try{ui.injectInputEvent(event,true)}finally{event.recycle()}}
                val x0=rect.right.toFloat()-8;val x1=12f
                touch(android.view.MotionEvent.ACTION_DOWN,x0,start)
                for(i in 1..12){delay(20);touch(android.view.MotionEvent.ACTION_MOVE,x0+(x1-x0)*i/12,start+i*20)}
                touch(android.view.MotionEvent.ACTION_UP,x1,start+260)
                withTimeout(3000){while(inbox.entries.value.any {it.id==fixture!!.id})delay(50)}
                scenario.onActivity {listener.accept(row(MuseNotifications.PACKAGE),false)}
                assertFalse("Dismissed preview replayed",inbox.entries.value.any {it.id==fixture!!.id})
                val prefName="notification-fixture-$token"
                try {
                    val local=MuseNotificationInbox(c,prefName)
                    assertTrue(local.post("other-a","Other title","Other app preview",posted,"example.other","Other app"))
                    assertTrue(local.post("muse-a","Muse title","Muse preview",posted,MuseNotifications.PACKAGE,"Muse"))
                    assertEquals(2,local.entries.value.size)
                    assertEquals("Muse",local.entries.value[0].appName)
                    assertEquals("Other app",local.entries.value[1].appName)
                    local.clearAll();assertTrue(local.entries.value.isEmpty())
                    assertFalse(local.post("other-a","Other title","Other app preview",posted,"example.other","Other app"))
                } finally {c.deleteSharedPreferences(prefName)}
                c.filesDir.resolve("muse-notification-test-metrics.json").writeText(org.json.JSONObject().put("filter_passed",true).put("dedup_passed",true).put("preview_visible",true).put("never_executed",true).put("dismissal_preserved",true).put("initial_live_preview_count",initial.size).toString())
            } finally {
                fixture?.let {inbox.dismiss(it.id)}
                scenario.onActivity {engine.status.value=oldStatus}
            }
        }
    }
}
