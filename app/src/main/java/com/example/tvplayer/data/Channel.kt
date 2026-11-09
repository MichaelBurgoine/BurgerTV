package com.example.tvplayer.data

enum class Section(val label: String) { UK("UK"), USA("USA"), SPORTS("Sports") }

data class PlaylistSource(val section: Section, val url: String, val cacheName: String)

data class Channel(
    val number: Int,
    val id: String?,
    val name: String,
    val logo: String?,
    val group: String?,
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    val section: Section,
    val geoBlocked: Boolean = false,
) {
    /** tvg-id without its feed suffix (BBCOne.uk@HD -> bbcone.uk), used to match guide data. */
    val guideKey: String? = id?.substringBefore('@')?.lowercase()

    /** Stable key for favourites and "last channel"; survives stream URL changes. */
    val key: String = id ?: url
}

data class Programme(
    val start: Long,
    val end: Long,
    val title: String,
    val description: String?,
)
