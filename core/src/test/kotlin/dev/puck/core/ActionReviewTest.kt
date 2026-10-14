package dev.puck.core

import org.junit.Assert.*
import org.junit.Test

class ActionReviewTest {
    private val surfaces = listOf(DeviceAction.Camera, DeviceAction.Navigate("London"), DeviceAction.Calendar("Lunch"), DeviceAction.OpenApp("dev.test", "dev.test.Main"))
    @Test fun requestedSurfacesCanSkipReviewButUnsolicitedOnesCannot() {
        for (action in surfaces) {
            assertTrue(ActionReview.mayOpenImmediately(action, true))
            assertFalse(ActionReview.mayOpenImmediately(action, false))
        }
    }
    @Test fun communicationsKeepReviewEvenWithAnExplicitRecipient() {
        for (action in listOf(DeviceAction.Dial(), DeviceAction.Dial("+123456789"), DeviceAction.Message("Hello"), DeviceAction.Message("Hello", "+123456789"))) {
            assertFalse(ActionReview.mayOpenImmediately(action, true))
            assertFalse(ActionReview.mayOpenImmediately(action, false))
        }
    }
}
