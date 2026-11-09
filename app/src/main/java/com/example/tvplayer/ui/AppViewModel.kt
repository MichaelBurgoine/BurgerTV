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
import com.example.tvplayer.data.StreamSource
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
    ALL("All", null),
    UK("UK", Section.UK),
    USA("USA", Section.USA),
    SPORTS("Sports", Section.SPORTS),
    WORLD("World", Section.WORLD);

    companion object {
        fun of(section: Section) = entries.first { it.section == section }
    }
}

data class CheckProgress(val done: Int, val total: Int)

data class UiState(
    val channels: List<Channel> = emptyList(),
    val guide: Map<String, List<Programme>> = emptyMap(),
    val tab: GuideTab = GuideTab.UK,
    val category: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val guideError: String? = null,
    val playingIndex: Int? = null,
    val playTab: GuideTab = GuideTab.UK,
    val playCategory: String? = null,
    val previousIndex: Int? = null,
    val lastIndex: Int? = null,
    val favourites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val status: Map<String, StreamStatus> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val checkProgress: CheckProgress? = null,
) {
    val favouriteSet: Set<String> = favourites.toHashSet()
    val guideConfigured: Boolean get() = settings.epgUrl.isNotBlank()

    fun isFavourite(c: Channel) = c.key in favouriteSet

    /** true = a stream is known to work, false = all failed, null = not checked yet. */
    fun channelOk(c: Channel): Boolean? = when {
        c.streams.any { status[it.url]?.ok == true } -> true
        isOffline(c) -> false
        else -> null
    }

    fun nowOn(c: Channel, now: Long): Programme? =
        c.guideKey?.let { guide[it] }?.firstOrNull { now >= it.start && now < it.end }

    fun nextOn(c: Channel, now: Long): Programme? {
        val list = c.guideKey?.let { guide[it] } ?: return null
        val current = list.firstOrNull { now >= it.start && now < it.end }
        return list.firstOrNull { it.start >= (current?.end ?: now) }
    }

    /** Offline only when every one of the channel's streams failed its last check. */
    fun isOffline(c: Channel) = c.streams.all { status[it.url]?.ok == false }

    /** Working streams first, then untested, then failed; otherwise playlist order. */
    fun orderedStreams(c: Channel): List<StreamSource> =
        c.streams.sortedBy {
            when (status[it.url]?.ok) {
                true -> 0
                null -> 1
                false -> 2
            }
        }

    private fun hidden(c: Channel) =
        (settings.hideOffline && isOffline(c)) || (settings.hideGeoBlocked && c.geoBlocked)

    private fun base(tab: GuideTab): List<IndexedValue<Channel>> = when (tab) {
        GuideTab.FAVOURITES -> {
            val byKey = channels.withIndex().groupBy { it.value.key }
            favourites.flatMap { byKey[it].orEmpty() }
        }
        GuideTab.ALL -> channels.withIndex().filter { (i, c) -> i == playingIndex || !hidden(c) }
        else -> channels.withIndex().filter { (i, c) ->
            c.section == tab.section && (i == playingIndex || !hidden(c))
        }
    }

    /** The World tab filters by country; every other tab filters by category. */
    fun matchesFilter(tab: GuideTab, c: Channel, filter: String): Boolean =
        if (tab == GuideTab.WORLD) c.countryName == filter else filter in c.categories

    /** Channels for a tab and optional filter. Favourites always show; the playing channel is never hidden. */
    fun visible(tab: GuideTab, category: String?): List<IndexedValue<Channel>> {
        val list = base(tab)
        return if (category == null) list
        else list.filter { matchesFilter(tab, it.value, category) || it.index == playingIndex }
    }

    /** Filter chips for a tab (countries on World, categories elsewhere), most channels first. */
    fun categoriesFor(tab: GuideTab): List<String> =
        base(tab).flatMap { if (tab == GuideTab.WORLD) listOf(it.value.countryName) else it.value.categories }
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    fun searchable(): List<IndexedValue<Channel>> =
        channels.withIndex().filter { (i, c) -> i == playingIndex || isFavourite(c) || !hidden(c) }

    /** The tab a channel should zap within: the preferred one if it contains the channel. */
    fun tabFor(index: Int, preferred: GuideTab): GuideTab {
        val c = channels.getOrNull(index) ?: return preferred
        return when {
            preferred == GuideTab.ALL -> preferred
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
    private var onDemandJob: Job? = null
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            settings = prefs.loadSettings(),
            favourites = prefs.loadFavourites(),
            recents = prefs.loadRecents(),
        ).let {
            it.copy(tab = if (it.favourites.isNotEmpty()) GuideTab.FAVOURITES else GuideTab.UK)
        }
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val player = TvPlayer(
        context = app,
        scope = viewModelScope,
        onStreamResult = { url, ok -> recordStream(url, ok) },
    )

    init {
        player.instantChange = state.value.settings.instantChannelChange
        viewModelScope.launch {
            _state.update { it.copy(status = checker.load()) }
            // 1. Saved data first so the screen fills instantly.
            channelRepo.cached(state.value.settings)?.takeIf { it.isNotEmpty() }?.let { cached ->
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
        s.playingIndex?.let { s.channels.getOrNull(it) }?.let {
            player.play(s.orderedStreams(it))
            preloadNeighbour()
        }
    }

    // ---------- browsing ----------

    fun selectTab(tab: GuideTab) = _state.update { it.copy(tab = tab, category = null) }

    fun selectCategory(category: String?) {
        _state.update { it.copy(category = category) }
        checkBrowsedCountry()
    }

    /** Opens the guide on a tab and category, e.g. from a home row's "See all". */
    fun openGuide(tab: GuideTab, category: String?) {
        _state.update { it.copy(tab = tab, category = category) }
        checkBrowsedCountry()
    }

    /** World channels aren't all checked up front; check a country's channels when you open it. */
    private fun checkBrowsedCountry() {
        val s = state.value
        if (s.tab != GuideTab.WORLD || s.category == null) return
        val cutoff = System.currentTimeMillis() - Config.STREAM_RECHECK_HOURS * 3_600_000L
        val streams = s.visible(GuideTab.WORLD, s.category)
            .take(Config.ON_DEMAND_CHECK_LIMIT)
            .flatMap { it.value.streams }
            .distinctBy { it.url }
            .filter { (s.status[it.url]?.checkedAt ?: 0L) < cutoff }
        if (streams.isEmpty()) return
        onDemandJob?.cancel()
        onDemandJob = viewModelScope.launch { runChecks(streams) }
    }

    // ---------- playback ----------

    fun play(index: Int, preferredTab: GuideTab = state.value.tab, category: String? = state.value.category) {
        val s = state.value
        val channel = s.channels.getOrNull(index) ?: return
        val tab = s.tabFor(index, preferredTab)
        val playCategory = category?.takeIf { tab == preferredTab && s.matchesFilter(tab, channel, it) }
        val previous = (s.playingIndex ?: s.lastIndex)?.takeIf { it != index }
        _state.update {
            it.copy(
                playingIndex = index,
                lastIndex = index,
                playTab = tab,
                playCategory = playCategory,
                tab = tab,
                category = if (tab == it.tab) it.category else null,
                previousIndex = previous ?: it.previousIndex,
            )
        }
        prefs.lastChannel = channel.key
        val recents = (listOf(channel.key) + s.recents.filter { it != channel.key }).take(MAX_RECENTS)
        prefs.saveRecents(recents)
        _state.update { it.copy(recents = recents) }
        player.play(s.orderedStreams(channel))
        preloadNeighbour()
    }

    /** Channel up/down within the tab and category you're watching from. */
    fun zap(delta: Int) {
        lastZapDelta = delta
        val s = state.value
        val current = s.playingIndex ?: return
        val list = s.visible(s.playTab, s.playCategory)
        val pos = list.indexOfFirst { it.index == current }
        if (pos < 0 || list.isEmpty()) return
        val n = list.size
        play(list[((pos + delta) % n + n) % n].index, s.playTab, s.playCategory)
    }

    fun previousChannel() {
        val s = state.value
        val prev = s.previousIndex ?: return
        play(prev, s.playTab, s.playCategory)
    }

    fun stop() {
        player.stop()
        _state.update { it.copy(playingIndex = null) }
    }

    private fun preloadNeighbour() {
        val s = state.value
        val current = s.playingIndex ?: return
        val list = s.visible(s.playTab, s.playCategory)
        val pos = list.indexOfFirst { it.index == current }
        if (pos < 0 || list.size < 2) return
        val n = list.size
        player.preload(s.orderedStreams(list[((pos + lastZapDelta) % n + n) % n].value))
    }

    private fun maybeResume() {
        if (resumeAttempted) return
        resumeAttempted = true
        val s = state.value
        if (!s.settings.resumeLastChannel || s.playingIndex != null) return
        val key = prefs.lastChannel ?: return
        val index = s.channels.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        val c = s.channels[index]
        play(index, if (s.isFavourite(c)) GuideTab.FAVOURITES else GuideTab.of(c.section), null)
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
            old.channelsUrl != new.channelsUrl || old.playlists != new.playlists ||
                old.includeWorld != new.includeWorld -> viewModelScope.launch {
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
            val fresh = channelRepo.refresh(state.value.settings)
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
                        "Channels couldn't be loaded. Check the Fire Stick is online and the addresses in Settings, then reopen the app."
                    else null,
                )
            }
        }
    }

    /** Keeps playing/last/previous pointing at the same channel when the list is refreshed. */
    private fun remap(old: List<Channel>, new: List<Channel>, index: Int?): Int? {
        val c = index?.let { old.getOrNull(it) } ?: return null
        val urls = c.streams.mapTo(HashSet()) { it.url }
        return new.indexOfFirst { it.key == c.key }.takeIf { it >= 0 }
            ?: new.indexOfFirst { n -> n.streams.any { it.url in urls } }.takeIf { it >= 0 }
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
                s.copy(guideError = "The guide couldn't be downloaded. Check the address and that the computer or site serving it is up.")
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
            val recent = s.recents.toHashSet()
            // Favourites first, then the tab you're looking at, then everything else. Every backup stream is checked.
            // World channels are only checked automatically if they're favourites or recently watched
            // (the rest are checked when you open their country, or with "Check all channels now").
            val todo = s.channels
                .filter { c -> force || c.section != Section.WORLD || s.isFavourite(c) || c.key in recent }
                .sortedBy { c ->
                    when {
                        s.isFavourite(c) -> 0
                        c.section == s.tab.section -> 1
                        else -> 2
                    }
                }
                .flatMap { it.streams }
                .distinctBy { it.url }
                .filter { force || (s.status[it.url]?.checkedAt ?: 0L) < cutoff }
            if (todo.isEmpty()) return@launch
            runChecks(todo, showProgress = true)
        }
    }

    private suspend fun runChecks(todo: List<StreamSource>, showProgress: Boolean = false) {
        run {
            val results = ConcurrentHashMap<String, StreamStatus>()
            val done = AtomicInteger()
            if (showProgress) _state.update { it.copy(checkProgress = CheckProgress(0, todo.size)) }

            // Publish in batches so the guide isn't redrawn for every single result.
            val publisher = viewModelScope.launch {
                while (true) {
                    delay(3_000)
                    publish(results, done.get(), todo.size, showProgress)
                }
            }
            val permits = Semaphore(Config.STREAM_CHECK_PARALLEL)
            try {
                coroutineScope {
                    todo.forEach { stream ->
                        launch {
                            permits.withPermit {
                                results[stream.url] = StreamStatus(checker.check(stream), System.currentTimeMillis())
                                done.incrementAndGet()
                            }
                        }
                    }
                }
            } finally {
                publisher.cancel()
                publish(results, done.get(), todo.size, showProgress)
                if (showProgress) _state.update { it.copy(checkProgress = null) }
                withContext(NonCancellable) { checker.save(state.value.status) }
            }
        }
    }

    private fun publish(results: ConcurrentHashMap<String, StreamStatus>, done: Int, total: Int, showProgress: Boolean) {
        val batch = HashMap(results)
        results.keys.removeAll(batch.keys)
        if (batch.isEmpty() && !showProgress) return
        _state.update {
            it.copy(
                status = it.status + batch,
                checkProgress = if (showProgress) CheckProgress(done, total) else it.checkProgress,
            )
        }
    }

    /** Result from actual playback, which is more reliable than the background check. */
    private fun recordStream(url: String, ok: Boolean) {
        if (ok && state.value.status[url]?.ok == true) return
        _state.update { it.copy(status = it.status + (url to StreamStatus(ok, System.currentTimeMillis()))) }
        saveStatusSoon()
    }

    /** With tens of thousands of streams the status file is large, so writes are batched. */
    private fun saveStatusSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(10_000)
            checker.save(state.value.status)
        }
    }

    override fun onCleared() = player.release()

    private companion object {
        const val MAX_RECENTS = 15
    }
}
