package eu.kanade.tachiyomi.extension.id.layaranime

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import rx.schedulers.Schedulers

/**
 * LayarAnime — streaming anime sub Indo.
 *
 * Struktur situs (hasil validasi langsung, bukan dugaan):
 *  - kartu seri  : `<article>` dengan `a[itemprop=url]`, `meta[itemprop=image]`,
 *                  `h2[itemprop=name]`, beberapa `meta[itemprop=genre]`
 *  - daftar      : /ongoing (ongoing) dan /release (rilis terbaru), 24 kartu per halaman
 *  - cari        : /search?q=<kata>
 *  - detail      : /<slug> (slug bisa memuat karakter ter-encode, mis. /keroro-gunsou%e2%98%86)
 *  - episode     : /<slug>/episode-<n>
 *  - pemutar     : halaman episode memuat iframe -> player.ablink.sbs/iframe?url=<X>
 *                  pembungkus itu memuat `file = "https://player.ablink.sbs/<kode>"`
 *                  dan halaman <kode> memuat URL .m3u8 langsung
 *
 * Catatan penting: segmen HLS-nya berekstensi menyamar (`.pict`) dan bertipe MPEG-TS.
 * Pemutar harus diberi URL m3u8 apa adanya; jangan menebak ekstensi segmen.
 */
class Layaranime : HttpSource() {

    override val name = "LayarAnime"
    override val lang = "id"
    override val baseUrl = "https://layaranime.net"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                .build()
            chain.proceed(req)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // --- Pembantu ------------------------------------------------------------

    /** Ambil dokumen HTML lengkap (Jsoup). Dua permintaan tidak boleh berbagi Response. */
    private fun dokumen(path: String, rujuk: String = "$baseUrl/"): Document {
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else baseUrl + path)
            .header("User-Agent", UA)
            .header("Referer", rujuk)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { r: Response ->
            val isi = r.body?.string() ?: error("Balasan kosong dari ${r.request.url}")
            return Jsoup.parse(isi, r.request.url.toString())
        }
    }

    private fun teks(path: String, rujuk: String): String {
        val req = Request.Builder()
            .url(path)
            .header("User-Agent", UA)
            .header("Referer", rujuk)
            .build()
        client.newCall(req).execute().use { r: Response ->
            return r.body?.string() ?: error("Balasan kosong dari $path")
        }
    }

    private fun keManga(article: Element): SManga? {
        val a = article.selectFirst("a[itemprop=url]") ?: article.selectFirst("a[href]") ?: return null
        val url = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val nama = article.selectFirst("h2[itemprop=name]")?.text()
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val gambar = article.selectFirst("meta[itemprop=image]")?.attr("content")
        val genre = article.select("meta[itemprop=genre]").map { it.attr("content") }
            .filter { it.isNotBlank() }
        return SManga.create().apply {
            this.url = url
            title = nama
            thumbnail_url = gambar
            this.genre = genre.joinToString(", ").takeIf { it.isNotBlank() }
        }
    }

    private fun daftar(path: String): MangasPage {
        val doc = dokumen(path)
        val kartu = doc.select("article").mapNotNull { keManga(it) }
        // Situs memuat 24 kartu per halaman; halaman berikutnya ada bila jumlahnya penuh.
        return MangasPage(kartu, kartu.size >= 24)
    }

    // --- Populer / terbaru / cari -------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        daftar("/ongoing?page=$page")
    }.subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        daftar("/release?page=$page")
    }.subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            daftar("/search?q=$q&page=$page")
        }.subscribeOn(Schedulers.io())

    // --- Detail --------------------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val doc = dokumen(manga.url, rujuk = baseUrl + "/")
        manga.apply {
            val judul = doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("h2[itemprop=name]")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            if (!judul.isNullOrBlank()) title = judul.removeSuffix(" Sub Indo").trim()

            doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { thumbnail_url = it }
                ?: doc.selectFirst("meta[itemprop=image]")?.attr("content")?.let { thumbnail_url = it }

            val sinopsis = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.select("p").firstOrNull { it.text().length > 80 }?.text()

            val genre = doc.select("meta[itemprop=genre]").map { it.attr("content") }
                .filter { it.isNotBlank() }
                .ifEmpty {
                    doc.select("a[href^=/genre/]").map { it.text() }.filter { it.isNotBlank() }
                }

            description = buildString {
                sinopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
                if (genre.isNotEmpty()) append("Genre: ").append(genre.distinct().joinToString(", ")).append("\n")
                append("Sumber: LayarAnime")
            }
            initialized = true
        }
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val doc = dokumen(manga.url, rujuk = baseUrl + "/")
        doc.select("a[href*=episode-]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!href.contains("episode-")) return@mapNotNull null
                val nomor = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val label = a.text().trim().ifBlank { "Episode ${nomor.toInt()}" }
                SChapter.create().apply {
                    url = href
                    name = label
                    chapter_number = nomor
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.chapter_number }
    }.subscribeOn(Schedulers.io())

    // --- Tautan video --------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val jalurEpisode = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val halamanEpisode = dokumen(jalurEpisode, rujuk = baseUrl + "/")

        // 1) iframe pembungkus pemutar
        val iframe = halamanEpisode.selectFirst("iframe[src]")?.attr("src")
            ?: error("Pemutar tidak ditemukan di halaman episode ini.")

        // 2) halaman pembungkus: memuat URL pemutar sebenarnya
        val pembungkus = teks(iframe, rujuk = jalurEpisode)
        val kandidat = Regex("""(?:var\s+)?file\s*[:=]\s*["'](https?://[^"']+)["']""")
            .findAll(pembungkus)
            .map { it.groupValues[1] }
            .filter { it.contains("ablink") || it.contains("player") }
            .toList()

        // fallback: ambil URL player apa pun di halaman pembungkus
        val pemutar = kandidat.firstOrNull()
            ?: Regex("""https://player\.[a-z0-9.-]+/[A-Za-z0-9_-]{6,}""").find(pembungkus)?.value
            ?: error("URL pemutar tidak ditemukan di pembungkus iframe.")

        // 3) halaman pemutar: di sini URL m3u8 berada
        val halamanPemutar = teks(pemutar, rujuk = iframe)
        val m3u8 = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
            .findAll(halamanPemutar)
            .map { it.value }
            .distinct()
            .toList()

        if (m3u8.isEmpty()) {
            error("Tautan video (m3u8) tidak ditemukan untuk episode ini.")
        }
        // Kualitas tertinggi ditaruh lebih dulu: master.m3u8 biasanya yang paling lengkap.
        val urut = m3u8.sortedByDescending { if (it.contains("master")) 1 else 0 }
        urut.mapIndexed { i, tautan -> Page(i, tautan) }
    }.subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(
        eu.kanade.tachiyomi.source.model.Filter.Header(
            "Cari judul lewat kotak pencarian. Daftar \"Populer\" memakai halaman ongoing, \"Terbaru\" memakai halaman release.",
        ),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-A125F) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
