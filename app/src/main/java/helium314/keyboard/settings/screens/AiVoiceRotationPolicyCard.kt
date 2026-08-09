// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.RotationConfig
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Card 6 persists independent policy inputs; RotationCoordinator owns every runtime decision. */
@Composable
internal fun AiVoiceRotationPolicyCard(config: AiVoiceConfig, repository: AiVoiceSettingsRepository) {
    val scope = rememberCoroutineScope()
    var inFlight by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<RotationPicker?>(null) }
    var saveFailed by remember { mutableStateOf(false) }

    fun update(reason: String, transform: (RotationConfig) -> RotationConfig) {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                repository.update(reason) { current -> current.copy(rotation = transform(current.rotation)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                inFlight = false
            }
        }
    }

    val rotation = config.rotation
    ToggleRow(stringResource(R.string.ai_voice_failure_fallback), stringResource(R.string.ai_voice_failure_fallback_summary), rotation.failureRotationEnabled, !inFlight) {
        enabled -> update("failure_rotation_changed") { it.copy(failureRotationEnabled = enabled) }
    }
    ToggleRow(stringResource(R.string.ai_voice_disable_failed_profile), stringResource(R.string.ai_voice_disable_failed_profile_summary), rotation.disableFailedProfile, !inFlight) {
        enabled -> update("disable_failed_profile_changed") { it.copy(disableFailedProfile = enabled) }
    }
    SelectorRow(stringResource(R.string.ai_voice_day_rotation), dayOptionFor(rotation.dayRotationIntervalDays), !inFlight, { picker = RotationPicker.DAY }, ::dayLabel)
    SelectorRow(stringResource(R.string.ai_voice_clock_rotation), clockOptionFor(rotation.clockRotationIntervalMillis), !inFlight, { picker = RotationPicker.CLOCK }, ::clockLabel)
    SelectorRow(stringResource(R.string.ai_voice_usage_rotation), usageOptionFor(rotation.usageRotationLimitMillis), !inFlight, { picker = RotationPicker.USAGE }, ::usageLabel)

    when (picker) {
        RotationPicker.DAY -> ListPickerDialog(
            onDismissRequest = { picker = null }, title = { Text(stringResource(R.string.ai_voice_day_rotation)) }, items = dayOptions,
            selectedItem = dayOptionFor(rotation.dayRotationIntervalDays), getItemName = ::dayLabel,
            onItemSelected = { value -> update("day_rotation_changed") { it.copy(dayRotationIntervalDays = value.value) } },
        )
        RotationPicker.CLOCK -> ListPickerDialog(
            onDismissRequest = { picker = null }, title = { Text(stringResource(R.string.ai_voice_clock_rotation)) }, items = clockOptions,
            selectedItem = clockOptionFor(rotation.clockRotationIntervalMillis), getItemName = ::clockLabel,
            onItemSelected = { value -> update("clock_rotation_changed") { it.copy(clockRotationIntervalMillis = value.value) } },
        )
        RotationPicker.USAGE -> ListPickerDialog(
            onDismissRequest = { picker = null }, title = { Text(stringResource(R.string.ai_voice_usage_rotation)) }, items = usageOptions,
            selectedItem = usageOptionFor(rotation.usageRotationLimitMillis), getItemName = ::usageLabel,
            onItemSelected = { value -> update("usage_rotation_changed") { it.copy(usageRotationLimitMillis = value.value) } },
        )
        null -> Unit
    }
    if (saveFailed) InfoDialog(stringResource(R.string.ai_voice_rotation_save_failed)) { saveFailed = false }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Preference(name = title, description = summary, onClick = { if (enabled) onChanged(!checked) }) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChanged)
    }
}

@Composable
private fun <T> SelectorRow(title: String, value: T, enabled: Boolean, onClick: () -> Unit, label: @Composable (T) -> String) {
    Preference(name = title, description = label(value), onClick = { if (enabled) onClick() })
}

private enum class RotationPicker { DAY, CLOCK, USAGE }
private data class DayOption(val value: Int?)
private data class TimeOption(val value: Long?)
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private val dayOptions = listOf(DayOption(null), DayOption(1), DayOption(2), DayOption(3), DayOption(7))
private val clockOptions = listOf(
    TimeOption(null),
    TimeOption(1 * MINUTE), TimeOption(2 * MINUTE), TimeOption(3 * MINUTE), TimeOption(4 * MINUTE),
    TimeOption(5 * MINUTE), TimeOption(6 * MINUTE), TimeOption(7 * MINUTE), TimeOption(10 * MINUTE),
    TimeOption(15 * MINUTE), TimeOption(20 * MINUTE), TimeOption(30 * MINUTE), TimeOption(45 * MINUTE),
    TimeOption(1 * HOUR), TimeOption(2 * HOUR), TimeOption(3 * HOUR), TimeOption(6 * HOUR),
)
private val usageOptions = listOf(
    TimeOption(null),
    TimeOption(30_000L),
    TimeOption(1 * MINUTE), TimeOption(2 * MINUTE), TimeOption(3 * MINUTE), TimeOption(4 * MINUTE),
    TimeOption(5 * MINUTE), TimeOption(6 * MINUTE), TimeOption(7 * MINUTE), TimeOption(8 * MINUTE),
    TimeOption(9 * MINUTE), TimeOption(10 * MINUTE), TimeOption(13 * MINUTE), TimeOption(15 * MINUTE),
    TimeOption(20 * MINUTE), TimeOption(30 * MINUTE), TimeOption(45 * MINUTE),
    TimeOption(1 * HOUR), TimeOption(2 * HOUR), TimeOption(6 * HOUR),
)

@Composable private fun dayLabel(option: DayOption) = option.value?.let { pluralStringResource(R.plurals.ai_voice_day, it, it) } ?: stringResource(R.string.ai_voice_off)
@Composable private fun clockLabel(option: TimeOption) = option.value?.let {
    if (it % HOUR == 0L) pluralStringResource(R.plurals.ai_voice_hour, (it / HOUR).toInt(), (it / HOUR).toInt())
    else pluralStringResource(R.plurals.ai_voice_minute, (it / MINUTE).toInt(), (it / MINUTE).toInt())
} ?: stringResource(R.string.ai_voice_off)
@Composable private fun usageLabel(option: TimeOption) = option.value?.let {
    when {
        it % HOUR == 0L -> pluralStringResource(R.plurals.ai_voice_hour, (it / HOUR).toInt(), (it / HOUR).toInt())
        it % MINUTE == 0L -> pluralStringResource(R.plurals.ai_voice_minute, (it / MINUTE).toInt(), (it / MINUTE).toInt())
        else -> stringResource(R.string.ai_voice_seconds, (it / 1000).toInt())
    }
} ?: stringResource(R.string.ai_voice_off)

private fun dayOptionFor(value: Int?) = dayOptions.firstOrNull { it.value == value } ?: DayOption(value)
private fun clockOptionFor(value: Long?) = clockOptions.firstOrNull { it.value == value } ?: TimeOption(value)
private fun usageOptionFor(value: Long?) = usageOptions.firstOrNull { it.value == value } ?: TimeOption(value)
