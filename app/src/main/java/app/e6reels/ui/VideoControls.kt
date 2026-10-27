package app.e6reels.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

val PLAYBACK_SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * Bottom-only controls in the style of X/Twitter: a thin edge-to-edge progress bar and, under it,
 * play/pause, remaining time, speed, sound and rotate. Nothing covers the middle of the video.
 *
 * [scrubMs] is non-null while the user drags the bar or swipes sideways, so the bar follows the finger.
 */
@Composable
fun VideoControls(
    player: Player,
    paused: Boolean,
    muted: Boolean,
    speed: Float,
    landscape: Boolean,
    scrubMs: Long?,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleLandscape: () -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L) // TIME_UNSET is negative
            delay(200)
        }
    }

    val shown = scrubMs ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        modifier
            .fillMaxWidth()
            // darkens only enough to keep white controls readable over a bright video edge
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .padding(top = 20.dp),
    ) {
        SeekBar(
            fraction = fraction,
            onScrub = { f ->
                onInteract()
                if (duration > 0) onScrub((f * duration).toLong())
            },
            onCommit = { f -> if (duration > 0) onSeek((f * duration).toLong()) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                onTogglePause()
                onInteract()
            }) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Play" else "Pause",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                "-" + formatTime(duration - shown),
                color = Color(0xCCFFFFFF),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                onCycleSpeed()
                onInteract()
            }) { Text(formatSpeed(speed), color = Color.White, style = MaterialTheme.typography.bodyLarge) }
            IconButton(onClick = {
                onToggleMute()
                onInteract()
            }) {
                Icon(
                    if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = onToggleLandscape) {
                Icon(
                    if (landscape) Icons.Filled.StayCurrentPortrait else Icons.Filled.ScreenRotation,
                    contentDescription = "Rotate",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Thin white progress line across the whole width; thickens while dragging. Tap or drag to seek. */
@Composable
private fun SeekBar(fraction: Float, onScrub: (Float) -> Unit, onCommit: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var last by remember { mutableFloatStateOf(0f) }
    val thickness by animateDpAsState(if (dragging) 8.dp else 3.dp, label = "seekThickness")

    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp) // a generous touch area around a thin line
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrub(f)
                    onCommit(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        last = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                    onDragEnd = {
                        dragging = false
                        onCommit(last)
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thickness).background(Color(0x4DFFFFFF)))
        Box(Modifier.fillMaxWidth(fraction).height(thickness).background(Color.White))
    }
}
