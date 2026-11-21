package com.u4mess.ume.settings

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by the Android Keystore. Only the ciphertext is written
 * to SharedPreferences; the key never leaves the keystore.
 */
internal object SecureStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ume_cloud_api_key"
    private const val PREF_KEY = "deepseek_api_key"
    private const val GCM_TAG_BITS = 128

    fun save(prefs: SharedPreferences, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(PREF_KEY).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val blob = ByteArray(1 + iv.size + encrypted.size)
            blob[0] = iv.size.toByte()
            iv.copyInto(blob, 1)
            encrypted.copyInto(blob, 1 + iv.size)
            prefs.edit()
                .putString(PREF_KEY, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        }
    }

    fun load(prefs: SharedPreferences): String? = runCatching {
        val encoded = prefs.getString(PREF_KEY, null) ?: return@runCatching null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        if (blob.size < 3) return@runCatching null
        val ivSize = blob[0].toInt()
        if (ivSize <= 0 || blob.size <= 1 + ivSize) return@runCatching null
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val data = blob.copyOfRange(1 + ivSize, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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
}
