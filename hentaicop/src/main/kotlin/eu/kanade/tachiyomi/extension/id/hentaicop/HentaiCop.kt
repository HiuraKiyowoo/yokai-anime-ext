package eu.kanade.tachiyomi.extension.id.hentaicop

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import rx.Observable
import rx.schedulers.Schedulers

/**
 * HentaiCop (hentaicop.com) — NSFW.
 *
 * WordPress. REST API terbuka: /wp-json/wp/v2/posts (orderby=date|modified|title
 * saja — comment_count & rand ditolak 400) dan API milik situs /wp-json/hc/v1/.
 *
 * Stream: halaman post -> <iframe src="/play/v/<id>/?q=720">
 *         -> halaman pemutar memuat URL HLS di CDN jav.<hash>.site:
 *            /hls/jav/<kode>/<KODE-XX>_720p.mp4/index-v1-a1.m3u8?md5=..&expires=..
 *
 * DUA GERBANG WAJIB, kalau tidak: 403 "Akses ditolak: Fake Referer terdeteksi."
 *   1. Referer = URL halaman episode itu sendiri (bukan beranda)
 *   2. Accept-Language harus terisi (mis. "id-ID,id;q=0.9")
 *
 * Jebakan lain: "&" pada URL .m3u8 di halaman ter-escape jadi "&#038;" — wajib
 * di-unescape. Poster tidak ada di REST (featured_media 0, og:image = fav.webp),
 * jadi diambil dari gambar /wp-content/uploads/ pertama di halaman episode.
 */
private const val PROYEK = "https://hentaicop.com"
private const val UA = "Mozilla/5.0 (Linux; Android 14; AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

class HentaiCop : HttpSource() {

    override val name = "HentaiCop"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    // Referer & Accept-Language ikut dikirim ke pemutar video oleh aplikasi,
    // jadi CDN HentaiCop tidak lagi membalas 403 saat memutar .m3u8.
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", "$PROYEK/")

    // --- HTTP ---------------------------------------------------------------

    private fun teks(url: String, referer: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        referer?.let { b.header("Referer", it) }
        return client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HentaiCop: HTTP ${r.code} untuk $url")
            r.body.string()
        }
    }

    // --- Katalog ------------------------------------------------------------

    private fun halaman(orderby: String, page: Int): MangasPage {
        val arr = JSONArray(teks("$PROYEK/wp-json/wp/v2/posts?orderby=$orderby&order=desc&per_page=24&page=$page"))
        val isi = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug")
            if (slug.isBlank()) null else SManga.create().apply {
                url = "/$slug/"
                title = bersih(o.optJSONObject("title")?.optString("rendered").orEmpty())
                    .ifBlank { slug }
                initialized = false
            }
        }
        return MangasPage(isi, isi.isNotEmpty())
    }

    private fun bersih(s: String): String =
        Parser.unescapeEntities(s.replace(Regex("<[^>]+>"), ""), false).trim()

    // Populer: tidak ada data jumlah tontonan di API ini (archive-nya dirender JS
    // lewat POST yang butuh nonce), jadi dipakai "baru diperbarui" — orderby=modified.
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halaman("modified", page) }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halaman("date", page) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = query.trim()
            if (kunci.isBlank() || page > 1) return@fromCallable halaman("date", page)
            val o = org.json.JSONObject(
                teks("$PROYEK/wp-json/hc/v1/suggest?q=${java.net.URLEncoder.encode(kunci, "UTF-8")}"),
            )
            val arr = o.optJSONArray("items") ?: JSONArray()
            val isi = (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = it.optString("url").removePrefix(PROYEK)
                if (path.isBlank()) null else SManga.create().apply {
                    url = path
                    title = it.optString("title").ifBlank { path }
                    thumbnail_url = it.optString("thumb").ifBlank { null }
                    initialized = false
                }
            }
            MangasPage(isi, false)
        }
        .subscribeOn(Schedulers.io())

    // --- Detail & episode ---------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val halaman = teks("$PROYEK${manga.url}")
            val doc = Jsoup.parse(halaman)
            manga.apply {
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.ifBlank { null }?.let { title = bersih(it) }
                // Deskripsi: aplikasi menampilkan "Tidak ada deskripsi" kalau kosong.
                val ket = doc.selectFirst("meta[property=og:description]")?.attr("content")
                    ?.ifBlank { null }
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
                ket?.let { description = bersih(it) }
                thumbnail_url = posterDari(doc, halaman) ?: thumbnail_url
                status = SManga.COMPLETED
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    /** Gambar asli episode; og:image selalu fav.webp jadi harus disaring. */
    private fun posterDari(doc: org.jsoup.nodes.Document, html: String): String? {
        val kandidat = doc.select("img[src]").map { it.absUrl("src") }
            .filter { it.contains("/wp-content/uploads/") && !it.contains("/fav") }
        kandidat.firstOrNull { Regex("""\.(?:webp|jpg|jpeg|png)$""").containsMatchIn(it.substringBefore("?")) }
            ?.let { return it.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), "") }
        Regex("""https://hentaicop\.com/wp-content/uploads/[^\s"'<>]+\.(?:webp|jpg|jpeg|png)""")
            .findAll(html)
            .map { it.value }
            .firstOrNull { !it.contains("/fav") }
            ?.let { return it.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), "") }
        return null
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            // satu post = satu judul (episodenya sudah digabung di satu halaman).
            // Nama bab TIDAK boleh sama dengan judul seri: ChapterRecognition di
            // aplikasi membuang judul seri dari nama bab, sehingga barisnya jadi
            // kosong di daftar bab.
            val nomor = Regex("""(?i)episode\s*(\d+(?:\.\d+)?)""").find(manga.title)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Episode ${if (nomor % 1f == 0f) nomor.toInt() else nomor}"
                    chapter_number = nomor
                },
            )
        }
        .subscribeOn(Schedulers.io())

    // --- Tautan video -------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val referer = "$PROYEK${chapter.url}"
            val html = teks(referer)
            val iframes = Regex("""<iframe[^>]+src="([^"]+)"""")
                .findAll(html)
                .map { it.groupValues[1] }
                .filter { it.contains("/play/") }
                .map { if (it.startsWith("http")) it else "$PROYEK$it" }
                .distinct()
                .toList()

            val hasil = LinkedHashSet<String>()
            for (f in iframes) {
                // halaman pemutar: butuh Referer = halaman episode + Accept-Language
                val isi = runCatching { teks(f, referer = referer) }.getOrNull() ?: continue
                Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                    .findAll(isi)
                    .map { Parser.unescapeEntities(it.value, false) }
                    .forEach { hasil += it }
            }
            hasil.mapIndexed { i, u -> Page(i, u) }
        }
        .subscribeOn(Schedulers.io())
}