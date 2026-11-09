package com.example.tvplayer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming XMLTV parser. Only keeps programmes for channels we actually have,
 * inside the time window, so memory stays small even with a big guide file.
 */
object XmltvParser {

    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val withZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val noZone = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(raw: String?): Long? {
            val s = raw?.trim() ?: return null
            return runCatching {
                if (s.length >= 19) withZone.parse(s)!!.time else noZone.parse(s.take(14))!!.time
            }.getOrNull()
        }

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val out = HashMap<String, MutableList<Programme>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")?.substringBefore('@')?.lowercase()
                val start = time(parser.getAttributeValue(null, "start"))
                val end = time(parser.getAttributeValue(null, "stop"))
                if (channel != null && channel in wanted && start != null && end != null && end > from && start < to) {
                    var title: String? = null
                    var desc: String? = null
                    while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        when (parser.name) {
                            "title" -> if (title == null) title = parser.nextText().trim() else skip(parser)
                            "desc" -> if (desc == null) desc = parser.nextText().trim() else skip(parser)
                            else -> skip(parser)
                        }
                    }
                    out.getOrPut(channel) { ArrayList() } +=
                        Programme(start, end, title?.ifBlank { null } ?: "Untitled", desc?.ifBlank { null })
                } else {
                    skip(parser)
                }
            }
            event = parser.next()
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    /** Skips the current element and everything inside it. */
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
