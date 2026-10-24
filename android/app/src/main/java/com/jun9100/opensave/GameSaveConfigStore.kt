package com.jun9100.opensave

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GameSaveConfig(
    val enabled: Boolean = false,
    val serviceUrl: String = "",
    val syncKey: String = "",
)

/** Persists only the service address in plaintext; the sync key stays in Android Keystore encryption. */
class GameSaveConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): GameSaveConfig = GameSaveConfig(
        enabled = preferences.getBoolean(ENABLED_KEY, false),
        serviceUrl = preferences.getString(URL_KEY, "").orEmpty(),
        syncKey = decrypt(preferences.getString(SYNC_KEY, null)),
    )

    fun save(config: GameSaveConfig) {
        preferences.edit()
            .putBoolean(ENABLED_KEY, config.enabled)
            .putString(URL_KEY, GameSaveClient.normalizeServiceUrl(config.serviceUrl))
            .apply {
                if (config.syncKey.isBlank()) remove(SYNC_KEY) else putString(SYNC_KEY, encrypt(config.syncKey))
            }
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val combined = Base64.getDecoder().decode(value)
            require(combined.size > GCM_IV_BYTES) { "同步密钥密文无效" }
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "opensave_game_save"
        const val ENABLED_KEY = "enabled"
        const val URL_KEY = "service_url"
        const val SYNC_KEY = "sync_key"
        const val KEY_ALIAS = "opensave_game_save_sync_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
