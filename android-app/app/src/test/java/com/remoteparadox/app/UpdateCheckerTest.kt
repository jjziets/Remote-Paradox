package com.remoteparadox.app

import com.remoteparadox.app.data.GitHubAsset
import com.remoteparadox.app.data.GitHubRelease
import com.remoteparadox.app.data.UpdateChecker
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `findWatchAsset returns watch APK when present`() {
        val assets = listOf(
            GitHubAsset("remote-paradox-phone.apk", "https://example.com/phone.apk", 5_000_000),
            GitHubAsset("remote-paradox-watch.apk", "https://example.com/watch.apk", 3_000_000),
        )
        val result = UpdateChecker.findWatchAsset(assets)
        assertNotNull(result)
        assertEquals("https://example.com/watch.apk", result!!.browser_download_url)
    }

    @Test
    fun `findWatchAsset returns null when no watch APK`() {
        val assets = listOf(
            GitHubAsset("remote-paradox-phone.apk", "https://example.com/phone.apk", 5_000_000),
        )
        val result = UpdateChecker.findWatchAsset(assets)
        assertNull(result)
    }

    @Test
    fun `findPhoneAsset returns phone APK when present`() {
        val assets = listOf(
            GitHubAsset("remote-paradox-phone.apk", "https://example.com/phone.apk", 5_000_000),
            GitHubAsset("remote-paradox-watch.apk", "https://example.com/watch.apk", 3_000_000),
        )
        val result = UpdateChecker.findPhoneAsset(assets)
        assertNotNull(result)
        assertEquals("https://example.com/phone.apk", result!!.browser_download_url)
    }

    @Test
    fun `findPhoneAsset falls back to first APK without watch in name`() {
        val assets = listOf(
            GitHubAsset("app-release.apk", "https://example.com/app.apk", 5_000_000),
            GitHubAsset("remote-paradox-watch.apk", "https://example.com/watch.apk", 3_000_000),
        )
        val result = UpdateChecker.findPhoneAsset(assets)
        assertNotNull(result)
        assertEquals("https://example.com/app.apk", result!!.browser_download_url)
    }

    @Test
    fun `isNewer returns true when latest is greater`() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `isNewer returns false when same or older`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
    }
}
