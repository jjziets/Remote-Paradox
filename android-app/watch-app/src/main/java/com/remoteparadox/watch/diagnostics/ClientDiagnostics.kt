package com.remoteparadox.watch.diagnostics

import android.app.Application
import com.remoteparadox.watch.BuildConfig
import com.remoteparadox.watch.data.WatchTokenStore
import com.remoteparadox.diagnostics.DiagnosticEvent
import com.remoteparadox.diagnostics.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal data class DiagnosticSession(val scope: String, val generation: Long)

internal object ClientDiagnostics {
    val lock = Any()
    private val generation = MutableStateFlow(0L)
    val changes = generation.asStateFlow()
    private var boundScope: String? = null
    private var pin: String? = null
    var onReset: () -> Unit = {}

    fun bind(baseUrl: String?, username: String?, fingerprint: String?, token: String?) {
        synchronized(lock) {
            val scope = if (token.isNullOrBlank()) null else Diagnostics.scope(baseUrl, username)
            if (scope != boundScope || fingerprint != pin) {
                generation.value++
                if (scope == boundScope) Diagnostics.clear()
                boundScope = scope
                pin = fingerprint
                Diagnostics.bindScope(scope)
                runCatching { onReset() }
            } else {
                Diagnostics.bindScope(scope)
            }
        }
    }

    fun capture(): DiagnosticSession? = synchronized(lock) {
        boundScope?.let { DiagnosticSession(it, generation.value) }
    }

    fun matches(session: DiagnosticSession): Boolean = synchronized(lock) {
        session.scope == boundScope && session.generation == generation.value
    }

    fun record(event: DiagnosticEvent, session: DiagnosticSession? = capture()) {
        if (session == null) return
        synchronized(lock) {
            if (matches(session)) Diagnostics.record(event, session.scope)
        }
    }
}

class DiagnosticsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(File(noBackupFilesDir, "diagnostics"), "watch", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        WatchTokenStore(this)
        ClientDiagnostics.record(DiagnosticEvent(kind = "app_start", source = "watch_app"))
    }
}
