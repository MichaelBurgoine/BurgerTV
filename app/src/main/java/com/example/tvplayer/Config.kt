package com.example.tvplayer

/** Defaults. Playlists and the guide address can be changed in Settings on the device. */
object Config {
    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"
    const val DEFAULT_UK = "$IPTV_ORG/countries/uk.m3u"
    const val DEFAULT_USA = "$IPTV_ORG/countries/us.m3u"
    const val DEFAULT_SPORTS = "$IPTV_ORG/categories/sports.m3u"

    /** XMLTV guide built with github.com/iptv-org/epg (see README). Blank = no guide. */
    const val DEFAULT_EPG_URL = ""

    const val GUIDE_REFRESH_HOURS = 6
    const val STREAM_RECHECK_HOURS = 12
    const val STREAM_CHECK_PARALLEL = 6
    const val FAST_SCROLL_ROWS = 8
}
