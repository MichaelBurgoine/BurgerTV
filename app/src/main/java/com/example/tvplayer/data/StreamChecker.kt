package com.example.tvplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class StreamStatus(val ok: Boolean, val checkedAt: Long)

/** Lightweight stream health check: fetches the first bytes of each stream and confirms it answers. */
class StreamChecker(context: Context) {
    private val file = File(context.filesDir, "stream_status.json")
    private val saveLock = Mutex()

    suspend fun load(): Map<String, StreamStatus> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        runCatching {
            val json = JSONObject(file.readText())
            val out = HashMap<String, StreamStatus>(json.length())
            json.keys().forEach { k ->
                val a = json.getJSONArray(k)
                out[k] = StreamStatus(a.getInt(0) == 1, a.getLong(1))
            }
            out as Map<String, StreamStatus>
        }.getOrDefault(emptyMap())
    }

    suspend fun save(status: Map<String, StreamStatus>) = withContext(Dispatchers.IO) {
        saveLock.withLock {
            val json = JSONObject()
            status.forEach { (k, v) -> json.put(k, JSONArray().put(if (v.ok) 1 else 0).put(v.checkedAt)) }
            file.writeAtomically(json.toString())
        }
    }

    suspend fun check(channel: Channel): Boolean = withContext(Dispatchers.IO) {
        runCatching { probe(channel) }.getOrDefault(false)
    }

    private fun probe(channel: Channel): Boolean {
        var url = channel.url
        repeat(5) { // follow up to 5 redirects, including http <-> https
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", channel.userAgent ?: Http.USER_AGENT)
                channel.referrer?.let { setRequestProperty("Referer", it) }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return false
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                if (code !in 200..299) return false
                val buf = ByteArray(256)
                val n = conn.inputStream.read(buf)
                if (n <= 0) return false
                val head = String(buf, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                val isPlaylist = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                    conn.contentType?.contains("mpegurl", ignoreCase = true) == true
                return if (isPlaylist) head.startsWith("#EXTM3U") else true
            } finally {
                conn.disconnect()
            }
        }
        return false
    }
}
