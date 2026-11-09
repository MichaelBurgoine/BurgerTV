package com.example.tvplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import com.example.tvplayer.data.Http
import com.example.tvplayer.data.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaybackStatus { IDLE, TUNING, PLAYING, RECONNECTING, FAILED }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val streamNumber: Int = 1,
    val streamCount: Int = 1,
    val reason: String? = null,
)

/**
 * Plays a channel's streams in order, switching to the next backup when one fails.
 * Two ExoPlayers: one plays, the other pre-buffers the channel you're likely to zap to next.
 * Live streams that stall, fall behind the live window or drop out are reconnected.
 * HLS, DASH and RTSP are supported.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStreamResult: (url: String, ok: Boolean) -> Unit,
) {
    private inner class Slot {
        val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        val player: ExoPlayer = buildPlayer(http)
        var stream: StreamSource? = null

        fun load(s: StreamSource, play: Boolean) {
            stream = s
            http.setUserAgent(s.userAgent ?: Http.USER_AGENT)
            http.setDefaultRequestProperties(s.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
            player.volume = if (play) 1f else 0f
            player.playWhenReady = play
            player.setMediaItem(mediaItemFor(s))
            player.prepare()
        }

        fun clear() {
            stream = null
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

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    var instantChange: Boolean = true
        set(value) {
            field = value
            if (!value) standby.clear()
        }

    private var streams: List<StreamSource> = emptyList()
    private var streamIdx = 0
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

    /** [ordered] = the channel's streams, best first (working ones ahead of untested and failed). */
    fun play(ordered: List<StreamSource>) {
        if (ordered.isEmpty()) return
        cancelJobs()
        retries = 0
        streams = ordered
        streamIdx = 0
        val first = ordered[0]
        val sb = standby
        if (instantChange && sb.stream?.url == first.url && sb.player.playbackState != Player.STATE_IDLE) {
            active.clear()
            activeIdx = 1 - activeIdx
            active.player.volume = 1f
            active.player.playWhenReady = true
            _player.value = active.player
            setStatus(PlaybackStatus.TUNING)
            onActiveState(active.player.playbackState)
        } else {
            active.load(first, play = true)
            setStatus(PlaybackStatus.TUNING)
        }
    }

    /** Pre-buffer a channel's best stream in the background (no-op when instant change is off). */
    fun preload(ordered: List<StreamSource>) {
        val first = ordered.firstOrNull() ?: return
        if (!instantChange || first.url == active.stream?.url || standby.stream?.url == first.url) return
        standby.load(first, play = false)
    }

    fun stop() {
        cancelJobs()
        slots.forEach { it.clear() }
        streams = emptyList()
        _state.value = PlaybackState()
    }

    fun release() {
        cancelJobs()
        slots.forEach { it.player.release() }
    }

    private fun setStatus(status: PlaybackStatus, reason: String? = null) {
        _state.value = PlaybackState(status, streamIdx + 1, streams.size.coerceAtLeast(1), reason)
    }

    private fun onActiveState(state: Int) {
        when (state) {
            Player.STATE_READY -> {
                retries = 0
                stallJob?.cancel()
                setStatus(PlaybackStatus.PLAYING)
                active.stream?.let { onStreamResult(it.url, true) }
            }
            Player.STATE_BUFFERING -> {
                if (_state.value.status != PlaybackStatus.RECONNECTING) setStatus(PlaybackStatus.TUNING)
                watchForStall()
            }
            Player.STATE_ENDED -> retry("The stream ended") // a live channel shouldn't end
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
        val (reason, fatal) = describe(error)
        if (fatal) nextStream(reason) else retry(reason)
    }

    private fun watchForStall() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(STALL_MS)
            if (active.player.playbackState == Player.STATE_BUFFERING) retry("The stream stopped responding")
        }
    }

    /** Temporary problem: reconnect to the same stream a few times before giving up on it. */
    private fun retry(reason: String) {
        if (active.stream == null) return
        if (retries >= MAX_RETRIES) {
            nextStream(reason)
            return
        }
        retries++
        setStatus(PlaybackStatus.RECONNECTING, reason)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000L * retries)
            active.player.seekToDefaultPosition()
            active.player.prepare()
        }
    }

    /** This stream is no good: record it and move to the channel's next backup stream. */
    private fun nextStream(reason: String) {
        cancelJobs()
        active.stream?.let { onStreamResult(it.url, false) }
        retries = 0
        if (streamIdx + 1 < streams.size) {
            streamIdx++
            setStatus(PlaybackStatus.TUNING, reason)
            active.load(streams[streamIdx], play = true)
        } else {
            setStatus(PlaybackStatus.FAILED, reason)
        }
    }

    /** Plain-English reason, and whether retrying the same stream is pointless. */
    private fun describe(e: PlaybackException): Pair<String, Boolean> {
        val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            http == 401 || http == 403 || http == 451 -> "Blocked in your country or by the broadcaster" to true
            http == 404 || http == 410 -> "Stream not found" to true
            http != null && http in 400..499 -> "The stream refused the connection (error $http)" to true
            http != null -> "The stream's server had a problem (error $http)" to false
            e.errorCode in UNSUPPORTED || e.errorCode in 6000..6999 -> "Unsupported stream format" to true
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Couldn't connect to the stream" to false
            else -> "The stream stopped working" to false
        }
    }

    private fun mediaItemFor(s: StreamSource): MediaItem {
        val builder = MediaItem.Builder().setUri(s.url)
        val path = s.url.substringBefore('?').lowercase()
        when {
            path.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
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
        val UNSUPPORTED = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
    }
}
