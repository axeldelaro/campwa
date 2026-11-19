package com.sentinelcam.app.crypto

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
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
    val wakePushTopic: String,
    val bootstrapEncKey: ByteArray,
    val bootstrapAuthKey: ByteArray
)

data class SasPair(
    val sasCode: String,
    val sasSymbols: String
)

data class EphemeralEcdhPair(
    val keyPair: KeyPair,
    val publicRawBase64: String
)

/**
 * SentinelCam v8.0 Hardened Cryptographic Engine — 100% byte-identical with WebCrypto (`cryptoEngine.ts`).
 *
 * Security Guarantees:
 * - 100,000-round PBKDF2-HMAC-SHA256 key stretching (memoized in RAM for 0ms reconnects)
 * - True 1-RTT Ephemeral ECDH P-256 (secp256r1) Perfect Forward Secrecy (PFS) mixed into HKDF-SHA256
 * - AES-256-GCM with 96-bit CSPRNG IV and Additional Authenticated Data (AAD) binding envelope metadata
 * - Constant-time HMAC verification & ±120s anti-replay timestamp window
 */
object CryptoProtocol {
    private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PBKDF2_SALT = "sentinelcam-v3-pbkdf2-salt"
    private const val PBKDF2_ITERATIONS = 100_000
    private val secureRandom = SecureRandom()
    private val keyCache = ConcurrentHashMap<String, BootstrapKeys>()

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

