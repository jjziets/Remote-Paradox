package com.remoteparadox.watch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import retrofit2.Response

internal const val STATUS_MAX_AGE_MS = 15_000L
internal const val LEGACY_TOKEN_REFRESH_AGE_MS = 36 * 60 * 60 * 1000L

internal fun needsLegacyTokenRefresh(refreshToken: String?, tokenAgeMs: Long): Boolean =
    refreshToken.isNullOrBlank() && tokenAgeMs >= LEGACY_TOKEN_REFRESH_AGE_MS

@Serializable
data class StatusSnapshot(val status: AlarmStatus, val receivedAt: Long) {
    fun isFresh(now: Long): Boolean = now - receivedAt in 0 until STATUS_MAX_AGE_MS
}

internal data class TileStatus(val snapshot: StatusSnapshot? = null, val error: String? = null)

internal suspend fun loadTileStatus(
    cached: () -> StatusSnapshot?,
    fetch: suspend () -> Response<AlarmStatus>,
    refreshAuth: suspend () -> Unit,
    now: () -> Long = System::currentTimeMillis,
    proactiveRefresh: Boolean = false,
): TileStatus {
    val result = try {
        withTimeoutOrNull(2_000) {
            if (proactiveRefresh) refreshAuth()
            cached()?.takeIf { now() - it.receivedAt in 0 until 5_000L }
                ?.let { return@withTimeoutOrNull TileStatus(snapshot = it) }
            var response = fetch()
            if (response.code() == 401 && !proactiveRefresh) {
                refreshAuth()
                response = fetch() // Only the read is retried, never an alarm command.
            }
            when {
                response.code() == 401 -> TileStatus(error = "Sign in required\nOpen app")
                response.isSuccessful && response.body() != null ->
                    TileStatus(snapshot = StatusSnapshot(response.body()!!, now()))
                else -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return result ?: cached()?.takeIf { it.isFresh(now()) }?.let { TileStatus(snapshot = it) }
        ?: TileStatus(error = "Status unavailable\nOpen app")
}
