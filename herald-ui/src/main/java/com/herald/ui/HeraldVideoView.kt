package com.herald.ui

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** When a card's video plays on its own. Pure, so it is unit tested. */
internal object VideoAutoplay {
    /**
     * Muted autoplay runs only for a card with `videoAutoplay`, while at least half of it is visible, and
     * never with the system "remove animations" setting on, and not again for a video that failed (it stays on
     * its poster).
     */
    fun shouldPlay(visibleFraction: Float, autoplay: Boolean, reducedMotion: Boolean, failed: Boolean = false) =
        autoplay && !reducedMotion && !failed && visibleFraction >= 0.5f

    /** The system "remove animations" setting sets the animator duration scale to 0. */
    fun animationsRemoved(animatorDurationScale: Float) = animatorDurationScale == 0f

    /** The share of a card on screen, 0 to 1. */
    fun visibleFraction(visibleHeight: Float, fullHeight: Int) =
        if (fullHeight > 0) (visibleHeight / fullHeight).coerceIn(0f, 1f) else 0f
}

/**
 * Decides when a video's `video_play` and `video_complete` go out: one of each per play. A play starts on a
 * tap, an autoplay start, or a replay from the controls after the end; it completes at the end. A loop (which
 * never reports an end) or a resume after pausing is not a new play.
 */
internal class VideoPlayEvents {
    private var playing = false

    /** True when this start begins a new play (log `video_play`). */
    fun started(): Boolean {
        if (playing) return false
        playing = true
        return true
    }

    /** True when the end belongs to a play in progress (log `video_complete`). */
    fun ended(): Boolean {
        if (!playing) return false
        playing = false
        return true
    }
}

/**
 * How much of the card is on screen (0 to 1). [ContentCardFeed] sets it for cards with an autoplay video;
 * elsewhere it stays 0, so autoplay never starts.
 */
internal val LocalHeraldVisibleFraction = compositionLocalOf<State<Float>> { NotVisible }
private val NotVisible: State<Float> = mutableStateOf(0f)

private enum class VideoMode { POSTER, TAPPED, AUTOPLAY }

/** Platform [VideoView] that reports every start, including a replay from the controller, and can mute and loop. */
private class HeraldPlayerView(context: Context) : VideoView(context) {
    var onStarted: () -> Unit = {}
    private var player: MediaPlayer? = null

    var muted = false
        set(value) { field = value; applyAudioAndLoop() }
    var looping = false
        set(value) { field = value; applyAudioAndLoop() }

    init {
        setOnPreparedListener { player = it; applyAudioAndLoop() }
    }

    private fun applyAudioAndLoop() {
        val volume = if (muted) 0f else 1f
        player?.apply { isLooping = looping; setVolume(volume, volume) }
    }

    override fun start() {
        super.start()
        onStarted()
    }
}

/**
 * A video in a card or in-app message: the [posterUrl] with a centered play button; a tap plays it inline
 * with sound and the standard controls. With [autoplay] it plays muted and looped while at least half of the
 * card is visible, paused otherwise, and never with "remove animations" on. [onPlay] and [onComplete] run once
 * per play (see [VideoPlayEvents]).
 */
@Composable
internal fun HeraldVideo(
    url: String,
    posterUrl: String?,
    modifier: Modifier,
    autoplay: Boolean = false,
    onPlay: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = remember {
        VideoAutoplay.animationsRemoved(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
    val visible = LocalHeraldVisibleFraction.current
    var failed by remember(url) { mutableStateOf(false) }
    val shouldAutoplay by remember(autoplay, reducedMotion, visible, failed) {
        derivedStateOf { VideoAutoplay.shouldPlay(visible.value, autoplay, reducedMotion, failed) }
    }
    val events = remember { VideoPlayEvents() }
    var mode by remember { mutableStateOf(VideoMode.POSTER) }
    if (mode == VideoMode.POSTER && shouldAutoplay) mode = VideoMode.AUTOPLAY

    Box(modifier, contentAlignment = Alignment.Center) {
        if (mode == VideoMode.POSTER) {
            CardImage(posterUrl, description = null, Modifier.matchParentSize())
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClickLabel = null, role = Role.Button) { mode = VideoMode.TAPPED },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.herald_play_video), tint = Color.White)
            }
        } else {
            val tapped = mode == VideoMode.TAPPED
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    HeraldPlayerView(ctx).apply {
                        muted = !tapped
                        looping = !tapped
                        if (tapped) setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        // Muted autoplay must not take audio focus (it would pause the user's music).
                        if (!tapped) setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        onStarted = { if (events.started()) onPlay() }
                        // No system "Can't play this video" dialog: fall back to the poster and stay there.
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            events.ended()
                            mode = VideoMode.POSTER
                            true
                        }
                        setOnCompletionListener { if (events.ended()) onComplete() }
                        setVideoURI(Uri.parse(url))
                        start()
                    }
                },
                update = { view ->
                    // A video the user started is left to its controls.
                    if (!tapped) {
                        if (shouldAutoplay) { if (!view.isPlaying) view.start() } else if (view.isPlaying) view.pause()
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        }
    }
}

/** Provides the card's visible fraction to a video inside [content]. */
@Composable
internal fun WithVisibleFraction(fraction: State<Float>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeraldVisibleFraction provides fraction, content = content)
}
