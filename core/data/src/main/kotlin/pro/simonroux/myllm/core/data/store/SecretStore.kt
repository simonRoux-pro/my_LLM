package pro.simonroux.myllm.core.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsDataStore by preferencesDataStore(name = "myllm_secrets")

/**
 * API keys and tokens, encrypted with a key that never leaves the secure hardware.
 *
 * The master key lives in the Android Keystore, so the ciphertext stored on disk
 * is worthless on another device and unreadable to another app even on a rooted
 * one. What is stored here are third-party credentials, so losing them to a
 * backup or a file manager would mean losing someone else's money, not just
 * privacy.
 *
 * AES-GCM is used rather than CBC because it authenticates: a tampered value
 * fails to decrypt instead of returning plausible garbage.
 */
class SecretStore(private val context: Context) {

    // The return type is spelled out: an expression body would infer DataStore's
    // Preferences from edit(), putting that type in this class's public API and
    // on the compile classpath of everything that stores a key.
    suspend fun put(alias: String, value: String): Unit = withContext(Dispatchers.IO) {
        val encrypted = encrypt(value)
        context.secretsDataStore.edit { it[stringPreferencesKey(alias)] = encrypted }
        Unit
    }

    suspend fun get(alias: String): String? = withContext(Dispatchers.IO) {
        val stored = context.secretsDataStore.data
            .map { it[stringPreferencesKey(alias)] }
            .first() ?: return@withContext null
        decrypt(stored)
    }

    suspend fun remove(alias: String): Unit = withContext(Dispatchers.IO) {
        context.secretsDataStore.edit { it.remove(stringPreferencesKey(alias)) }
        Unit
    }

    suspend fun has(alias: String): Boolean = get(alias) != null

    /** Never returns the value itself, only enough to recognise it in the UI. */
    suspend fun preview(alias: String): String? {
        val value = get(alias) ?: return null
        return if (value.length <= 8) "..." else value.take(4) + "..." + value.takeLast(4)
    }

    // --- crypto ----------------------------------------------------------

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        // The IV is generated per encryption and is not secret, so it travels
        // with the ciphertext rather than being stored separately.
        return Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Fetches the keystore key, creating it on first use.
     *
     * No user authentication requirement is attached: a background sync or a
     * scheduled skill has to be able to reach the key while the phone is in a
     * pocket, and the device lock already gates access to the keystore itself.
     */
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "myllm_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
