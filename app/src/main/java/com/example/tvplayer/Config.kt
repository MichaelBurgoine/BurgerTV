package com.example.tvplayer

/** Defaults. Playlists and the guide address can be changed in Settings on the device. */
object Config {
    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"
    const val DEFAULT_UK = "$IPTV_ORG/countries/uk.m3u"
    const val DEFAULT_USA = "$IPTV_ORG/countries/us.m3u"
    const val DEFAULT_SPORTS = "$IPTV_ORG/categories/sports.m3u"

    /** Every stream iptv-org lists, all countries. Used for the World tab in playlist mode. */
    const val DEFAULT_WORLD = "$IPTV_ORG/index.m3u"

    /**
     * channels.json built by tools/build_channels.py (GitHub workflow), e.g.
     * "https://<you>.github.io/<repo>/channels.json". When set it replaces the three playlists
     * and adds backup streams, categories and alternative names. Blank = use the playlists.
     */
    const val DEFAULT_CHANNELS_URL = ""

    /** XMLTV guide built with github.com/iptv-org/epg (see README). Blank = no guide. */
    const val DEFAULT_EPG_URL = ""

    const val GUIDE_REFRESH_HOURS = 6
    const val STREAM_RECHECK_HOURS = 12
    const val STREAM_CHECK_PARALLEL = 6

    /** World channels are checked when you browse a country, up to this many at a time. */
    const val ON_DEMAND_CHECK_LIMIT = 300
    const val FAST_SCROLL_ROWS = 8
}
