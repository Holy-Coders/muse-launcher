package dev.puck

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
class NotificationMirrorTest {
    @Test fun retainedMirrorSurvivesRestartAndOldCallbacks() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs="mirror-fixture-${UUID.randomUUID()}"
        try {
            val inbox=MuseNotificationInbox(c,prefs)
            assertFalse(inbox.hideSystem)
            inbox.hideSystem=true
            inbox.post("key","Title","First",1)
            assertTrue(inbox.holdMirror("key",1))
            inbox.sourceRemoved("key",1,true)
            inbox.retain(emptyList())
            val reloaded=MuseNotificationInbox(c,prefs)
            assertTrue(reloaded.hideSystem)
            assertEquals("First",reloaded.entries.value.single().text)
            reloaded.post("key","Title","Second",2)
            reloaded.sourceRemoved("key",1,true)
            assertEquals("Second",reloaded.entries.value.single().text)
            reloaded.sourceRemoved("key",2,false)
            assertTrue(reloaded.entries.value.isEmpty())
            reloaded.post("ordinary","Title","Ordinary",3)
            reloaded.retain(emptyList())
            assertTrue(reloaded.entries.value.isEmpty())
            reloaded.post("dismiss","Title","Dismiss",4)
            reloaded.holdMirror("dismiss",4)
            reloaded.clearAll()
            assertFalse(reloaded.post("dismiss","Title","Dismiss",4))
            assertTrue(MuseNotificationInbox(c,prefs).entries.value.isEmpty())
        } finally {c.deleteSharedPreferences(prefs)}
    }

    @Test fun androidTrayDisappearsButBubbleRemains()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckNotificationMirror")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val c=inst.targetContext
        val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val inbox=engine.notifications;val wasHidden=inbox.hideSystem;val status=engine.status.value
        val tag="mirror_fixture_${UUID.randomUUID().toString().replace("-","")}"
        val text="Generated_mirror_retention_fixture"
        var fixture:MuseNotificationInbox.Entry?=null
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario->
            try {
                withTimeout(15000){while(MuseNotifications.live==null)delay(100)}
                val listener=MuseNotifications.live!!
                inbox.hideSystem=true
                // Protected categories remain present in both UIs; no cancel request is made.
                for(category in listOf(android.app.Notification.CATEGORY_CALL,android.app.Notification.CATEGORY_ALARM)) {
                    val row=android.service.notification.StatusBarNotification("com.android.shell","com.android.shell",99,tag+category,android.os.Process.myUid(),0,0,
                        android.app.Notification.Builder(c,"appliance").setSmallIcon(R.drawable.puck_icon).setContentTitle("Protected fixture").setContentText(text+category).setCategory(category).build(),
                        android.os.UserHandle.getUserHandleForUid(android.os.Process.myUid()),System.currentTimeMillis())
                    try {
                        listener.accept(row,false)
                        assertFalse("Protected alert was selected for cancellation",inbox.entries.value.first {it.text==text+category}.mirroredOnly)
                    } finally {inbox.remove(row.key)}
                }
                inst.uiAutomation.executeShellCommand("cmd notification post -t MirrorFixture $tag $text").let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
                withTimeout(10000){while(fixture==null){fixture=inbox.entries.value.firstOrNull {it.text==text && it.mirroredOnly};delay(50)}}
                withTimeout(5000){while(listener.activeNotifications.any {it.tag==tag})delay(50)}
                delay(300) // allow removal callback to run
                assertTrue(inbox.entries.value.any {it.id==fixture!!.id})
                val reloaded=MuseNotificationInbox(c)
                assertTrue("Mirror lost after reconnect",reloaded.entries.value.any {it.id==fixture!!.id})
                scenario.recreate()
                assertTrue("Mirror lost after recreation",inbox.entries.value.any {it.id==fixture!!.id})
                withContext(Dispatchers.Main){listener.dismissPreview(fixture!!.id)}
                assertFalse(inbox.entries.value.any {it.id==fixture!!.id})
            } finally {
                inbox.hideSystem=wasHidden
                fixture?.let {inbox.dismiss(it.id)}
                MuseNotifications.live?.activeNotifications?.firstOrNull {it.tag==tag}?.let {MuseNotifications.live?.cancelNotification(it.key)}
                scenario.onActivity {engine.status.value=status}
            }
        }
    }
    @Test fun enableMirroringOnPersonalDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckEnableMirror")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        MuseNotificationInbox.get(c).hideSystem=true
        // Existing tray alerts stay until a new post/update; no bulk cancellation during setup.
        assertTrue(MuseNotificationInbox.get(c).hideSystem)
    }
}
