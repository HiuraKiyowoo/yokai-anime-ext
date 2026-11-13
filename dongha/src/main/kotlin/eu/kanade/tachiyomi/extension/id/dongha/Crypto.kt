package eu.kanade.tachiyomi.extension.id.dongha

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * Port dari crypto gateway Dongha (versi Node: `hkdf()` + `#decrypt()`).
 *
 * Konstruksi di sisi server:
 *  - kunci  : HKDF-SHA256(ikm = base64(session_secret), salt = session_id,
 *             info = "dongha-gateway|v1|<scope>|<kid>") → 32 bita
 *  - cipher  : ChaCha20-Poly1305, nonce 12 bita dari server
 *  - AAD     : join("\n", [method, pathTanpaQuery, upstream, status, content_type, kid])
 *  - wire    : ciphertext base64 = (ciphertext || 16 bita tag)
 *
 * ChaCha20-Poly1305 di Android tersedia sejak API 28; modul ini memakai minSdk 28.
 */
internal object Crypto {

    /** HKDF-SHA256 (RFC 5869). Ukuran 32 bita = satu blok, jadi cukup satu HMAC. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")

        // HKDF-Extract
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // HKDF-Expand (satu iterasi cukup untuk length <= 32)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        return if (length == okm.size) okm else okm.copyOf(length)
    }

    fun aad(method: String, barePath: String, upstream: String, status: String, contentType: String, kid: String): ByteArray =
        listOf(method, barePath, upstream, status, contentType, kid).joinToString("\n").toByteArray(Charsets.UTF_8)

    /**
     * @param payload base64 dari (ciphertext || tag)
     * @return plaintext mentah (belum di-decompress; lihat [maybeDecompress])
     */
    fun decryptChaCha20Poly1305(key: ByteArray, nonceB64: String, payloadB64: String, aad: ByteArray): ByteArray {
        val isi = B64.decode(payloadB64)
        require(isi.size > 16) { "ciphertext terlalu pendek" }
        val ct = isi.copyOfRange(0, isi.size - 16)
        val tag = isi.copyOfRange(isi.size - 16, isi.size)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(B64.decode(nonceB64)),
        )
        cipher.updateAAD(aad)
        // JCE menaruh tag di akhir input.
        return cipher.doFinal(ct + tag)
    }

    /** Server kadang mengirim gzip atau zlib; deteksi dari dua bita pertama. */
    fun maybeDecompress(buf: ByteArray): ByteArray {
        if (buf.size < 2) return buf
        val b0 = buf[0].and(0xFF.toByte()).toInt()
        val b1 = buf[1].and(0xFF.toByte()).toInt()
        return when {
            b0 == 0x1F && b1 == 0x8B -> java.util.zip.GZIPInputStream(buf.inputStream()).readBytes()
            b0 == 0x78 && (b1 == 0x01 || b1 == 0x9C || b1 == 0xDA) ->
                java.util.zip.InflaterInputStream(buf.inputStream()).readBytes()
            else -> buf
        }
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Base64 tanpa bergantung pada android.util (aman untuk unit test JVM). */
internal object B64 {
    fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    fun encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
}