    fun deriveBootstrapKeys(code: String): BootstrapKeys {
        val normalized = normalizePairingCode(code)
        require(normalized.length == 6) { "Pairing code must be 6 chars" }

        keyCache[normalized]?.let { return it }

        val spec = PBEKeySpec(
            normalized.toCharArray(),
            PBKDF2_SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            512 // 64 bytes
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val master = factory.generateSecret(spec).encoded
        spec.clearPassword()

        val topicId = bytesToHex(master.copyOfRange(0, 16))
        val encBytes = master.copyOfRange(16, 48)
        val authBytes = master.copyOfRange(48, 64)

        val derived = BootstrapKeys(
            normalizedCode = normalized,
            topicId = topicId,
            wakePushTopic = "sc_wake_${topicId.take(20)}",
            bootstrapEncKey = encBytes,
            bootstrapAuthKey = authBytes
        )
        keyCache[normalized] = derived
        return derived
    }

    /* ==================== EPHEMERAL ECDH P-256 (PERFECT FORWARD SECRECY) ==================== */

    fun generateEphemeralEcdh(): EphemeralEcdhPair? {
        return try {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
            val kp = kpg.generateKeyPair()
            val ecPub = kp.public as ECPublicKey
            val raw65 = encodeUncompressedP256Point(ecPub.w)
            EphemeralEcdhPair(
                keyPair = kp,
                publicRawBase64 = Base64.encodeToString(raw65, Base64.NO_WRAP)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun computeEcdhSharedSecret(localKeyPair: KeyPair, peerRawBase64: String): ByteArray? {
        return try {
            val raw = Base64.decode(peerRawBase64, Base64.NO_WRAP)
            if (raw.size != 65 || raw[0] != 0x04.toByte()) return null
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            val ecParams = (localKeyPair.public as ECPublicKey).params
            val peerSpec = ECPublicKeySpec(ECPoint(x, y), ecParams)
            val peerPub = KeyFactory.getInstance("EC").generatePublic(peerSpec)

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(localKeyPair.private as PrivateKey)
            ka.doPhase(peerPub, true)
            val secret = ka.generateSecret()
            if (secret.size == 32) secret else normalize32Bytes(secret)
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodeUncompressedP256Point(point: ECPoint): ByteArray {
        val x = bigIntToFixed32(point.affineX)
        val y = bigIntToFixed32(point.affineY)
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(x, 0, out, 1, 32)
        System.arraycopy(y, 0, out, 33, 32)
        return out
    }

    private fun bigIntToFixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return normalize32Bytes(raw)
    }

    private fun normalize32Bytes(raw: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (raw.size == 32) {
            System.arraycopy(raw, 0, out, 0, 32)
        } else if (raw.size > 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    /* ==================== HKDF / HMAC / SESSION KEYS ==================== */

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

    fun computeHelloWakeProof(
        bootstrapAuthRaw: ByteArray,
        clientNonce: String,
        tokenId: String,
        clientEcdhPub: String = ""
    ): String {
        val suffix = if (clientEcdhPub.isNotEmpty()) ":$clientEcdhPub" else ""
        return hmacSha256Hex(bootstrapAuthRaw, "hello-wake-v3:$clientNonce:$tokenId$suffix")
    }

    fun computeHelloAckProof(
        bootstrapAuthRaw: ByteArray,
        clientNonce: String,
        serverNonce: String,
        deviceId: String,
        serverEcdhPub: String = ""
    ): String {
        val suffix = if (serverEcdhPub.isNotEmpty()) ":$serverEcdhPub" else ""
        return hmacSha256Hex(bootstrapAuthRaw, "hello-ack-v3:$clientNonce:$serverNonce:$deviceId$suffix")
    }

    fun deriveFastSessionKey(
        bootstrapAuthRaw: ByteArray,
        clientNonce: String,
        serverNonce: String,
        deviceId: String,
        ecdhSharedSecret: ByteArray? = null
    ): ByteArray {
        val salt = "$clientNonce:$serverNonce:$deviceId".toByteArray(Charsets.UTF_8)
        if (ecdhSharedSecret != null && ecdhSharedSecret.isNotEmpty()) {
            val combinedIkm = ByteArray(bootstrapAuthRaw.size + ecdhSharedSecret.size)
            System.arraycopy(bootstrapAuthRaw, 0, combinedIkm, 0, bootstrapAuthRaw.size)
            System.arraycopy(ecdhSharedSecret, 0, combinedIkm, bootstrapAuthRaw.size, ecdhSharedSecret.size)
            return hkdfSha256(combinedIkm, salt, "sentinelcam-v8-pfs-session", 32)
        }
        return hkdfSha256(bootstrapAuthRaw, salt, "sentinelcam-v3-fast-session", 32)
    }

    fun computeSas(
        normalizedCode: String,
        tokenId: String,
        deviceId: String,
        bootstrapAuthRaw: ByteArray
    ): SasPair {
        val authHex = bytesToHex(bootstrapAuthRaw)
        val input = "sentinelcam-sas-v3:$normalizedCode:$tokenId:$deviceId:$authHex"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        val p1 = String.format("%02X", digest[0].toInt() and 0xFF)
        val p2 = String.format("%02X", digest[1].toInt() and 0xFF)
        val p3 = String.format("%02X", digest[2].toInt() and 0xFF)

        val b3 = digest[3].toInt() and 0xFF
        val b4 = digest[4].toInt() and 0xFF
        val s1 = SAS_SYMBOLS_TABLE[b3 and 0x0F]
        val s2 = SAS_SYMBOLS_TABLE[(b3 ushr 4) and 0x0F]
        val s3 = SAS_SYMBOLS_TABLE[b4 and 0x0F]
        val s4 = SAS_SYMBOLS_TABLE[(b4 ushr 4) and 0x0F]

        return SasPair(sasCode = "$p1-$p2-$p3", sasSymbols = "$s1 $s2 $s3 $s4")
    }

    fun extractSdpFingerprint(sdp: String): String {
        val regex = Regex("a=fingerprint:sha-256\\s+([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(sdp) ?: return ""
        return match.groupValues[1].uppercase()
    }

    /* ==================== AES-256-GCM + AAD METADATA BINDING ==================== */

    private fun buildEnvelopeAad(id: String, mode: String, sender: String, ts: Long): ByteArray {
        return "v1:$id:$mode:$sender:$ts".toByteArray(Charsets.UTF_8)
    }

    fun encryptEnvelope(
        payload: JSONObject,
        aesKeyBytes: ByteArray,
        mode: String,
        senderId: String
    ): String {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val id = randomHex(12)
        val ts = System.currentTimeMillis()
        val aad = buildEnvelopeAad(id, mode, senderId, ts)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val envelope = JSONObject().apply {
            put("v", 1)
            put("aad", 1)
            put("id", id)
            put("mode", mode)
            put("sender", senderId)
            put("ts", ts)
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

        val id = envelope.optString("id", "")
        val mode = envelope.optString("mode", "")
        val sender = envelope.optString("sender", "")
        val ivB64 = envelope.optString("iv", "")
        val ctB64 = envelope.optString("ct", "")
        if (id.isEmpty() || ivB64.isEmpty() || ctB64.isEmpty()) return null

        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            if (iv.size != 12) return null
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
            if (envelope.optInt("aad", 0) == 1) {
                cipher.updateAAD(buildEnvelopeAad(id, mode, sender, ts))
            }
            val plainBytes = cipher.doFinal(ct)
            JSONObject(String(plainBytes, Charsets.UTF_8))
        } catch (_: Throwable) {
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
