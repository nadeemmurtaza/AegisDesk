package com.newax.aegis.engine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP RFC 6238 implementation using HMAC-SHA1 and Base32 encoding.
 * Compatible with Google Authenticator.
 */
object TotpManager {
    private const val TIME_STEP = 30L
    private const val DIGITS = 6
    private const val HMAC_ALGO = "HmacSHA1"
    private const val DRIFT_WINDOWS = 1
    private const val KEY_SECRET = "totp_secret"
    private const val KEY_ENROLLED = "totp_enrolled"

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context, "aegis_totp", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val isEnrolled: Boolean get() = prefs?.getBoolean(KEY_ENROLLED, false) == true

    /** Generates a new cryptographically random Base32 secret (160-bit). */
    fun generateSecret(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return base32Encode(bytes)
    }

    /** Returns the currently stored secret (for display during setup). Empty if not enrolled. */
    fun storedSecret(): String = prefs?.getString(KEY_SECRET, "") ?: ""

    /** Stores the secret and marks enrollment complete after code is verified. */
    fun enroll(secret: String) {
        prefs?.edit()?.apply {
            putString(KEY_SECRET, secret.uppercase().trim())
            putBoolean(KEY_ENROLLED, true)
            apply()
        }
    }

    fun clearEnrollment() {
        prefs?.edit()?.apply {
            remove(KEY_SECRET)
            putBoolean(KEY_ENROLLED, false)
            apply()
        }
    }

    /**
     * Verifies a 6-digit TOTP code. Accepts DRIFT_WINDOWS steps in either direction
     * to handle clock skew between device and Google Authenticator.
     */
    fun verify(code: String): Boolean {
        val secret = prefs?.getString(KEY_SECRET, null) ?: return false
        val secretBytes = base32Decode(secret)
        val timeStep = System.currentTimeMillis() / 1000L / TIME_STEP
        for (i in -DRIFT_WINDOWS..DRIFT_WINDOWS) {
            if (computeTotp(secretBytes, timeStep + i) == code.trim()) return true
        }
        return false
    }

    /** Returns the otpauth URI for Google Authenticator enrollment. */
    fun otpauthUri(accountName: String = "NewaxDevice"): String {
        val secret = prefs?.getString(KEY_SECRET, null) ?: return ""
        return "otpauth://totp/Newax:$accountName?secret=$secret&issuer=Newax&algorithm=SHA1&digits=$DIGITS&period=$TIME_STEP"
    }

    /** Generates a QR code Bitmap from the current otpauth URI. Null if not enrolled. */
    fun qrBitmap(size: Int = 400): Bitmap? {
        val uri = otpauthUri().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            bmp
        } catch (_: Exception) { null }
    }

    private fun computeTotp(key: ByteArray, timeStep: Long): String {
        val data = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) { data[i] = (value and 0xFF).toByte(); value = value ushr 8 }
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(key, HMAC_ALGO))
        val hmac = mac.doFinal(data)
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val code = ((hmac[offset].toInt() and 0x7F) shl 24) or
                   ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                   ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                   (hmac[offset + 3].toInt() and 0xFF)
        return (code % 10.0.pow(DIGITS).toInt()).toString().padStart(DIGITS, '0')
    }

    private val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0; var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bitsLeft += 8
            while (bitsLeft >= 5) { bitsLeft -= 5; sb.append(B32[(buffer shr bitsLeft) and 0x1F]) }
        }
        if (bitsLeft > 0) sb.append(B32[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it in B32 }
        val out = mutableListOf<Byte>()
        var buffer = 0; var bitsLeft = 0
        for (c in clean) {
            buffer = (buffer shl 5) or B32.indexOf(c); bitsLeft += 5
            if (bitsLeft >= 8) { bitsLeft -= 8; out.add((buffer shr bitsLeft).toByte()) }
        }
        return out.toByteArray()
    }
}
