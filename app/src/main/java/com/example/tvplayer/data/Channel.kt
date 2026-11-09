package com.example.tvplayer.data

import java.util.Locale

enum class Section(val label: String) { UK("UK"), USA("USA"), SPORTS("Sports"), WORLD("World") }

/** "fr" -> "France". iptv-org uses "uk" for the United Kingdom. */
fun countryNameOf(code: String): String {
    val iso = if (code.equals("UK", ignoreCase = true)) "GB" else code.uppercase()
    val name = Locale("", iso).getDisplayCountry(Locale.UK)
    return if (name.isBlank() || name.equals(iso, ignoreCase = true)) code.uppercase() else name
}

/** Country code from an iptv-org channel ID: "TF1.fr@SD" -> "FR". */
fun countryFromId(id: String?): String? =
    id?.substringBefore('@')?.substringAfterLast('.', "")?.takeIf { it.length in 2..3 }?.uppercase()

data class PlaylistSource(val section: Section, val url: String, val cacheName: String)

/** One way of watching a channel. Channels can have several, used as automatic backups. */
data class StreamSource(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    val quality: String? = null,
    val geoBlocked: Boolean = false,
)

data class Channel(
    val number: Int,
    val id: String?,
    val name: String,
    val logo: String?,
    val categories: List<String>,
    val altNames: List<String>,
    val streams: List<StreamSource>,
    val section: Section,
    val country: String? = null,
    val countryLabel: String? = null,
) {
    /** Country name for browsing the World tab. */
    val countryName: String = countryLabel ?: country?.let { countryNameOf(it) } ?: "Other"

    /** Channel ID without its feed suffix (BBCOne.uk@HD -> bbcone.uk), used to match guide data. */
    val guideKey: String? = id?.substringBefore('@')?.lowercase()

    /** Stable key for favourites and "last channel"; survives stream URL changes. */
    val key: String = id ?: "name:${name.lowercase()}"

    val geoBlocked: Boolean get() = streams.all { it.geoBlocked }

    /** Categories as one line of text, for subtitles. */
    val group: String? get() = categories.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

data class Programme(
    val start: Long,
    val end: Long,
    val title: String,
    val description: String?,
)
