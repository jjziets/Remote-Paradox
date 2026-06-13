package com.remoteparadox.watch.data

import kotlinx.serialization.Serializable

@Serializable
data class PushTokenRequest(
    val token: String,
    val platform: String = "watch",
)
