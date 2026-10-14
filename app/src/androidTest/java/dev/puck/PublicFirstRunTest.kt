package dev.puck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.appliance.ApplianceActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a clean unpaired test install, never clears personal device state. */
@RunWith(AndroidJUnit4::class)
class PublicFirstRunTest {
    @get:Rule val compose=createAndroidComposeRule<ApplianceActivity>()
    @Test fun cleanSetupExplainsSecondPhoneAndOptionalRolesThenOffersTouchTalk() {
        val c=compose.activity
        assumeTrue(!MuseRuntime.get(c).vault.hasCredentials())
        compose.onNodeWithText("Meet your Muse").assertIsDisplayed()
        compose.onNodeWithText("You need Muse signed in on a second phone for Bluetooth pairing. This phone needs internet and Bluetooth. No root or Wi-Fi password is required.").assertExists()
        compose.onNodeWithText("Start Muse Launcher").assertIsNotEnabled()
        assertNull(dev.puck.appliance.ApplianceStore(c).key)
        val role=c.getSystemService(android.app.role.RoleManager::class.java)
        assertFalse("First run replaced Home without consent",role.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
        compose.onNodeWithText("Try Muse Launcher before connecting").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Avatar: tap to record, tap again to send; hold and release to send; double tap for camera").assertIsDisplayed()
        assertTrue(c.getSharedPreferences("appliance",0).getBoolean("onboarding_done",false))
        assertNull("Onboarding armed a hardware key",dev.puck.appliance.ApplianceStore(c).key)
        compose.onNodeWithText("Avatar controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Avatar controls info").performClick()
        compose.onNodeWithText("Avatar controls").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("Avatar controls").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Observe physical button").assertDoesNotExist()
        compose.onNodeWithContentDescription("Launcher developer mode").performScrollTo().performClick()
        compose.onNodeWithText("Observe physical button").assertExists()
        compose.onNodeWithContentDescription("Launcher developer mode").performScrollTo().performClick()
        compose.onNodeWithText("Observe physical button").assertDoesNotExist()
    }
}
