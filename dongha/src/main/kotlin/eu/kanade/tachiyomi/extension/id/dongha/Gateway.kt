package eu.kanade.tachiyomi.extension.id.dongha

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Klien gateway Dongha: bootstrap sesi terenkripsi, kirim permintaan, dekripsi balasan.
 *
 * Alurnya (dicocokkan dengan skrip Node `dongha.js`):
 *  1. POST /gateway/session/bootstrap  → { data: { session_id, session_secret, scope, kid } }
 *  2. kunci = HKDF-SHA256(session_secret, session_id, "dongha-gateway|v1|scope|kid")
 *  3. setiap balasan berbentuk envelope { alg:"C20P", nonce, ciphertext, upstream, status, content_type, kid }
 *  4. plaintext = ChaCha20Poly1305-dekripsi(ciphertext, AAD=method|path|upstream|status|type|kid)
 */
internal class Gateway(private val client: OkHttpClient, private val token: String?) {

    private var kid: String? = null
    private var key: ByteArray? = null

    private val jsonType = "application/json".toMediaType()

    fun bootstrap() {
        val (status, body) = kirim("POST", "/gateway/session/bootstrap", "{}", sudahBootstrap = false)
        if (status != 200) throw Exception("Bootstrap gateway gagal (HTTP $status)")
        val data = JSONObject(body.toString(Charsets.UTF_8)).optJSONObject("data")
            ?: throw Exception("Bootstrap gateway: data kosong")
        val sid = data.optString("session_id")
        val secret = data.optString("session_secret")
        val scope = data.optString("scope")
        val kidBaru = data.optString("kid")
        if (sid.isEmpty() || secret.isEmpty() || kidBaru.isEmpty()) {
            throw Exception("Bootstrap gateway: field sesi tidak lengkap")
        }
        key = Crypto.hkdfSha256(
            ikm = B64.decode(secret),
            salt = sid.toByteArray(Charsets.UTF_8),
            info = "dongha-gateway|v1|$scope|$kidBaru".toByteArray(Charsets.UTF_8),
        )
        kid = kidBaru
    }

    /** Ambil path relatif (mis. "/explore?limit=24") dan kembalikan teks balasan yang sudah didekripsi. */
    fun get(path: String): String {
        if (key == null) bootstrap()
        val (status, body) = kirim("GET", path, null, sudahBootstrap = true)
        val teks = body.toString(Charsets.UTF_8)
        if (status >= 400) {
            val pesan = try {
                JSONObject(teks).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            } catch (_: Exception) {
                null
            }
            throw Exception(pesan?.takeIf { it.isNotBlank() } ?: "Gateway HTTP $status")
        }
        return teks
    }

    fun post(path: String, json: String): JSONObject {
        if (key == null) bootstrap()
        val (status, body) = kirim("POST", path, json, sudahBootstrap = true)
        val teks = body.toString(Charsets.UTF_8)
        if (status >= 400) {
            val pesan = try {
                JSONObject(teks).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            } catch (_: Exception) {
                null
            }
            throw Exception(pesan?.takeIf { it.isNotBlank() } ?: "Gateway HTTP $status")
        }
        return JSONObject(teks)
    }

    private fun kirim(method: String, path: String, body: String?, sudahBootstrap: Boolean): Pair<Int, ByteArray> {
        val bare = path.substringBefore("?")

        val builder = Request.Builder().url(GATEWAY_BASE + path)
        builder.header("User-Agent", UA)
        builder.header("Accept", "application/json, */*")
        kid?.let { builder.header("X-Gateway-Kid", it) }
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }

        if (body != null) {
            builder.method(method, body.toRequestBody(jsonType))
        } else {
            builder.method(method, null)
        }

        val respons = client.newCall(builder.build()).execute()
        respons.use { r ->
            val mentah = r.body?.bytes() ?: ByteArray(0)
            val teks = mentah.toString(Charsets.UTF_8)
            val env = try {
                JSONObject(teks).takeIf { it.optString("alg") == "C20P" }
            } catch (_: Exception) {
                null
            }
            if (env == null) {
                // Tidak terenkripsi (mis. bootstrap): kembalikan apa adanya.
                return r.code to mentah
            }

            val kunci = key ?: throw Exception("Envelope terenkripsi diterima sebelum bootstrap")
            val aad = Crypto.aad(
                method = method,
                barePath = bare,
                upstream = env.optString("upstream"),
                status = env.optString("status"),
                contentType = env.optString("content_type"),
                kid = env.optString("kid"),
            )
            val plain = Crypto.maybeDecompress(
                Crypto.decryptChaCha20Poly1305(
                    key = kunci,
                    nonceB64 = env.optString("nonce"),
                    payloadB64 = env.optString("ciphertext"),
                    aad = aad,
                ),
            )
            val statusPlain = env.optString("status").toIntOrNull() ?: r.code
            return statusPlain to plain
        }
    }

    companion object {
        const val GATEWAY_BASE = "https://gateway.dongha.id"
        const val ASSETS_BASE = "https://dongha.epeministudio.com"
        const val VIDEO_BASE = "https://video.epeministudio.com"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Dongha) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** URL aset (poster/thumbnail) dari nama berkas yang dikirim server. */
        fun aset(nama: String?): String? {
            if (nama.isNullOrBlank()) return null
            if (nama.startsWith("http")) return nama
            return "$ASSETS_BASE/$nama"
        }

        /** URL video: server mengirim path relatif (mis. "<hash>/1080p.mp4"). */
        fun video(path: String?): String? {
            if (path.isNullOrBlank()) return null
            if (path.startsWith("http")) return path
            return "$VIDEO_BASE/${path.trimStart('/')}"
        }
    }
}