package com.example.tvplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tvplayer.Config
import com.example.tvplayer.data.AppSettings
import com.example.tvplayer.data.Channel
import com.example.tvplayer.data.ChannelRepository
import com.example.tvplayer.data.GuideRepository
import com.example.tvplayer.data.Prefs
import com.example.tvplayer.data.Programme
import com.example.tvplayer.data.Section
import com.example.tvplayer.data.StreamChecker
import com.example.tvplayer.data.StreamStatus
import com.example.tvplayer.player.TvPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class GuideTab(val label: String, val section: Section?) {
    FAVOURITES("Favourites", null),
    UK("UK", Section.UK),
    USA("USA", Section.USA),
    SPORTS("Sports", Section.SPORTS);

    companion object {
        fun of(section: Section) = entries.first { it.section == section }
    }
}

data class CheckProgress(val done: Int, val total: Int)

data class UiState(
    val channels: List<Channel> = emptyList(),
    val guide: Map<String, List<Programme>> = emptyMap(),
    val tab: GuideTab = GuideTab.UK,
    val loading: Boolean = true,
    val error: String? = null,
    val guideError: String? = null,
    val playingIndex: Int? = null,
    val playTab: GuideTab = GuideTab.UK,
    val previousIndex: Int? = null,
    val lastIndex: Int? = null,
    val favourites: List<String> = emptyList(),
    val status: Map<String, StreamStatus> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val checkProgress: CheckProgress? = null,
) {
    val favouriteSet: Set<String> = favourites.toHashSet()
    val guideConfigured: Boolean get() = settings.epgUrl.isNotBlank()

    fun isFavourite(c: Channel) = c.key in favouriteSet
    fun isOffline(c: Channel) = status[c.url]?.ok == false

    private fun hidden(c: Channel) =
        (settings.hideOffline && isOffline(c)) || (settings.hideGeoBlocked && c.geoBlocked)

    /** Channels shown for a tab. Favourites always show; the playing channel is never hidden. */
    fun visible(tab: GuideTab): List<IndexedValue<Channel>> {
        if (tab == GuideTab.FAVOURITES) {
            val byKey = channels.withIndex().groupBy { it.value.key }
            return favourites.flatMap { byKey[it].orEmpty() }
        }
        return channels.withIndex().filter { (i, c) ->
            c.section == tab.section && (i == playingIndex || !hidden(c))
        }
    }

    fun searchable(): List<IndexedValue<Channel>> =
        channels.withIndex().filter { (i, c) -> i == playingIndex || isFavourite(c) || !hidden(c) }

    /** The tab a channel should zap within: the preferred one if it contains the channel. */
    fun tabFor(index: Int, preferred: GuideTab): GuideTab {
        val c = channels.getOrNull(index) ?: return preferred
        return when {
            preferred == GuideTab.FAVOURITES && isFavourite(c) -> preferred
            preferred.section == c.section -> preferred
            else -> GuideTab.of(c.section)
        }
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val channelRepo = ChannelRepository(app)
    private val guideRepo = GuideRepository(app)
    private val checker = StreamChecker(app)
    private val guideLock = Mutex()
    private var checkJob: Job? = null
    private var resumeAttempted = false
    private var lastZapDelta = 1

    private val _state = MutableStateFlow(
        UiState(settings = prefs.loadSettings(), favourites = prefs.loadFavourites()).let {
            it.copy(tab = if (it.favourites.isNotEmpty()) GuideTab.FAVOURITES else GuideTab.UK)
        }
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val player = TvPlayer(
        context = app,
        scope = viewModelScope,
        onPlaying = { ch -> if (state.value.status[ch.url]?.ok != true) recordStatus(ch, true) },
        onFailed = { ch -> recordStatus(ch, false) },
    )

    init {
        player.instantChange = state.value.settings.instantChannelChange
        viewModelScope.launch {
            _state.update { it.copy(status = checker.load()) }
            // 1. Saved data first so the screen fills instantly.
            channelRepo.cached(state.value.settings.playlists)?.takeIf { it.isNotEmpty() }?.let { cached ->
                _state.update { it.copy(channels = cached, loading = false) }
                maybeResume()
                loadCachedGuide()
            }
            // 2. Fresh data in the background.
            reloadChannels()
            refreshGuide(force = false)
            checkStreams(force = false)
        }
    }

    // ---------- lifecycle ----------

    fun onAppStart() {
        resumeIfPlaying()
        viewModelScope.launch { refreshGuide(force = false) }
        checkStreams(force = false)
    }

    fun pauseForBackground() = player.stop()

    private fun resumeIfPlaying() {
        val s = state.value
        s.playingIndex?.let { s.channels.getOrNull(it) }?.let { player.play(it); preloadNeighbour() }
    }

    // ---------- playback ----------

    fun selectTab(tab: GuideTab) = _state.update { it.copy(tab = tab) }

    fun play(index: Int, preferredTab: GuideTab = state.value.tab) {
        val s = state.value
        val channel = s.channels.getOrNull(index) ?: return
        val tab = s.tabFor(index, preferredTab)
        val previous = (s.playingIndex ?: s.lastIndex)?.takeIf { it != index }
        _state.update {
            it.copy(
                playingIndex = index,
                lastIndex = index,
                playTab = tab,
                tab = tab,
                previousIndex = previous ?: it.previousIndex,
            )
        }
        prefs.lastChannel = channel.key
        player.play(channel)
        preloadNeighbour()
    }

    /** Channel up/down within the tab you're watching from. */
    fun zap(delta: Int) {
        lastZapDelta = delta
        val s = state.value
        val current = s.playingIndex ?: return
        val list = s.visible(s.playTab)
        val pos = list.indexOfFirst { it.index == current }
        if (pos < 0 || list.isEmpty()) return
        val n = list.size
        play(list[((pos + delta) % n + n) % n].index, s.playTab)
    }

    fun previousChannel() {
        val s = state.value
        val prev = s.previousIndex ?: return
        play(prev, s.playTab)
    }

    fun stop() {
        player.stop()
        _state.update { it.copy(playingIndex = null) }
    }

    private fun preloadNeighbour() {
        val s = state.value
        val current = s.playingIndex ?: return
        val list = s.visible(s.playTab)
        val pos = list.indexOfFirst { it.index == current }
        if (pos < 0 || list.size < 2) return
        val n = list.size
        player.preload(list[((pos + lastZapDelta) % n + n) % n].value)
    }

    private fun maybeResume() {
        if (resumeAttempted) return
        resumeAttempted = true
        val s = state.value
        if (!s.settings.resumeLastChannel || s.playingIndex != null) return
        val key = prefs.lastChannel ?: return
        val index = s.channels.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        play(index, if (s.isFavourite(s.channels[index])) GuideTab.FAVOURITES else GuideTab.of(s.channels[index].section))
    }

    // ---------- favourites ----------

    fun toggleFavourite(index: Int) {
        val c = state.value.channels.getOrNull(index) ?: return
        val favs = state.value.favourites
        setFavourites(if (c.key in favs) favs - c.key else favs + c.key)
    }

    fun moveFavourite(index: Int, delta: Int) {
        val c = state.value.channels.getOrNull(index) ?: return
        val list = state.value.favourites.toMutableList()
        val from = list.indexOf(c.key)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        Collections.swap(list, from, to)
        setFavourites(list)
    }

    private fun setFavourites(list: List<String>) {
        prefs.saveFavourites(list)
        _state.update { it.copy(favourites = list) }
    }

    // ---------- settings ----------

    fun updateSettings(new: AppSettings) {
        val old = state.value.settings
        if (new == old) return
        prefs.saveSettings(new)
        _state.update { it.copy(settings = new) }
        player.instantChange = new.instantChannelChange
        when {
            old.playlists != new.playlists -> viewModelScope.launch {
                reloadChannels()
                refreshGuide(force = false)
                checkStreams(force = false)
            }
            old.epgUrl != new.epgUrl -> viewModelScope.launch {
                _state.update { it.copy(guide = emptyMap(), guideError = null) }
                refreshGuide(force = true)
            }
        }
    }

    fun refreshGuideNow() {
        viewModelScope.launch { refreshGuide(force = true) }
    }

    fun checkAllStreams() = checkStreams(force = true)

    // ---------- channels ----------

    private suspend fun reloadChannels() {
        try {
            val fresh = channelRepo.refresh(state.value.settings.playlists)
            val wasPlaying = state.value.playingIndex != null
            _state.update { s ->
                s.copy(
                    channels = fresh,
                    loading = false,
                    error = null,
                    playingIndex = remap(s.channels, fresh, s.playingIndex),
                    lastIndex = remap(s.channels, fresh, s.lastIndex),
                    previousIndex = remap(s.channels, fresh, s.previousIndex),
                )
            }
            if (wasPlaying && state.value.playingIndex == null) player.stop()
            maybeResume()
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = if (it.channels.isEmpty())
                        "Channels couldn't be loaded. Check the Fire Stick is online, then reopen the app."
                    else null,
                )
            }
        }
    }

    /** Keeps playing/last/previous pointing at the same channel when the list is refreshed. */
    private fun remap(old: List<Channel>, new: List<Channel>, index: Int?): Int? {
        val c = index?.let { old.getOrNull(it) } ?: return null
        return new.indexOfFirst { it.url == c.url }.takeIf { it >= 0 }
            ?: new.indexOfFirst { it.key == c.key }.takeIf { it >= 0 }
    }

    // ---------- guide ----------

    private suspend fun loadCachedGuide() {
        val keys = keysOf(state.value.channels)
        if (keys.isEmpty()) return
        runCatching { guideRepo.cached(state.value.settings.epgUrl, keys) }.getOrNull()?.let(::setGuide)
    }

    private suspend fun refreshGuide(force: Boolean) = guideLock.withLock {
        val url = state.value.settings.epgUrl
        val keys = keysOf(state.value.channels)
        if (url.isBlank() || keys.isEmpty()) return@withLock
        val result = runCatching { guideRepo.refreshIfStale(url, keys, force) }
        result.exceptionOrNull()?.let {
            _state.update { s ->
                s.copy(guideError = "The guide couldn't be downloaded. Check the address and that the computer serving it is on.")
            }
        }
        val fresh = result.getOrNull()
        when {
            fresh != null -> { setGuide(fresh); _state.update { it.copy(guideError = null) } }
            state.value.guide.isEmpty() -> loadCachedGuide()
        }
    }

    private fun setGuide(guide: Map<String, List<Programme>>) = _state.update { it.copy(guide = guide) }

    private fun keysOf(channels: List<Channel>) = channels.mapNotNullTo(HashSet()) { it.guideKey }

    // ---------- stream checks ----------

    private fun checkStreams(force: Boolean) {
        if (checkJob?.isActive == true) {
            if (!force) return
            checkJob?.cancel()
        }
        checkJob = viewModelScope.launch {
            val s = state.value
            if (s.channels.isEmpty()) return@launch
            val cutoff = System.currentTimeMillis() - Config.STREAM_RECHECK_HOURS * 3_600_000L
            // Favourites first, then the tab you're looking at, then everything else.
            val todo = s.channels.distinctBy { it.url }
                .filter { force || (s.status[it.url]?.checkedAt ?: 0L) < cutoff }
                .sortedBy { c ->
                    when {
                        s.isFavourite(c) -> 0
                        c.section == s.tab.section -> 1
                        else -> 2
                    }
                }
            if (todo.isEmpty()) return@launch

            val results = ConcurrentHashMap<String, StreamStatus>()
            val done = AtomicInteger()
            _state.update { it.copy(checkProgress = CheckProgress(0, todo.size)) }

            // Publish in batches so the guide isn't redrawn for every single result.
            val publisher = launch {
                while (true) {
                    delay(3_000)
                    publish(results, done.get(), todo.size)
                }
            }
            val permits = Semaphore(Config.STREAM_CHECK_PARALLEL)
            try {
                coroutineScope {
                    todo.forEach { c ->
                        launch {
                            permits.withPermit {
                                results[c.url] = StreamStatus(checker.check(c), System.currentTimeMillis())
                                done.incrementAndGet()
                            }
                        }
                    }
                }
            } finally {
                publisher.cancel()
                publish(results, done.get(), todo.size)
                _state.update { it.copy(checkProgress = null) }
                withContext(NonCancellable) { checker.save(state.value.status) }
            }
        }
    }

    private fun publish(results: ConcurrentHashMap<String, StreamStatus>, done: Int, total: Int) {
        val batch = HashMap(results)
        results.keys.removeAll(batch.keys)
        _state.update { it.copy(status = it.status + batch, checkProgress = CheckProgress(done, total)) }
    }

    private fun recordStatus(c: Channel, ok: Boolean) {
        _state.update { it.copy(status = it.status + (c.url to StreamStatus(ok, System.currentTimeMillis()))) }
        viewModelScope.launch { checker.save(state.value.status) }
    }

    override fun onCleared() = player.release()
}
