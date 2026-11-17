package com.mau.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCategoryTest {
    private val placed = mapOf("com.tuya.smart" to "⚡", "com.watchfacestudio.a" to "⌚", "com.google.android.keep" to "🌎")

    @Test fun sameDeveloperFollowsYourChoice() = assertEquals("⚡", AutoCategory.guess("com.tuya.smartlife2", "Some app", -1, placed))
    @Test fun sharedPrefixesDontCount() = assertEquals("📸", AutoCategory.guess("com.google.android.GoogleCamera", "Camera", -1, placed))
    @Test fun wordsInName() = assertEquals("🍥", AutoCategory.guess("org.telegram.messenger", "Telegram", -1, placed))
    @Test fun shortWordsWholeOnly() = assertEquals(AutoCategory.UNSORTED, AutoCategory.guess("com.example.ledger", "Ledgerly", -1, placed))
    @Test fun declaredCategory() = assertEquals("🎮", AutoCategory.guess("com.foo.bar", "Bar", 0, placed))
    @Test fun unknownWaits() = assertEquals(AutoCategory.UNSORTED, AutoCategory.guess("com.foo.bar", "Bar", -1, placed))
    @Test fun ownAppsGoHome() = assertEquals("🏠", AutoCategory.guess("io.github.mauriporte.gymcoach", "Gym Coach", 0, placed))
    @Test fun homelabGoesHome() = assertEquals("🏠", AutoCategory.guess("com.github.catfriend1.syncthingandroid", "Syncthing", -1, placed))
}
