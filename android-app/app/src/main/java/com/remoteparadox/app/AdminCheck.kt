package com.remoteparadox.app

import java.util.Base64

object AdminCheck {

    fun isAdmin(storedRole: String?, jwtToken: String?): Boolean {
        if (storedRole == "admin") return true
        return extractRoleFromToken(jwtToken) == "admin"
    }

    fun extractRoleFromToken(jwtToken: String?): String? {
        val token = jwtToken ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            Regex("\"role\":\"(\\w+)\"").find(payload)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}
