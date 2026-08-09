// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsRepository
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.Preference
import java.text.DateFormat
import java.util.Date

/** Passive Card 8 viewer. It neither creates nor mutates engine diagnostic events. */
@Composable
internal fun AiVoiceDiagnosticsCard(diagnostics: AiDiagnosticsRepository) {
    val events by diagnostics.events.collectAsState()
    var showConsole by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Preference(
        name = stringResource(R.string.ai_voice_debug_console),
        description = events.lastOrNull()?.let { event ->
            val reason = event.reason?.let { " — $it" }.orEmpty()
            "${event.code} · ${event.message}$reason"
        } ?: stringResource(R.string.ai_voice_debug_console_empty),
        onClick = { showConsole = true },
    )
    Preference(
        name = stringResource(R.string.ai_voice_clear_diagnostics),
        description = stringResource(R.string.ai_voice_clear_diagnostics_summary),
        onClick = { if (events.isNotEmpty()) confirmClear = true },
    )
    if (showConsole) AlertDialog(
        onDismissRequest = { showConsole = false },
        title = { Text(stringResource(R.string.ai_voice_debug_console)) },
        text = {
            if (events.isEmpty()) Text(stringResource(R.string.ai_voice_debug_console_empty))
            else LazyColumn { itemsIndexed(events.asReversed(), key = { index, _ -> index }) { _, event ->
                Preference(name = "${event.code} · ${event.message}", description = eventDescription(event), onClick = {})
            } }
        },
        confirmButton = { TextButton(onClick = { showConsole = false }) { Text(stringResource(android.R.string.ok)) } },
    )
    if (confirmClear) ConfirmationDialog(
        onDismissRequest = { confirmClear = false },
        onConfirmed = { diagnostics.clear(); confirmClear = false },
        title = { Text(stringResource(R.string.ai_voice_clear_diagnostics)) },
        content = { Text(stringResource(R.string.ai_voice_clear_diagnostics_confirmation)) },
        confirmButtonText = stringResource(R.string.ai_voice_clear),
    )
}

private fun eventDescription(event: AiDiagnosticEvent): String {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.epochMillis))
    val session = event.sessionId?.let { " · session ${it.takeLast(8)}" }.orEmpty()
    val profile = event.profileSerial?.let { " · profile #$it" }.orEmpty()
    val provider = event.providerId?.let { " · $it" }.orEmpty()
    val reason = event.reason?.let { "\n  $it" }.orEmpty()
    return "$time$session$profile$provider$reason"
}
