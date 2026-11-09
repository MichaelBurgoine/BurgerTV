package com.example.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.example.tvplayer.ui.AppViewModel
import com.example.tvplayer.ui.ChannelActions
import com.example.tvplayer.ui.GuideScreen
import com.example.tvplayer.ui.GuideTab
import com.example.tvplayer.ui.PlayerScreen
import com.example.tvplayer.ui.SearchScreen
import com.example.tvplayer.ui.SettingsScreen
import com.example.tvplayer.ui.theme.TvTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvTheme { AppRoot(vm) } }
    }

    override fun onStart() { super.onStart(); vm.onAppStart() }
    override fun onStop() { vm.pauseForBackground(); super.onStop() }
}

private enum class Screen { GUIDE, SEARCH, SETTINGS }

@Composable
private fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exo by vm.player.player.collectAsStateWithLifecycle()
    val status by vm.player.status.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.GUIDE) }
    var searchStart by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<Int?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        val playing = state.playingIndex?.let { state.channels.getOrNull(it) }
        when {
            playing != null -> PlayerScreen(
                state = state,
                channel = playing,
                player = exo,
                status = status,
                onZap = vm::zap,
                onPrevious = vm::previousChannel,
                onTune = { vm.play(it, state.playTab) },
                onActions = { actionsFor = it },
                onBack = vm::stop,
            )
            screen == Screen.SEARCH -> SearchScreen(
                state = state,
                initialQuery = searchStart,
                onPlay = { index ->
                    screen = Screen.GUIDE
                    vm.play(index, GuideTab.of(state.channels[index].section))
                },
                onActions = { actionsFor = it },
                onClose = { screen = Screen.GUIDE },
            )
            screen == Screen.SETTINGS -> SettingsScreen(
                state = state,
                onSave = vm::updateSettings,
                onRefreshGuide = vm::refreshGuideNow,
                onCheckStreams = vm::checkAllStreams,
                onClose = { screen = Screen.GUIDE },
            )
            else -> GuideScreen(
                state = state,
                onSelectTab = vm::selectTab,
                onPlay = { vm.play(it) },
                onOpenSearch = { searchStart = it; screen = Screen.SEARCH },
                onOpenSettings = { screen = Screen.SETTINGS },
                onActions = { actionsFor = it },
            )
        }

        actionsFor?.let { index ->
            state.channels.getOrNull(index)?.let { channel ->
                ChannelActions(
                    channel = channel,
                    isFavourite = state.isFavourite(channel),
                    isOffline = state.isOffline(channel),
                    showReorder = playing == null && screen == Screen.GUIDE &&
                        state.tab == GuideTab.FAVOURITES && state.isFavourite(channel),
                    onToggleFavourite = { vm.toggleFavourite(index) },
                    onMove = { vm.moveFavourite(index, it) },
                    onDismiss = { actionsFor = null },
                )
            }
        }
    }
}
