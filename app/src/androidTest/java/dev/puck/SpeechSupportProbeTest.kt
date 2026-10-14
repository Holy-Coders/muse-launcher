package dev.puck

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Queries provider language support without opening the microphone. */
class SpeechSupportProbeTest {
    @Test fun reportOfflineLanguageSupportAndRequestMissingModel() = probe(true)
    @Test fun reportOfflineLanguageSupportOnly() = probe(false)
    private fun probe(requestMissing: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            println("PUCK_SUPPORT on-device support query unavailable")
            return
        }
        val done = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null
        instrumentation.runOnMainSync {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onResults(results: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer!!.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val language = Locale.getDefault().toLanguageTag()
                    println("PUCK_SUPPORT language=$language installed=${support.installedOnDeviceLanguages} pending=${support.pendingOnDeviceLanguages} supported=${support.supportedOnDeviceLanguages}")
                    if (requestMissing && support.pendingOnDeviceLanguages.none { it.equals(language, true) } && support.installedOnDeviceLanguages.none { it.equals(language, true) } && support.supportedOnDeviceLanguages.any { it.equals(language, true) }) {
                        recognizer!!.triggerModelDownload(intent)
                        println("PUCK_SUPPORT model download requested; completion not verified")
                    }
                    done.countDown()
                }
                override fun onError(error: Int) { println("PUCK_SUPPORT query error=$error"); done.countDown() }
            })
        }
        if (!done.await(8, TimeUnit.SECONDS)) println("PUCK_SUPPORT query timed out; model state unknown")
        instrumentation.runOnMainSync { recognizer?.destroy() }
    }
}
