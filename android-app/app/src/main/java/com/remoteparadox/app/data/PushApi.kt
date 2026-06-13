package com.remoteparadox.app.data

import kotlinx.serialization.Serializable

@Serializable
data class PushTokenRequest(
    val token: String,
    val platform: String = "phone",
)
