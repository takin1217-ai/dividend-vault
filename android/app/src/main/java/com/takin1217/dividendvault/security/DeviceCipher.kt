package com.takin1217.dividendvault.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts bytes with a non-exportable Android Keystore key. Files stay on this device. */
object DeviceCipher {
    private const val ALIAS = "dividend-vault-aes"
    private const val VERSION: Byte = 1

    fun writeText(context: Context, file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(encrypt(context, text.toByteArray(Charsets.UTF_8)))
    }

    fun readText(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return decrypt(context, file.readBytes()).toString(Charsets.UTF_8)
    }

    fun encrypt(context: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(VERSION) + iv + body
    }

    fun decrypt(context: Context, blob: ByteArray): ByteArray {
        if (blob.size < 14 || blob[0] != VERSION) error("암호화된 파일을 읽지 못했습니다.")
        val iv = blob.copyOfRange(1, 13)
        val body = blob.copyOfRange(13, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
