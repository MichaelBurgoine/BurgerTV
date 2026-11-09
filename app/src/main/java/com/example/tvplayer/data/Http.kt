package com.example.tvplayer.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; AFTMM) TVPlayer/0.3"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Streams straight to disk so large guide files never sit in memory. */
    fun download(url: String, target: File) {
        val conn = open(url)
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val tmp = File(target.parentFile, "${target.name}.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "Couldn't save ${target.name}" }
        } finally {
            conn.disconnect()
        }
    }
}

internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    tmp.renameTo(this)
}
