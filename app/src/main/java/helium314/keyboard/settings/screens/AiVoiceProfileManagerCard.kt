// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.domain.ApiKeyStore
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.NewProfileDraft
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.ProviderDescriptor
import helium314.keyboard.latin.aivoice.domain.ValidationState
import helium314.keyboard.latin.aivoice.runtime.AiVoiceDependencies
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRecordingState
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRuntimeState
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.longPressDraggableHandle
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Entry only. Profile management itself lives on its own settings route. */
@Composable
internal fun AiVoiceProfileManagerCard(config: AiVoiceConfig, onOpenProfiles: () -> Unit) {
    val enabledCount = config.profiles.count { it.enabled }
    Preference(
        name = stringResource(R.string.ai_voice_manage_profiles),
        description = stringResource(
            R.string.ai_voice_profiles_enabled_total,
            enabledCount,
            config.profiles.size,
            AiVoiceConfig.MAX_PROFILES,
        ),
        onClick = onOpenProfiles,
    )
}

/** Independent full-screen profile list; it deliberately does not reuse the toolbar selector. */
@Composable
fun AiVoiceProfilesScreen(onClickBack: () -> Unit) {
    val dependencies = remember { AiVoiceDependencies.get() }
    val config by dependencies.settingsRepository.config.collectAsState()
    val runtime by dependencies.runtimeState.state.collectAsState()
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ai_voice_api_profiles),
        settings = emptyList(),
        content = {
            AiVoiceProfilesPage(
                config = config,
                runtime = runtime,
                repository = dependencies.settingsRepository,
                catalog = dependencies.catalog,
                apiKeyStore = dependencies.apiKeyStore,
            )
        },
    )
}

