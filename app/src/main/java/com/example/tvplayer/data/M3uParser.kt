package com.example.tvplayer.data

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val bracketTags = Regex("""\s*\[[^\]]*]""")

    /** Numbers are assigned later, once all playlists are merged. */
    fun parse(text: String, section: Section): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line; userAgent = null; referrer = null
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    userAgent = line.substringAfter("=")
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                    referrer = line.substringAfter("=")
                line.isNotEmpty() && !line.startsWith("#") && info != null -> {
                    val inf = info!!
                    val attrs = attrRegex.findAll(inf)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = inf.substring(inf.lastIndexOf('"') + 1)
                        .substringAfter(",")
                        .replace(bracketTags, "")
                        .trim()
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }

                    if (group?.contains("xxx", ignoreCase = true) != true && name.isNotEmpty()) {
                        out += Channel(
                            number = 0,
                            id = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            name = name,
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            group = group?.replace(";", ", "),
                            url = line,
                            userAgent = userAgent ?: attrs["http-user-agent"],
                            referrer = referrer ?: attrs["http-referrer"],
                            section = section,
                            geoBlocked = inf.contains("[Geo-blocked]", ignoreCase = true),
                        )
                    }
                    info = null
                }
            }
        }
        return out
    }
}
