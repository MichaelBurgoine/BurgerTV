package com.example.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.example.tvplayer.ui.AppViewModel
import com.example.tvplayer.ui.ChannelActions
import com.example.tvplayer.ui.GuideScreen
import com.example.tvplayer.ui.GuideTab
import com.example.tvplayer.ui.HomeScreen
import com.example.tvplayer.ui.NavRail
import com.example.tvplayer.ui.PlayerScreen
import com.example.tvplayer.ui.RailCollapsed
import com.example.tvplayer.ui.Screen
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

@Composable
private fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exo by vm.player.player.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchStart by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<Int?>(null) }
    val railRequester = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }

    fun openSearch(start: String) {
        searchStart = start
        screen = Screen.SEARCH
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        val playing = state.playingIndex?.let { state.channels.getOrNull(it) }
        if (playing != null) {
            PlayerScreen(
                state = state,
                channel = playing,
                player = exo,
                playback = playback,
                onZap = vm::zap,
                onPrevious = vm::previousChannel,
                onTune = { vm.play(it, state.playTab, state.playCategory) },
                onActions = { actionsFor = it },
                onBack = vm::stop,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(start = RailCollapsed)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            state = state,
                            onPlay = { index, tab, category -> vm.play(index, tab, category) },
                            onSeeAll = { tab, category ->
                                vm.openGuide(tab, category)
                                screen = Screen.GUIDE
                            },
                            onOpenSearch = ::openSearch,
                            onActions = { actionsFor = it },
                        )
                        Screen.GUIDE -> GuideScreen(
                            state = state,
                            onSelectTab = vm::selectTab,
                            onSelectCategory = vm::selectCategory,
                            onPlay = { vm.play(it) },
                            onOpenSearch = ::openSearch,
                            onClose = { screen = Screen.HOME },
                            onActions = { actionsFor = it },
                        )
                        Screen.SEARCH -> SearchScreen(
                            state = state,
                            initialQuery = searchStart,
                            onPlay = { index ->
                                vm.play(index, GuideTab.of(state.channels[index].section), null)
                            },
                            onActions = { actionsFor = it },
                            onClose = { screen = Screen.HOME },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            state = state,
                            onSave = vm::updateSettings,
                            onRefreshGuide = vm::refreshGuideNow,
                            onCheckStreams = vm::checkAllStreams,
                            onClose = { screen = Screen.HOME },
                        )
                    }
                }
                NavRail(
                    current = screen,
                    onSelect = { s -> if (s == Screen.SEARCH) openSearch("") else screen = s },
                    selectedRequester = railRequester,
                    onFocusChanged = { railFocused = it },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // On Home, back first moves to the menu; back again leaves the app.
                BackHandler(enabled = screen == Screen.HOME && !railFocused) {
                    runCatching { railRequester.requestFocus() }
                }
            }
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
