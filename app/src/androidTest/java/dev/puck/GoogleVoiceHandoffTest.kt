package dev.puck

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.core.DeviceAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device integration probe: resolves handlers without calling or sending. */
@RunWith(AndroidJUnit4::class)
class GoogleVoiceHandoffTest {
    @Test fun installedVoiceHandlesReviewedDialWithoutPlacingACall() {
        val intent = requireNotNull(googleVoiceIntent(DeviceAction.Dial("+18005550100")))
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(intent, 0).any { it.activityInfo.packageName == "com.google.android.apps.googlevoice" })
    }
    @Test fun installedVoiceHandlesTextShareWithoutAssumingRecipientOrSending() {
        val intent = requireNotNull(googleVoiceIntent(DeviceAction.Message(body = "Fixture draft", number = "+18005550100")))
        assertEquals("Fixture draft", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.data) // No unsupported recipient URI or guessed extra.
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(intent, 0).any { it.activityInfo.packageName == "com.google.android.apps.googlevoice" })
    }
}
