package dev.puck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.*
import dev.puck.core.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionUiTest {
    @Test fun dobbyReplySwipesAwayAndCameraControlsStayBelowPreview() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckReplyUi")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation
        val c=inst.targetContext;val engine=ApplianceEngine.get(c)
        val old=engine.update.value;val oldStatus=engine.status.value
        val checkins=engine.store.prefs.getBoolean("checkins",true)
        engine.store.prefs.edit().putBoolean("checkins",false).commit()
        fun find(n:android.view.accessibility.AccessibilityNodeInfo?,label:String):android.view.accessibility.AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label)return n
            for(i in 0 until n.childCount)find(n.getChild(i),label)?.let {return it}
            return null
        }
        suspend fun waitNode(label:String):android.view.accessibility.AccessibilityNodeInfo {
            return withTimeout(10_000){while(true){find(ui.rootInActiveWindow,label)?.let {return@withTimeout it};delay(100)};error("unreachable")}
        }
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario->
            try {
                scenario.onActivity {engine.update.value="Generated Dobby swipe fixture";engine.status.value="Reply ready"}
                val rect=android.graphics.Rect().also(waitNode("Generated Dobby swipe fixture")::getBoundsInScreen)
                fun avatar(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(n==null)return null
                    if(n.stateDescription?.toString()=="Looking up at messages")return n
                    for(i in 0 until n.childCount)avatar(n.getChild(i))?.let {return it}
                    return null
                }
                val pet=android.graphics.Rect().also((avatar(ui.rootInActiveWindow)?:error("Reply avatar not docked"))::getBoundsInScreen)
                assertTrue("Reply avatar not on left",pet.left<60)
                assertTrue("Reply text overlaps avatar",rect.bottom<=pet.top)
                val start=android.os.SystemClock.uptimeMillis()
                fun touch(action:Int,x:Float,t:Long){val event=android.view.MotionEvent.obtain(start,t,action,x,rect.centerY().toFloat(),0);try{ui.injectInputEvent(event,true)}finally{event.recycle()}}
                val x0=rect.left+20f;val x1=rect.right-5f
                touch(android.view.MotionEvent.ACTION_DOWN,x0,start)
                for(i in 1..12){delay(20);touch(android.view.MotionEvent.ACTION_MOVE,x0+(x1-x0)*i/12,start+i*20)}
                touch(android.view.MotionEvent.ACTION_UP,x1,start+260)
                withTimeout(5000){while(engine.update.value.isNotEmpty())delay(100)}
                assertEquals("",engine.store.prefs.getString("update","missing"))
                scenario.recreate()
                scenario.onActivity {engine.update.value="New Dobby reply fixture";engine.status.value="Reply ready"}
                waitNode("New Dobby reply fixture")
                scenario.onActivity {engine.cameraGesture.value++}
                val controls=android.graphics.Rect().also(waitNode("Tap red for photo · Hold red for video")::getBoundsInScreen)
                val preview=android.graphics.Rect().also(waitNode("Camera preview")::getBoundsInScreen)
                assertTrue("Camera text overlaps preview",controls.top>=preview.bottom)
                c.filesDir.resolve("reply-camera-ui-result.json").writeText(org.json.JSONObject().put("reply_swipe_durable",true).put("new_reply_visible",true).put("controls_below_preview",true).toString())
            } finally {
                scenario.onActivity {engine.dismiss();engine.update.value=old;engine.store.prefs.edit().putString("update",old).putBoolean("checkins",checkins).commit();engine.status.value=oldStatus}
            }
        }
    }

    @Test fun largeCaptionIsScrollableAndCameraEventOpensPreview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckCameraUi")=="true")
        val inst=InstrumentationRegistry.getInstrumentation()
        val automation=inst.uiAutomation
        val c=inst.targetContext
        assumeTrue(c.checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val engine=ApplianceEngine.get(c)
        val originalStatus=engine.status.value
        val originalCaption=engine.transcript.value
        val checkins=engine.store.prefs.getBoolean("checkins",true)
        engine.store.prefs.edit().putBoolean("checkins",false).commit()
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            try {
                scenario.onActivity {engine.status.value="Listening";engine.transcript.value=("Caption fixture: larger words should stay readable while I speak. ").repeat(18)}
                val rootDeadline=System.currentTimeMillis()+8000
                while(automation.rootInActiveWindow==null && System.currentTimeMillis()<rootDeadline)Thread.sleep(100)
                Thread.sleep(500)
                val scrolls=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun walk(n:android.view.accessibility.AccessibilityNodeInfo) {
                    if(n.isScrollable)scrolls.add(n)
                    for(i in 0 until n.childCount)n.getChild(i)?.let(::walk)
                }
                val root=automation.rootInActiveWindow
                assertNotNull("Puck UI not ready",root);walk(root)
                assertTrue("Caption has no scrollable surface",scrolls.isNotEmpty())
                val caption=scrolls.minBy {n->android.graphics.Rect().also(n::getBoundsInScreen).height()}
                assertTrue("Caption cannot scroll backward",caption.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                inst.uiAutomation.takeScreenshot()?.let {bitmap->c.filesDir.resolve("caption-ui-preview.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                scenario.onActivity {engine.cameraGesture.value++}
                val deadline=System.currentTimeMillis()+5000
                var opened=false
                while(System.currentTimeMillis()<deadline) {
                    fun hasShutter(n:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                        if(n.text?.toString()=="Take photo")return true
                        for(i in 0 until n.childCount)if(n.getChild(i)?.let(::hasShutter)==true)return true
                        return false
                    }
                    inst.uiAutomation.rootInActiveWindow?.let {opened=hasShutter(it)}
                    if(opened)break
                    Thread.sleep(100)
                }
                assertTrue("Camera event did not open in-Puck preview",opened)
                assertEquals(c.packageName,inst.uiAutomation.rootInActiveWindow.packageName)
            } finally {
                engine.status.value=originalStatus;engine.transcript.value=originalCaption
                engine.store.prefs.edit().putBoolean("checkins",checkins).commit()
            }
        }
    }
    @Test fun shutterEventCapturesPreviewsAndReturns() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckPhotoTest")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation
        val c=inst.targetContext;val engine=ApplianceEngine.get(c)
        val original=engine.photo.value;val checkins=engine.store.prefs.getBoolean("checkins",true)
        engine.photo.value=null;engine.store.prefs.edit().putBoolean("checkins",false).commit()
        fun hasText(n:android.view.accessibility.AccessibilityNodeInfo?,text:String):Boolean {
            if(n==null)return false
            if(n.text?.toString()?.contains(text)==true)return true
            for(i in 0 until n.childCount)if(hasText(n.getChild(i),text))return true
            return false
        }
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            try {
                Thread.sleep(700);scenario.onActivity {engine.cameraGesture.value++}
                val ready=System.currentTimeMillis()+12000
                while(!engine.cameraReady && System.currentTimeMillis()<ready)Thread.sleep(100)
                assertTrue("Camera not ready",engine.cameraReady)
                scenario.onActivity {engine.shutterGesture.value++}
                val deadline=System.currentTimeMillis()+10000;var preview=false
                while(System.currentTimeMillis()<deadline){
                    if(hasText(ui.rootInActiveWindow,"Photo attached · returning")){preview=true;break}
                    Thread.sleep(50)
                }
                assertTrue("Captured image preview missing",preview)
                val photo=engine.photo.value;assertTrue("Photo missing",photo?.isFile==true)
                val shownAt=System.currentTimeMillis()
                while(engine.cameraActive && System.currentTimeMillis()-shownAt<4000)Thread.sleep(50)
                assertFalse("Did not return from camera",engine.cameraActive)
                assertTrue("Preview disappeared too quickly",System.currentTimeMillis()-shownAt>=650)
                assertEquals("Photo lost on return",photo,engine.photo.value)
                assertEquals(c.packageName,ui.rootInActiveWindow.packageName)
                photo?.delete() // Test-only room image never leaves the phone.
            } finally {
                engine.photo.value?.takeIf {it!=original}?.delete();engine.photo.value=original
                engine.store.prefs.edit().putBoolean("checkins",checkins).commit()
            }
        }
    }
    @Test fun homeFooterAndAvatarUpdate()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckHomeUpdate")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation;val c=inst.targetContext
        val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val checkins=engine.store.prefs.getBoolean("checkins",true);val oldUpdate=engine.update.value
        val oldStatus=engine.status.value
        engine.store.prefs.edit().putBoolean("checkins",false).commit()
        val runtime=MuseRuntime.get(c);runtime.connect()
        withTimeout(60000){runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        fun find(n:android.view.accessibility.AccessibilityNodeInfo?,match:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
            if(n==null)return null
            if(match(n))return n
            for(i in 0 until n.childCount)find(n.getChild(i),match)?.let {return it}
            return null
        }
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            try {
                scenario.onActivity {engine.dismiss();engine.status.value="Resting"}
                delay(800)
                val root=ui.rootInActiveWindow
                val photo=find(root){it.text?.toString()=="Photo"}?:error("Photo button missing")
                val bounds=android.graphics.Rect().also(photo::getBoundsInScreen)
                val screen=android.graphics.Rect().also(root::getBoundsInScreen)
                // Text bounds are narrow; check its clickable parent (button) for width.
                var button=photo
                while(!button.isClickable && button.parent!=null)button=button.parent
                val buttonBounds=android.graphics.Rect().also(button::getBoundsInScreen)
                assertTrue("Photo not bottom aligned",bounds.centerY()>screen.height()*.80)
                assertTrue("Photo not wide",buttonBounds.width()>screen.width()*.80)
                assertNull("Resting home should not scroll",find(root){it.isScrollable && it.actionList.any {a->a.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD}})
                val pet=find(root){it.contentDescription?.toString()?.startsWith("Get ")==true}?:error("Avatar not accessible")
                assertTrue("Avatar tap handler unavailable",pet.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(5000){engine.status.first {it=="Checking for updates"}}
                withTimeout(150000){engine.status.first {it!="Checking for updates"}}
                c.filesDir.resolve("avatar-update-diagnostic.json").writeText(org.json.JSONObject().put("stage",engine.store.prefs.getString("checkin_stage","unknown")).put("error",engine.store.prefs.getString("checkin_error","none")).put("runtime_status",runtime.setupStatus.value).put("connection",runtime.connection.value.toString()).toString())
                assertEquals("Avatar update failed", "Update ready",engine.status.value)
                assertTrue("Avatar returned no visible update",engine.update.value.isNotBlank())
                c.filesDir.resolve("avatar-update-test-metrics.json").writeText(org.json.JSONObject().put("update_visible",true).put("footer_bottom_wide",true).put("status",engine.status.value).toString())
            } finally {
                withContext(Dispatchers.Main){engine.update.value=oldUpdate;engine.status.value=oldStatus}
                engine.store.prefs.edit().putString("update",oldUpdate).putBoolean("checkins",checkins).commit()
            }
        }
    }
    @Test fun localReadAloudStartsAndStops()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckSpeakerTest")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val speech=withContext(Dispatchers.Main){ApplianceSpeech(c)}
        try {
            withTimeout(15000){speech.ready.first {it}}
            withContext(Dispatchers.Main){speech.read("Puck voice is ready. This is a short speaker test.")}
            withTimeout(5000){speech.speaking.first {it}}
            withContext(Dispatchers.Main){speech.stop()}
            assertFalse(speech.speaking.value)
            val audio=c.getSystemService(android.media.AudioManager::class.java)
            c.filesDir.resolve("speaker-test-metrics.json").writeText(org.json.JSONObject().put("offline_voice_ready",speech.ready.value).put("playback_started",true).put("stopped",true).put("media_volume",audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)).toString())
        } finally {withContext(Dispatchers.Main){speech.close()}}
    }
    @Test fun syncActualMuseAvatarOrPreserveExistingCompanion()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckSyncAvatar")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60000){runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val before=engine.portrait.value
        withContext(Dispatchers.Main){engine.syncPortrait()}
        withTimeout(150000){engine.portraitStatus.first {it!="Linking your Muse avatar…"}}
        val linked=engine.portraitStatus.value=="Muse avatar linked"
        if(linked)assertTrue(engine.portrait.value.image?.isFile==true) else assertEquals(before,engine.portrait.value)
        c.filesDir.resolve("avatar-sync-metrics.json").writeText(org.json.JSONObject().put("original_avatar_linked",linked).put("image_bytes",engine.portrait.value.image?.length()?:0).put("fallback_preserved",!linked).put("stage",engine.store.prefs.getString("portrait_stage","unknown")).toString())
    }
}
