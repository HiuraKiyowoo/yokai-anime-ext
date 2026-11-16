package eu.kanade.tachiyomi.extension.id.anoboy

import eu.kanade.tachiyomi.source.model.Filter
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
 * Anoboy (anoboy.li) — anime sub Indo, WordPress.
 *
 * Struktur (semua diukur langsung ke situs, bukan dugaan):
 *  - katalog   : /anime/?order=update|popular&page=N  → kartu `article.bs`
 *                (`div.bsx > a[href][itemprop=url]`, poster `img[data-src]`, badge `div.limit`)
 *  - cari      : /anime/?s=<kata>&page=N
 *  - genre     : /genres/<slug>/ (+ /page/N/) — parameter `?genre=` di /anime/ BALAS 500,
 *                jadi genre harus lewat jalur arsip ini
 *  - detail    : /anime/<slug>/ → h1, og:image, genre lewat a[href*=/genres/]
 *  - episode   : `.eplister ul li a[href]` (`.epl-num`, `.epl-title`, `.epl-date`)
 *  - video     : halaman episode memuat satu <iframe> ke www.blogger.com/video.g?token=…
 *                Halaman iframe (27 KB) menyimpan URL `googlevideo.com/videoplayback`
 *                dalam bentuk ter-escape (`\u003d`, `\u0026`, `\/`), terbanyak 3 berkas:
 *                itag 22 (720p), 18 (360p), dan satu tanpa itag. URL itu terikat
 *                `ip=<IP pengguna>` + `expire=<unix>`, jadi harus diambil saat diputar
 *                (modul ini melakukannya di fetchPageList, bukan disimpan).
 */
class Anoboy : HttpSource() {

    override val name = "Anoboy"
    override val lang = "id"
    override val baseUrl = "https://anoboy.be"
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

