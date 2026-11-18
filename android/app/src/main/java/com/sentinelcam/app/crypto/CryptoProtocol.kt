package com.sentinelcam.app.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

data class BootstrapKeys(
    val normalizedCode: String,
    val topicId: String,
    val bootstrapEncKey: ByteArray,
    val bootstrapAuthKey: ByteArray
)

data class SasPair(
    val sasCode: String,
    val sasSymbols: String
)

/**
 * Cryptographic Protocol Engine — 100% byte-for-byte interoperable with WebCrypto API
 * (`src/crypto/cryptoEngine.ts`).
 *
 * - PBKDF2-HMAC-SHA256 (150,000 iterations)
 * - RFC 5869 HKDF-SHA256
 * - HMAC-SHA256 Mutual Challenge-Response
 * - AES-256-GCM (96-bit random IV + 128-bit authentication tag + ±120s anti-replay)
 */
object CryptoProtocol {
    private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PBKDF2_SALT = "sentinelcam-v1-pbkdf2-salt"
    private const val PBKDF2_ITERATIONS = 150_000
    private val secureRandom = SecureRandom()

    private val SAS_SYMBOLS_TABLE = arrayOf(
        "🛡️", "🔐", "⚡", "🛰️", "🔑", "💎", "🧭", "🔥",
        "🌐", "🧿", "🎯", "🏔️", "🦅", "⚓", "🧬", "🔮"
    )

    fun normalizePairingCode(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().take(6)
    }

    fun generateSecurePairingCode(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(6)
        for (b in bytes) {
            val idx = (b.toInt() and 0xFF) % PAIRING_ALPHABET.length
            sb.append(PAIRING_ALPHABET[idx])
        }
        return sb.toString()
    }

    fun randomHex(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return bytesToHex(bytes)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun deriveBootstrapKeys(code: String): BootstrapKeys {
        val normalized = normalizePairingCode(code)
        require(normalized.length == 6) { "Pairing code must be 6 chars" }

        val spec = PBEKeySpec(
            normalized.toCharArray(),
            PBKDF2_SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            512 // 64 bytes
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val master = factory.generateSecret(spec).encoded
        spec.clearPassword()

        val topicBytes = master.copyOfRange(0, 16)
        val encBytes = master.copyOfRange(16, 48)
        val authBytes = master.copyOfRange(48, 64)

        return BootstrapKeys(
            normalizedCode = normalized,
            topicId = bytesToHex(topicBytes),
            bootstrapEncKey = encBytes,
            bootstrapAuthKey = authBytes
        )
    }

    /**
     * RFC 5869 HKDF-SHA256 implementation matching WebCrypto HKDF bit-for-bit.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, infoStr: String, lengthBytes: Int = 32): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256Raw(actualSalt, ikm)

        val infoBytes = infoStr.toByteArray(Charsets.UTF_8)
        val result = ByteArray(lengthBytes)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < lengthBytes) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            t = mac.doFinal()

            val toCopy = min(t.size, lengthBytes - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    fun hmacSha256Raw(keyBytes: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmacSha256Hex(keyBytes: ByteArray, message: String): String {
        return bytesToHex(hmacSha256Raw(keyBytes, message.toByteArray(Charsets.UTF_8)))
    }

    fun computeClientChallengeProof(
        pairedKeyHex: String,
        clientNonce: String,
        serverNonce: String,
        tokenId: String,
        deviceId: String
    ): String {
        return hmacSha256Hex(
            hexToBytes(pairedKeyHex),
            "client-auth-v1:$clientNonce:$serverNonce:$tokenId:$deviceId"
        )
    }

    fun computeServerChallengeProof(
        pairedKeyHex: String,
        clientNonce: String,
        serverNonce: String,
        tokenId: String,
        deviceId: String
    ): String {
        return hmacSha256Hex(
            hexToBytes(pairedKeyHex),
            "server-auth-v1:$clientNonce:$serverNonce:$tokenId:$deviceId"
        )
    }

    fun deriveSessionAesKey(
        pairedKeyHex: String,
        clientNonce: String,
        serverNonce: String
    ): ByteArray {
        val salt = "$clientNonce:$serverNonce".toByteArray(Charsets.UTF_8)
        return hkdfSha256(
            hexToBytes(pairedKeyHex),
            salt,
            "sentinelcam-session-aes256gcm-v1",
            32
        )
    }

    fun computeSas(
        normalizedCode: String,
        tokenId: String,
        deviceId: String,
        pairedKeyHex: String
    ): SasPair {
        val input = "sentinelcam-sas-v1:$normalizedCode:$tokenId:$deviceId:${pairedKeyHex.lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        val p1 = String.format("%02X", digest[0].toInt() and 0xFF)
        val p2 = String.format("%02X", digest[1].toInt() and 0xFF)
        val p3 = String.format("%02X", digest[2].toInt() and 0xFF)
        val sasCode = "$p1-$p2-$p3"

        val b3 = digest[3].toInt() and 0xFF
        val b4 = digest[4].toInt() and 0xFF
        val s1 = SAS_SYMBOLS_TABLE[b3 and 0x0F]
        val s2 = SAS_SYMBOLS_TABLE[(b3 ushr 4) and 0x0F]
        val s3 = SAS_SYMBOLS_TABLE[b4 and 0x0F]
        val s4 = SAS_SYMBOLS_TABLE[(b4 ushr 4) and 0x0F]

        return SasPair(sasCode = sasCode, sasSymbols = "$s1 $s2 $s3 $s4")
    }

    fun extractSdpFingerprint(sdp: String): String {
        val regex = Regex("a=fingerprint:sha-256\\s+([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(sdp) ?: return ""
        return match.groupValues[1].uppercase()
    }

    fun encryptEnvelope(
        payload: JSONObject,
        aesKeyBytes: ByteArray,
        mode: String,
        senderId: String
    ): String {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val envelope = JSONObject().apply {
            put("v", 1)
            put("id", randomHex(12))
            put("mode", mode)
            put("sender", senderId)
            put("ts", System.currentTimeMillis())
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        return envelope.toString()
    }

    fun decryptEnvelope(
        envelope: JSONObject,
        aesKeyBytes: ByteArray,
        maxSkewMs: Long = 120_000L
    ): JSONObject? {
        if (envelope.optInt("v", 0) != 1) return null
        val ts = envelope.optLong("ts", 0L)
        if (abs(System.currentTimeMillis() - ts) > maxSkewMs) return null

        val ivB64 = envelope.optString("iv", "")
        val ctB64 = envelope.optString("ct", "")
        if (ivB64.isEmpty() || ctB64.isEmpty()) return null

        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(ct)
            JSONObject(String(plainBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun constantTimeEqualHex(a: String, b: String): Boolean {
        val bytesA = a.lowercase().toByteArray(Charsets.UTF_8)
        val bytesB = b.lowercase().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(bytesA, bytesB)
    }

    fun calculateLockoutDurationMs(failedAttempts: Int): Long {
        if (failedAttempts < 3) return 0L
        val exp = min(failedAttempts - 3, 5)
        return (30_000.0 * 2.0.pow(exp.toDouble())).toLong()
    }
}
