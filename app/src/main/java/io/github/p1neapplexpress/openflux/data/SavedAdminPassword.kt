package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SavedAdminPassword {
    private const val KEY_ALIAS = "terzaet.admin.password.v1"
    private const val PREFS = "protected_admin_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun read(context: Context, host: String, user: String, port: Int): String? {
        val encrypted = preferences(context).getString(identity(host, user, port), null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.remaining() >= 4)
            val ivLength = buffer.int
            require(ivLength in 12..16 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(ciphertext), Charsets.UTF_8)
            }
        }.getOrElse {
            preferences(context).edit().remove(identity(host, user, port)).apply()
            null
        }
    }

    fun save(context: Context, host: String, user: String, port: Int, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size).put(iv).put(ciphertext).array()
        preferences(context).edit()
            .putString(identity(host, user, port), Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun forget(context: Context, host: String, user: String, port: Int) {
        preferences(context).edit().remove(identity(host, user, port)).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun identity(host: String, user: String, port: Int) =
        "${host.trim().lowercase()}|${user.trim()}|$port"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}