    /**
     * PENTING: JANGAN menaruh `Referer` di sini.
     *
     * `headersBuilder()` ikut diteruskan aplikasi ke PEMUTAR VIDEO. CDN pemutar di situs ini
     * (`vkcdn5.com`) membalas **403** kalau Referer-nya halaman situs
     * (terukur: Referer `https://anoboy.be/` → 403, tanpa Referer → 206 video/mp4),
     * jadi satu baris Referer di sini membuat semua episode gagal diputar.
     * Referer untuk kebutuhan internal modul dipasang per-permintaan di [minta].
     */
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)

    // --- Pembantu ------------------------------------------------------------

    private fun minta(alamat: String, rujuk: String = "$baseUrl/"): String {
        val req = Request.Builder()
            .url(if (alamat.startsWith("http")) alamat else baseUrl + alamat)
            .header("User-Agent", UA)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .header("Referer", rujuk)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} untuk ${req.url}")
            return r.body?.string() ?: error("Balasan kosong dari ${req.url}")
        }
    }

    private fun dokumen(alamat: String, rujuk: String = "$baseUrl/"): Document {
        val url = if (alamat.startsWith("http")) alamat else baseUrl + alamat
        return Jsoup.parse(minta(url, rujuk), url)
    }

    private fun absolut(tautan: String): String = when {
        tautan.startsWith("http") -> tautan
        tautan.startsWith("//") -> "https:$tautan"
        else -> baseUrl + tautan
    }

    private fun bersih(s: String): String =
        org.jsoup.parser.Parser.unescapeEntities(s.replace(Regex("<[^>]+>"), ""), false).trim()

    /** Satu kartu katalog. `list-mode` adalah tautan tampilan, bukan judul. */
    private fun keManga(kartu: Element): SManga? {
        val a = kartu.selectFirst("a[href*=/anime/]") ?: kartu.selectFirst("a[href]") ?: return null
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.isBlank() || href.contains("/list-mode")) return null

        val img = kartu.selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val judul = a.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: kartu.selectFirst("h2, .title")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        return SManga.create().apply {
            url = href.removePrefix(baseUrl)
            title = bersih(judul)
            if (poster != null) thumbnail_url = absolut(poster)
            initialized = false
        }
    }

    private fun dariArsip(alamat: String): MangasPage {
        val doc = dokumen(alamat)
        val isi = doc.select("article.bs").mapNotNull { keManga(it) }.distinctBy { it.url }
        // Tanda halaman berikut: rel=next atau blok paginasi yang memang punya tautan.
        val lanjut = doc.selectFirst("link[rel=next], a.next, .pagination a[href], .hpage a[href]") != null
        return MangasPage(isi, lanjut)
    }

    // --- Katalog -------------------------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        dariArsip("/anime/?order=popular&page=$page")
    }.subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        dariArsip("/anime/?order=update&page=$page")
    }.subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val genre = filters.filterIsInstance<Pilih>().firstOrNull()?.nilai.orEmpty()
            when {
                q.isNotBlank() -> dariArsip("/anime/?s=$q&page=$page")
                genre.isNotBlank() && page > 1 -> dariArsip("/genres/$genre/page/$page/")
                genre.isNotBlank() -> dariArsip("/genres/$genre/")
                else -> dariArsip("/anime/?order=update&page=$page")
            }
        }.subscribeOn(Schedulers.io())

    // --- Detail --------------------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val doc = dokumen(manga.url)
        manga.apply {
            val judul = doc.selectFirst("h1.entry-title, h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            if (judul != null) title = bersih(judul)

            val gambar = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumb img, .entry-content img")?.attr("src")
            if (!gambar.isNullOrBlank()) thumbnail_url = absolut(gambar)

            val genre = doc.select("a[href*=/genres/]").map { it.text() }.filter { it.isNotBlank() }
                .map { it.trim() }.distinct()
            val status = Regex("""(?i)Status\s*:?\s*</?[^>]*>?\s*([A-Za-z ]{3,20})""")
                .find(doc.html())?.groupValues?.get(1)?.trim()
            val sinopsis = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("[itemprop=description] p, .entry-content p")?.text()

            description = buildString {
                sinopsis?.takeIf { it.isNotBlank() }?.let { append(bersih(it)).append("\n\n") }
                if (genre.isNotEmpty()) append("Genre: ").append(genre.joinToString(", ")).append("\n")
                if (!status.isNullOrBlank()) append("Status: ").append(status).append("\n")
                append("Sumber: Anoboy")
            }
            initialized = true
        }
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val doc = dokumen(manga.url)
        doc.select(".eplister ul li a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val nomor = a.selectFirst(".epl-num")?.text()?.trim()?.toFloatOrNull()
                ?: Regex("""episode[-\s]*(\d+)""", RegexOption.IGNORE_CASE).find(href)
                    ?.groupValues?.get(1)?.toFloatOrNull()
                ?: 0f
            SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                // Nama bab tidak boleh sama dengan judul seri (ChapterRecognition membuangnya).
                name = if (nomor > 0f) "Episode ${nomor.toInt()}" else bersih(a.text()).ifBlank { "Episode" }
                chapter_number = nomor
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }.subscribeOn(Schedulers.io())

    // --- Tautan video --------------------------------------------------------

    /** Lepaskan escape JSON/HTML yang dipakai halaman embed. */
    private fun lepasEscape(s: String): String = s
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    /** itag muxed: 22 = 720p, 18 = 360p. Yang lain dipakai sebagai cadangan. */
    private fun bobotItag(u: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return when (itag) {
            22 -> 3
            18 -> 2
            59, 78, 43, 44 -> 1
            else -> 0
        }
    }

    // --- Bongkar packer JW Player (vkspeed) ---------------------------------

    /** Ubah angka jadi "huruf" basis-a seperti packer Dean Edwards. */
    private fun kataPacker(n: Int, basis: Int): String {
        val digit = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val d = if (n < digit.length) digit[n].toString() else ""
        return if (n < basis) d else kataPacker(n / basis, basis) + d
    }

    /**
     * Halaman embed vkspeed menaruh konfigurasi JW Player di dalam
     * `eval(function(p,a,c,k,e,d){…}('…',a,c,'k1|k2|…'.split('|'),0,{}))`.
     * Isinya `sources:[{file:"https://…vkcdn5.com/…/v.mp4",label:"360p"}]`.
     * Bongkar di sini — jangan bawa mesin JS ke ekstensi.
     */
    private fun bongkarPacker(html: String): String {
        val m = Regex(
            """\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)[\s\S]{0,400}?\}\s*\(\s*'([\s\S]*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]*?)'\s*\.split\(\s*'\|'\s*\)""",
        ).find(html) ?: return ""

        var p = m.groupValues[1]
        val basis = m.groupValues[2].toIntOrNull() ?: return ""
        val jumlah = m.groupValues[3].toIntOrNull() ?: return ""
        val kamus = m.groupValues[4].split("|")

        for (i in 0 until jumlah) {
            val kata = kamus.getOrNull(i) ?: break
            if (kata.isEmpty()) continue
            p = p.replace(Regex("\\b" + Regex.escape(kataPacker(i, basis)) + "\\b"), Regex.escapeReplacement(kata))
        }
        return p
    }

    /** label kualitas ("360p", "720p") → angka, untuk mengurutkan yang terbaik dulu. */
    private fun bobotLabel(label: String): Int =
        Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val halamanEpisode = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val html = minta(halamanEpisode)

        val iframes = Regex("""<iframe[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("blogger.com") || it.contains("/video") || it.contains("embed") || it.contains("vkspeed") }
            .map { absolut(it) }
            .distinct()
            .toList()

        // pasangan url → kualitas
        val hasil = LinkedHashMap<String, String>()
        for (frame in iframes) {
            val isi = runCatching { minta(frame, rujuk = halamanEpisode) }.getOrNull() ?: continue

            // (a) vkspeed: konfigurasi JW Player ter-packer → sources[{file,label}]
            val konfig = bongkarPacker(isi)
            if (konfig.isNotEmpty()) {
                Regex("""file\s*:\s*["']([^"']+)["']""").findAll(konfig).forEach { fm ->
                    val url = fm.groupValues[1]
                    if (!url.startsWith("http")) return@forEach
                    // label terdekat setelah file
                    val ekor = konfig.substring(fm.range.last + 1).take(120)
                    val label = Regex("""label\s*:\s*["']([^"']+)["']""").find(ekor)?.groupValues?.get(1).orEmpty()
                    hasil[url] = label
                }
            }

            // (b) blogger: URL googlevideo tersembunyi di data ter-escape
            val teks = lepasEscape(isi)
            Regex("""https://[^\s"'\\<>]+googlevideo\.com/videoplayback[^\s"'\\<>]*""")
                .findAll(teks)
                .forEach { hasil[it.value] = "itag:" + bobotItag(it.value) }
        }

        if (hasil.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""")
                .findAll(html)
                .map { org.jsoup.parser.Parser.unescapeEntities(it.value, false) }
                .forEach { hasil[it] = "" }
        }

        if (hasil.isEmpty()) {
            throw Exception(
                if (iframes.isEmpty()) {
                    "Halaman episode ini tidak memuat pemutar. Coba episode lain."
                } else {
                    "Pemutar tidak mengembalikan berkas video. Coba lagi beberapa saat."
                },
            )
        }

        hasil.entries
            .sortedByDescending { e ->
                if (e.value.startsWith("itag:")) bobotItag(e.key) else bobotLabel(e.value)
            }
            .mapIndexed { i, e -> Page(i, e.key) }
    }.subscribeOn(Schedulers.io())

    // --- Filter --------------------------------------------------------------

    /**
     * Daftar genre diambil dari arsip `/anime/` (tautan `/genres/<slug>/`), di-cache 6 jam.
     * Kalau gagal, dropdown tetap berisi "Semua" saja — jangan pernah mengosongkan panel.
     */
    private fun opsiGenre(): List<Pair<String, String>> {
        val sekarang = System.currentTimeMillis()
        kueGenre?.let { if (sekarang - waktuGenre < 6 * 3600_000L) return it }
        val hasil = runCatching {
            val doc = dokumen("/anime/")
            doc.select("a[href*=/genres/]").mapNotNull { a ->
                val slug = a.absUrl("href").ifBlank { a.attr("href") }
                    .trimEnd('/').substringAfterLast('/')
                val label = a.text().trim()
                if (slug.isBlank() || label.isBlank()) null else slug to label
            }.distinctBy { it.first }
        }.getOrDefault(emptyList())
        if (hasil.isNotEmpty()) {
            kueGenre = hasil
            waktuGenre = sekarang
        }
        return hasil
    }

    override fun getFilterList(): FilterList {
        val genre = opsiGenre()
        val daftar = mutableListOf<Filter<*>>()
        if (genre.isNotEmpty()) {
            daftar += Pilih("Genre", listOf("" to "Semua") + genre)
        } else {
            daftar += Filter.Header("Cari lewat kotak pencarian. Daftar genre sedang tidak terbaca.")
        }
        return FilterList(daftar)
    }

    private class Pilih(nama: String, private val pasangan: List<Pair<String, String>>) :
        Filter.Select<String>(nama, pasangan.map { it.second }.toTypedArray(), 0) {
        val nilai: String get() = pasangan.getOrNull(state)?.first.orEmpty()
    }

    private var kueGenre: List<Pair<String, String>>? = null
    private var waktuGenre = 0L

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}