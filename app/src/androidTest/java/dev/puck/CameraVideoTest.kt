package dev.puck

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Application gesture requests, not hardware keys. Silent room-video fixtures stay private and are deleted. */
@RunWith(AndroidJUnit4::class)
class CameraVideoTest {
    @Test fun releaseFinalizesVideoAndBackgroundCancels()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckVideoTest")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();inst.uiAutomation
        val c=inst.targetContext;val engine=withContext(Dispatchers.Main){ApplianceEngine.get(c)}
        val oldPendingVideo=engine.pendingVideo.value;val oldPhoto=engine.photo.value
        withContext(Dispatchers.Main){engine.photo.value=null}
        val oldVideo=engine.video.value;val oldDismissed=engine.videoDismissed.value;val oldStatus=engine.status.value
        val prefs=engine.store.prefs;val audio=prefs.getBoolean("video_audio",true);val checks=prefs.getBoolean("checkins",true)
        prefs.edit().putBoolean("video_audio",false).putBoolean("checkins",false).commit()
        val initial=engine.store.dir.listFiles()?.toSet().orEmpty()
        androidx.test.core.app.ActivityScenario.launch(ApplianceActivity::class.java).use {scenario->
            try {
                delay(500);prefs.edit().putString("video_test_stage","opening").commit();scenario.onActivity {engine.cameraGesture.value++}
                withTimeout(15000){while(!engine.cameraReady)delay(100)}
                scenario.onActivity {engine.videoRequest.value=ApplianceEngine.VideoRequest(engine.videoRequest.value.id+1,true)}
                prefs.edit().putString("video_test_stage","starting").commit()
                withTimeout(5000){engine.videoRecording.first {it}}
                prefs.edit().putString("video_test_stage","recording").commit();delay(2500)
                scenario.onActivity {engine.videoRequest.value=engine.videoRequest.value.copy(held=false)}
                prefs.edit().putString("video_test_stage","finalizing").commit()
                withTimeout(15000){while(engine.video.value==oldVideo)delay(100)}
                prefs.edit().putString("video_test_stage","metadata").commit()
                val clip=engine.video.value!!
                val metrics=MediaMetadataRetriever().use {r->
                    r.setDataSource(clip.path)
                    assertEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Clip too short or empty",duration>=1000)
                    val frame=r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,160,160)
                    assertNotNull("No decodable video frame",frame);frame?.recycle()
                    org.json.JSONObject().put("duration_ms",duration).put("bytes",clip.length()).put("decodable_frame",true).put("audio_disabled_for_test",true)
                }
                withTimeout(5000){while(engine.cameraActive)delay(50)}
                assertFalse(engine.videoRecording.value)
                prefs.edit().putString("video_test_stage","reopening").commit()
                scenario.onActivity {engine.cameraGesture.value++}
                withTimeout(15000){while(!engine.cameraReady)delay(100)}
                scenario.onActivity {engine.videoRequest.value=ApplianceEngine.VideoRequest(engine.videoRequest.value.id+1,true)}
                withTimeout(5000){engine.videoRecording.first {it}}
                delay(1200)
                prefs.edit().putString("video_test_stage","background_cancel").commit()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                withTimeout(5000){while(engine.videoRecording.value)delay(50)}
                delay(1000)
                assertEquals("Cancelled clip replaced saved video",clip,engine.video.value)
                val extras=engine.store.dir.listFiles()?.filter {it.extension=="mp4" && it !in initial && it!=clip}.orEmpty()
                assertTrue("Cancelled clip left behind",extras.isEmpty())
                c.filesDir.resolve("video-test-metrics.json").writeText(metrics.put("background_cancel_passed",true).toString())
            } finally {
                engine.store.dir.listFiles()?.filter {it.extension=="mp4" && it !in initial}?.forEach {it.delete()}
                withContext(Dispatchers.Main){engine.photo.value=oldPhoto;engine.pendingVideo.value=oldPendingVideo;engine.video.value=oldVideo;engine.videoDismissed.value=oldDismissed;engine.status.value=oldStatus}
                val edit=prefs.edit().putBoolean("video_pending",oldPendingVideo!=null).putBoolean("video_dismissed",oldDismissed).putBoolean("video_audio",audio).putBoolean("checkins",checks)
                if(oldVideo==null)edit.remove("last_video") else edit.putString("last_video",oldVideo.name)
                edit.commit()
            }
        }
    }
}
