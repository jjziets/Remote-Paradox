package com.remoteparadox.app

import java.util.Base64

object AdminCheck {

    fun isAdmin(storedRole: String?, jwtToken: String?): Boolean {
        if (storedRole == "admin") return true

        val token = jwtToken ?: return false
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return false
            val decoded = Base64.getUrlDecoder().decode(parts[1])
            val payload = String(decoded)
            payload.contains("\"role\":\"admin\"") || payload.contains("\"role\": \"admin\"")
        } catch (_: Exception) {
            false
        }
    }
}
