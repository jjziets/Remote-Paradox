package com.remoteparadox.app

import com.remoteparadox.app.data.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class AlarmCommandPolicyTest {
    @Test fun `transport does not transparently replay a lost request`() {
        ApiClient.create("https://127.0.0.1:9433/")
        assertFalse(ApiClient.httpClient.retryOnConnectionFailure)
    }

    @Test fun `HTTP success is not panel acceptance`() {
        assertFalse(Response.success(ActionResult(false)).panelAccepted())
        assertFalse(Response.success<ActionResult>(null).panelAccepted())
        assertFalse(Response.error<ActionResult>(503, "".toResponseBody()).panelAccepted())
        assertTrue(Response.success(ActionResult(true)).panelAccepted())
    }

    @Test fun `silent websocket expires after fifteen seconds`() {
        assertFalse(statusStreamStale(14_999, 0))
        assertTrue(statusStreamStale(15_000, 0))
        assertFalse(statusStreamStale(20_000, 19_000))
    }
}
