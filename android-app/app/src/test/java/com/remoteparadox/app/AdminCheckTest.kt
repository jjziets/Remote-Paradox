package com.remoteparadox.app

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AdminCheckTest {

    private fun buildJwt(role: String): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"Hannes","role":"$role","iat":1710000000,"exp":1710259200}""".toByteArray())
        return "$header.$payload.fakesig"
    }

    @Test
    fun `returns true when stored role is admin`() {
        assertTrue(AdminCheck.isAdmin(storedRole = "admin", jwtToken = null))
    }

    @Test
    fun `returns false when stored role is user and no token`() {
        assertFalse(AdminCheck.isAdmin(storedRole = "user", jwtToken = null))
    }

    @Test
    fun `returns true when stored role is null but JWT has admin`() {
        val token = buildJwt("admin")
        assertTrue(AdminCheck.isAdmin(storedRole = null, jwtToken = token))
    }

    @Test
    fun `returns false when stored role is user and JWT has user`() {
        val token = buildJwt("user")
        assertFalse(AdminCheck.isAdmin(storedRole = "user", jwtToken = token))
    }

    @Test
    fun `returns true when stored role is admin even if JWT says user`() {
        val token = buildJwt("user")
        assertTrue(AdminCheck.isAdmin(storedRole = "admin", jwtToken = token))
    }

    @Test
    fun `returns false for malformed token with no stored role`() {
        assertFalse(AdminCheck.isAdmin(storedRole = null, jwtToken = "not.a.jwt"))
    }

    @Test
    fun `returns false for null role and null token`() {
        assertFalse(AdminCheck.isAdmin(storedRole = null, jwtToken = null))
    }
}
