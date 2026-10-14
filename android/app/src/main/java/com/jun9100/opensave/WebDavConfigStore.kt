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

class WebDavConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): WebDavConfig {
        return WebDavConfig(
            enabled = preferences.getBoolean(ENABLED_KEY, false),
            url = preferences.getString(URL_KEY, "").orEmpty(),
            username = decrypt(preferences.getString(USERNAME_KEY, null)),
            password = decrypt(preferences.getString(PASSWORD_KEY, null)),
        )
    }

    fun save(config: WebDavConfig) {
        preferences.edit()
            .putBoolean(ENABLED_KEY, config.enabled)
            .putString(URL_KEY, config.url.trim())
            .apply {
                if (config.username.isBlank()) remove(USERNAME_KEY) else putString(USERNAME_KEY, encrypt(config.username))
                if (config.password.isBlank()) remove(PASSWORD_KEY) else putString(PASSWORD_KEY, encrypt(config.password))
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
        const val PREFERENCES = "opensave_webdav"
        const val ENABLED_KEY = "enabled"
        const val URL_KEY = "url"
        const val USERNAME_KEY = "username"
        const val PASSWORD_KEY = "password"
        const val KEY_ALIAS = "opensave_webdav_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
