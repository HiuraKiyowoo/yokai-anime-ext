package eu.kanade.tachiyomi.extension.id.kaitoani

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import rx.schedulers.Schedulers

/**
 * KaitoAni — provider dewasa (NSFW), WordPress.
 *
 * Struktur (hasil validasi langsung, bukan dugaan):
 *  - kartu daftar : `article.bs` → `a[href]` + `img.ts-post-image` (poster)
 *  - daftar       : /hentai-list/ (+ /page/N/), beranda "/"
 *  - cari         : /?s=<kata>
 *  - detail seri  : /hentai/<slug>/ → `h1`, `og:image`, tautan episode
 *  - episode      : /<kode>-episode-N-sub-indo/ — di halaman ini ada 3 tautan
 *                   MediaFire (1080p/720p/480p). Berkasnya nyata (uji Range → 206,
 *                   video/mp4), tanpa enkripsi dan tanpa header khusus.
 *
 * Catatan: pemutar utama situs memakai host pihak ketiga yang memakai packer
 * kaomoji (kunci X-Ts/X-Nc/X-Sg lahir dari packer itu), jadi modul ini memakai
 * jalur unduhan yang sudah server-render di halaman episode. Kalau kelak packer
 * diporting ke Kotlin, cukup ganti isi [fetchPageList].
 */
class Kaitoani : HttpSource() {

    override val name = "KaitoAni"
    override val lang = "id"
    override val baseUrl = "https://kaitoani.org"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                    .build(),
            )
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // --- Pembantu ------------------------------------------------------------

    private fun minta(url: String, rujuk: String = "$baseUrl/"): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", rujuk)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} untuk $url")
            return r.body?.string() ?: error("Balasan kosong dari $url")
        }
    }

    private fun dokumen(url: String, rujuk: String = "$baseUrl/"): Document =
        Jsoup.parse(minta(absolut(url), rujuk), absolut(url))

    private fun absolut(tautan: String): String = when {
        tautan.startsWith("http") -> tautan
        tautan.startsWith("//") -> "https:$tautan"
        else -> baseUrl + tautan
    }

    /** Poster sering memakai host gambar WordPress (i0/i3.wp.com); dipakai apa adanya. */
    private fun keManga(article: Element): SManga? {
        val a = article.selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val img = article.selectFirst("img")

        val judul = a.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: article.selectFirst("h2, .tt, .title")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val src = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return SManga.create().apply {
            url = href
            title = judul.trim()
            thumbnail_url = if (src != null) absolut(src) else null
        }
    }

    private fun daftar(path: String, rujuk: String = "$baseUrl/"): MangasPage {
        val doc = dokumen(path, rujuk)
        var kartu = doc.select("article.bs").mapNotNull { keManga(it) }
        if (kartu.isEmpty()) {
            kartu = doc.select("article, .bsx, .listupd .bs").mapNotNull { keManga(it) }
        }
        kartu = kartu.distinctBy { it.url }
        val adaHalamanBerikut = doc.selectFirst("a.next, link[rel=next], .pagination a[href*=page]") != null
        return MangasPage(kartu, kartu.size >= 20 || adaHalamanBerikut)
    }

    // --- Populer / terbaru / cari -------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        if (page <= 1) daftar("/hentai-list/") else daftar("/hentai-list/page/$page/")
    }.subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        if (page <= 1) daftar("/") else daftar("/page/$page/")
    }.subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            if (page <= 1) daftar("/?s=$q") else daftar("/page/$page/?s=$q")
        }.subscribeOn(Schedulers.io())

    // --- Detail --------------------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val doc = dokumen(manga.url)
        manga.apply {
            doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }?.let { title = it.trim() }

            val gambar = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.ts-post-image, .thumb img")?.attr("src")
            if (!gambar.isNullOrBlank()) thumbnail_url = absolut(gambar)

            val genre = doc.select("a[href*=/genres/]").map { it.text() }.filter { it.isNotBlank() }
            val sinopsis = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("[itemprop=description]")?.text()

            description = buildString {
                sinopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
                if (genre.isNotEmpty()) append("Genre: ").append(genre.distinct().joinToString(", ")).append("\n")
                append("Sumber: KaitoAni")
            }
            initialized = true
        }
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val doc = dokumen(manga.url)
        doc.select("a[href*=episode], a[href*=Episode]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!href.contains("episode", true)) return@mapNotNull null
                val nomor = Regex("""episode[-_]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                // Nama bab TIDAK boleh sama dengan judul seri (aplikasi membuangnya).
                SChapter.create().apply {
                    url = href
                    name = "Episode ${nomor.toInt()}"
                    chapter_number = nomor
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.chapter_number }
    }.subscribeOn(Schedulers.io())

    // --- Tautan video --------------------------------------------------------

    /**
     * Ambil satu tautan unduh langsung MediaFire dari halaman berkasnya.
     * MediaFire menaruh URL akhir di `href` dengan host `download<angka>.mediafire.com`.
     */
    private fun tautanMediafire(halaman: String): String? {
        val doc = Jsoup.parse(halaman)
        val langsung = doc.select("a[href^=https://download], a#downloadButton[href]")
            .map { it.attr("href") }
            .firstOrNull { it.contains("mediafire.com") }
        if (langsung != null) return langsung
        return Regex("""https://download\d*\.mediafire\.com/[^"'\s<]+""")
            .find(halaman)?.value
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val jalur = absolut(chapter.url)
        val doc = dokumen(jalur)

        val tautan = doc.select("a[href*=mediafire.com]")
            .map { it.attr("href") }
            .filter { it.contains("/file/") }
            .distinct()

        if (tautan.isEmpty()) {
            error("Tidak ada tautan berkas di episode ini (server memuatnya lewat skrip).")
        }

        val hasil = ArrayList<Pair<Int, String>>()
        for (t in tautan) {
            val kualitas = Regex("""(\d{3,4})p""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val halaman = minta(t, jalur)
            val berkas = tautanMediafire(halaman) ?: continue
            hasil += kualitas to berkas
        }

        if (hasil.isEmpty()) error("Tautan berkas tidak bisa dibaca dari MediaFire.")
        hasil.distinctBy { it.second }
            .sortedByDescending { it.first }
            .mapIndexed { i, (_, berkas) -> Page(i, berkas) }
    }.subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(
        eu.kanade.tachiyomi.source.model.Filter.Header(
            "Cari lewat kotak pencarian. \"Populer\" memakai halaman daftar, \"Terbaru\" memakai beranda.",
        ),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
