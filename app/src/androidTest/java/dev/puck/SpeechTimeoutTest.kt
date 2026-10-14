package dev.puck

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SpeechTimeoutTest {
    private class SilentSession : SpeechSession {
        lateinit var callback: RecognitionListener
        var cancelled = false
        var destroyed = false
        var intent: Intent? = null
        override fun setListener(listener: RecognitionListener) { this.callback = listener }
        override fun start(intent: Intent) { this.intent = intent }
        override fun stop() {}
        override fun cancel() { cancelled = true }
        override fun destroy() { destroyed = true }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
    @Test fun missingFinalTimesOutPreservesPartialAndRejectsLateResult() {
        val sessions = mutableListOf<SilentSession>()
        var draft = ""
        var failures = 0
        var finals = 0
        lateinit var speech: SpeechInput
        main {
            speech = SpeechInput(instrumentation.targetContext, { draft = it },
                { SilentSession().also(sessions::add) }, 2_000, 60, { true })
            speech.onFailure = { failures++ }
            speech.onFinalResult = { finals++ }
            speech.start()
            sessions[0].callback.onPartialResults(result("saved partial"))
            speech.stop()
        }
        Thread.sleep(180)
        main {
            assertFalse(speech.processing)
            assertFalse(speech.listening)
            assertTrue(speech.message.contains("timed out"))
            assertTrue(sessions[0].cancelled && sessions[0].destroyed)
            assertEquals("saved partial", draft)
            assertEquals(1, failures)
            speech.start()
            sessions[0].callback.onResults(result("stale result"))
            assertEquals("saved partial", draft)
            assertEquals(0, finals)
            sessions[1].callback.onResults(result("fresh result"))
            assertEquals("fresh result", draft)
            assertEquals(1, finals)
        }
    }
    @Test fun silenceEndTimesOutAndInterruptionCancelsDeadline() {
        val session = SilentSession()
        var failures = 0
        lateinit var speech: SpeechInput
        main {
            speech = SpeechInput(instrumentation.targetContext, {}, { session }, 2_000, 60, { true })
            speech.onFailure = { failures++ }
            speech.start()
            session.callback.onEndOfSpeech()
        }
        Thread.sleep(180)
        main {
            assertFalse(speech.processing)
            assertEquals(1, failures)
            speech.start()
            speech.stop()
            speech.interrupt("Interrupted")
        }
        Thread.sleep(180)
        main {
            assertEquals(1, failures)
            assertEquals("Interrupted", speech.message)
            assertFalse(speech.processing)
        }
    }
    @Test fun stalledCaptureTimesOutWithoutSubmitting() {
        val session = SilentSession()
        var finals = 0
        lateinit var speech: SpeechInput
        main {
            speech = SpeechInput(instrumentation.targetContext, {}, { session }, 60, 2_000, { true })
            speech.onFinalResult = { finals++ }
            speech.start()
        }
        Thread.sleep(180)
        main {
            assertFalse(speech.listening)
            assertFalse(speech.processing)
            assertTrue(session.cancelled && session.destroyed)
            session.callback.onResults(result("late"))
            assertEquals(0, finals)
        }
    }

    @Test fun explicitSystemChoiceSelectsSystemBackendEvenWhenOfflineServiceExists() {
        val session = SilentSession()
        var selectedOffline: Boolean? = null
        main {
            val speech = SpeechInput(instrumentation.targetContext, {}, {
                selectedOffline = it; session
            }, deviceAvailable = { true })
            speech.start(networkAllowed = true)
            assertEquals(false, selectedOffline)
            assertFalse(session.intent!!.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            speech.interrupt()
            speech.start()
            assertEquals(true, selectedOffline)
            assertTrue(session.intent!!.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            speech.interrupt()
        }
    }

}
