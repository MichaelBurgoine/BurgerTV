package com.example.tvplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths used at startup and while browsing the guide and search,
 * so they're precompiled on install. Run on an Android TV emulator (API 33+):
 * Gradle task :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.tvplayer",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // If the app resumed into a channel, go back to the guide.
        device.pressBack()
        device.waitForIdle()

        repeat(12) { device.pressDPadDown() }   // scroll the guide
        repeat(4) { device.pressDPadRight() }   // move through time
        repeat(4) { device.pressDPadLeft() }
        device.waitForIdle()

        device.pressMenu()                       // open search
        device.waitForIdle()
        repeat(3) { device.pressDPadRight(); device.pressDPadCenter() }
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}
