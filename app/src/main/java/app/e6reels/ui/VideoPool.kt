package app.e6reels.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.e6reels.E6App
import app.e6reels.data.Post

@androidx.annotation.OptIn(UnstableApi::class)
private fun createPlayer(context: Context): ExoPlayer {
    val factory = (context.applicationContext as E6App).container.cacheDataSourceFactory
    // start after ~0.4 s of buffer, and ask for 2 s before resuming a stall (fewer stutters than 1 s).
    // The buffer is capped at 12 s: several players preload at once, and skipped videos shouldn't cost much data.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(3_000, 12_000, 400, 2_000)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_ONE }
}

/** A short, honest reason for a playback failure. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun describePlaybackError(e: PlaybackException): String {
    val http = generateSequence<Throwable>(e) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        http?.responseCode == 404 || http?.responseCode == 410 -> "File unavailable"
        http?.responseCode == 429 -> "Too many requests"
        http != null -> "e621 returned ${http.responseCode}"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection"
        else -> "Couldn't play this"
    }
}

/**
 * One ExoPlayer per video in a small window around the current page (previous, current, next).
 *
 * A single shared player had two problems: every swipe had to start a video from zero (slow), and the
 * surface of the new page briefly showed the last frame of the previous video. With one player per page
 * the next video is already buffered with its first frame on screen when the user swipes to it, and a
 * page can never show another video's frame.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPool(private val context: Context) {
    private val players = HashMap<Int, ExoPlayer>()
    private val listeners = HashMap<Int, Player.Listener>()
    private val createdAt = HashMap<Int, Long>()
    private val handler = Handler(Looper.getMainLooper())

    /** Bumped whenever a player is added or removed, so composables that read [get] recompose. */
    val version = mutableIntStateOf(0)
    val buffering = mutableStateMapOf<Int, Boolean>()
    val ready = mutableStateMapOf<Int, Boolean>()
    val errors = mutableStateMapOf<Int, String>()

    /** Players that have put a frame on screen: their page can drop the still behind the video. */
    val rendered = mutableStateMapOf<Int, Boolean>()

    /** Reads [version], so calling it in composition subscribes to changes. */
    fun get(id: Int): ExoPlayer? {
        version.intValue
        return players[id]
    }

    fun peek(id: Int): ExoPlayer? = players[id]

    fun forEach(block: (Int, ExoPlayer) -> Unit) = players.forEach { (id, p) -> block(id, p) }

    fun ensure(post: Post) {
        if (players.containsKey(post.id)) return
        val id = post.id
        val p = createPlayer(context)
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering[id] = playbackState == Player.STATE_BUFFERING
                ready[id] = playbackState == Player.STATE_READY
            }


            override fun onRenderedFirstFrame() {
                rendered[id] = true
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering[id] = false
                errors[id] = describePlaybackError(error)
            }
        }
        p.addListener(l)
        p.setMediaItem(MediaItem.fromUri(post.mediaUrl))
        p.prepare()
        players[id] = p
        listeners[id] = l
        createdAt[id] = SystemClock.uptimeMillis()
        version.intValue++
    }

    /** Releases every player whose post is not in [keep]. */
    fun retain(keep: Set<Int>) {
        val drop = players.keys.filter { it !in keep }
        if (drop.isEmpty()) return
        drop.forEach { id ->
            val p = players.remove(id) ?: return@forEach
            listeners.remove(id)?.let(p::removeListener)
            createdAt.remove(id)
            buffering.remove(id)
            ready.remove(id)
            errors.remove(id)
            rendered.remove(id)
            p.playWhenReady = false
            // let the page drop its PlayerView first (it sees the player vanish) and release right after
            handler.postDelayed({ p.release() }, 400)
        }
        version.intValue++
    }

    fun retry(post: Post) {
        val p = players[post.id] ?: return
        errors.remove(post.id)
        p.setMediaItem(MediaItem.fromUri(post.mediaUrl))
        p.prepare()
    }

    fun releaseAll() = retain(emptySet())
}
