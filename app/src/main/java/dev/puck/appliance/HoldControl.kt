package dev.puck.appliance

import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private class HoldTouch {var held=false;var tracking=false;var width=0;var height=0}

/** Real pointer input, including Android ACTION_CANCEL; never arms a hardware key. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HoldControl(label:String,enabled:Boolean=true,press:()->Unit,release:()->Unit,cancel:()->Unit) {
    val touch=remember {HoldTouch()}
    val onPress by rememberUpdatedState(press)
    val onRelease by rememberUpdatedState(release)
    val onCancel by rememberUpdatedState(cancel)
    DisposableEffect(enabled){onDispose {if(touch.held){touch.held=false;onCancel()};touch.tracking=false}}
    Surface(color=if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)
            .semantics {contentDescription=label}
            .onSizeChanged {touch.width=it.width;touch.height=it.height}
            .pointerInteropFilter {event->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN->{if(enabled){touch.tracking=true;touch.held=true;onPress()}}
                    MotionEvent.ACTION_MOVE->{if(touch.held && (event.x !in 0f..touch.width.toFloat() || event.y !in 0f..touch.height.toFloat())){touch.held=false;onCancel()}}
                    MotionEvent.ACTION_UP->{if(touch.held){touch.held=false;onRelease()}}
                    MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{if(touch.held){touch.held=false;onCancel()}}
                }
                val handled=touch.tracking
                if(event.actionMasked in setOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL))touch.tracking=false
                handled
            }) {Box(Modifier.padding(16.dp),contentAlignment=Alignment.Center){Text(label,color=if(enabled)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)}}
}
