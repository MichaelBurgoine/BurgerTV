package com.example.tvplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.example.tvplayer.data.Channel

@Composable
fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surfaceVariant,
            contentColor = c.onSurface,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/** Hold-select menu for a channel: favourite it, and reorder when viewing favourites. */
@Composable
fun ChannelActions(
    channel: Channel,
    isFavourite: Boolean,
    isOffline: Boolean,
    showReorder: Boolean,
    onToggleFavourite: () -> Unit,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(Modifier.width(380.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${channel.number}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                val note = when {
                    isOffline -> "This channel failed its last stream check."
                    channel.geoBlocked -> "iptv-org marks this channel as geo-blocked."
                    else -> null
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionButton(
                    if (isFavourite) "Remove from favourites" else "Add to favourites",
                    Modifier.fillMaxWidth().focusRequester(first),
                ) { onToggleFavourite(); onDismiss() }
                if (showReorder) {
                    ActionButton("Move up", Modifier.fillMaxWidth()) { onMove(-1) }
                    ActionButton("Move down", Modifier.fillMaxWidth()) { onMove(1) }
                }
                ActionButton("Close", Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}
