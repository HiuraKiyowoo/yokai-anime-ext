package eu.kanade.tachiyomi.extension.id.hentaicop

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import rx.Observable
import rx.schedulers.Schedulers
import java.net.URLEncoder

/**
 * HentaiCop (hentaicop.com) — NSFW.
 *
 * WordPress. REST API terbuka: /wp-json/wp/v2/posts (orderby=date|modified|title
 * saja — comment_count & rand ditolak 400) dan API milik situs /wp-json/hc/v1/.
 *
 * Stream: halaman post -> <iframe src="/play/v/<id>/?q=720">
 *         -> halaman pemutar memuat URL HLS di CDN <host>.my.id / jav.<hash>.site:
 *            /hls/.../<judul>_720p.mp4/index-v1-a1.m3u8?md5=..&expires=..
 *
 * DUA GERBANG WAJIB di halaman pemutar, kalau tidak: 403 "Fake Referer terdeteksi."
 *   1. Referer = URL halaman episode itu sendiri (bukan beranda)
 *   2. Accept-Language harus terisi (mis. "id-ID,id;q=0.9")
 *
 * .m3u8-nya sendiri punya gerbang berbeda dan **per host**: sebagian host membalas
 * 200 tanpa Referer, sebagian WAJIB ber-Referer (terukur: host 9385b44e..site →
 * 403 tanpa Referer, 200 dengan "Referer: https://hentaicop.com/"). Karena pemutar
 * memakai header milik ekstensi ini (Referer beranda), keduanya aman. Tanpa
 * Referer sama sekali pemutar menampilkan durasi 00:00 dan tidak pernah mulai.
 *
 * Jebakan lain: "&" pada URL .m3u8 di halaman ter-escape jadi "&#038;" — wajib
 * di-unescape. Poster tidak ada di REST (featured_media 0, og:image = fav.webp),
 * jadi diambil dari gambar /wp-content/uploads/ pertama di halaman episode.
 */
private const val PROYEK = "https://hentaicop.com"
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val AL = "id-ID,id;q=0.9,en;q=0.8"

/** Satu dropdown = satu parameter GET di /series/ (mis. genre, studio, tahun). */
private class Pilih(
    nama: String,
    val param: String,
    val pasangan: List<Pair<String, String>>,
    bawaan: Int = 0,
) : Filter.Select<String>(nama, pasangan.map { it.second }.toTypedArray(), bawaan) {
    val nilai: String get() = pasangan.getOrNull(state)?.first.orEmpty()
}

class HentaiCop : HttpSource() {

    override val name = "HentaiCop"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    // Referer & Accept-Language ikut dikirim ke pemutar video oleh aplikasi,
    // jadi CDN HentaiCop tidak lagi membalas 403 saat memutar .m3u8.
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Accept-Language", AL)
        .add("Referer", "$PROYEK/")

    // --- HTTP ---------------------------------------------------------------

