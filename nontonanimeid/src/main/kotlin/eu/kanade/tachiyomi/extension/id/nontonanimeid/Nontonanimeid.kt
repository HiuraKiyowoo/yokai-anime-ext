package eu.kanade.tachiyomi.extension.id.nontonanimeid

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import rx.schedulers.Schedulers

/**
 * NontonAnimeID (s13.nontonanimeid.boats) — anime sub Indo, WordPress (tema kotakanime).
 *
 * Struktur (hasil validasi langsung ke situs, bukan dugaan):
 *  - kartu daftar : `div.sera > a[href]` (gambar + jumlah episode)
 *  - daftar       : /popular-series/ · /ongoing-list/ · beranda "/" · /anime/?page=N
 *  - cari         : /?s=<kata>
 *  - detail seri  : /anime/<slug>/  → `h1 [data-title-default]`, tautan episode
 *  - episode      : /<slug>-episode-N/ → `data-post`, lalu tiap mirror punya
 *                   `data-nume` + `data-type` (Lokal-c, Kotakvideo, Streamku, ...)
 *  - stream       : POST /wp-admin/admin-ajax.php (action=player_ajax, post, nume, type)
 *                   → balas iframe pemutar → halaman embed memuat skrip ter-obfuscate:
 *                        kunci[j] = (angka[j] - PENGALI*(j+3)) & 255
 *                        teks     = base64(blob) XOR kunci (berulang) → UTF-8
 *                   URL ada di dalam teks itu (…/go/dl/?url=k.<hash>) → 302 → berkas mp4.
 *                   Jadi tidak perlu mesin JS: cukup XOR + regex.
 */
class Nontonanimeid : HttpSource() {

    override val name = "NontonAnimeID"
    override val lang = "id"
    override val baseUrl = "https://s13.nontonanimeid.boats"
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

