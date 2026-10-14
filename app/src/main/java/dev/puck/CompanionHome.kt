package dev.puck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.sin
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.puck.core.*

/** Original Puck companion; animation stays in this View's frame loop. */
private class MuseAvatarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val started = SystemClock.uptimeMillis()
    var moving = false
        set(value) { field = value; invalidate() }
    var status = ""
        set(value) { if (field != value) { field = value; invalidate() } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = if (moving) (SystemClock.uptimeMillis() - started) else 0L
        val bob = if (moving) sin(elapsed / 900.0).toFloat() * 2f else 0f
        val checkpoint = canvas.save()
        val scale = minOf(width, height) / 128f
        canvas.translate((width - 128 * scale) / 2, (height - 128 * scale) / 2)
        canvas.scale(scale, scale)
        fun color(value: String) { paint.color = android.graphics.Color.parseColor(value) }
        color("#233A36")
        canvas.drawOval(31f, 108f, 97f, 117f, paint)
        canvas.translate(0f, bob)
        color("#ABD9C0")
        canvas.drawRoundRect(57f, 18f, 71f, 40f, 7f, 7f, paint)
        canvas.drawCircle(64f, 17f, 7f, paint)
        color("#F2D4A9")
        canvas.drawRoundRect(22f, 35f, 106f, 106f, 30f, 30f, paint)
        color("#DBB88C")
        canvas.drawRoundRect(13f, 66f, 26f, 85f, 6f, 6f, paint)
        canvas.drawRoundRect(102f, 66f, 115f, 85f, 6f, 6f, paint)
        color("#142923")
        val blink = moving && elapsed % 4800 > 4620
        val eyeHeight = if (blink) 2f else 11f
        canvas.drawRoundRect(43f, 62f, 50f, 62f + eyeHeight, 3.5f, 3.5f, paint)
        canvas.drawRoundRect(78f, 62f, 85f, 62f + eyeHeight, 3.5f, 3.5f, paint)
        if (status == "Listening") {
            canvas.drawCircle(64f, 85f, 4f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(54f, 73f, 74f, 89f, 25f, 130f, false, paint)
            paint.style = Paint.Style.FILL
        }
        color("#EDB39C")
        canvas.drawOval(33f, 78f, 44f, 84f, paint)
        canvas.drawOval(84f, 78f, 95f, 84f, paint)
        canvas.restoreToCount(checkpoint)
        if (moving && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(66)
    }
    override fun onDetachedFromWindow() { moving = false; super.onDetachedFromWindow() }
}

@Composable
private fun MuseAvatar(motion: Boolean, label: String, enabled: Boolean, tap: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var systemMotion by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        fun refresh() { systemMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        refresh()
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val config = LocalConfiguration.current
    val extent = if (config.fontScale > 1.4f) 96.dp else minOf(140, (config.screenHeightDp * .24f).toInt()).dp
    // Keep the accessible interaction on a Compose parent. AndroidView's
    // interop holder can mask semantics attached directly to the decorative view.
    Box(Modifier.size(extent)
        .clickable(enabled = enabled, onClickLabel = "Check in with Muse", role = androidx.compose.ui.semantics.Role.Button, onClick = tap)
        .semantics { contentDescription = "Muse companion, $label" }) {
        AndroidView(factory = { MuseAvatarView(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
            update = { it.moving = motion && systemMotion && resumed; it.status = label }, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionHome(
    clock: String, connection: ConnectionStatus, usingMuse: Boolean,
    thinking: Boolean, listening: Boolean, mood: String, motion: Boolean,
    tasks: List<PuckTask>, read: Set<String>, ready: Boolean,
    review: (PuckTask) -> Unit, ask: () -> Unit, talk: () -> Unit,
    apps: () -> Unit, updates: () -> Unit, settings: () -> Unit, checkIn: () -> Unit,
    dismissed: Set<String> = emptySet(), dismiss: (PuckTask) -> Unit = {}
) {
    val pending = tasks.firstOrNull { it.phase in setOf(TaskPhase.PREPARING, TaskPhase.AWAITING_APPROVAL, TaskPhase.DISPATCHING) }
    val unread = tasks.filter { it.source == "muse" && it.phase == TaskPhase.COMPLETED && it.id !in read && it.id !in dismissed }
    // Dismissal clears the preview instead of resurfacing an older reply.
    val newest = tasks.firstOrNull { it.source == "muse" && it.phase == TaskPhase.COMPLETED }
    val latest = pending ?: newest?.takeIf { it.id !in dismissed }
    val status = when {
        listening -> "Listening"
        thinking -> "Thinking…"
        pending?.phase == TaskPhase.AWAITING_APPROVAL -> "Needs your review"
        connection == ConnectionStatus.Connecting -> "Connecting…"
        connection is ConnectionStatus.Blocked || connection == ConnectionStatus.Disconnected -> "Connection paused"
        unread.isNotEmpty() -> "New from Muse"
        !usingMuse -> "Offline companion"
        else -> "Here with you"
    }
    val accent = when (mood) { "cheerful" -> Color(0xFFF4C790); "concerned" -> Color(0xFFEBA6A6); "thoughtful" -> Color(0xFFB3C4F4); else -> MaterialTheme.colorScheme.primary }
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(clock, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (!usingMuse) "Offline demo" else if (connection == ConnectionStatus.MuseConnected) "Muse connected" else "Muse unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Type a request") }, onClick = { menuOpen = false; ask() }, enabled = ready)
                DropdownMenuItem(text = { Text("Apps") }, onClick = { menuOpen = false; apps() })
                DropdownMenuItem(text = { Text(if (unread.isNotEmpty()) "Updates (${unread.size})" else "Updates") }, onClick = { menuOpen = false; updates() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; settings() })
            }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        MuseAvatar(motion, status, ready && !thinking, checkIn)
        Text(status, color = accent, style = MaterialTheme.typography.titleMedium)
    }
    if (latest != null) {
        val dismissible = latest.source == "muse" && latest.phase == TaskPhase.COMPLETED && latest.request is WorkspaceRequest.ContentCard
        val card: @Composable () -> Unit = {
            Surface(onClick = { review(latest) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics {
                if (dismissible) customActions = listOf(CustomAccessibilityAction("Dismiss update") { dismiss(latest); true })
            }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (val request = latest.request) {
                    is WorkspaceRequest.ContentCard -> request.title
                    is WorkspaceRequest.Question -> "A question for you"
                    is WorkspaceRequest.Confirmation -> "Review an action"
                    is WorkspaceRequest.TaskStatus -> "Muse update"
                }, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dismissible) TextButton(onClick = { dismiss(latest) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Dismiss update" }) { Text("×") }
                }
                Text(when (val request = latest.request) {
                    is WorkspaceRequest.ContentCard -> request.text
                    is WorkspaceRequest.Question -> request.prompt
                    is WorkspaceRequest.Confirmation -> request.action.summary()
                    is WorkspaceRequest.TaskStatus -> request.text
                }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(if (latest.phase == TaskPhase.AWAITING_APPROVAL) "Tap to review · approval required" else if (dismissible) "Tap to read · swipe to dismiss" else "Tap to read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        }
        if (dismissible) key(latest.id) {
            val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) dismiss(latest)
                value != SwipeToDismissBoxValue.Settled
            })
            SwipeToDismissBox(state = swipe, backgroundContent = {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }) { card() }
        } else card()
    }
    Button(onClick = talk, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Talk") }
}
