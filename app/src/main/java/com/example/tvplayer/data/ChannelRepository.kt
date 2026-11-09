package com.example.tvplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two ways in: the compact channels.json from the GitHub workflow (backup streams, categories,
 * alternative names from the iptv-org database), or the three M3U playlists directly.
 */
class ChannelRepository(context: Context) {
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }
    private val compactCache = File(dir, "channels.json")

    suspend fun cached(settings: AppSettings): List<Channel>? = withContext(Dispatchers.IO) {
        if (settings.channelsUrl.isNotBlank()) {
            if (!compactCache.exists()) null
            else runCatching { number(compact(compactCache.readText(), settings)) }.getOrNull()
        } else {
            val texts = settings.playlists.associate { src ->
                src.section to File(dir, src.cacheName).takeIf(File::exists)?.readText()
            }
            if (texts.values.any { it == null }) null
            else runCatching { mergePlaylists(texts.mapValues { it.value!! }) }.getOrNull()
        }
    }

    suspend fun refresh(settings: AppSettings): List<Channel> = withContext(Dispatchers.IO) {
        if (settings.channelsUrl.isNotBlank()) {
            val fresh = runCatching { Http.getText(settings.channelsUrl) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") }
            if (fresh != null) compactCache.writeAtomically(fresh)
            val text = fresh ?: compactCache.takeIf(File::exists)?.readText()
                ?: error("The channel list couldn't be downloaded")
            number(compact(text, settings))
        } else {
            val texts = coroutineScope {
                settings.playlists.map { src ->
                    async {
                        val cache = File(dir, src.cacheName)
                        val text = runCatching { Http.getText(src.url) }.getOrNull()
                            ?.takeIf { it.contains("#EXTINF") }
                        if (text != null) cache.writeAtomically(text)
                        src.section to (text
                            ?: cache.takeIf(File::exists)?.readText()
                            ?: error("${src.section.label} channels couldn't be downloaded"))
                    }
                }.awaitAll().toMap()
            }
            mergePlaylists(texts)
        }
    }

    private fun compact(text: String, settings: AppSettings) =
        CompactChannels.parse(text).filter { settings.includeWorld || it.section != Section.WORLD }

    /**
     * Sports gets every sports channel; UK and USA get the rest of their country lists;
     * World gets everything else from the all-countries list.
     */
    private fun mergePlaylists(texts: Map<Section, String>): List<Channel> {
        val sports = M3uParser.group(M3uParser.parse(texts.getValue(Section.SPORTS)), Section.SPORTS)
        val seenKeys = sports.mapTo(HashSet()) { it.key }
        val seenUrls = sports.flatMap { it.streams }.mapTo(HashSet()) { it.url }
        fun fresh(c: Channel) = c.key !in seenKeys && c.streams.none { it.url in seenUrls }
        fun remember(list: List<Channel>) = list.onEach { c ->
            seenKeys += c.key
            c.streams.forEach { seenUrls += it.url }
        }
        val uk = remember(M3uParser.group(M3uParser.parse(texts.getValue(Section.UK)), Section.UK).filter { fresh(it) })
        val usa = remember(M3uParser.group(M3uParser.parse(texts.getValue(Section.USA)), Section.USA).filter { fresh(it) })
        val world = texts[Section.WORLD]?.let { text ->
            M3uParser.group(M3uParser.parse(text), Section.WORLD)
                .filter { fresh(it) }
                .map { c ->
                    when (c.country) {
                        "UK", "GB" -> c.copy(section = Section.UK)
                        "US" -> c.copy(section = Section.USA)
                        else -> c
                    }
                }
        }.orEmpty()
        return number(uk + usa + sports + world)
    }

    private fun number(list: List<Channel>) =
        list.sortedBy { it.section.ordinal }.mapIndexed { i, c -> c.copy(number = i + 1) }
}
