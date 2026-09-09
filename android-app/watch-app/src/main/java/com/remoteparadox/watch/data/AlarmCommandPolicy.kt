package com.remoteparadox.watch.data

import retrofit2.Response
import java.util.concurrent.atomic.AtomicBoolean

internal fun Response<ActionResult>.panelAccepted(): Boolean =
    isSuccessful && body()?.success == true

internal const val UNCONFIRMED_COMMAND = "Panel did not confirm. Check status before retrying."

internal object AlarmCommandGate {
    private val sending = AtomicBoolean(false)
    fun tryBegin(): Boolean = sending.compareAndSet(false, true)
    fun finish() { sending.set(false) }
}
