package com.example.tvplayer.data

import android.app.ActivityManager
import android.content.Context
import com.example.tvplayer.Config

data class AppSettings(
    val ukPlaylist: String = Config.DEFAULT_UK,
    val usaPlaylist: String = Config.DEFAULT_USA,
    val sportsPlaylist: String = Config.DEFAULT_SPORTS,
    val epgUrl: String = Config.DEFAULT_EPG_URL,
    val hideOffline: Boolean = true,
    val hideGeoBlocked: Boolean = false,
    val resumeLastChannel: Boolean = true,
    val instantChannelChange: Boolean = true,
) {
    val playlists: List<PlaylistSource>
        get() = listOf(
            PlaylistSource(Section.UK, ukPlaylist, "uk.m3u"),
            PlaylistSource(Section.USA, usaPlaylist, "us.m3u"),
            PlaylistSource(Section.SPORTS, sportsPlaylist, "sports.m3u"),
        )
}

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("tvplayer", Context.MODE_PRIVATE)
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    fun loadSettings(): AppSettings {
        // Instant channel change is off by default on low-memory devices.
        val d = AppSettings(instantChannelChange = !lowRam)
        return AppSettings(
            ukPlaylist = prefs.getString("uk", null) ?: d.ukPlaylist,
            usaPlaylist = prefs.getString("usa", null) ?: d.usaPlaylist,
            sportsPlaylist = prefs.getString("sports", null) ?: d.sportsPlaylist,
            epgUrl = prefs.getString("epg", null) ?: d.epgUrl,
            hideOffline = prefs.getBoolean("hide_offline", d.hideOffline),
            hideGeoBlocked = prefs.getBoolean("hide_geo", d.hideGeoBlocked),
            resumeLastChannel = prefs.getBoolean("resume", d.resumeLastChannel),
            instantChannelChange = prefs.getBoolean("instant", d.instantChannelChange),
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString("uk", s.ukPlaylist)
            .putString("usa", s.usaPlaylist)
            .putString("sports", s.sportsPlaylist)
            .putString("epg", s.epgUrl)
            .putBoolean("hide_offline", s.hideOffline)
            .putBoolean("hide_geo", s.hideGeoBlocked)
            .putBoolean("resume", s.resumeLastChannel)
            .putBoolean("instant", s.instantChannelChange)
            .apply()
    }

    /** Favourites in the user's chosen order. */
    fun loadFavourites(): List<String> =
        prefs.getString("favourites", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun saveFavourites(keys: List<String>) {
        prefs.edit().putString("favourites", keys.joinToString("\n")).apply()
    }

    var lastChannel: String?
        get() = prefs.getString("last_channel", null)
        set(value) { prefs.edit().putString("last_channel", value).apply() }
}