@Composable
private fun AiVoiceProfilesPage(
    config: AiVoiceConfig,
    runtime: AiVoiceRuntimeState,
    repository: AiVoiceSettingsRepository,
    catalog: ProviderCatalog,
    apiKeyStore: ApiKeyStore,
) {
    val scope = rememberCoroutineScope()
    var displayedProfiles by remember(config.profiles) { mutableStateOf(config.profiles) }
    var draft by remember { mutableStateOf<ProfileEditDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<ApiProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val reorderFailedMessage = stringResource(R.string.ai_voice_profile_reorder_failed)
    val toggleFailedMessage = stringResource(R.string.ai_voice_profile_toggle_failed)
    val saveFailedMessage = stringResource(R.string.ai_voice_profile_save_failed)
    val deleteBlockedMessage = stringResource(R.string.ai_voice_profile_delete_blocked)
    val deleteFailedMessage = stringResource(R.string.ai_voice_profile_delete_failed)
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        displayedProfiles = displayedProfiles.toMutableList().apply { add(to.index, removeAt(from.index)) }
        val order = displayedProfiles.map { it.id }
        scope.launch {
            try {
                repository.reorderProfiles(order)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = reorderFailedMessage
            }
        }
    }
    val enabledCount = config.profiles.count { it.enabled }
    val atProfileLimit = config.profiles.size >= AiVoiceConfig.MAX_PROFILES

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.ai_voice_profiles_page_count, enabledCount, config.profiles.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, top = 12.dp, end = 24.dp),
        )
        Text(
            stringResource(R.string.ai_voice_profiles_maximum, AiVoiceConfig.MAX_PROFILES),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        if (!atProfileLimit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { draft = ProfileEditDraft.new(catalog) },
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text(stringResource(R.string.ai_voice_add_profile)) }
            }
        } else {
            Text(
                stringResource(R.string.ai_voice_profiles_limit, AiVoiceConfig.MAX_PROFILES),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (displayedProfiles.isEmpty()) {
            Text(
                stringResource(R.string.ai_voice_profiles_empty),
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(displayedProfiles, key = { it.id }) { profile ->
                    ReorderableItem(state = reorderState, key = profile.id) { dragging ->
                        val elevation by animateDpAsState(if (dragging) 4.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            ProfileRow(
                                profile = profile,
                                catalog = catalog,
                                onEdit = { if (!saving) draft = profile.toDraft() },
                                onEnabledChange = { enabled ->
                                    scope.launch {
                                        try {
                                            repository.setProfileEnabled(profile.id, enabled)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            error = toggleFailedMessage
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    draft?.let { editing -> ProfileEditorDialog(
        initial = editing,
        catalog = catalog,
        apiKeyStore = apiKeyStore,
        saving = saving,
        onDismiss = { if (!saving) draft = null },
        onSave = { submitted ->
            saving = true
            scope.launch {
                var persisted: ApiProfile? = null
                try {
                    val saved = if (submitted.id == null) repository.createProfile(submitted.toNewProfile()) else {
                        val existing = config.profiles.firstOrNull { it.id == submitted.id }
                            ?: throw IllegalStateException("Profile no longer exists")
                        require(submitted.apiKey.isNotBlank() ||
                            (submitted.providerId == existing.providerId && submitted.modelId == existing.modelId)) {
                            "A replacement API key is required when changing provider or model"
                        }
                        repository.saveProfile(submitted.toExisting(existing))
                    }
                    persisted = saved
                    try {
                        if (submitted.apiKey.isNotBlank()) apiKeyStore.write(saved.id, submitted.apiKey)
                        if (submitted.id == null && config.profiles.none { it.id == config.activeProfileId && it.enabled }) {
                            repository.selectActiveProfile(saved.id, recordingActive = false)
                        }
                    } finally {
                        draft = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    draft = persisted?.let { saved -> submitted.copy(id = saved.id, serialNumber = saved.serialNumber, apiKey = "") }
                        ?: submitted.copy(apiKey = "")
                    error = saveFailedMessage
                } finally {
                    saving = false
                }
            }
        },
        onDelete = editing.id?.let { id -> {
            config.profiles.firstOrNull { it.id == id }?.let { target ->
                if (target.id == config.activeProfileId || target.id == config.pendingProfileId ||
                    (runtime.recording != AiVoiceRecordingState.IDLE && runtime.profileId == target.id)) {
                    error = deleteBlockedMessage
                } else {
                    deleteTarget = target
                }
            }
        } },
    )

    deleteTarget?.let { target -> ConfirmationDialog(
        onDismissRequest = { deleteTarget = null },
        onConfirmed = {
            deleteTarget = null
            scope.launch {
                try {
                    val oldKey = apiKeyStore.read(target.id)
                    apiKeyStore.delete(target.id)
                    try {
                        repository.deleteProfile(target.id)
                    } catch (exception: Exception) {
                        if (!oldKey.isNullOrBlank()) runCatching { apiKeyStore.write(target.id, oldKey) }
                        throw exception
                    }
                    draft = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = deleteFailedMessage
                }
            }
        },
        title = { Text(stringResource(R.string.ai_voice_remove_profile)) },
        content = { Text(stringResource(R.string.ai_voice_remove_profile_confirmation, target.displayName)) },
        confirmButtonText = stringResource(R.string.ai_voice_remove),
    )
    error?.let { message -> InfoDialog(message) { error = null } }
    }
    }
}

@Composable
private fun ProfileRow(
    profile: ApiProfile,
    catalog: ProviderCatalog,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_drag_indicator),
            contentDescription = stringResource(R.string.ai_voice_reorder_profile),
            modifier = Modifier.longPressDraggableHandle().padding(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text("#${profile.serialNumber} ${profile.displayName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                profileProviderModel(profile, catalog),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.ai_voice_edit_profile))
        }
        Switch(
            checked = profile.enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

private data class ProfileEditDraft(
    val id: String?,
    val serialNumber: Int?,
    val displayName: String,
    val providerId: String,
    val modelId: String,
    val validation: ValidationState = ValidationState.UNTESTED,
    val apiKey: String = "",
    val providerOptions: Map<String, String> = emptyMap(),
) {
    override fun toString() = "ProfileEditDraft(id=$id, serialNumber=$serialNumber, displayName=$displayName, providerId=$providerId, modelId=$modelId, apiKey=<redacted>)"
    fun toNewProfile() = NewProfileDraft(displayName.trim(), providerId, modelId, providerOptions)
    fun toExisting(existing: ApiProfile) = existing.copy(
        displayName = displayName.trim(), providerId = providerId, modelId = modelId,
        validation = if (providerId == existing.providerId && modelId == existing.modelId) existing.validation else ValidationState.UNTESTED,
        providerOptions = providerOptions,
    )
    companion object {
        fun new(catalog: ProviderCatalog): ProfileEditDraft {
            val provider = catalog.providers().firstOrNull { it.models.isNotEmpty() }
                ?: error("No speech provider models are installed")
            return ProfileEditDraft(null, null, "", provider.id, provider.models.first().id)
        }
    }
}

private fun ApiProfile.toDraft() = ProfileEditDraft(id, serialNumber, displayName, providerId, modelId, validation, providerOptions = providerOptions)

@Composable
private fun ProfileEditorDialog(
    initial: ProfileEditDraft,
    catalog: ProviderCatalog,
    apiKeyStore: ApiKeyStore,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProfileEditDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var choosingProvider by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    LaunchedEffect(initial.id) {
        if (initial.id != null) {
            val storedKey = try {
                apiKeyStore.read(initial.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            draft = initial.copy(apiKey = storedKey.orEmpty())
        }
    }
    val descriptor = catalog.provider(draft.providerId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) stringResource(R.string.ai_voice_add_profile) else stringResource(R.string.ai_voice_edit_profile)) },
        text = {
            Column {
                OutlinedTextField(value = draft.displayName, onValueChange = { draft = draft.copy(displayName = it) }, label = { Text(stringResource(R.string.ai_voice_profile_name)) }, enabled = !saving, modifier = Modifier.fillMaxWidth())
                Preference(name = stringResource(R.string.ai_voice_provider), description = descriptor?.label ?: stringResource(R.string.ai_voice_unavailable_provider), onClick = { if (!saving) choosingProvider = true })
                Preference(name = stringResource(R.string.ai_voice_model), description = descriptor?.models?.firstOrNull { it.id == draft.modelId }?.label ?: stringResource(R.string.ai_voice_unavailable_model), onClick = { if (!saving && descriptor != null) choosingModel = true })
                OutlinedTextField(value = draft.apiKey, onValueChange = { draft = draft.copy(apiKey = it) }, label = { Text(stringResource(R.string.ai_voice_api_key)) }, enabled = !saving, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !saving && draft.displayName.isNotBlank() && draft.apiKey.isNotBlank() && catalog.supports(draft.providerId, draft.modelId), onClick = { onSave(draft) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            Column {
                onDelete?.let { TextButton(enabled = !saving, onClick = it) { Text(stringResource(R.string.ai_voice_remove_profile)) } }
                TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
    if (choosingProvider) ListPickerDialog(onDismissRequest = { choosingProvider = false }, title = { Text(stringResource(R.string.ai_voice_provider)) }, items = catalog.providers().filter { it.models.isNotEmpty() }, selectedItem = descriptor, getItemName = ProviderDescriptor::label, onItemSelected = { provider -> draft = draft.copy(providerId = provider.id, modelId = provider.models.first().id, validation = ValidationState.UNTESTED, providerOptions = emptyMap()); choosingProvider = false })
    if (choosingModel && descriptor != null) ListPickerDialog(onDismissRequest = { choosingModel = false }, title = { Text(stringResource(R.string.ai_voice_model)) }, items = descriptor.models, selectedItem = descriptor.models.firstOrNull { it.id == draft.modelId }, getItemName = { it.label }, onItemSelected = { model -> draft = draft.copy(modelId = model.id, validation = ValidationState.UNTESTED); choosingModel = false })
}

@Composable
private fun profileProviderModel(profile: ApiProfile, catalog: ProviderCatalog): String {
    val provider = catalog.provider(profile.providerId)?.label ?: stringResource(R.string.ai_voice_unavailable_provider)
    val model = catalog.provider(profile.providerId)?.models?.firstOrNull { it.id == profile.modelId }?.label
        ?: stringResource(R.string.ai_voice_unavailable_model)
    return "$provider - $model"
}
