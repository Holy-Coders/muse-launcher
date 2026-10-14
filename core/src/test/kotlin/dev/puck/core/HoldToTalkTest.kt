package dev.puck.core

import org.junit.Assert.*
import org.junit.Test

class HoldToTalkTest {
    // Test code 777 is a synthetic fixture, never an Android/vendor mapping.
    private fun controller() = HoldToTalk { "utterance-1" }.also { it.armObservedKey(777) }
    @Test fun unconfiguredOrDifferentKeysNeverStart() {
        assertEquals(HoldEffect.Ignore, HoldToTalk { "id" }.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Ignore, controller().onKey(778, true, 0, true))
    }
    @Test fun releaseWaitsForFinalTranscriptAndSubmitsOnce() {
        val c = controller()
        assertEquals(HoldEffect.Start("utterance-1"), c.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Stop("utterance-1"), c.onKey(777, false, 0, true))
        assertEquals(HoldEffect.Submit("utterance-1", "text Hello"), c.transcript("utterance-1", " text Hello "))
        assertEquals(HoldEffect.Ignore, c.transcript("utterance-1", "duplicate"))
        assertEquals(HoldEffect.Consume, c.onKey(777, false, 0, true))
    }
    @Test fun earlyFinalResultStillWaitsForRelease() {
        val c = controller()
        c.onKey(777, true, 0, true)
        assertEquals(HoldEffect.Consume, c.transcript("utterance-1", "camera"))
        assertEquals(HoldEffect.Submit("utterance-1", "camera"), c.onKey(777, false, 0, true))
    }
    @Test fun repeatsAndRepeatedDownDoNotStartAgain() {
        val c = controller()
        c.onKey(777, true, 0, true)
        assertEquals(HoldEffect.Consume, c.onKey(777, true, 5, true))
        assertEquals(HoldEffect.Consume, c.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Stop("utterance-1"), c.onKey(777, false, 0, true))
    }
    @Test fun releaseIsHonoredEvenIfItCarriesARepeatCount() {
        val c = controller()
        c.onKey(777, true, 0, true)
        assertEquals(HoldEffect.Stop("utterance-1"), c.onKey(777, false, 3, true))
        assertEquals(HoldEffect.Consume, c.onKey(777, false, 3, true))
    }
    @Test fun denialLockedBackgroundAndUnavailableAreBlocked() {
        val c = controller()
        assertEquals(HoldEffect.Blocked, c.onKey(777, true, 0, false))
        assertEquals(HoldEffect.Consume, c.onKey(777, true, 0, true)) // Permission callback cannot start.
        c.onKey(777, false, 0, true)
        assertEquals(HoldEffect.Start("utterance-1"), c.onKey(777, true, 0, true))
    }
    @Test fun interruptionRequiresReleaseAndRejectsLateTranscripts() {
        val c = controller()
        c.onKey(777, true, 0, true)
        c.interrupt()
        assertEquals(HoldEffect.Ignore, c.transcript("utterance-1", "late"))
        assertEquals(HoldEffect.Consume, c.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Consume, c.onKey(777, false, 0, true))
        assertEquals(HoldEffect.Start("utterance-1"), c.onKey(777, true, 0, true))
    }
    @Test fun interruptionAfterReleaseCancelsPendingSubmission() {
        val c = controller()
        c.onKey(777, true, 0, true)
        c.onKey(777, false, 0, true)
        c.interrupt()
        assertEquals(HoldEffect.Ignore, c.transcript("utterance-1", "late"))
    }
    @Test fun lateResultFromOldUtteranceCannotSubmitTheNextOne() {
        var count = 0
        val c = HoldToTalk { "voice-${++count}" }.also { it.armObservedKey(777) }
        c.onKey(777, true, 0, true)
        c.onKey(777, false, 0, true)
        c.interrupt()
        assertEquals(HoldEffect.Start("voice-2"), c.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Ignore, c.transcript("voice-1", "stale"))
        c.onKey(777, false, 0, true)
        assertEquals(HoldEffect.Submit("voice-2", "new request"), c.transcript("voice-2", "new request"))
    }
    @Test fun ineligibleReleaseAndBlankResultNeverSubmit() {
        val c = controller()
        c.onKey(777, true, 0, true)
        assertEquals(HoldEffect.Consume, c.onKey(777, false, 0, false))
        assertEquals(HoldEffect.Ignore, c.transcript("utterance-1", "not safe"))
        c.onKey(777, true, 0, true)
        c.onKey(777, false, 0, true)
        assertEquals(HoldEffect.Consume, c.transcript("utterance-1", "  "))
    }
    @Test fun captureTestNeverSubmitsEvenWithFinalResultBeforeOrAfterRelease() {
        for (early in listOf(true, false)) {
            val c = HoldToTalk(reviewOnly = true) { "trial" }.also { it.armObservedKey(777) }
            assertEquals(HoldEffect.Start("trial"), c.onKey(777, true, 0, true))
            assertEquals(HoldEffect.Consume, c.onKey(777, true, 8, true))
            if (early) {
                assertEquals(HoldEffect.Consume, c.transcript("trial", "camera"))
                assertEquals(HoldEffect.Review("trial", "camera"), c.onKey(777, false, 0, true))
            } else {
                assertEquals(HoldEffect.Stop("trial"), c.onKey(777, false, 0, true))
                assertEquals(HoldEffect.Review("trial", "camera"), c.transcript("trial", "camera"))
            }
            assertEquals(HoldEffect.Ignore, c.transcript("trial", "duplicate"))
            assertEquals(HoldEffect.Consume, c.onKey(777, false, 0, true))
        }
    }
    @Test fun captureTestInterruptRejectsLateResultAndRequiresRelease() {
        val c = HoldToTalk(reviewOnly = true) { "trial" }.also { it.armObservedKey(777) }
        c.onKey(777, true, 0, true)
        c.interrupt()
        assertEquals(HoldEffect.Ignore, c.transcript("trial", "late"))
        assertEquals(HoldEffect.Consume, c.onKey(777, true, 0, true))
        assertEquals(HoldEffect.Consume, c.onKey(777, false, 0, true))
        assertEquals(HoldEffect.Start("trial"), c.onKey(777, true, 0, true))
    }

}
