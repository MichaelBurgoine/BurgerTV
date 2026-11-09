package com.example.tvplayer.data

object M3uParser {
    /** One line of a playlist: a single stream for a channel. */
    data class Entry(
        val id: String?,
        val name: String,
        val logo: String?,
        val categories: List<String>,
        val stream: StreamSource,
    )

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val bracketTags = Regex("""\s*\[[^\]]*]""")
    private val qualityTag = Regex("""\s*\((\d{3,4}[pi])\)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
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
                    val rawName = inf.substring(inf.lastIndexOf('"') + 1).substringAfter(",")
                    val noTags = rawName.replace(bracketTags, "")
                    val quality = qualityTag.find(noTags)?.groupValues?.get(1)
                    val name = noTags.replace(qualityTag, "").trim()
                    val group = attrs["group-title"].orEmpty()

                    if (!group.contains("xxx", ignoreCase = true) && name.isNotEmpty()) {
                        out += Entry(
                            id = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            name = name,
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            categories = group.split(';').map { it.trim() }
                                .filter { it.isNotEmpty() && !it.equals("Undefined", ignoreCase = true) },
                            stream = StreamSource(
                                url = line,
                                userAgent = userAgent ?: attrs["http-user-agent"],
                                referrer = referrer ?: attrs["http-referrer"],
                                quality = quality,
                                geoBlocked = rawName.contains("[Geo-blocked]", ignoreCase = true),
                            ),
                        )
                    }
                    info = null
                }
            }
        }
        return out
    }

    /** Joins lines that are the same channel (same tvg-id, or same name without one) into one channel with backups. */
    fun group(entries: List<Entry>, section: Section): List<Channel> {
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) groups.getOrPut(e.id ?: "name:${e.name.lowercase()}") { ArrayList() }.add(e)
        return groups.values.map { list ->
            val first = list.first()
            Channel(
                number = 0,
                id = first.id,
                name = first.name,
                logo = list.firstNotNullOfOrNull { it.logo },
                categories = list.flatMap { it.categories }.distinct(),
                altNames = emptyList(),
                streams = list.map { it.stream }.distinctBy { it.url },
                section = section,
                country = countryFromId(first.id),
            )
        }
    }
}
