// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    if (showConsole) AiVoiceDebugConsoleDialog(
        events = events,
        onDismiss = { showConsole = false },
    )
    if (confirmClear) ConfirmationDialog(
        onDismissRequest = { confirmClear = false },
        onConfirmed = { diagnostics.clear(); confirmClear = false },
        title = { Text(stringResource(R.string.ai_voice_clear_diagnostics)) },
        content = { Text(stringResource(R.string.ai_voice_clear_diagnostics_confirmation)) },
        confirmButtonText = stringResource(R.string.ai_voice_clear),
    )
}

@Composable
private fun AiVoiceDebugConsoleDialog(
    events: List<AiDiagnosticEvent>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
            propagateMinConstraints = true
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                contentColor = contentColorFor(MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                ) {
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleMedium) {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            Text(stringResource(R.string.ai_voice_debug_console))
                        }
                    }
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                        if (events.isEmpty()) {
                            Box(Modifier.weight(weight = 1f, fill = false).padding(bottom = 8.dp)) {
                                Text(stringResource(R.string.ai_voice_debug_console_empty))
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(weight = 1f, fill = false)
                                    .padding(bottom = 8.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                Column {
                                    events.asReversed().forEachIndexed { index, event ->
                                        AiVoiceDebugEventRow(
                                            event = event,
                                            onCopy = { text ->
                                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.ai_voice_debug_console), text))
                                                Toast.makeText(ctx, ctx.getString(R.string.toast_msg_clipboard_copy), Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        if (index < events.size - 1)
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiVoiceDebugEventRow(
    event: AiDiagnosticEvent,
    onCopy: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${event.code} · ${event.message}")
            Text(
                text = eventDescription(event),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onCopy(eventCopyText(event)) },
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Icon(
                painterResource(R.drawable.sym_keyboard_copy),
                contentDescription = stringResource(R.string.copy_to_clipboard),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun eventCopyText(event: AiDiagnosticEvent): String = buildString {
    append("${event.code} · ${event.message}")
    event.reason?.let { append("\n  $it") }
    append("\n${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.epochMillis))}")
    event.sessionId?.let { append("\n  session ${it.takeLast(8)}") }
    event.chunkSequence?.let { append("\n  chunk #$it") }
    event.profileSerial?.let { append("\n  profile #$it") }
    event.providerId?.let { append("\n  provider $it") }
    event.modelId?.let { append("\n  model $it") }
    append("\n  level ${event.level}")
}

private fun eventDescription(event: AiDiagnosticEvent): String {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.epochMillis))
    val session = event.sessionId?.let { " · session ${it.takeLast(8)}" }.orEmpty()
    val profile = event.profileSerial?.let { " · profile #$it" }.orEmpty()
    val provider = event.providerId?.let { " · $it" }.orEmpty()
    val reason = event.reason?.let { "\n  $it" }.orEmpty()
    return "$time$session$profile$provider$reason"
}
