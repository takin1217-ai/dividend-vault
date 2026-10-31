package com.takin1217.dividendvault.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AppLock(private val context: Context) {
    private val file = File(context.filesDir, "lock.bin")
    private val random = SecureRandom()

    fun hasConsent(): Boolean = read().optString("consentAt").isNotEmpty()

    fun acceptConsent() {
        val json = read()
        json.put("consentAt", Instant.now().toString())
        write(json)
    }

    fun hasPin(): Boolean = read().optString("pinHash").isNotEmpty()

    fun setPin(pin: String) {
        require(pin.matches(PIN)) { "암호는 숫자 4자리입니다." }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val json = read()
        json.put("pinSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
        json.put("pinHash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
        json.put("pinSetAt", Instant.now().toString())
        write(json)
    }

    fun checkPin(pin: String): Boolean {
        if (!pin.matches(PIN)) return false
        val json = read()
        val salt = Base64.decode(json.optString("pinSalt"), Base64.NO_WRAP)
        val expected = Base64.decode(json.optString("pinHash"), Base64.NO_WRAP)
        return java.security.MessageDigest.isEqual(hash(pin, salt), expected)
    }

    fun batteryPrompted(): Boolean = read().optBoolean("batteryPrompted")

    fun markBatteryPrompted() {
        val json = read()
        json.put("batteryPrompted", true)
        write(json)
    }

    fun wipeSecrets() {
        file.delete()
        File(context.filesDir, "kis.bin").delete()
        File(context.filesDir, "kis-tokens.bin").delete()
        File(context.filesDir, "kis.json").delete()
        File(context.filesDir, "kis-tokens.json").delete()
    }

    private fun read(): JSONObject {
        val text = DeviceCipher.readText(context, file) ?: return JSONObject()
        return JSONObject(text)
    }

    private fun write(json: JSONObject) {
        DeviceCipher.writeText(context, file, json.toString())
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    companion object {
        private val PIN = Regex("\\d{4}")
    }
}
