package com.v2ray.ang.ui.chapar

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The stored session exists but cannot be read right now (a passing Keystore problem); try again later. */
class SessionUnavailable(cause: Throwable) : Exception(cause)

/** What the app keeps about the signed-in user. The session token is the only secret. */
interface SessionStore {
    /** The token, or null when there is none (or it can never be read again). Throws [SessionUnavailable]. */
    fun token(): String?
    fun nickname(): String?
    fun save(token: String, nickname: String)
    fun clear()
}

/**
 * [SessionStore] on MMKV, with the token encrypted by an AES-GCM key that lives in the Android
 * Keystore (SECURITY.md: credentials in Keystore-backed storage). The key never leaves the Keystore;
 * MMKV holds only the ciphertext. If the key is lost (for example after a restore to another
 * phone), decryption fails and the user simply signs in again.
 */
class KeystoreSessionStore : SessionStore {

    override fun token(): String? {
        val stored = MmkvManager.decodeSettingsString(KEY_TOKEN) ?: return null
        val parts = stored.split(":")
        val decoded = runCatching { parts.map { Base64.decode(it, Base64.NO_WRAP) } }.getOrNull()
        if (parts.size != 2 || decoded == null) return forget("stored session malformed")
        val (iv, data) = decoded
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // A wrong tag, an invalidated or unrecoverable key: this token can never be read again.
            if (e is AEADBadTagException || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException) {
                return forget("stored session can no longer be decrypted")
            }
            throw SessionUnavailable(e)
        } catch (e: RuntimeException) { // ProviderException and friends: the Keystore is having a moment
            throw SessionUnavailable(e)
        }
    }

    private fun forget(reason: String): String? {
        LogUtil.w(AppConfig.TAG, "Chapar: $reason; signing out")
        clear()
        return null
    }

    override fun nickname(): String? = MmkvManager.decodeSettingsString(KEY_NICKNAME)

    override fun save(token: String, nickname: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        MmkvManager.encodeSettings(KEY_TOKEN, stored)
        MmkvManager.encodeSettings(KEY_NICKNAME, nickname)
    }

    override fun clear() {
        MmkvManager.encodeSettings(KEY_TOKEN, null as String?)
        MmkvManager.encodeSettings(KEY_NICKNAME, null as String?)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "chapar_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKEN = "chapar_session_token"
        const val KEY_NICKNAME = "chapar_nickname"
    }
}
