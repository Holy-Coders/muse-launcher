package dev.puck

import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Queries availability without recording audio, submitting transcripts or changing permissions. */
@RunWith(AndroidJUnit4::class)
class SpeechReadinessTest {
    @Test fun recordRecognizerReadinessWithoutStartingCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val onDevice = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        context.filesDir.resolve("speech-readiness.json").writeText(JSONObject().put("microphoneGranted", permission).put("recognizerAvailable", available).put("onDeviceAvailable", onDevice).toString())
        assertEquals(permission, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
}
