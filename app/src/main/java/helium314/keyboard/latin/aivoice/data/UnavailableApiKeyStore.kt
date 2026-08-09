// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import helium314.keyboard.latin.aivoice.domain.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Encrypted, separate credential store. Keys never enter AI configuration, diagnostics, or UI state after save. */
class EncryptedPrefsApiKeyStore(context: Context) : ApiKeyStore {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(appContext, "ai_voice_credentials", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override suspend fun read(profileId: String): String? = withContext(Dispatchers.IO) { prefs.getString(profileId, null) }
    override suspend fun write(profileId: String, key: String) = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "API key is blank" }
        check(prefs.edit().putString(profileId, key).commit()) { "API key commit failed" }
    }
    override suspend fun delete(profileId: String) = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(profileId).commit()) { "API key removal failed" }
    }
}
