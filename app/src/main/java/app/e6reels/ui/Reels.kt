package app.e6reels.ui

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.e6reels.E6App
import app.e6reels.R
import app.e6reels.data.Post
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible

/**
 * Full-screen vertical feed. Each video page in a small window around the current one has its own
 * ExoPlayer (see [VideoPool]), so the next video is ready before the user gets to it.
 */
/** How long the playing video may sit without its clock moving before the watchdog reopens it. */
private const val STALL_MS = 3_500L

@OptIn(UnstableApi::class)
@Composable
fun ReelsPager(
    posts: List<Post>,
    pagerState: PagerState,
    active: Boolean,
    likedIds: Set<Int>,
    onToggleLike: (Post) -> Unit,
    onLike: (Post) -> Unit,
    onOpenDetails: (Post) -> Unit,
    onNearEnd: () -> Unit,
    landscape: Boolean,
    onToggleLandscape: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tells the host when the current video shows its controls, so it can hide the navigation bar. */
    onControlsVisible: (Boolean) -> Unit = {},
    /**
     * Space at the bottom the pages must not use (the host's navigation bar). The pager keeps this size
     * even when the bar slides away, so the video never moves; the video controls use the freed strip.
     */
    bottomReserved: Dp = 0.dp,
    /** True when the feed has nothing more to load; the last page then offers a refresh. */
    endReached: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Called once each time a post settles on screen (the feed uses it for "already seen" and history). */
    onShown: (Post) -> Unit = {},
    /** "Less like this"; null hides the button (e.g. in the likes viewer). */
    onDislike: ((Post) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pool = remember { VideoPool(context) }
    DisposableEffect(pool) { onDispose { pool.releaseAll() } }

    var muted by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var paused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }

    val settled = pagerState.settledPage
    val currentPost = posts.getOrNull(settled)
    val currentId = currentPost?.id
    val poolVersion = pool.version.intValue
    val currentPlayer = if (currentId != null) pool.get(currentId) else null

    // Video controls live here, above the pager, so they can use the strip the host's navigation bar frees
    // (a page can't draw outside the pager's bounds).
    var controls by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) } // bump to restart the auto-hide timer
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val videoActive = active && currentPost?.isVideo == true

    LaunchedEffect(settled) {
        controls = false // a new page always starts with the controls hidden
        scrubMs = null
    }
    LaunchedEffect(videoActive) { if (!videoActive) controls = false }
    LaunchedEffect(controls, videoActive) { onControlsVisible(controls && videoActive) }
    DisposableEffect(Unit) { onDispose { onControlsVisible(false) } }
    // hide the controls a few seconds after the last touch, unless paused or scrubbing
    LaunchedEffect(controls, interaction, paused, scrubMs == null) {
        if (controls && !paused && scrubMs == null) {
            delay(3500)
            controls = false
        }
    }

    LaunchedEffect(currentId, active) { paused = false }
    val shownCallback by rememberUpdatedState(onShown)
    LaunchedEffect(currentId, active) { if (active) currentPost?.let(shownCallback) }

    // Players for the current video and its neighbours (one behind, two ahead). The current one is created
    // first and the others 300 ms later, so it gets the connection first but they all load in parallel:
    // the CDN's latency (~0.6 s per request) is what makes a cold video slow, and that can't be shortened,
    // only started earlier. A player that leaves the window is released.
    var previousId by remember { mutableStateOf<Int?>(null) }
    val aheadIds = listOf(settled + 1, settled + 2).mapNotNull { posts.getOrNull(it)?.id }
    LaunchedEffect(currentId, aheadIds, active) {
        val cur = currentPost
        val window = (settled - 1..settled + 2).mapNotNull { posts.getOrNull(it) }.filter { it.isVideo }.map { it.id }.toSet()
        pool.retain(if (active) window else emptySet())
        // a video we scrolled away from starts over next time
        previousId?.takeIf { it != currentId }?.let { pool.peek(it)?.seekTo(0) }
        previousId = currentId
        if (!active) return@LaunchedEffect
        if (cur != null && cur.isVideo) pool.ensure(cur)
        delay(300)
        listOfNotNull(posts.getOrNull(settled + 1), posts.getOrNull(settled + 2), posts.getOrNull(settled - 1))
            .filter { it.isVideo }
            .forEach { pool.ensure(it) }
    }
    // Watchdog: if the current video should be playing but its clock hasn't moved for a few seconds (a
    // download that stalled, a load that never started), reopen it from where it stopped: the same thing
    // the user would otherwise do by swiping away and back. A second miss prepares it from scratch.
    LaunchedEffect(currentId, active, poolVersion) {
        val cur = currentPost ?: return@LaunchedEffect
        if (!active || !cur.isVideo) return@LaunchedEffect
        var lastPosition = -1L
        var stuckSince = SystemClock.uptimeMillis()
        var nudges = 0
        while (true) {
            delay(500)
            val p = pool.peek(cur.id) ?: continue
            val shouldPlay = p.playWhenReady && pool.errors[cur.id] == null
            val position = p.currentPosition
            val now = SystemClock.uptimeMillis()
            if (!shouldPlay || position != lastPosition || p.playbackState == Player.STATE_ENDED) {
                lastPosition = position
                stuckSince = now
                if (shouldPlay && p.playbackState == Player.STATE_READY) nudges = 0
                continue
            }
            if (now - stuckSince < STALL_MS) continue
            if (nudges == 0 && p.playbackState != Player.STATE_IDLE) {
                p.seekTo(position) // closes the stalled request and opens a fresh one at the same point
            } else {
                pool.retry(cur)
            }
            nudges++
            stuckSince = now
        }
    }
    LaunchedEffect(paused, active, resumed, currentId, poolVersion) {
        pool.forEach { id, p -> p.playWhenReady = id == currentId && !paused && active && resumed }
    }
    LaunchedEffect(muted, poolVersion) { pool.forEach { _, p -> p.volume = if (muted) 0f else 1f } }
    LaunchedEffect(speed, currentId, poolVersion) {
        pool.forEach { id, p -> p.setPlaybackSpeed(if (id == currentId) speed else 1f) }
    }

    // Warm the image cache for what is coming. Only the bytes matter, so decode tiny (cheap) and keep it out of memory.
    val upcoming = posts.drop(settled + 1).take(3)
    LaunchedEffect(upcoming.map { it.id }) {
        val loader = context.imageLoader
        upcoming.forEach { p ->
            loader.enqueue(
                ImageRequest.Builder(context).data(p.posterUrl)
                    .size(64, 64).memoryCachePolicy(CachePolicy.DISABLED).build(),
            )
        }
    }

    LaunchedEffect(pagerState.currentPage, posts.size) {
        if (posts.isNotEmpty() && pagerState.currentPage >= posts.size - 5) onNearEnd()
    }

    Box(modifier.fillMaxSize()) {
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().padding(bottom = bottomReserved),
        beyondViewportPageCount = 1,
        // landscape = "locked": no accidental swipes to another post
        userScrollEnabled = !landscape,
        key = { posts.getOrNull(it)?.id ?: -(it + 1) },
    ) { index ->
        val post = posts.getOrNull(index) ?: return@VerticalPager
        val isCurrent = index == settled
        ReelPage(
            post = post,
            isCurrent = isCurrent,
            showVideo = isCurrent && active && post.isVideo,
            player = pool.get(post.id),
            videoDrawn = post.isVideo && pool.rendered[post.id] == true,
            paused = isCurrent && paused,
            muted = muted,
            liked = post.id in likedIds,
            onTogglePause = { paused = !paused },
            onDoubleTap = { onLike(post) },
            onToggleLike = { onToggleLike(post) },
            onToggleMute = { muted = !muted },
            controls = controls && isCurrent,
            onControlsChange = {
                controls = it
                interaction++
            },
            onScrub = { scrubMs = it },
            onInteract = { interaction++ },
            buffering = isCurrent && pool.buffering[post.id] == true,
            error = if (isCurrent) pool.errors[post.id] else null,
            onRetry = { pool.retry(post) },
            speed = speed,
            onCycleSpeed = { speed = PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(speed) + 1) % PLAYBACK_SPEEDS.size] },
            landscape = landscape,
            onToggleLandscape = onToggleLandscape,
            onOpenDetails = { onOpenDetails(post) },
            onDislike = onDislike?.let { dislike ->
                {
                    dislike(post)
                    // move on: the user said they don't want this
                    scope.launch { pagerState.animateScrollToPage((index + 1).coerceAtMost(posts.lastIndex)) }
                }
            },
        )
    }

    AnimatedVisibility(
        visible = endReached && settled == posts.lastIndex && !controls && !landscape,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).clickable(onClick = onRefresh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .background(Color(0x44000000), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xDDFFFFFF), modifier = Modifier.size(16.dp))
                Text("End of feed", color = Color(0xDDFFFFFF), style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    AnimatedVisibility(
        visible = controls && videoActive && currentPlayer != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
    ) {
        val shownPlayer = currentPlayer ?: return@AnimatedVisibility
        VideoControls(
            player = shownPlayer,
            paused = paused,
            muted = muted,
            speed = speed,
            landscape = landscape,
            scrubMs = scrubMs,
            onScrub = { scrubMs = it },
            onSeek = {
                shownPlayer.seekTo(it)
                scrubMs = null
            },
            onTogglePause = { paused = !paused },
            onToggleMute = { muted = !muted },
            onCycleSpeed = { speed = PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(speed) + 1) % PLAYBACK_SPEEDS.size] },
            onToggleLandscape = onToggleLandscape,
            onInteract = { interaction++ },
        )
    }
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelPage(
    post: Post,
    isCurrent: Boolean,
    showVideo: Boolean,
    player: ExoPlayer?,
    videoDrawn: Boolean,
    paused: Boolean,
    muted: Boolean,
    liked: Boolean,
    onTogglePause: () -> Unit,
    onDoubleTap: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleMute: () -> Unit,
    controls: Boolean,
    onControlsChange: (Boolean) -> Unit,
    onScrub: (Long?) -> Unit,
    onInteract: () -> Unit,
    buffering: Boolean,
    error: String?,
    onRetry: () -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    landscape: Boolean,
    onToggleLandscape: () -> Unit,
    onOpenDetails: () -> Unit,
    onDislike: (() -> Unit)?,
) {
    var heart by remember { mutableStateOf(false) }
    // read inside the long-lived tap handler, which must see the latest values
    val showVideoNow by rememberUpdatedState(showVideo)
    val controlsNow by rememberUpdatedState(controls)

    // only show a spinner if buffering lasts: short stalls shouldn't flash UI
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        if (buffering) {
            delay(400)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }

    LaunchedEffect(heart) {
        if (heart) {
            delay(700)
            heart = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A single tap only acts after the double-tap window (~300 ms), so the first tap of a double tap
            // never flashes the video controls before the like lands.
            .pointerInput(post.id, landscape) {
                detectTapGestures(
                    onTap = { if (showVideoNow) onControlsChange(!controlsNow) },
                    onDoubleTap = {
                        if (!landscape) {
                            onDoubleTap()
                            heart = true
                        }
                    },
                )
            }
            // swipe sideways on a video to seek: a full-width swipe = 60s
            .pointerInput(showVideo, player) {
                if (!showVideo || player == null) return@pointerInput
                var base = 0L
                var total = 0f
                var target: Long? = null
                detectHorizontalDragGestures(
                    onDragStart = {
                        base = player.currentPosition
                        total = 0f
                        target = null
                    },
                    onDragEnd = {
                        target?.let { player.seekTo(it) }
                        onScrub(null)
                        onInteract()
                    },
                    onDragCancel = { onScrub(null) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        target = (base + (total / size.width * 60_000).toLong()).coerceIn(0L, end)
                        onScrub(target)
                        if (!controls) onControlsChange(true)
                    },
                )
            },
    ) {
        // layers: tiny preview (instant) -> still -> animated gif
        // The still sits under the video only until the video draws: its framing can differ slightly from
        // the video's, and left underneath it shows as a border around the picture.
        if (!videoDrawn) {
            AsyncImage(post.thumbUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            AsyncImage(post.posterUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (isCurrent && post.ext == "gif") {
            AsyncImage(post.mediaUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (post.isVideo && player != null) {
            AndroidView(
                factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val overlays = !landscape && !controls
        if (overlays) {
        if (showVideo && showSpinner && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(36.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }
        if (showVideo && error != null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = onRetry, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Try again", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Text(error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // readability gradient under the caption
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(130.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )

        // Caption, Instagram style: who made it and a "more" that opens the description (all the tags).
        // The row is 48 dp tall with the text at the bottom, so the touch area grows upward.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 72.dp, bottom = 2.dp)
                .heightIn(min = 48.dp)
                .clickable(onClick = onOpenDetails),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (post.artists.isNotEmpty()) {
                Text(
                    post.artists.joinToString(", ") { "@$it" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "more",
                color = Color(0xAAFFFFFF),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActionButton(
                icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                description = if (liked) "Unlike" else "Like",
                tint = if (liked) Accent else Color.White,
                label = post.score.toString(),
                onClick = onToggleLike,
            )
            if (post.isVideo) {
                ActionButton(
                    icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    description = if (muted) "Unmute" else "Mute",
                    onClick = onToggleMute,
                )
            }
            if (onDislike != null) {
                ActionButton(icon = Icons.Outlined.ThumbDown, description = "Less like this", onClick = onDislike)
            }
            ActionButton(icon = Icons.Filled.ScreenRotation, description = "Rotate", onClick = onToggleLandscape)
        }

        }

        // in landscape the only chrome is this button (the video controls carry their own rotate button)
        if (landscape && !(controls && showVideo)) {
            IconButton(
                onClick = onToggleLandscape,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    if (landscape) Icons.Filled.StayCurrentPortrait else Icons.Filled.ScreenRotation,
                    contentDescription = "Rotate",
                    tint = Color.White,
                )
            }
        }

        Box(Modifier.align(Alignment.Center)) {
            AnimatedVisibility(
                visible = heart,
                enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Accent, modifier = Modifier.size(112.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    description: String,
    tint: Color = Color.White,
    label: String? = null,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(28.dp))
        }
        if (label != null) Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