    private fun teks(url: String, referer: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", AL)
        referer?.let { b.header("Referer", it) }
        return client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HentaiCop: HTTP ${r.code} untuk $url")
            r.body.string()
        }
    }

    /** Probe ringan: 200/206/416 = berkas ada (416 = rentang di luar, artinya hidup). */
    private fun hidup(url: String): Boolean = runCatching {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", AL)
            .header("Referer", "$PROYEK/")
            .header("Range", "bytes=0-1023")
        client.newCall(b.build()).execute().use { it.isSuccessful || it.code == 416 }
    }.getOrDefault(false)

    // --- Filter -------------------------------------------------------------

    private var kueFilter: JSONObject? = null
    private var waktuKue = 0L

    private fun filterJson(): JSONObject {
        val sekarang = System.currentTimeMillis()
        val tersimpan = kueFilter
        if (tersimpan != null && sekarang - waktuKue < 6 * 3600_000L) return tersimpan
        val baru = runCatching { JSONObject(teks("$PROYEK/wp-json/hc/v1/filters")) }.getOrNull()
        if (baru != null) {
            kueFilter = baru
            waktuKue = sekarang
            return baru
        }
        return tersimpan ?: JSONObject()
    }

    private fun opsi(json: JSONObject, kunci: String, semua: Boolean = true): List<Pair<String, String>> {
        val arr = json.optJSONArray(kunci) ?: JSONArray()
        val hasil = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val v = o.optString("v")
            if (v.isBlank()) null else v to o.optString("l").ifBlank { v }
        }
        return if (semua) listOf("" to "Semua") + hasil else hasil
    }

    override fun getFilterList(): FilterList {
        val o = filterJson().optJSONObject("options") ?: JSONObject()
        val urut = opsi(o, "order", semua = false).ifEmpty {
            listOf(
                "latest" to "Terbaru",
                "update" to "Update terbaru",
                "popular" to "Terpopuler",
                "rating" to "Rating",
                "title" to "Judul A–Z",
            )
        }
        return FilterList(
            listOf<Filter<*>>(
                Pilih("Genre", "genre", opsi(o, "genre")),
                Pilih("Studio", "studio", opsi(o, "studio")),
                Pilih("Tipe", "type", opsi(o, "type")),
                Pilih("Sensor", "sensor", opsi(o, "sensor")),
                Pilih("Status", "status", opsi(o, "status")),
                Pilih("Tahun", "tahun", opsi(o, "tahun")),
                Pilih("Urutkan", "order", urut),
            ),
        )
    }

    private fun params(filters: FilterList): List<String> = filters
        .filterIsInstance<Pilih>()
        .mapNotNull { f ->
            val v = f.nilai
            if (v.isBlank()) null else "${f.param}=${URLEncoder.encode(v, "UTF-8")}"
        }

    /** Arsip HTML: /series/?<params> · halaman berikutnya /series/page/N/?<params>. */
    private fun dariArsip(page: Int, param: List<String>): MangasPage {
        val q = if (param.isEmpty()) "" else "?" + param.joinToString("&")
        val alamat = if (page <= 1) "$PROYEK/series/$q" else "$PROYEK/series/page/$page/$q"
        val doc = Jsoup.parse(teks(alamat), alamat)
        val isi = doc.select("article.ht-card").mapNotNull { kartu ->
            val a = kartu.selectFirst("h2.ht-card__title a[href]") ?: kartu.selectFirst("a[href]")
            ?: return@mapNotNull null
            val url = a.absUrl("href").ifBlank { a.attr("href") }
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url.removePrefix(PROYEK)
                title = bersih(kartu.selectFirst("h2")?.text().orEmpty()).ifBlank { a.text().trim() }
                thumbnail_url = kartu.selectFirst("img[src]")?.absUrl("src")?.ifBlank { null }
                initialized = false
            }
        }.distinctBy { it.url }
        return MangasPage(isi, isi.isNotEmpty())
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

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = query.trim()
            val param = params(filters).toMutableList()
            // Pencarian judul situs (param "s") hanya jalan mulai 3 huruf.
            if (kunci.length >= 3) param += "s=${URLEncoder.encode(kunci, "UTF-8")}"

            if (param.isNotEmpty()) return@fromCallable dariArsip(page, param)
            if (kunci.isBlank() || page > 1) return@fromCallable halaman("date", page)

            // Tanpa filter: pakai saran judul dari situs (lebih akurat daripada WP search).
            val o = JSONObject(teks("$PROYEK/wp-json/hc/v1/suggest?q=${URLEncoder.encode(kunci, "UTF-8")}"))
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

            // Beberapa host menolak tanpa Referer. Tautan yang hidup diletakkan lebih
            // dulu supaya pemutar tidak membuka kualitas yang mati (gejala: 00:00).
            // Kalau tidak ada yang hidup, semuanya tetap ditampilkan.
            val daftar = hasil.toList()
            if (daftar.size <= 1) return@fromCallable daftar.mapIndexed { i, u -> Page(i, u) }
            val (nyala, mati) = daftar.partition { hidup(it) }
            (nyala + mati).mapIndexed { i, u -> Page(i, u) }
        }
        .subscribeOn(Schedulers.io())
}
