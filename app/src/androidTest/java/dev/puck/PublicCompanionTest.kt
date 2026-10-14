package dev.puck

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.appliance.HoldControl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real screen gestures, no microphone/network and no physical-key certification. */
@RunWith(AndroidJUnit4::class)
class PublicCompanionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun touchHoldReleasesOnceAndDragOutCancels() {
        var pressed=0;var released=0;var cancelled=0
        compose.setContent {MaterialTheme {HoldControl("Fixture Talk",press={pressed++},release={released++},cancel={cancelled++})}}
        compose.onNodeWithContentDescription("Fixture Talk").performTouchInput {down(center);advanceEventTime(700);up()}
        compose.runOnIdle {assertEquals(1,pressed);assertEquals(1,released);assertEquals(0,cancelled)}
        compose.onNodeWithContentDescription("Fixture Talk").performTouchInput {down(center);advanceEventTime(700);moveTo(Offset(center.x,height.toFloat()+50));up()}
        compose.runOnIdle {assertEquals(2,pressed);assertEquals(1,released);assertEquals(1,cancelled)}
    }
    @Test fun pointerCancellationDoesNotSend() {
        var sent=0;var cancelled=0
        compose.setContent {MaterialTheme {HoldControl("Fixture Talk",press={},release={sent++},cancel={cancelled++})}}
        compose.onNodeWithContentDescription("Fixture Talk").performTouchInput {down(center);advanceEventTime(700);cancel()}
        compose.runOnIdle {assertEquals(0,sent);assertEquals(1,cancelled)}
    }
}
