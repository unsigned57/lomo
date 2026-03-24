package com.lomo.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreBackedPreferences(
    context: Context,
    preferenceFileName: String,
    keyAlias: String,
) {
    private val keyStoreAlias = "$KEY_ALIAS_PREFIX$keyAlias"
    private val prefs: SharedPreferences =
        context.getSharedPreferences(preferenceFileName, Context.MODE_PRIVATE)

    fun getString(key: String): String? {
        val encryptedValue = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(encryptedValue) }
            .onFailure { prefs.edit { remove(key) } }
            .getOrNull()
    }

    fun putString(
        key: String,
        value: String?,
    ) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove(key)
            } else {
                putString(key, encrypt(value))
            }
            apply()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = requireNotNull(cipher.iv) { "Cipher IV is unavailable" }
        return listOf(PAYLOAD_VERSION, encode(iv), encode(cipherText)).joinToString(PAYLOAD_SEPARATOR)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = PAYLOAD_PART_LIMIT)
        require(parts.size == PAYLOAD_PART_LIMIT && parts.first() == PAYLOAD_VERSION) {
            "Unsupported secure preference payload"
        }
        val iv = decode(parts[1])
        val cipherText = decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyStoreAlias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec
                .Builder(
                    keyStoreAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_ALIAS_PREFIX = "com.lomo.secure."
        const val PAYLOAD_PART_LIMIT = 3
        const val PAYLOAD_VERSION = "v1"
        const val PAYLOAD_SEPARATOR = ":"
    }
}
