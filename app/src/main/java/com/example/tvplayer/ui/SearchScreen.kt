@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.tvplayer.data.Channel
import com.example.tvplayer.data.ChannelSearch
import com.example.tvplayer.data.Programme

// Alphabetical grid: fewer, more predictable D-pad moves than QWERTY.
private val KEY_ROWS = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890")
private val KEY_SIZE = 48.dp
private val KEY_GAP = 6.dp
private const val MAX_QUERY = 40

@Composable
fun SearchScreen(
    state: UiState,
    initialQuery: String,
    onPlay: (Int) -> Unit,
    onActions: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val channels = remember(state.channels, state.favourites, state.status, state.settings) { state.searchable() }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var systemInput by remember { mutableStateOf(false) }
    val search = remember(channels) { ChannelSearch(channels) }
    val results = remember(search, query) { search.search(query) }
    val firstKey = remember { FocusRequester() }

    fun type(s: String) { if (query.length < MAX_QUERY) query += s }

    // Focus lands on the keyboard when the screen opens and after using the system keyboard.
    LaunchedEffect(systemInput) {
        if (!systemInput) {
            withFrameNanos { }
            runCatching { firstKey.requestFocus() }
        }
    }
    BackHandler { if (systemInput) systemInput = false else onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp)
            // Letters and numbers from a Bluetooth keyboard or a remote with number keys.
            .onPreviewKeyEvent { e ->
                if (systemInput || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val cp = e.utf16CodePoint
                when {
                    e.key == Key.Backspace -> { query = query.dropLast(1); true }
                    cp > 0 && Character.isLetterOrDigit(cp) -> { type(String(Character.toChars(cp)).uppercase()); true }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (systemInput) {
                    SystemField(query, onValueChange = { query = it }, onDone = { systemInput = false })
                } else {
                    QueryText(query)
                }
            }
            Spacer(Modifier.width(16.dp))
            KeyButton("Phone or voice keyboard", width = 260.dp) { systemInput = true }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize()) {
            Keyboard(
                firstKey = firstKey,
                onType = { type(it) },
                onDelete = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
            Spacer(Modifier.width(32.dp))
            Results(query, results, state, onPlay, onActions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QueryText(query: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            query.ifEmpty { "Search channels" },
            style = MaterialTheme.typography.headlineMedium,
            color = if (query.isEmpty()) c.onSurfaceVariant else c.onSurface,
            maxLines = 1,
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(width = 3.dp, height = 32.dp).background(c.primary))
        }
    }
}

/** Real text field so the Fire TV keyboard, its voice dictation and the Fire TV phone app can type into it. */
@Composable
private fun SystemField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_QUERY)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onDone()
            },
    )
}

@Composable
private fun Keyboard(
    firstKey: FocusRequester,
    onType: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        KEY_ROWS.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEachIndexed { c, ch ->
                    KeyButton(
                        ch.toString(),
                        width = KEY_SIZE,
                        modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                    ) { onType(ch.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            val wide = KEY_SIZE * 2 + KEY_GAP
            KeyButton("Space", wide) { onType(" ") }
            KeyButton("Delete", wide, onClick = onDelete)
            KeyButton("Clear", wide, onClick = onClear)
        }
    }
}

@Composable
private fun KeyButton(label: String, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = width, height = KEY_SIZE),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Results(
    query: String,
    results: List<IndexedValue<Channel>>,
    state: UiState,
    onPlay: (Int) -> Unit,
    onActions: (Int) -> Unit,
    modifier: Modifier,
) {
    val now = remember(results) { System.currentTimeMillis() }
    val c = MaterialTheme.colorScheme
    Column(modifier) {
        when {
            query.isBlank() -> Hint(
                "Type a channel name or number. Results update with every letter, " +
                    "so two or three letters is usually enough. Press right to jump to the results."
            )
            results.isEmpty() -> Hint("No channels match \"${query.trim()}\". Try fewer letters.")
            else -> {
                Text(
                    if (results.size >= ChannelSearch.LIMIT) "Top ${results.size} matches"
                    else "${results.size} ${if (results.size == 1) "match" else "matches"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.value.number }) { r ->
                        ResultRow(
                            channel = r.value,
                            state = state,
                            now = now,
                            onClick = { onPlay(r.index) },
                            onLongClick = { onActions(r.index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultRow(channel: Channel, state: UiState, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val current = channel.guideKey?.let { state.guide[it] }?.firstOrNull { now >= it.start && now < it.end }
    val offline = state.isOffline(channel)
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ListItemDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
        headlineContent = {
            Text(
                (if (state.isFavourite(channel)) "★ " else "") + channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                when {
                    offline -> "Offline at last check"
                    current != null -> "Now: ${current.title}"
                    channel.geoBlocked -> "Geo-blocked"
                    else -> channel.group ?: ""
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
                ChannelLogo(channel.logo, 40.dp)
            }
        },
        trailingContent = { Text(channel.section.label, style = MaterialTheme.typography.labelLarge) },
    )
}
