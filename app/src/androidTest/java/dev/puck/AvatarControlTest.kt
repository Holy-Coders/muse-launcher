package dev.puck

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.appliance.AvatarControl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarControlTest {
    @get:Rule val compose=createComposeRule()
    private val label="Avatar: tap to record, tap again to send; hold and release to send; double tap for camera"
    @Test fun movingAvatarForCaptionsKeepsTheOriginalHoldBounds() {
        var shifted by mutableStateOf(false)
        var starts=0;var ends=0;var cancels=0
        compose.setContent {MaterialTheme {
            AvatarControl(Modifier.offset(y=if(shifted)160.dp else 0.dp).size(200.dp),{},
                {starts++;shifted=true},{ends++},{},{cancels++}){Text("Fixture Muse")}
        }}
        val avatar=compose.onNodeWithContentDescription(label)
        val initial=avatar.fetchSemanticsNode().boundsInRoot.center
        avatar.performTouchInput {down(center)}
        Thread.sleep(600);compose.waitForIdle()
        val moved=avatar.fetchSemanticsNode().boundsInRoot
        avatar.performTouchInput {moveTo(Offset(initial.x-moved.left,initial.y-moved.top));up()}
        compose.runOnIdle {assertEquals(1,starts);assertEquals(1,ends);assertEquals(0,cancels)}
    }
    @Test fun singleDoubleHoldAndCancelDispatchOnlyTheirIntendedEffects() {
        var toggles=0;var starts=0;var ends=0;var cameras=0;var cancelled=0
        var visible by mutableStateOf(true)
        compose.setContent {MaterialTheme {if(visible)AvatarControl(Modifier.size(200.dp),{toggles++},{starts++},{ends++},{cameras++},{cancelled++}){Text("Fixture Muse")} else Text("Gone")}}
        val avatar=compose.onNodeWithContentDescription(label)
        avatar.performTouchInput {click()}
        Thread.sleep(450);compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,toggles);assertEquals(0,starts)}
        avatar.performTouchInput {doubleClick()}
        Thread.sleep(450);compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,cameras);assertEquals(1,toggles);assertEquals(0,starts)}
        avatar.performTouchInput {down(center)}
        Thread.sleep(600)
        avatar.performTouchInput {up()}
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,starts);assertEquals(1,ends)}
        avatar.performTouchInput {down(center)}
        Thread.sleep(600)
        avatar.performTouchInput {cancel()}
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(2,starts);assertEquals(1,ends);assertEquals(1,cancelled)}
        avatar.performTouchInput {down(center)}
        // Leaving the composition cancels an unfinished press rather than submitting it.
        compose.runOnIdle {visible=false}
        Thread.sleep(450);compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,toggles)}
    }
}
