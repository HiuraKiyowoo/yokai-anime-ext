package eu.kanade.tachiyomi.extension.en.cosplaytele

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import rx.Observable
import rx.schedulers.Schedulers
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cosplaytele (cosplaytele.com) — NSFW (galeri foto cosplay + video).
 *
 * WordPress 7.1.4, REST terbuka:
 *   - katalog   : /wp-json/wp/v2/posts (+ _embed=wp:featuredmedia untuk poster)
 *   - populer   : /wp-json/wordpress-popular-posts/v1/popular-posts (jumlah tontonan asli)
 *   - pencarian : /wp-json/wp/v2/posts?search=..
 *
 * Video TIDAK di-host di situsnya: setiap post menyisipkan
 *   <iframe src="https://cossora.stream/embed/<uuid>">
 * dan halaman embed itu memuat DUA string base64 yang dienkripsi
 * **AES-CBC dengan kunci 32-hex yang BERGANTI TIAP VIDEO** (dan tiap permintaan):
 *
 *   const videoURL   = '<448 char base64>';   // -> URL .m3u8
 *   const portadaURL = '<236 char base64>';   // -> screenshot.png
 *   const kunci      = '<32 hex>';            // kunci AES-256
 *
 * Tata letak sandi: 16 bita pertama = IV, sisanya = ciphertext (PKCS7).
 * Kunci dipakai sebagai 32 bita UTF-8 apa adanya (bukan hasil hex-decode).
 *
 * Dua gerbang terukur:
 *   1. Halaman embed WAJIB ber-Referer halaman post-nya (tanpa itu 404 · 43 bita).
 *   2. Berkas .m3u8-nya sendiri TIDAK butuh header apa pun (200 + playlist).
 *
 * Jadi modul ini: post -> uuid -> embed -> regex kunci & muatan -> AES -> .m3u8.
 */
private const val PROYEK = "https://cosplaytele.com"
private const val UA = "Mozilla/5.0 (Linux, Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val PER_HALAMAN = 24
private const val CMS = "https://cossora.stream"

private const val BIDANG = "id,slug,link,title,featured_media,_links,_embedded"

class Cosplaytele : HttpSource() {

    override val name = "Cosplaytele"
    override val lang = "en"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Accept-Language", "en-US,en;q=0.9")

    // --- HTTP ---------------------------------------------------------------

