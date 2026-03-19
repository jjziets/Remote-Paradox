package com.remoteparadox.app.data

import android.util.Log
import com.remoteparadox.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"
private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val html_url: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val hasUpdate: Boolean,
    val releaseNotes: String,
    val downloadUrl: String?,
    val releaseUrl: String,
)

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun check(): UpdateInfo {
        val currentVersion = BuildConfig.VERSION_NAME
        val release = fetchLatestRelease()
        val latestVersion = release.tag_name.removePrefix("v")
        val apkAsset = findPhoneAsset(release.assets)

        return UpdateInfo(
            latestVersion = latestVersion,
            currentVersion = currentVersion,
            hasUpdate = isNewer(latestVersion, currentVersion),
            releaseNotes = release.body,
            downloadUrl = apkAsset?.browser_download_url,
            releaseUrl = release.html_url,
        )
    }

    fun checkWatch(currentWatchVersion: String): UpdateInfo {
        val release = fetchLatestRelease()
        val latestVersion = release.tag_name.removePrefix("v")
        val watchAsset = findWatchAsset(release.assets)

        return UpdateInfo(
            latestVersion = latestVersion,
            currentVersion = currentWatchVersion,
            hasUpdate = isNewer(latestVersion, currentWatchVersion),
            releaseNotes = release.body,
            downloadUrl = watchAsset?.browser_download_url,
            releaseUrl = release.html_url,
        )
    }

    fun fetchLatestRelease(): GitHubRelease {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) {
            throw Exception("GitHub API error: ${response.code}")
        }
        return json.decodeFromString<GitHubRelease>(body)
    }

    fun findWatchAsset(assets: List<GitHubAsset>): GitHubAsset? {
        return assets.firstOrNull { it.name.contains("watch", ignoreCase = true) && it.name.endsWith(".apk") }
    }

    fun findPhoneAsset(assets: List<GitHubAsset>): GitHubAsset? {
        return assets.firstOrNull { it.name.contains("phone", ignoreCase = true) && it.name.endsWith(".apk") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("watch", ignoreCase = true) }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val lParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(lParts.size, cParts.size)) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
