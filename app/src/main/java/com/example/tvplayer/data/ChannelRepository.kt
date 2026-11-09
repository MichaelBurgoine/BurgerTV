package com.example.tvplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

class ChannelRepository(context: Context) {
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }

    /** Saved playlists from the last successful download, for an instant start. */
    suspend fun cached(sources: List<PlaylistSource>): List<Channel>? = withContext(Dispatchers.IO) {
        val texts = sources.associate { src ->
            src.section to File(dir, src.cacheName).takeIf(File::exists)?.readText()
        }
        if (texts.values.any { it == null }) null
        else runCatching { merge(texts.mapValues { it.value!! }) }.getOrNull()
    }

    /** Downloads all playlists in parallel; falls back to the saved copy for any that fail. */
    suspend fun refresh(sources: List<PlaylistSource>): List<Channel> = withContext(Dispatchers.IO) {
        val texts = coroutineScope {
            sources.map { src ->
                async {
                    val cache = File(dir, src.cacheName)
                    val fresh = runCatching { Http.getText(src.url) }.getOrNull()
                        ?.takeIf { it.contains("#EXTINF") }
                    if (fresh != null) cache.writeAtomically(fresh)
                    src.section to (fresh
                        ?: cache.takeIf(File::exists)?.readText()
                        ?: error("${src.section.label} channels couldn't be downloaded"))
                }
            }.awaitAll().toMap()
        }
        merge(texts)
    }

    /**
     * Sports gets every channel from the sports playlist (any country).
     * UK and USA get their country lists minus anything already in Sports.
     */
    private fun merge(texts: Map<Section, String>): List<Channel> {
        val sports = M3uParser.parse(texts.getValue(Section.SPORTS), Section.SPORTS).distinctBy { it.url }
        val seen = sports.mapTo(HashSet()) { it.url }
        val uk = M3uParser.parse(texts.getValue(Section.UK), Section.UK).filter { seen.add(it.url) }
        val usa = M3uParser.parse(texts.getValue(Section.USA), Section.USA).filter { seen.add(it.url) }
        return (uk + usa + sports).mapIndexed { i, c -> c.copy(number = i + 1) }
    }
}
