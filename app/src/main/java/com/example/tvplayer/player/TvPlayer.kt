package com.example.tvplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.tvplayer.data.Channel
import com.example.tvplayer.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaybackStatus { IDLE, TUNING, PLAYING, RECONNECTING, FAILED }

/**
 * Two ExoPlayers: one plays, the other quietly pre-buffers the channel you're likely to
 * zap to next, so changing to it is near instant. Also recovers live streams that stall,
 * fall behind the live window, or drop their connection.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPlaying: (Channel) -> Unit,
    private val onFailed: (Channel) -> Unit,
) {
    private inner class Slot {
        val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        val player: ExoPlayer = buildPlayer(http)
        var channel: Channel? = null

        fun load(ch: Channel, play: Boolean) {
            channel = ch
            http.setUserAgent(ch.userAgent ?: Http.USER_AGENT)
            http.setDefaultRequestProperties(ch.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
            player.volume = if (play) 1f else 0f
            player.playWhenReady = play
            player.setMediaItem(MediaItem.fromUri(ch.url))
            player.prepare()
        }

        fun clear() {
            channel = null
            player.stop()
            player.clearMediaItems()
        }
    }

    private val slots = arrayOf(Slot(), Slot())
    private var activeIdx = 0
    private val active get() = slots[activeIdx]
    private val standby get() = slots[1 - activeIdx]

    private val _player = MutableStateFlow(slots[0].player)
    val player: StateFlow<ExoPlayer> = _player.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus.IDLE)
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    var instantChange: Boolean = true
        set(value) {
            field = value
            if (!value) standby.clear()
        }

    private var retries = 0
    private var retryJob: Job? = null
    private var stallJob: Job? = null

    init {
        slots.forEach { slot ->
            slot.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (slot === active) onActiveState(state)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (slot === active) onActiveError(error) else slot.clear()
                }
            })
        }
    }

    fun play(ch: Channel) {
        cancelJobs()
        retries = 0
        val sb = standby
        if (instantChange && sb.channel?.url == ch.url && sb.player.playbackState != Player.STATE_IDLE) {
            active.clear()
            activeIdx = 1 - activeIdx
            active.player.volume = 1f
            active.player.playWhenReady = true
            _player.value = active.player
            onActiveState(active.player.playbackState)
        } else {
            active.load(ch, play = true)
            _status.value = PlaybackStatus.TUNING
        }
    }

    /** Pre-buffer a channel in the background (no-op when instant change is off). */
    fun preload(ch: Channel?) {
        if (!instantChange || ch == null || ch.url == active.channel?.url) return
        if (standby.channel?.url == ch.url) return
        standby.load(ch, play = false)
    }

    fun stop() {
        cancelJobs()
        slots.forEach { it.clear() }
        _status.value = PlaybackStatus.IDLE
    }

    fun release() {
        cancelJobs()
        slots.forEach { it.player.release() }
    }

    private fun onActiveState(state: Int) {
        when (state) {
            Player.STATE_READY -> {
                retries = 0
                stallJob?.cancel()
                _status.value = PlaybackStatus.PLAYING
                active.channel?.let(onPlaying)
            }
            Player.STATE_BUFFERING -> {
                if (_status.value != PlaybackStatus.RECONNECTING) _status.value = PlaybackStatus.TUNING
                watchForStall()
            }
            Player.STATE_ENDED -> retry() // a live channel shouldn't end; reconnect
            else -> Unit
        }
    }

    private fun onActiveError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Fell behind the live stream: jump back to the live edge.
            active.player.seekToDefaultPosition()
            active.player.prepare()
            return
        }
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (http != null && http in 400..499) retries = MAX_RETRIES // gone or blocked, don't keep trying
        retry()
    }

    private fun watchForStall() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(STALL_MS)
            if (active.player.playbackState == Player.STATE_BUFFERING) retry()
        }
    }

    private fun retry() {
        val ch = active.channel ?: return
        if (retries >= MAX_RETRIES) {
            stallJob?.cancel()
            _status.value = PlaybackStatus.FAILED
            onFailed(ch)
            return
        }
        retries++
        _status.value = PlaybackStatus.RECONNECTING
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000L * retries)
            active.player.seekToDefaultPosition()
            active.player.prepare()
        }
    }

    private fun cancelJobs() {
        retryJob?.cancel()
        stallJob?.cancel()
    }

    private fun buildPlayer(http: DataSource.Factory): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            // min buffer, max buffer, buffer before playback starts, buffer after a stall
            .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
            .build()
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setViewportSizeToPhysicalDisplaySize(context, true)
        )
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val STALL_MS = 15_000L
    }
}
