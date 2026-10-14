package dev.puck

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.ApplianceActivity
import dev.puck.appliance.PcmCapture
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in hardware capture; never submits recorded audio or changes button verification. */
@RunWith(AndroidJUnit4::class)
class ApplianceCaptureTest {
    @Test fun microphoneWritesWavAndCancellationDeletesAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckHardwareCapture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(c.cacheDir,"capture-fixture-${UUID.randomUUID()}.wav")
        ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            val captionBytes=java.util.concurrent.atomic.AtomicInteger()
            val capture=PcmCapture(file,{_,n->captionBytes.addAndGet(n)})
            val engine=dev.puck.appliance.ApplianceEngine.get(c)
            val waitUntil=System.currentTimeMillis()+3000
            while(engine.microphoneMode==null && System.currentTimeMillis()<waitUntil)Thread.sleep(50)
            assertNotNull("Idle foreground service unavailable",engine.microphoneMode)
            try {
                scenario.onActivity {engine.microphoneMode!!.invoke(true);capture.start()}
                Thread.sleep(500)
                assertNotNull(capture.finish())
                val bytes=file.readBytes()
                assertEquals("RIFF",String(bytes.copyOfRange(0,4)))
                assertEquals("WAVE",String(bytes.copyOfRange(8,12)))
                assertEquals(16000,java.nio.ByteBuffer.wrap(bytes,24,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
                assertTrue(bytes.size>3244)
                assertEquals("Caption feed changed captured audio",bytes.size-44,captionBytes.get())
                assertTrue("Microphone returned only zeros",bytes.drop(44).any {it.toInt()!=0})
            } finally {capture.cancel();scenario.onActivity {engine.microphoneMode?.invoke(false)};file.delete()}
            assertFalse(file.exists())
        }
    }
}
