package com.wxgy.checkin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地存储。账密用 Android Keystore 里的 AES 密钥加密后再落盘，
 * 不依赖明文 SharedPreferences；照片从不落盘。
 */
object Prefs {

    private const val FILE = "wxgy_checkin"
    private const val ALIAS = "wxgy_checkin_secret"

    const val KEY_USER = "username"
    const val KEY_PASS = "password"
    const val KEY_TOKEN = "token"
    const val KEY_NAME = "name"
    const val KEY_NO = "no"
    const val KEY_GRADE = "grade"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ct = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        "$iv:$ct"
    }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun putSecret(ctx: Context, key: String, value: String?) {
        val e = sp(ctx).edit()
        if (value.isNullOrEmpty()) e.remove(key) else e.putString(key, encrypt(value))
        e.apply()
    }

    fun getSecret(ctx: Context, key: String): String? {
        val raw = sp(ctx).getString(key, null) ?: return null
        return decrypt(raw)
    }

    fun put(ctx: Context, key: String, value: String?) {
        val e = sp(ctx).edit()
        if (value.isNullOrEmpty()) e.remove(key) else e.putString(key, value)
        e.apply()
    }

    fun get(ctx: Context, key: String): String? = sp(ctx).getString(key, null)

    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}
