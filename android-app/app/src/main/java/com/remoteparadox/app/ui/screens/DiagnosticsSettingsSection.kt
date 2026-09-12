package com.remoteparadox.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remoteparadox.app.diagnostics.DiagnosticReportState

@Composable
internal fun DiagnosticsSettingsSection(state: DiagnosticReportState, send: () -> Unit, retry: () -> Unit, forget: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.reportId?.let { Text("${state.identifierLabel}: $it", style = MaterialTheme.typography.bodySmall) }
        state.watchStatus?.let {
            Text("Watch: " + when (it) {
                "included" -> "included"
                "scope_mismatch" -> "different server or account; not included"
                else -> "unavailable; not included"
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = send, enabled = !state.busy && !state.pending, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.BugReport, null)
            Spacer(Modifier.width(8.dp))
            Text("Send diagnostic report")
        }
        if (state.pending) {
            OutlinedButton(onClick = retry, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Retry saved report")
            }
            TextButton(onClick = forget, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DeleteOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("Forget saved report")
            }
        }
    }
}
