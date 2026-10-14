package dev.puck.appliance

import org.junit.Assert.*
import org.junit.Test
import dev.puck.appliance.AvatarGesture.Effect.*

class AvatarGestureTest {
    @Test fun singleTapWaitsForDoubleWindowThenTogglesOnce(){val g=AvatarGesture();assertEquals(NONE,g.down(0));assertEquals(NONE,g.up(50));assertEquals(NONE,g.tick(399));assertEquals(TOGGLE,g.tick(400));assertEquals(NONE,g.tick(900))}
    @Test fun doubleTapNeverStartsRecording(){val g=AvatarGesture();g.down(0);g.up(50);assertEquals(NONE,g.down(200));assertEquals(CAMERA,g.up(250));assertEquals(NONE,g.tick(1000))}
    @Test fun holdStartsAtThresholdAndEndsOnce(){val g=AvatarGesture();g.down(0);assertEquals(NONE,g.tick(449));assertEquals(HOLD_START,g.tick(450));assertEquals(NONE,g.tick(1000));assertEquals(HOLD_END,g.up(1200));assertEquals(NONE,g.up(1400));assertEquals(NONE,g.tick(2000))}
    @Test fun holdingSecondPressCancelsPendingTap(){val g=AvatarGesture();g.down(0);g.up(50);g.down(200);assertEquals(HOLD_START,g.tick(650));assertEquals(HOLD_END,g.up(900));assertEquals(NONE,g.tick(1500))}
    @Test fun cancellationPreventsDelayedTapAndHoldSend(){val g=AvatarGesture();g.down(0);g.up(50);assertEquals(CANCEL,g.cancel());assertEquals(NONE,g.tick(900));g.down(1000);g.tick(1450);assertEquals(CANCEL,g.cancel());assertEquals(NONE,g.up(1700));g.down(2000);assertEquals(HOLD_START,g.tick(2450))}
}
