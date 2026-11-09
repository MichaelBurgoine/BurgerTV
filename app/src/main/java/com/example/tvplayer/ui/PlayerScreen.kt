package com.example.tvplayer.ui

import android.graphics.Color as AColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.tvplayer.data.Channel
import com.example.tvplayer.data.Programme
import com.example.tvplayer.player.PlaybackState
import com.example.tvplayer.player.PlaybackStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remote: up/down change channel, left flips back to the previous channel,
 * select or right opens the mini guide, menu opens channel options, back returns to the guide.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: UiState,
    channel: Channel,
    player: ExoPlayer,
    playback: PlaybackState,
    onZap: (Int) -> Unit,
    onPrevious: () -> Unit,
    onTune: (Int) -> Unit,
    onActions: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var showBanner by remember { mutableStateOf(true) }
    var bannerTrigger by remember { mutableIntStateOf(0) }
    var miniGuide by remember { mutableStateOf(false) }
    val playingIndex = state.playingIndex ?: return
    val programmes = channel.guideKey?.let { state.guide[it] }

    LaunchedEffect(channel, bannerTrigger) {
        showBanner = true
        delay(4_000)
        showBanner = false
    }
    // Focus returns to the player whenever the mini guide closes.
    LaunchedEffect(miniGuide) {
        if (!miniGuide) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }

    BackHandler { if (miniGuide) miniGuide = false else onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (miniGuide || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> { onZap(1); true }
                    Key.DirectionDown, Key.ChannelDown -> { onZap(-1); true }
                    Key.DirectionLeft, Key.LastChannel -> { onPrevious(); true }
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> { miniGuide = true; true }
                    Key.Menu -> { onActions(playingIndex); true }
                    Key.Info -> { bannerTrigger++; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(AColor.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
            update = { it.player = player }, // follows the swap when instant change kicks in
        )

        val message = when (playback.status) {
            PlaybackStatus.TUNING ->
                if (playback.streamNumber > 1) "Trying backup stream ${playback.streamNumber} of ${playback.streamCount}…"
                else "Tuning…"
            PlaybackStatus.RECONNECTING -> "Reconnecting…"
            PlaybackStatus.FAILED ->
                (if (playback.streamCount > 1) "None of this channel's ${playback.streamCount} streams are working. "
                else "This channel isn't working right now. ") +
                    (playback.reason?.let { "Reason: $it. " } ?: "") +
                    "Press up or down for another channel, or left to go back."
            else -> null
        }
        if (message != null && !miniGuide) {
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }

        AnimatedVisibility(
            visible = showBanner && !miniGuide,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NowPlayingBanner(channel, programmes, state.isFavourite(channel))
        }

        if (miniGuide) {
            MiniGuide(
                state = state,
                currentIndex = playingIndex,
                onTune = { miniGuide = false; onTune(it) },
                onActions = onActions,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

@Composable
private fun MiniGuide(
    state: UiState,
    currentIndex: Int,
    onTune: (Int) -> Unit,
    onActions: (Int) -> Unit,
    modifier: Modifier,
) {
    val rows = remember(state.channels, state.playTab, state.playCategory, state.favourites, state.status, state.settings) {
        state.visible(state.playTab, state.playCategory)
    }
    val start = remember { rows.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    val now = remember { System.currentTimeMillis() }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val c = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(480.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF2101828), Color(0xD9101828))))
            .padding(start = 48.dp, end = 16.dp, top = 27.dp, bottom = 27.dp),
    ) {
        Text(
            state.playTab.label + (state.playCategory?.let { ": $it" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
            color = c.primary,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows, key = { _, r -> r.value.number }) { i, r ->
                val ch = r.value
                val onNow = ch.guideKey?.let { state.guide[it] }?.firstOrNull { now >= it.start && now < it.end }
                ListItem(
                    selected = r.index == currentIndex,
                    onClick = { onTune(r.index) },
                    onLongClick = { onActions(r.index) },
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = c.onSurface,
                        selectedContainerColor = c.surfaceVariant,
                        selectedContentColor = c.onSurface,
                        focusedContainerColor = c.primary,
                        focusedContentColor = c.onPrimary,
                    ),
                    headlineContent = {
                        Text(
                            (if (state.isFavourite(ch)) "★ " else "") + ch.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            onNow?.let { "${fmt.format(Date(it.start))}  ${it.title}" }
                                ?: if (state.isOffline(ch)) "Offline at last check" else "No guide information",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.number.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                            ChannelLogo(ch.logo, 36.dp)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBanner(channel: Channel, programmes: List<Programme>?, favourite: Boolean) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val now = System.currentTimeMillis()
    val current = programmes?.firstOrNull { now >= it.start && now < it.end }
    val next = programmes?.firstOrNull { it.start >= (current?.end ?: now) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            channel.number.toString(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.width(20.dp))
        ChannelLogo(channel.logo, 64.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (favourite) "★ " else "") + channel.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                ProgrammeLine("Now", fmt.format(Date(current.start)), current.title)
            }
            if (next != null) {
                ProgrammeLine("Next", fmt.format(Date(next.start)), next.title)
            }
            if (current == null && next == null) {
                channel.group?.let {
                    Text(it, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            "Select: guide   Left: last channel   Menu: options",
            color = Color(0x99FFFFFF),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ProgrammeLine(label: String, time: String, title: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(52.dp))
        Text(time, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(60.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
