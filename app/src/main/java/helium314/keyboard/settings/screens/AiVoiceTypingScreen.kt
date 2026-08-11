// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.runtime.AiVoiceDependencies
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

/** Engine-backed settings route. No card owns a parallel local preference store. */
@Composable
fun AiVoiceTypingScreen(onClickBack: () -> Unit, onOpenProfiles: () -> Unit) {
    val context = LocalContext.current
    val dependencies = remember { AiVoiceDependencies.get() }
    val engineConfig by dependencies.settingsRepository.config.collectAsState()
    val runtimeState by dependencies.runtimeState.state.collectAsState()
    val activity = context as? Activity
    val microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_voice_typing),
        settings = emptyList(),
        content = {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            ) {
                AiVoiceActiveStatusCard(engineConfig, runtimeState)
                CompositionLocalProvider(
                    LocalContentColor provides if (microphoneGranted) LocalContentColor.current else MaterialTheme.colorScheme.error
                ) {
                    Preference(
                        name = stringResource(R.string.ai_voice_microphone_permission),
                        description = stringResource(if (microphoneGranted) R.string.ai_voice_microphone_permission_granted else R.string.ai_voice_microphone_permission_required),
                        onClick = {
                            if (!microphoneGranted && activity != null) {
                                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                            }
                        },
                    )
                }

                PreferenceCategory(stringResource(R.string.ai_voice_category_active_profile))
                AiVoiceActiveProfileSelector(
                    config = engineConfig,
                    runtime = runtimeState,
                    repository = dependencies.settingsRepository,
                )
                AiVoiceProfileManagerCard(
                    config = engineConfig,
                    onOpenProfiles = onOpenProfiles,
                )

                PreferenceCategory(stringResource(R.string.ai_voice_category_recording))
                AiVoiceRecordingBehaviourCard(engineConfig, dependencies.settingsRepository)

                PreferenceCategory(stringResource(R.string.ai_voice_category_silence))
                AiVoiceSilencePolicyCard(engineConfig, dependencies.settingsRepository)

                PreferenceCategory(stringResource(R.string.ai_voice_category_auto_stop))
                AiVoiceRecordingTimeoutCard(engineConfig, dependencies.settingsRepository)

                PreferenceCategory(stringResource(R.string.ai_voice_category_rotation))
                AiVoiceRotationPolicyCard(engineConfig, dependencies.settingsRepository)

                PreferenceCategory(stringResource(R.string.ai_voice_category_diagnostics))
                AiVoiceDiagnosticsCard(dependencies.diagnostics)
            }
        },
    )
}

private const val REQUEST_RECORD_AUDIO = 7_401

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    AiVoiceDependencies.initialize(LocalContext.current)
    Theme(previewDark) { AiVoiceTypingScreen(onClickBack = { }, onOpenProfiles = { }) }
}
