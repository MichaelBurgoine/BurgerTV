package com.example.tvplayer.data

import android.content.Context
import com.example.tvplayer.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class GuideRepository(context: Context) {
    private val file = File(context.filesDir, "guide.xml")
    private val sourceFile = File(context.filesDir, "guide.src")

    private fun savedFrom(url: String) =
        file.exists() && sourceFile.exists() && sourceFile.readText() == url

    /** Parses the saved guide, if it came from this address. */
    suspend fun cached(url: String, keys: Set<String>): Map<String, List<Programme>>? = withContext(Dispatchers.IO) {
        if (url.isBlank() || !savedFrom(url)) null else parse(keys)
    }

    /** Downloads a new guide when the saved one is old, from another address, or [force]d. Null = nothing changed. */
    suspend fun refreshIfStale(url: String, keys: Set<String>, force: Boolean): Map<String, List<Programme>>? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            val age = System.currentTimeMillis() - file.lastModified()
            if (!force && savedFrom(url) && age < Config.GUIDE_REFRESH_HOURS * HOUR) return@withContext null
            Http.download(url, file)
            sourceFile.writeText(url)
            parse(keys)
        }

    private fun parse(keys: Set<String>): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        return openMaybeGzipped().use { XmltvParser.parse(it, keys, now - 2 * HOUR, now + 24 * HOUR) }
    }

    /** Works for plain .xml and .xml.gz by checking the gzip magic bytes. */
    private fun openMaybeGzipped(): InputStream {
        val raw = BufferedInputStream(file.inputStream())
        raw.mark(2)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
