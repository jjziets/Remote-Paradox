package com.remoteparadox.watch.diagnostics

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

internal fun diagnosticReply(request: WatchLogRequest, scope: String?, snapshot: () -> DeviceLog): WatchLogReply =
    if (scope == request.scope) WatchLogReply(request.reportId, request.scope, "included", snapshot())
    else WatchLogReply(request.reportId, request.scope, "scope_mismatch")

class DiagnosticRequestService : WearableListenerService() {
    // WearableListenerService dispatches these callbacks on its background looper.
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/paradox/diagnostics/request" || event.data.size > 256) return
        val request = runCatching {
            DiagnosticCodec.json.decodeFromString<WatchLogRequest>(event.data.decodeToString(throwOnInvalidSequence = true))
        }.getOrNull() ?: return
        runCatching { runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                val session = ClientDiagnostics.capture()
                val reply = diagnosticReply(request, session?.scope) { Diagnostics.snapshot(48 * 1024) }
                val send = synchronized(ClientDiagnostics.lock) {
                    val safeReply = if (session != null && ClientDiagnostics.matches(session)) reply
                        else WatchLogReply(request.reportId, request.scope, "scope_mismatch")
                    val bytes = DiagnosticCodec.json.encodeToString(safeReply).toByteArray()
                    if (bytes.size > 64 * 1024) null else Wearable.getMessageClient(this@DiagnosticRequestService)
                        .sendMessage(event.sourceNodeId, "/paradox/diagnostics/response", bytes)
                }
                send?.await()
            }
        } } // A failed capture or transport produces an honest unavailable result on the phone.
    }
}
