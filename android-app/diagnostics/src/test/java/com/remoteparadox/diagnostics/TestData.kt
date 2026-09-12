package com.remoteparadox.diagnostics

internal const val SCOPE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val SCOPE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val PROCESS = "11111111-1111-4111-8111-111111111111"
internal const val REQUEST = "22222222-2222-4222-8222-222222222222"
internal const val REPORT = "33333333-3333-4333-8333-333333333333"

internal class TestClock(var wall: Long = 1_700_000_000_000, var monotonic: Long = 100) : DiagnosticClock {
    override fun wallMs() = wall
    override fun monotonicMs() = monotonic
    fun advance(ms: Long) { wall += ms; monotonic += ms }
}

internal fun stamped(sequence: Long = 1, time: Long = 1_700_000_000_000) = DiagnosticEvent(
    kind = "http_finished", source = "http", timeMs = time, monotonicMs = 100,
    processId = PROCESS, sequence = sequence, requestId = REQUEST, route = "/alarm/arm-away",
    httpStatus = 200, elapsedMs = 42, success = true,
)

internal fun fixtureReport() = DiagnosticReport(
    reportId = REPORT, watchStatus = "included",
    phone = DeviceLog("phone", "1.2.3", 123, 1_700_000_000_100, false, listOf(stamped())),
    watch = DeviceLog("watch", "1.2.3", 45, 1_700_000_000_100, false, listOf(
        DiagnosticEvent("tile_render", "watch_tile", 1_700_000_000_000, 200, PROCESS, 1,
            connected = true, mode = "disarmed", openZones = 2, bypassedZones = 0))),
)
