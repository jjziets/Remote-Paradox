package com.remoteparadox.app

import org.junit.Assert.*
import org.junit.Test

class BleRetryLogicTest {

    companion object {
        const val GATT_SUCCESS = 0
        const val GATT_ERROR = 133
        const val GATT_CONN_TIMEOUT = 8
        const val GATT_CONN_TERMINATE_LOCAL = 22
        const val MAX_RETRIES = 3

        fun isRetriableError(status: Int): Boolean =
            status == GATT_ERROR || status == GATT_CONN_TIMEOUT ||
                status == GATT_CONN_TERMINATE_LOCAL

        fun shouldRetry(status: Int, retries: Int): Boolean =
            isRetriableError(status) && retries < MAX_RETRIES
    }

    @Test
    fun `status 133 is retriable`() {
        assertTrue(isRetriableError(GATT_ERROR))
    }

    @Test
    fun `status 8 is retriable`() {
        assertTrue(isRetriableError(GATT_CONN_TIMEOUT))
    }

    @Test
    fun `status 22 is retriable`() {
        assertTrue(isRetriableError(GATT_CONN_TERMINATE_LOCAL))
    }

    @Test
    fun `status 0 is not retriable`() {
        assertFalse(isRetriableError(GATT_SUCCESS))
    }

    @Test
    fun `retries retriable error under max`() {
        assertTrue(shouldRetry(GATT_ERROR, 0))
        assertTrue(shouldRetry(GATT_ERROR, 2))
    }

    @Test
    fun `stops retrying at max retries`() {
        assertFalse(shouldRetry(GATT_ERROR, MAX_RETRIES))
    }

    @Test
    fun `does not retry non-retriable error`() {
        assertFalse(shouldRetry(GATT_SUCCESS, 0))
        assertFalse(shouldRetry(5, 0))
    }
}
