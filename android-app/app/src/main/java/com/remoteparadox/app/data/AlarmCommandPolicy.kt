package com.remoteparadox.app.data

import retrofit2.Response

internal fun Response<ActionResult>.panelAccepted(): Boolean =
    isSuccessful && body()?.success == true

internal const val UNCONFIRMED_COMMAND = "Panel did not confirm the command. Check status before retrying."

internal fun statusStreamStale(now: Long, lastStatusAt: Long): Boolean =
    now - lastStatusAt >= 15_000L
