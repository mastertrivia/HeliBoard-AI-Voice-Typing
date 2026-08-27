// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Non-exportable Android Keystore P-256 installation key (API 21+). */
class AndroidKeystoreInstallationSigner(private val context: Context) : InstallationSigner {
    override suspend fun hasUsableKey(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val store = keyStore()
            store.containsAlias(KEY_ALIAS) && store.getKey(KEY_ALIAS, null) != null &&
                store.getCertificate(KEY_ALIAS)?.publicKey?.algorithm == "EC"
        }.getOrDefault(false)
    }

    override suspend fun resetKey() = withContext(Dispatchers.IO) {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    override suspend fun publicKeySpkiBase64(): String = withContext(Dispatchers.IO) {
        ensureKey()
        val encoded = checkNotNull(keyStore().getCertificate(KEY_ALIAS)?.publicKey?.encoded)
        Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    override suspend fun signBase64Url(message: ByteArray): String = withContext(Dispatchers.IO) {
        ensureKey()
        val privateKey = checkNotNull(keyStore().getKey(KEY_ALIAS, null))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey as java.security.PrivateKey)
            update(message)
        }.sign() // JCA SHA256withECDSA emits DER, matching Node verify('sha256', ...).
        Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun ensureKey() {
        if (runCatching { keyStore().containsAlias(KEY_ALIAS) && keyStore().getKey(KEY_ALIAS, null) != null }.getOrDefault(false)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            generator.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build())
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            @Suppress("DEPRECATION")
            generator.initialize(KeyPairGeneratorSpec.Builder(context.applicationContext)
                .setAlias(KEY_ALIAS)
                .setSubject(X500Principal("CN=HeliBoard AI Voice Installation"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build())
        }
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_voice_live_installation_p256_v1"
    }
}

/** Separate from AiVoiceConfig and API-key storage; contains backend-issued non-secret identity only. */
class SharedPrefsInstallationStateStore(private val prefs: SharedPreferences) : InstallationStateStore {
    override suspend fun read(): InstallationIdentity? = withContext(Dispatchers.IO) {
        val installationId = prefs.getString(INSTALLATION_ID, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val keyId = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
        InstallationIdentity(installationId, keyId)
    }

    override suspend fun write(value: InstallationIdentity) = withContext(Dispatchers.IO) {
        require(value.installationId.isNotBlank() && value.keyId.isNotBlank())
        check(prefs.edit().putString(INSTALLATION_ID, value.installationId).putString(KEY_ID, value.keyId).commit())
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(INSTALLATION_ID).remove(KEY_ID).commit())
    }

    private companion object {
        const val INSTALLATION_ID = "backend_installation_id"
        const val KEY_ID = "backend_key_id"
    }
}