    private fun minta(request: Request, rujuk: String): String {
        val req = request.newBuilder()
            .header("User-Agent", UA)
            .header("Referer", rujuk)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} untuk ${req.url}")
            return r.body?.string() ?: error("Balasan kosong")
        }
    }

    private fun dokumen(path: String, rujuk: String = "$baseUrl/"): Document =
        Jsoup.parse(minta(Request.Builder().url(absolut(path)).build(), rujuk))

    private fun absolut(tautan: String): String = when {
        tautan.startsWith("http") -> tautan
        tautan.startsWith("//") -> "https:$tautan"
        else -> baseUrl + tautan
    }

    private fun keManga(a: Element): SManga? {
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val judul = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("h3, h2, .title")?.text()
            ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
        val gambar = a.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return SManga.create().apply {
            url = href
            title = judul.trim()
            thumbnail_url = gambar?.let { absolut(it) }
        }
    }

    private fun daftar(path: String): MangasPage {
        val doc = dokumen(path)
        var kartu = doc.select("div.sera > a[href], div.sera a[href]")
            .mapNotNull { keManga(it) }
        if (kartu.isEmpty()) {
            // sebagian tema menaruh kartu di .listupd / .bs
            kartu = doc.select("article a[href], .bs a[href], .listupd a[href]")
                .mapNotNull { keManga(it) }
        }
        kartu = kartu.distinctBy { it.url }
        val lanjut = doc.selectFirst("a.next, link[rel=next]") != null ||
            doc.select("a[href*=\"/page/\"]").any { it.text().contains("Next", true) || it.text().contains("Berikut", true) }
        return MangasPage(kartu, kartu.size >= 20 || lanjut)
    }

    // --- Populer / terbaru / cari -------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        daftar("/popular-series/?page=$page")
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
            val judul = doc.selectFirst("h1 [data-title-default]")?.attr("data-title-default")
                ?: doc.selectFirst("h1")?.attr("data-title-default")
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            if (!judul.isNullOrBlank()) title = judul.trim()

            val gambar = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumb img, .entry-content img")?.attr("src")
            if (!gambar.isNullOrBlank()) thumbnail_url = absolut(gambar)

            val genre = doc.select("a[href*=/genres/]").map { it.text() }.filter { it.isNotBlank() }
            val sinopsis = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("[itemprop=description], .entry-content p")?.text()
            description = buildString {
                sinopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
                if (genre.isNotEmpty()) append("Genre: ").append(genre.distinct().joinToString(", ")).append("\n")
                append("Sumber: NontonAnimeID")
            }
            initialized = true
        }
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val doc = dokumen(manga.url)
        doc.select("a[href*=episode-], a[href*=episode/]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nomor = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toFloatOrNull()
                val label = a.text().trim().ifBlank { "Episode ${nomor?.toInt() ?: 0}" }
                SChapter.create().apply {
                    url = href
                    name = label
                    chapter_number = nomor ?: 0f
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.chapter_number }
    }.subscribeOn(Schedulers.io())

    // --- Tautan video --------------------------------------------------------

    /** Satu mirror di halaman episode: pasangan data-nume + data-type. */
    private data class Mirror(val nume: String, val jenis: String)

    private fun mirrors(doc: Document): List<Mirror> =
        doc.select("[data-nume][data-type]").mapNotNull { el ->
            val nume = el.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val jenis = el.attr("data-type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Mirror(nume, jenis)
        }.distinct()

    /** Buka satu mirror → URL berkas video (mp4) dengan label kualitas. */
    private fun bukaMirror(post: String, mirror: Mirror, rujuk: String): List<Pair<String, String>> {
        val body = FormBody.Builder()
            .add("action", "player_ajax")
            .add("post", post)
            .add("nume", mirror.nume)
            .add("type", mirror.jenis)
            .build()
        val ajax = minta(
            Request.Builder().url("$baseUrl/wp-admin/admin-ajax.php").post(body).build(),
            rujuk,
        )
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)""").find(ajax)?.groupValues?.get(1)
            ?: return emptyList()
        val embed = minta(Request.Builder().url(absolut(iframe)).build(), rujuk)
        return dekodeSumber(embed)
    }

    /**
     * Baca skrip ter-obfuscate di halaman embed: cari daftar angka kunci, pengali,
     * lalu base64 yang di-XOR. Hasilnya teks JS; URL diambil dengan regex.
     */
    private fun dekodeSumber(embed: String): List<Pair<String, String>> {
        val blok = Regex("""<script(?![^>]*src)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(embed)
            .map { it.groupValues[1] }
            .firstOrNull { it.length > 2000 && (it.contains("'ob']") || it.contains("atob(")) }
            ?: return emptyList()

        val kepala = blok.take(700)
        val angka = Regex("""s='([0-9,]+)'\.split""").find(kepala)
            ?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?: return emptyList()
        val kali = Regex("""-\s*(\d+)\s*\*\s*\(\s*j\s*\+\s*3\s*\)""").find(kepala)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
        if (angka.isEmpty()) return emptyList()

        val idx = blok.indexOf("'ob']")
        if (idx < 0) return emptyList()
        val polaBuka = Regex("""^'ob'\]\(\s*'""").find(blok.substring(idx)) ?: return emptyList()
        val mulai = idx + polaBuka.value.length
        val akhir = blok.indexOf('\'', mulai)
        if (akhir <= mulai) return emptyList()

        val blob = blok.substring(mulai, akhir).replace(Regex("""\s+"""), "")
        val data = runCatching { Base64.decode(blob, Base64.DEFAULT) }.getOrNull() ?: return emptyList()
        val kunci = IntArray(angka.size) { (angka[it] - kali * (it + 3)) and 0xFF }
        val teksBiner = ByteArray(data.size) { i ->
            (data[i].toInt() xor kunci[i % kunci.size]).toByte()
        }
        val teks = String(teksBiner, Charsets.UTF_8)

        // Pasangan file+label bila ada, supaya kualitas bisa diurutkan.
        val pasangan = Regex(
            """["']file["']\s*:\s*["']([^"']+)["'][^}]*?["']label["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(teks).map { it.groupValues[1] to it.groupValues[2] }.toList()

        if (pasangan.isNotEmpty()) return pasangan

        // Kalau label tidak ada, ambil semua URL yang memang berkas video.
        return Regex("""https?://[^"'\\\s,)\]}]{10,240}""")
            .findAll(teks)
            .map { it.value }
            .filter { it.contains("/go/dl/") || it.endsWith(".mp4") || it.contains(".m3u8") }
            .map { it to "" }
            .toList()
    }

    /** 720p > 480p > 360p, supaya pilihan pertama yang diputar kualitas terbaik. */
    private fun tingkat(label: String): Int {
        val n = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return n
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val jalurEpisode = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val doc = dokumen(jalurEpisode)

        val post = doc.selectFirst("[data-post]")?.attr("data-post")?.takeIf { it.isNotBlank() }
            ?: Regex(""""post_id"\s*:\s*"?(\d+)""").find(doc.html())?.groupValues?.get(1)
            ?: error("ID postingan tidak ditemukan di halaman episode ini.")

        val daftarMirror = mirrors(doc)
        if (daftarMirror.isEmpty()) error("Tidak ada server yang terdaftar di episode ini.")

        // Coba mirror satu per satu; yang pertama menghasilkan tautan dipakai.
        var hasil: List<Pair<String, String>> = emptyList()
        var pesanTerakhir = ""
        for (mirror in daftarMirror) {
            try {
                val kandidat = bukaMirror(post, mirror, jalurEpisode)
                if (kandidat.isNotEmpty()) {
                    hasil = kandidat
                    break
                }
                pesanTerakhir = "server ${mirror.jenis} tidak memuat tautan"
            } catch (e: Exception) {
                pesanTerakhir = "server ${mirror.jenis}: ${e.message}"
            }
        }
        if (hasil.isEmpty()) error("Tidak ada tautan video yang bisa dibaca ($pesanTerakhir).")

        hasil
            .distinctBy { it.first }
            .sortedByDescending { tingkat(it.second) }
            .mapIndexed { i, (tautan, _) -> Page(i, tautan) }
    }.subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(
        eu.kanade.tachiyomi.source.model.Filter.Header(
            "Pakai kotak pencarian untuk cari judul. \"Populer\" dari /popular-series/, \"Terbaru\" dari beranda.",
        ),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
