@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.example.tvplayer.Config
import com.example.tvplayer.data.Channel
import com.example.tvplayer.data.Programme
import com.example.tvplayer.data.Section
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STEP_MS = 30 * 60 * 1000L          // grid moves in half hours
private const val WINDOW_MS = 4 * STEP_MS             // two hours on screen
private val CHANNEL_COL = 240.dp
private val ROW_HEIGHT = 56.dp

private fun floorToStep(t: Long) = t - (t % STEP_MS)

data class GuideFocus(val channel: Channel, val programme: Programme?)

@Composable
fun GuideScreen(
    state: UiState,
    onSelectTab: (GuideTab) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onPlay: (Int) -> Unit,
    onOpenSearch: (String) -> Unit,
    onClose: () -> Unit,
    onActions: (Int) -> Unit,
) {
    BackHandler(onBack = onClose)
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    var windowStart by remember { mutableLongStateOf(floorToStep(System.currentTimeMillis())) }
    var focusInfo by remember { mutableStateOf<GuideFocus?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }

    // Keep the grid from drifting into the past if the app is left open.
    LaunchedEffect(now) {
        val floor = floorToStep(now)
        if (windowStart < floor) windowStart = floor
    }

    val rows = remember(state.channels, state.tab, state.category, state.favourites, state.status, state.settings, state.playingIndex) {
        state.visible(state.tab, state.category)
    }
    val categories = remember(state.channels, state.tab, state.favourites, state.status, state.settings) {
        state.categoriesFor(state.tab)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 24.dp)
            // Menu button opens search; number keys open search with that number typed.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val cp = e.utf16CodePoint
                when {
                    e.key == Key.Menu || e.key == Key.Search -> { onOpenSearch(""); true }
                    cp in '0'.code..'9'.code -> { onOpenSearch(cp.toChar().toString()); true }
                    else -> false
                }
            },
    ) {
        InfoPanel(focusInfo, state, timeFmt, timeFmt.format(Date(now)))
        Spacer(Modifier.height(8.dp))
        GuideTabs(state.tab, onSelectTab)
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CategoryRow(
                categories,
                state.category,
                if (state.tab == GuideTab.WORLD) "All countries" else "All categories",
                onSelectCategory,
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.channels.isEmpty() && state.loading -> Message("Loading channels…")
            state.channels.isEmpty() -> Message(state.error ?: "No channels found.")
            rows.isEmpty() && state.tab == GuideTab.FAVOURITES ->
                Message("No favourites yet. Hold select on any channel to add it.")
            rows.isEmpty() && state.category != null ->
                Message("No ${state.category} channels here right now. Choose the first chip to see everything.")
            rows.isEmpty() -> Message("No ${state.tab.label} channels to show. Offline channels can be shown again in Settings.")
            else -> key(state.tab, state.category) {
                GuideGrid(
                    rows = rows,
                    state = state,
                    windowStart = windowStart,
                    now = now,
                    timeFmt = timeFmt,
                    requestInitialFocus = !initialFocusDone,
                    onInitialFocus = { initialFocusDone = true },
                    onFocus = { focusInfo = it },
                    onShift = { delta -> windowStart = maxOf(floorToStep(now), windowStart + delta) },
                    onPlay = onPlay,
                    onActions = onActions,
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(focus: GuideFocus?, state: UiState, fmt: SimpleDateFormat, clock: String) {
    val c = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Row(Modifier.fillMaxWidth().height(84.dp)) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            val p = focus?.programme
            when {
                focus == null -> Text("Pick a programme to watch", style = type.headlineSmall)
                p != null -> {
                    Text(p.title, style = type.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("${fmt.format(Date(p.start))}–${fmt.format(Date(p.end))}", style = type.titleSmall, color = c.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(focus.channel.name, style = type.titleSmall, color = c.onSurfaceVariant, maxLines = 1)
                    }
                    p.description?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = type.bodyMedium, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                else -> {
                    Text(focus.channel.name, style = type.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.guideConfigured) "No guide information for this channel."
                        else "The programme guide isn't set up yet. Add a guide address in Settings.",
                        style = type.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clock, style = type.headlineSmall, color = c.onSurfaceVariant)
            state.checkProgress?.let {
                Text("Checking streams ${it.done}/${it.total}", style = type.labelMedium, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GuideTabs(selected: GuideTab, onSelect: (GuideTab) -> Unit) {
    TabRow(selectedTabIndex = GuideTab.entries.indexOf(selected)) {
        GuideTab.entries.forEach { tab ->
            key(tab) {
                Tab(selected = tab == selected, onFocus = { onSelect(tab) }) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(categories: List<String>, selected: String?, allLabel: String, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        item(key = "__all") { ChoicePill(allLabel, selected == null) { onSelect(null) } }
        items(categories, key = { it }) { cat -> ChoicePill(cat, selected == cat) { onSelect(cat) } }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.surfaceVariant else Color.Transparent,
            contentColor = if (selected) c.primary else c.onSurfaceVariant,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp))
}

@Composable
private fun GuideGrid(
    rows: List<IndexedValue<Channel>>,
    state: UiState,
    windowStart: Long,
    now: Long,
    timeFmt: SimpleDateFormat,
    requestInitialFocus: Boolean,
    onInitialFocus: () -> Unit,
    onFocus: (GuideFocus) -> Unit,
    onShift: (Long) -> Unit,
    onPlay: (Int) -> Unit,
    onActions: (Int) -> Unit,
) {
    val startRow = remember { rows.indexOfFirst { it.index == state.lastIndex }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startRow - 2).coerceAtLeast(0))
    val focusTarget = remember { FocusRequester() }
    var focusRow by remember { mutableIntStateOf(startRow) }     // row holding the focus requester
    var currentRow by remember { mutableIntStateOf(startRow) }   // row that has focus now
    var focusRequest by remember { mutableIntStateOf(if (requestInitialFocus) 1 else 0) }

    LaunchedEffect(focusRequest) {
        if (focusRequest == 0) return@LaunchedEffect
        listState.scrollToItem((focusRow - 2).coerceAtLeast(0))
        withFrameNanos { }
        if (runCatching { focusTarget.requestFocus() }.isSuccess) onInitialFocus()
    }

    fun jump(delta: Int) {
        focusRow = (currentRow + delta).coerceIn(0, rows.lastIndex)
        focusRequest++
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Rewind / fast-forward (or page keys) jump several channels at a time.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaFastForward, Key.PageDown -> { jump(Config.FAST_SCROLL_ROWS); true }
                    Key.MediaRewind, Key.PageUp -> { jump(-Config.FAST_SCROLL_ROWS); true }
                    else -> false
                }
            },
    ) {
        val timelineWidth = maxWidth - CHANNEL_COL
        val dpPerMs = timelineWidth.value / WINDOW_MS
        val windowEnd = windowStart + WINDOW_MS
        val requesterRow = focusRow.coerceAtMost(rows.lastIndex)

        Column(Modifier.fillMaxSize()) {
            TimeRuler(windowStart, timelineWidth, dpPerMs, timeFmt)
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows, key = { _, r -> r.value.number }) { i, row ->
                        val channel = row.value
                        GuideRow(
                            channel = channel,
                            favourite = state.isFavourite(channel),
                            offline = state.isOffline(channel),
                            programmes = channel.guideKey?.let { state.guide[it] },
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            now = now,
                            timelineWidth = timelineWidth,
                            dpPerMs = dpPerMs,
                            firstCellModifier = if (i == requesterRow) Modifier.focusRequester(focusTarget) else Modifier,
                            onFocus = { currentRow = i; onFocus(it) },
                            onShift = onShift,
                            onPlay = { onPlay(row.index) },
                            onLongPress = { onActions(row.index) },
                        )
                    }
                }
                if (now in windowStart until windowEnd) {
                    Box(
                        Modifier
                            .offset(x = CHANNEL_COL + ((now - windowStart) * dpPerMs).dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, timelineWidth: Dp, dpPerMs: Float, fmt: SimpleDateFormat) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Spacer(Modifier.width(CHANNEL_COL))
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            for (step in 0 until (WINDOW_MS / STEP_MS).toInt()) {
                val t = windowStart + step * STEP_MS
                Text(
                    fmt.format(Date(t)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(x = ((t - windowStart) * dpPerMs).dp + 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    favourite: Boolean,
    offline: Boolean,
    programmes: List<Programme>?,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    timelineWidth: Dp,
    dpPerMs: Float,
    firstCellModifier: Modifier,
    onFocus: (GuideFocus) -> Unit,
    onShift: (Long) -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
) {
    val visible = remember(programmes, windowStart) {
        programmes.orEmpty().filter { it.end > windowStart && it.start < windowEnd }
    }
    val canGoBack = windowStart > floorToStep(now)

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(channel, favourite, offline)
        Row(Modifier.width(timelineWidth).fillMaxHeight()) {
            if (visible.isEmpty()) {
                GuideCell(
                    title = "No guide information",
                    width = timelineWidth,
                    live = false,
                    modifier = firstCellModifier,
                    onFocus = { onFocus(GuideFocus(channel, null)) },
                    onClick = onPlay,
                    onLongClick = onLongPress,
                    onEdgeKey = { false },
                )
            } else {
                var cursor = windowStart
                visible.forEachIndexed { i, p ->
                    val s = maxOf(p.start, windowStart)
                    val e = minOf(p.end, windowEnd)
                    if (s > cursor) Spacer(Modifier.width(((s - cursor) * dpPerMs).dp))
                    key(p.start) {
                        GuideCell(
                            title = p.title,
                            width = ((e - s) * dpPerMs).dp,
                            live = now in p.start until p.end,
                            modifier = if (i == 0) firstCellModifier else Modifier,
                            onFocus = { onFocus(GuideFocus(channel, p)) },
                            onClick = onPlay,
                            onLongClick = onLongPress,
                            onEdgeKey = { k ->
                                when {
                                    k == Key.DirectionRight && i == visible.lastIndex && p.end >= windowEnd -> { onShift(STEP_MS); true }
                                    k == Key.DirectionLeft && i == 0 && canGoBack -> { onShift(-STEP_MS); true }
                                    else -> false
                                }
                            },
                        )
                    }
                    cursor = e
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: Channel, favourite: Boolean, offline: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = c.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        ChannelLogo(channel.logo, 40.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (favourite) {
                    Text("★", style = MaterialTheme.typography.titleSmall, color = c.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (offline) c.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val note = when {
                offline -> "Offline"
                channel.geoBlocked -> "Geo-blocked"
                channel.section == Section.WORLD -> channel.countryName
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    width: Dp,
    live: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdgeKey: (Key) -> Boolean,
) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.width(width).fillMaxHeight().padding(end = 3.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxSize()
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && onEdgeKey(e.key) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (live) c.surfaceVariant else c.surface,
                contentColor = c.onSurface,
                focusedContainerColor = c.primary,
                focusedContentColor = c.onPrimary,
            ),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
            )
        }
    }
}
