package com.example.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.example.tvplayer.Config
import com.example.tvplayer.data.AppSettings

private enum class EditField(val title: String, val hint: String) {
    EPG("Guide address", "Link to an XMLTV file built with iptv-org/epg, ending .xml or .xml.gz. Leave blank for no guide."),
    UK("UK playlist", "M3U playlist link for the UK tab."),
    USA("USA playlist", "M3U playlist link for the USA tab."),
    SPORTS("Sports playlist", "M3U playlist link for the Sports tab.");

    fun get(s: AppSettings) = when (this) {
        EPG -> s.epgUrl
        UK -> s.ukPlaylist
        USA -> s.usaPlaylist
        SPORTS -> s.sportsPlaylist
    }

    fun set(s: AppSettings, v: String) = when (this) {
        EPG -> s.copy(epgUrl = v)
        UK -> s.copy(ukPlaylist = v.ifBlank { Config.DEFAULT_UK })
        USA -> s.copy(usaPlaylist = v.ifBlank { Config.DEFAULT_USA })
        SPORTS -> s.copy(sportsPlaylist = v.ifBlank { Config.DEFAULT_SPORTS })
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    onSave: (AppSettings) -> Unit,
    onRefreshGuide: () -> Unit,
    onCheckStreams: () -> Unit,
    onClose: () -> Unit,
) {
    val s = state.settings
    var editing by remember { mutableStateOf<EditField?>(null) }
    val first = remember { FocusRequester() }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item { Header("Channels") }
        item {
            SettingRow(
                "Hide offline channels",
                "Hides channels that failed their last stream check. Favourites always show.",
                onOff(s.hideOffline),
                Modifier.focusRequester(first),
            ) { onSave(s.copy(hideOffline = !s.hideOffline)) }
        }
        item {
            SettingRow("Hide geo-blocked channels", "Channels iptv-org says only work in certain countries.", onOff(s.hideGeoBlocked)) {
                onSave(s.copy(hideGeoBlocked = !s.hideGeoBlocked))
            }
        }
        item {
            SettingRow("Start on the last channel", "Opens straight into whatever you were watching.", onOff(s.resumeLastChannel)) {
                onSave(s.copy(resumeLastChannel = !s.resumeLastChannel))
            }
        }
        item {
            SettingRow(
                "Instant channel change",
                "Loads the next channel in the background. Uses more memory and data; turn off if playback stutters.",
                onOff(s.instantChannelChange),
            ) { onSave(s.copy(instantChannelChange = !s.instantChannelChange)) }
        }
        item { SettingRow("Check all channels now", checkSummary(state), null, onClick = onCheckStreams) }

        item { Header("Programme guide") }
        item {
            SettingRow("Guide address", EditField.EPG.hint, s.epgUrl.ifBlank { "Not set" }) { editing = EditField.EPG }
        }
        item {
            SettingRow("Update guide now", state.guideError ?: guideSummary(state), null, onClick = onRefreshGuide)
        }

        item { Header("Playlists") }
        listOf(EditField.UK, EditField.USA, EditField.SPORTS).forEach { f ->
            item { SettingRow(f.title, null, f.get(s)) { editing = f } }
        }
        item {
            SettingRow("Reset playlists", "Go back to the iptv-org UK, USA and Sports lists.", null) {
                onSave(s.copy(ukPlaylist = Config.DEFAULT_UK, usaPlaylist = Config.DEFAULT_USA, sportsPlaylist = Config.DEFAULT_SPORTS))
            }
        }
    }

    editing?.let { f ->
        TextEditDialog(
            title = f.title,
            hint = f.hint,
            initial = f.get(s),
            onSave = { onSave(f.set(s, it)) },
            onDismiss = { editing = null },
        )
    }
}

private fun onOff(v: Boolean) = if (v) "On" else "Off"

private fun checkSummary(state: UiState): String {
    state.checkProgress?.let { return "Checking ${it.done} of ${it.total}…" }
    if (state.status.isEmpty()) return "Not checked yet."
    val offline = state.status.values.count { !it.ok }
    return "$offline offline out of ${state.status.size} checked. Channels are re-checked every ${Config.STREAM_RECHECK_HOURS} hours."
}

private fun guideSummary(state: UiState) = when {
    !state.guideConfigured -> "Add a guide address first."
    state.guide.isEmpty() -> "No guide data loaded yet."
    else -> "Guide loaded for ${state.guide.size} channels. Updates every ${Config.GUIDE_REFRESH_HOURS} hours."
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    description: String?,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            value?.let {
                Spacer(Modifier.width(16.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 380.dp),
                )
            }
        }
    }
}

/** Text entry using the Fire TV keyboard, so the phone app and voice dictation can type into it. */
@Composable
private fun TextEditDialog(
    title: String,
    hint: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
        val c = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = c.surface, contentColor = c.onSurface),
        ) {
            Column(Modifier.width(640.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Save") { onSave(text.trim()); onDismiss() }
                    ActionButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
