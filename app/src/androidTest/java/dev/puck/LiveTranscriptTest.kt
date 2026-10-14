package dev.puck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.LiveTranscript
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** Generated PCM only: proves partial captions before release, without opening a microphone. */
@RunWith(AndroidJUnit4::class)
class LiveTranscriptTest {
    @Test fun partialTextArrivesBeforeReleaseAndCancelStopsInput() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLocalCaptions")=="true")
        val inst=InstrumentationRegistry.getInstrumentation()
        val c=inst.targetContext
        val prefs=c.getSharedPreferences("appliance",android.content.Context.MODE_PRIVATE)
        val original=prefs.getBoolean("accurate_captions",false)
        val enhanced=InstrumentationRegistry.getArguments().getString("puckCaptionMode")!="fast"
        prefs.edit().putBoolean("accurate_captions",enhanced).commit()
        withContext(Dispatchers.IO) {LiveTranscript.warm(c)}
        val bytes=inst.uiAutomation.executeShellCommand("cat /sdcard/Download/puck-generated-voice-test.wav").let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        val started=android.os.SystemClock.elapsedRealtime()
        val first=AtomicLong(0)
        val final=CompletableDeferred<String>()
        val texts=java.util.concurrent.CopyOnWriteArrayList<String>()
        val captions=LiveTranscript(c,{value->if(value.isNotBlank()){texts.add(value);first.compareAndSet(0,android.os.SystemClock.elapsedRealtime()-started)}},
            {state->if(state=="Your words · preview")final.complete(texts.lastOrNull().orEmpty())})
        try {
            for(offset in 44 until bytes.size step 3200) {
                val chunk=bytes.copyOfRange(offset,minOf(offset+3200,bytes.size))
                captions.accept(chunk,chunk.size);delay(100)
            }
            withTimeout(5000) {while(texts.isEmpty())delay(20)}
            assertTrue("No partial text before release",first.get()>0)
            captions.finish()
            val result=withTimeout(10000) {final.await()}
            assertTrue("Caption fixture not recognized",result.contains("audio") || result.contains("works"))
            captions.cancel()
            val count=texts.size
            captions.accept(bytes.copyOfRange(44,3244),3200);delay(200)
            assertEquals("Cancelled caption session accepted audio",count,texts.size)
            val secondFinal=CompletableDeferred<String>()
            var secondText=""
            val second=LiveTranscript(c,{secondText=it},{if(it=="Your words · preview")secondFinal.complete(secondText)})
            try {
                for(offset in 44 until bytes.size step 3200){val chunk=bytes.copyOfRange(offset,minOf(offset+3200,bytes.size));second.accept(chunk,chunk.size)}
                second.finish()
                val again=withTimeout(15000){secondFinal.await()}
                assertTrue("Reused recognizer retained stale state or failed",again.contains("audio") || again.contains("works"))
            } finally {second.cancel()}
            c.filesDir.resolve("local-caption-test-metrics.json").writeText(org.json.JSONObject().put("first_partial_ms",first.get()).put("partial_updates",count).put("passed",true).put("enhanced",enhanced).put("reused_recognizer_passed",true).toString())
        } finally {captions.cancel();prefs.edit().putBoolean("accurate_captions",original).commit()}
    }
}
