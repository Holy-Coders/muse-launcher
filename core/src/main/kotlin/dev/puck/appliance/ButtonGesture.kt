package dev.puck.appliance

/** Time supplied by a monotonic clock. No key code or Android dependency. */
class ButtonGesture(private val threshold: Long = 450,private val doubleTapWindow:Long=350) {
    enum class Effect { NONE, WAKE, RECORD, SEND, CANCEL, CAMERA, PHOTO, VIDEO_START, VIDEO_STOP, VIDEO_CANCEL }
    private var pressedAt: Long? = null
    private var recording = false
    private var blockedUntilUp = false
    private var cancelledPress=false
    private var cameraEligible=false
    private var shutterEligible=false
    private var cameraMode=false
    private var lastTapAt:Long?=null
    fun down(now: Long, eligible: Boolean, cameraEligible:Boolean=true, shutterEligible:Boolean=false,cameraMode:Boolean=false): Effect {
        if (pressedAt != null || blockedUntilUp) return Effect.NONE
        pressedAt = now
        this.cameraEligible=cameraEligible
        this.shutterEligible=shutterEligible
        this.cameraMode=cameraMode
        cancelledPress=false
        if (!eligible) blockedUntilUp = true
        return Effect.WAKE
    }
    fun tick(now: Long): Effect {
        val start = pressedAt ?: return Effect.NONE
        if (blockedUntilUp || recording || now - start < threshold) return Effect.NONE
        recording = true
        lastTapAt=null
        return if(cameraMode)Effect.VIDEO_START else Effect.RECORD
    }
    fun up(now:Long): Effect {
        if(pressedAt==null)return Effect.NONE
        val wasRecording = recording
        val valid = pressedAt != null && !blockedUntilUp
        val shortTap=pressedAt?.let {now-it in 0 until threshold}==true && !wasRecording && !cancelledPress && (cameraEligible || shutterEligible)
        pressedAt = null; recording = false; blockedUntilUp = false
        val shutter=shutterEligible
        val wasCamera=cameraMode
        cancelledPress=false;cameraEligible=false;shutterEligible=false;cameraMode=false
        if(valid && wasRecording){lastTapAt=null;return if(wasCamera)Effect.VIDEO_STOP else Effect.SEND}
        if(shortTap && shutter){lastTapAt=null;return Effect.PHOTO}
        val previous=lastTapAt
        if(shortTap && previous!=null && now-previous in 0..doubleTapWindow){lastTapAt=null;return Effect.CAMERA}
        lastTapAt=if(shortTap)now else null
        return Effect.NONE
    }
    fun cancel(): Effect {
        val wasRecording = recording
        if (pressedAt != null) blockedUntilUp = true
        cancelledPress=true;lastTapAt=null
        recording = false
        return if(wasRecording){if(cameraMode)Effect.VIDEO_CANCEL else Effect.CANCEL}else Effect.NONE
    }
}
