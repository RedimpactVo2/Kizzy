package com.my.kizzy.preference

import android.content.Context
import android.util.Base64
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Discord credential encrypted with an app-private Android Keystore AES key.
 * The old MMKV value is migrated on first successful read and then removed by Prefs.
 */
internal object SecureTokenStorage {
    private const val TAG = "RiproTokenStore"
    private const val PREFS = "secure_credentials"
    private const val TOKEN_KEY = "discord_token"
    private const val KEY_ALIAS = "ripro.discord.token.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun read(context: Context, legacyToken: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(TOKEN_KEY, null)
        if (stored != null) {
            return try {
                val packed = Base64.decode(stored, Base64.NO_WRAP)
                require(packed.size > IV_LENGTH) { "Invalid encrypted token payload" }
                val iv = packed.copyOfRange(0, IV_LENGTH)
                val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            } catch (error: Exception) {
                // Never fall back to a stale plaintext credential after encrypted data fails.
                Log.e(TAG, "Unable to decrypt stored credential", error)
                null
            }
        }

        if (legacyToken.isBlank()) return ""
        return if (write(context, legacyToken)) legacyToken else null
    }

    fun write(context: Context, token: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (token.isNullOrEmpty()) {
            return prefs.edit().remove(TOKEN_KEY).commit()
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.edit()
                .putString(TOKEN_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to encrypt credential", error)
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
