package dev.puck.appliance

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private class AvatarTouch {var tracking=false;var width=0;var height=0;var left=0f;var top=0f;var right=0f;var bottom=0f}

/** Keeps gesture bounds from DOWN, so live-caption layout changes do not cancel a hold. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AvatarControl(modifier:Modifier=Modifier,toggle:()->Unit,start:()->Unit,end:()->Unit,camera:()->Unit,cancel:()->Unit,content:@Composable ()->Unit) {
    val gesture=remember {AvatarGesture()};val touch=remember {AvatarTouch()}
    val handler=remember {Handler(Looper.getMainLooper())}
    val onToggle by rememberUpdatedState(toggle);val onStart by rememberUpdatedState(start)
    val onEnd by rememberUpdatedState(end);val onCamera by rememberUpdatedState(camera);val onCancel by rememberUpdatedState(cancel)
    val dispatch by rememberUpdatedState<(AvatarGesture.Effect)->Unit>({effect->when(effect){
        AvatarGesture.Effect.TOGGLE->onToggle();AvatarGesture.Effect.HOLD_START->onStart();AvatarGesture.Effect.HOLD_END->onEnd()
        AvatarGesture.Effect.CAMERA->onCamera();AvatarGesture.Effect.CANCEL->onCancel();else->Unit
    }})
    val timer=remember {Runnable {dispatch(gesture.tick(SystemClock.elapsedRealtime()))}}
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner){
        fun stop(){handler.removeCallbacks(timer);dispatch(gesture.cancel());touch.tracking=false}
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE)stop()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);stop()}
    }
    Box(modifier.semantics {contentDescription="Avatar: tap to record, tap again to send; hold and release to send; double tap for camera"}
        .onSizeChanged {touch.width=it.width;touch.height=it.height}
        .pointerInteropFilter {event->
            val now=SystemClock.elapsedRealtime()
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{
                    touch.tracking=true;touch.left=event.rawX-event.x;touch.top=event.rawY-event.y
                    touch.right=touch.left+touch.width;touch.bottom=touch.top+touch.height
                    handler.removeCallbacks(timer);dispatch(gesture.down(now));handler.postDelayed(timer,450)
                }
                MotionEvent.ACTION_UP->{if(touch.tracking){handler.removeCallbacks(timer);dispatch(gesture.up(now));handler.postDelayed(timer,350)}}
                MotionEvent.ACTION_MOVE->{if(touch.tracking && (event.rawX !in touch.left..touch.right || event.rawY !in touch.top..touch.bottom)){handler.removeCallbacks(timer);dispatch(gesture.cancel());touch.tracking=false}}
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{handler.removeCallbacks(timer);dispatch(gesture.cancel());touch.tracking=false}
            }
            val handled=touch.tracking || event.actionMasked==MotionEvent.ACTION_UP
            if(event.actionMasked==MotionEvent.ACTION_UP)touch.tracking=false
            handled
        }) {content()}
}
