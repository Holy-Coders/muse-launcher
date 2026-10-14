package dev.puck.appliance
import org.junit.Assert.*
import org.junit.Test
class ButtonGestureTest {
    @Test fun tapOnlyWakes() {
        val b = ButtonGesture()
        assertEquals(ButtonGesture.Effect.WAKE, b.down(0, true))
        assertEquals(ButtonGesture.Effect.NONE, b.tick(449))
        assertEquals(ButtonGesture.Effect.NONE, b.up(449))
        assertEquals(ButtonGesture.Effect.NONE, b.tick(1000))
    }
    @Test fun holdRecordsOnceAndReleaseSendsOnce() {
        val b = ButtonGesture()
        b.down(0, true)
        assertEquals(ButtonGesture.Effect.RECORD, b.tick(450))
        assertEquals(ButtonGesture.Effect.NONE, b.down(500, true))
        assertEquals(ButtonGesture.Effect.NONE, b.tick(1000))
        assertEquals(ButtonGesture.Effect.SEND, b.up(1100))
        assertEquals(ButtonGesture.Effect.NONE, b.up(1200))
    }
    @Test fun interruptionCannotSendAndRequiresFreshPress() {
        val b = ButtonGesture()
        b.down(0,true); b.tick(500)
        assertEquals(ButtonGesture.Effect.CANCEL,b.cancel())
        assertEquals(ButtonGesture.Effect.NONE,b.tick(1000))
        assertEquals(ButtonGesture.Effect.NONE,b.down(1100,true))
        assertEquals(ButtonGesture.Effect.NONE,b.up(1150))
        assertEquals(ButtonGesture.Effect.WAKE,b.down(1200,true))
    }
    @Test fun permissionGrantWhileHeldCannotStartCapture() {
        val b=ButtonGesture(); b.down(0,false)
        assertEquals(ButtonGesture.Effect.NONE,b.tick(2000)); b.up(2500)
        b.down(3000,true); assertEquals(ButtonGesture.Effect.RECORD,b.tick(3500))
    }
    @Test fun doubleTapOpensCameraWithoutRecordingOrSending() {
        val b=ButtonGesture()
        b.down(0,true);assertEquals(ButtonGesture.Effect.NONE,b.up(80))
        assertEquals(ButtonGesture.Effect.NONE,b.up(90))
        b.down(200,true);assertEquals(ButtonGesture.Effect.CAMERA,b.up(260))
        assertEquals(ButtonGesture.Effect.NONE,b.tick(1000))
    }
    @Test fun slowTapsAndSecondPressHoldCannotOpenCamera() {
        val b=ButtonGesture()
        b.down(0,true);b.up(80);b.down(600,true)
        assertEquals(ButtonGesture.Effect.NONE,b.up(660))
        b.down(800,true);assertEquals(ButtonGesture.Effect.RECORD,b.tick(1250))
        assertEquals(ButtonGesture.Effect.SEND,b.up(1300))
    }
    @Test fun cancelClearsPendingTapAndCameraDoesNotRequireMicrophonePermission() {
        val b=ButtonGesture()
        b.down(0,true);b.up(80);b.cancel();b.down(120,true)
        assertEquals(ButtonGesture.Effect.NONE,b.up(180))
        val camera=ButtonGesture()
        camera.down(0,false);camera.up(80);camera.down(160,false)
        assertEquals(ButtonGesture.Effect.CAMERA,camera.up(220))
        val busy=ButtonGesture()
        busy.down(0,false,false);busy.up(80);busy.down(160,false,false)
        assertEquals(ButtonGesture.Effect.NONE,busy.up(220))
    }
    @Test fun cameraTapTakesOnePhotoButHoldStillRecords() {
        val b=ButtonGesture()
        b.down(0,true,false,true)
        assertEquals(ButtonGesture.Effect.NONE,b.tick(100))
        assertEquals(ButtonGesture.Effect.PHOTO,b.up(100))
        assertEquals(ButtonGesture.Effect.NONE,b.up(110))
        b.down(200,true,false,true);b.tick(650)
        assertEquals(ButtonGesture.Effect.SEND,b.up(800))
        b.down(900,true,false,true);b.cancel()
        assertEquals(ButtonGesture.Effect.NONE,b.up(1000))
        b.down(1100,true,false,false)
        assertEquals(ButtonGesture.Effect.NONE,b.up(1200))
    }
    @Test fun cameraHoldStartsAndStopsVideoOnceWithoutVoiceOrPhoto() {
        val b=ButtonGesture()
        b.down(0,true,false,true,true)
        assertEquals(ButtonGesture.Effect.NONE,b.tick(449))
        assertEquals(ButtonGesture.Effect.VIDEO_START,b.tick(450))
        assertEquals(ButtonGesture.Effect.NONE,b.tick(600))
        assertEquals(ButtonGesture.Effect.VIDEO_STOP,b.up(900))
        assertEquals(ButtonGesture.Effect.NONE,b.up(910))
        b.down(1000,true,false,true,true)
        assertEquals(ButtonGesture.Effect.PHOTO,b.up(1100))
        b.down(1200,true,false,true,true);b.tick(1650)
        assertEquals(ButtonGesture.Effect.VIDEO_CANCEL,b.cancel())
        assertEquals(ButtonGesture.Effect.NONE,b.up(1900))
        b.down(2000,false,false,false,true)
        assertEquals(ButtonGesture.Effect.NONE,b.tick(2500))
        assertEquals(ButtonGesture.Effect.NONE,b.up(2600))
        b.down(3000,true,false,true,true);b.up(3100)
        b.down(3200,true,false,true,true)
        assertEquals(ButtonGesture.Effect.PHOTO,b.up(3300))
    }
}
