package dev.puck.appliance

/** Screen avatar: defer single taps so a double tap never starts the microphone. */
class AvatarGesture(private val holdMs:Long=450,private val doubleMs:Long=350) {
    enum class Effect { NONE, TOGGLE, HOLD_START, HOLD_END, CAMERA, CANCEL }
    private var downAt:Long?=null
    private var pendingTap:Long?=null
    private var second=false
    private var holding=false
    fun down(now:Long):Effect {
        if(downAt!=null)return Effect.NONE
        second=pendingTap?.let {now-it in 0..doubleMs}==true
        val expired=pendingTap!=null && !second
        pendingTap=null;downAt=now
        return if(expired)Effect.TOGGLE else Effect.NONE
    }
    fun tick(now:Long):Effect {
        val start=downAt
        if(start!=null && !holding && now-start>=holdMs){holding=true;second=false;pendingTap=null;return Effect.HOLD_START}
        if(start==null && pendingTap?.let {now-it>=doubleMs}==true){pendingTap=null;return Effect.TOGGLE}
        return Effect.NONE
    }
    fun up(now:Long):Effect {
        if(downAt==null)return Effect.NONE
        downAt=null
        if(holding){holding=false;second=false;return Effect.HOLD_END}
        if(second){second=false;pendingTap=null;return Effect.CAMERA}
        pendingTap=now;return Effect.NONE
    }
    fun cancel():Effect {
        val active=downAt!=null || pendingTap!=null || holding
        downAt=null;pendingTap=null;holding=false;second=false
        return if(active)Effect.CANCEL else Effect.NONE
    }
}