    private fun teks(url: String, referer: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { b.header("Referer", it) }
        return client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Cosplaytele: HTTP ${r.code} untuk $url")
            r.body.string()
        }
    }

    private fun bersih(s: String): String =
        Parser.unescapeEntities(s.replace(Regex("<[^>]+>"), " "), false)
            .replace(Regex("\\s+"), " ").trim()

    // --- Katalog ------------------------------------------------------------

    private fun wp(query: String): JSONArray =
        JSONArray(teks("$PROYEK/wp-json/wp/v2/posts?$query"))

    private fun baris(arr: JSONArray): List<SManga> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val slug = o.optString("slug")
        if (slug.isBlank()) return@mapNotNull null
        SManga.create().apply {
            url = "/$slug/"
            title = bersih(o.optJSONObject("title")?.optString("rendered").orEmpty()).ifBlank { slug }
            thumbnail_url = poster(o)
            initialized = false
        }
    }

    /** Poster dari lampiran unggulan (butuh _embed=wp:featuredmedia). */
    private fun poster(o: JSONObject): String? {
        val arr = o.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia") ?: return null
        val media = arr.optJSONObject(0) ?: return null
        val url = media.optString("source_url")
        if (url.isBlank()) return null
        // buang varian mini (-300x200) supaya posternya tajam
        return url.replace(Regex("-\\d+x\\d+(?=\\.\\w+$)"), "")
    }

    private fun terbaru(page: Int, cari: String? = null): MangasPage {
        val q = buildString {
            append("per_page=$PER_HALAMAN&page=$page&orderby=date&order=desc")
            append("&_embed=wp:featuredmedia&_fields=$BIDANG")
            if (!cari.isNullOrBlank()) append("&search=${URLEncoder.encode(cari, "UTF-8")}")
        }
        val isi = baris(wp(q))
        return MangasPage(isi, isi.isNotEmpty())
    }

    /**
     * Populer: /wordpress-popular-posts/v1/popular-posts membalas objek post lengkap
     * beserta jumlah tontonan, TAPI tidak mendukung _embed — jadi id-nya diambil dulu,
     * lalu satu permintaan kedua memakai include= untuk poster & slug.
     */
    private fun populer(page: Int): MangasPage {
        runCatching {
            val arr = JSONArray(teks("$PROYEK/wp-json/wordpress-popular-posts/v1/popular-posts?limit=$PER_HALAMAN&page=$page"))
            val id = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optInt("id")?.takeIf { it > 0 } }
            if (id.isEmpty()) return MangasPage(emptyList(), false)
            val isi = baris(wp("include=${id.joinToString(",")}&per_page=$PER_HALAMAN&orderby=include&_embed=wp:featuredmedia&_fields=$BIDANG"))
            if (isi.isNotEmpty()) return MangasPage(isi, id.size == PER_HALAMAN)
        }
        // cadangan bila plugin populer mati: pakai yang paling banyak diperbarui
        return terbaru(page)
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable { populer(page) }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { terbaru(page) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable { terbaru(page, query.trim()) }
        .subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(emptyList())

    // --- Isi post -----------------------------------------------------------

    private fun slug(manga: SManga): String = manga.url.trim('/').substringAfterLast('/')

    private fun objek(slug: String, tambahan: String = ""): JSONObject? {
        val q = "slug=$slug&_embed=wp:featuredmedia&_fields=title,content,featured_media,_links,_embedded$tambahan"
        return wp(q).optJSONObject(0)
    }

    private fun konten(slug: String): String =
        objek(slug)?.optJSONObject("content")?.optString("rendered").orEmpty()

    private val reEmbed = Regex("""cossora\.stream/embed/([A-Za-z0-9-]{20,})""")
    private val reFoto = Regex("""<img[^>]+src=["'](https://cosplaytele\.com/wp-content/uploads/[^"']+\.(?:jpe?g|png|webp))["']""")

    private fun embed(isi: String): List<String> =
        reEmbed.findAll(isi).map { "$CMS/embed/${it.groupValues[1]}" }.distinct().toList()

    private fun foto(isi: String): List<String> =
        reFoto.findAll(isi).map { it.groupValues[1] }
            .filter { !it.contains("/fav") }
            .distinct()
            .toList()

    // --- Detail & bab -------------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val o = objek(slug(manga)) ?: throw Exception("Cosplaytele: post tidak ditemukan")
            val isi = o.optJSONObject("content")?.optString("rendered").orEmpty()
            manga.apply {
                title = bersih(o.optJSONObject("title")?.optString("rendered").orEmpty()).ifBlank { manga.title }
                val jumlahVideo = embed(isi).size
                val jumlahFoto = foto(isi).size
                description = buildString {
                    append("Foto: ").append(jumlahFoto)
                    if (jumlahVideo > 0) append(" · Video: ").append(jumlahVideo)
                    val ket = bersih(isi).take(700)
                    if (ket.isNotBlank()) append("\n\n").append(ket)
                }
                thumbnail_url = poster(o) ?: thumbnail_url
                status = SManga.COMPLETED
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    /**
     * Url bab berbentuk "<mode>|<jalur post>", mis. "v0|/judul/" atau "f|/judul/".
     * Nama bab tidak boleh sama dengan judul seri: ChapterRecognition di aplikasi
     * membuang judul seri dari nama bab sehingga barisnya tampak kosong.
     */
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val isi = konten(slug(manga))
            val jumlahVideo = embed(isi).size
            val jumlahFoto = foto(isi).size
            val daftar = mutableListOf<SChapter>()
            for (i in 0 until jumlahVideo) {
                daftar += SChapter.create().apply {
                    url = "v$i|${manga.url}"
                    name = if (jumlahVideo == 1) "Video" else "Video ${i + 1}"
                    chapter_number = (i + 1).toFloat()
                }
            }
            if (jumlahFoto > 0) {
                daftar += SChapter.create().apply {
                    url = "f|${manga.url}"
                    name = "Foto ($jumlahFoto)"
                    chapter_number = 0f
                }
            }
            if (daftar.isEmpty()) {
                daftar += SChapter.create().apply {
                    url = "v0|${manga.url}"
                    name = "Video"
                    chapter_number = 1f
                }
            }
            daftar
        }
        .subscribeOn(Schedulers.io())

    // --- Halaman: video (.m3u8) atau foto -----------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val pisah = chapter.url.split("|", limit = 2)
            val mode = pisah.getOrElse(0) { "v0" }
            val jalur = pisah.getOrElse(1) { chapter.url }
            val isi = konten(jalur.trim('/').substringAfterLast('/'))

            if (mode == "f") {
                val gambar = foto(isi)
                if (gambar.isEmpty()) throw Exception("Cosplaytele: foto tidak ditemukan")
                return@fromCallable gambar.mapIndexed { i, u -> Page(i, u) }
            }

            val nomor = mode.removePrefix("v").toIntOrNull() ?: 0
            val daftar = embed(isi)
            val alamat = daftar.getOrNull(nomor)
                ?: throw Exception("Cosplaytele: video ke-${nomor + 1} tidak ada di post ini")
            // halaman embed menolak tanpa Referer halaman post-nya (404)
            val url = videoDari(alamat, "$PROYEK$jalur")
            listOf(Page(0, url))
        }
        .subscribeOn(Schedulers.io())

    // --- Enkripsi cossora ---------------------------------------------------

    private val reKunci = Regex("""['"]([0-9a-fA-F]{32})['"]""")
    private val reMuatan = Regex("""['"]([A-Za-z0-9+/=]{120,})['"]""")
    private val rePotongan = Regex("""['"]([A-Za-z0-9+/=]{16,})['"]""")

    /** IV = 16 bita pertama, sisanya ciphertext; kunci = 32 bita UTF-8 apa adanya. */
    private fun dekripsi(b64: String, kunci: String): String? = runCatching {
        val data = java.util.Base64.getDecoder().decode(b64.trim())
        if (data.size <= 32) return null
        val sandi = Cipher.getInstance("AES/CBC/PKCS5Padding")
        sandi.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(kunci.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(data.copyOfRange(0, 16)),
        )
        String(sandi.doFinal(data.copyOfRange(16, data.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun videoDari(embedUrl: String, referer: String): String {
        val html = teks(embedUrl, referer)
        val kunci = reKunci.findAll(html).map { it.groupValues[1] }.distinct().toList()
        val utuh = reMuatan.findAll(html).map { it.groupValues[1] }.distinct().toList()
        // Cadangan: sebagian halaman menyusun muatan dari beberapa potongan.
        val muatan = if (utuh.isNotEmpty()) utuh else {
            listOf(rePotongan.findAll(html).map { it.groupValues[1] }.joinToString(""))
        }
        for (k in kunci) {
            for (m in muatan) {
                val hasil = dekripsi(m, k) ?: continue
                if (hasil.contains(".m3u8")) return hasil
            }
        }
        throw Exception("Cosplaytele: token video gagal dibuka (kunci=${kunci.size}, muatan=${muatan.size})")
    }
}
