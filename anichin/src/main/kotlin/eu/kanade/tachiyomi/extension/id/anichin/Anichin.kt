package eu.kanade.tachiyomi.extension.id.anichin

import android.util.Base64
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
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import rx.Observable
import rx.schedulers.Schedulers
import java.net.URLEncoder

/**
 * Anichin (anichin.moe) — donghua sub Indo.
 *
 * WordPress, tapi /wp-json ditutup WAF (403) sehingga katalog dibaca dari HTML.
 * Kartu daftar: `article.bs a[href]`. Daftar episode satu seri ada di
 * `div.episodelist li a` pada halaman episode mana pun.
 *
 * STREAM — halaman episode memuat <select class="mirror"> berisi 5–11 pilihan
 * server dalam bentuk base64 (sebagian isinya `<IFRAME SRC=...>` huruf besar).
 * Jalur yang terbukti diputar:
 *
 *  1. ok.ru (ada di ±97% episode) -> halaman embed memuat
 *     `data-module="OKVideo"` + `data-options` (JSON). Di dalamnya:
 *     flashvars.metadata.hlsManifestUrl (HLS) ATAU
 *     flashvars.metadata.videos[].url (MP4 langsung). Ambil HLS dulu.
 *  2. Streamruby (`nuvorel.top` / `rubyvidhub.com`) -> master.m3u8 langsung
 *     di halaman embed-nya. Cadangan, ada di ±87% episode.
 *
 * JEBAKAN (semuanya sudah memakan waktu saat menelusuri):
 *  - **Host CDN berbeda tiap episode** (`ok6-1`, `ok6-7`, `ok6-10`, `vd576`,
 *    `ok6-8`, ...). Jangan sekali-kali di-hardcode; ambil dari URL manifestnya,
 *    kalau tidak varian playlist balas 400.
 *  - Varian di master playlist dan nama segmen di playlist varian ditulis
 *    sebagai **path relatif** -> wajib digabung ke path playlist-nya.
 *  - URL media bertanda `expires=` + `srcIp=` (terikat IP + kedaluwarsa), jadi
 *    harus diambil saat mau diputar, tidak boleh disimpan/disamarkan.
 *  - Odysee (`player.odycdn.com/.../free/...`) balas 401 tanpa tanda tangan ->
 *    jangan dijanjikan. VidHide (`morencius.com`) memakai packer JS.
 */
private const val PROYEK = "https://anichin.moe"
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** Satu dropdown = satu parameter GET di form /anime (mis. `status`, `type`, `order`). */
private class Pilih(
    nama: String,
    val param: String,
    val pasangan: List<Pair<String, String>>,
    bawaan: Int = 0,
) : Filter.Select<String>(nama, pasangan.map { it.second }.toTypedArray(), bawaan) {
    val nilai: String get() = pasangan.getOrNull(state)?.first.orEmpty()
}

/**
 * Nilai filter diambil langsung dari form milik situs
 * (`<form action="/anime" class="filters">`): radio status/type/sub/order + checkbox genre[].
 */
private val URUTAN = listOf(
    "update" to "Terbaru",
    "popular" to "Terpopuler",
    "rating" to "Rating",
    "latest" to "Rilis terbaru",
    "title" to "Judul A–Z",
    "titlereverse" to "Judul Z–A",
)

private val STATUS = listOf(
    "" to "Semua",
    "ongoing" to "Ongoing",
    "completed" to "Completed",
    "upcoming" to "Upcoming",
    "hiatus" to "Hiatus",
)

private val TIPE = listOf(
    "" to "Semua",
    "tv" to "TV",
    "ona" to "ONA",
    "ova" to "OVA",
    "movie" to "Movie",
    "donghua" to "Donghua",
    "special" to "Special",
    "bd" to "BD",
    "live action" to "Live Action",
    "music" to "Music",
)

private val SUB = listOf("" to "Semua", "sub" to "Sub", "dub" to "Dub", "raw" to "Raw")

/** Nilai `genre[]` dari form situs (duplikat seperti actions/adven dibuang). */
private val GENRE = listOf("" to "Semua") + listOf(
    "action", "adventure", "blood", "comedy", "crossdressing", "cultivation", "demons", "drama",
    "ecchi", "fantasy", "friendship", "game", "gore", "gourmet", "guoman", "harem", "historical",
    "horror", "isekai", "life", "magic", "martial-arts", "mecha", "military", "music", "mystery",
    "psychological", "reincarnation", "romance", "school", "sci-fi", "shoujo", "shounen",
    "slice-of-life", "space", "sports", "super-power", "supernatural", "suspense", "thriller",
    "time-travel", "urban-fantasy", "vampire", "video-game", "youth",
).map { it to it.split("-").joinToString(" ") { k -> k.replaceFirstChar { c -> c.uppercase() } } }

class Anichin : HttpSource() {

    override val name = "Anichin"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    // Referer wajib: /wp-content/uploads/* membalas 403 tanpa Referer situs.
    // Header ini juga ikut dipakai saat memuat poster dan saat memutar video.
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
            if (!r.isSuccessful) throw Exception("Anichin: HTTP ${r.code} untuk $url")
            r.body.string()
        }
    }

    // WAJIB memakai base URI: gambar & tautan di situs ini memakai path relatif
    // (/wp-content/..., /judul/), dan tanpa baseUri absUrl() mengembalikan "".
    private fun doc(url: String, referer: String? = null) = Jsoup.parse(teks(url, referer), url)

    private fun bersih(s: String) =
        Parser.unescapeEntities(s.replace(Regex("<[^>]+>"), ""), false).trim()

    /** Judul tautan: pakai atribut title, kalau kosong pakai teksnya. */
    private fun judulDari(el: org.jsoup.nodes.Element, cadangan: String): String {
        val t = el.attr("title").ifBlank { el.text() }
        return bersih(t).ifBlank { cadangan }
    }

    private fun dariKartu(d: Document): List<SManga> = d.select("article.bs a[href]")
        .map { a ->
            val url = a.absUrl("href").ifBlank { a.attr("href") }
            SManga.create().apply {
                this.url = url.removePrefix(PROYEK)
                title = judulDari(a, this.url.substringAfterLast('/').replace('-', ' '))
                val img = a.selectFirst("img")
                thumbnail_url = img?.absUrl("src")?.ifBlank { img.attr("data-src") }?.ifBlank { null }
                initialized = false
            }
        }
        .filter { it.url.length > 1 }
        .distinctBy { it.url }

    // --- Katalog ------------------------------------------------------------

    /**
     * Arsip `/anime/` punya form filternya sendiri: `?status=&type=&sub=&order=&genre[]=` dengan
     * halaman berikutnya di `/anime/page/N/?<param>`. Urutan asli yang terbukti berbeda:
     * popular (Perfect World, Soul Land) vs update (The Guardian of Daxia) vs rating (BTTH Movie).
     *
     * Sebelumnya modul ini memakai beranda untuk KEDUA tab (`$PROYEK/` + `/page/N/`) sehingga
     * daftar Populer dan Terbaru kembar persis.
     */
    private fun paramDari(filters: FilterList): List<String> = filters
        .filterIsInstance<Pilih>()
        .mapNotNull { f ->
            val v = f.nilai
            if (v.isBlank()) null else "${f.param}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun alamatArsip(page: Int, param: List<String>): String {
        val q = param.joinToString("&")
        val dasar = if (page <= 1) "$PROYEK/anime/" else "$PROYEK/anime/page/$page/"
        return if (q.isEmpty()) dasar else "$dasar?$q"
    }

    private fun dariArsip(page: Int, param: List<String>): MangasPage {
        val isi = dariKartu(doc(alamatArsip(page, param)))
        return MangasPage(isi, isi.isNotEmpty())
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable { dariArsip(page, listOf("status=", "type=", "order=popular")) }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { dariArsip(page, listOf("status=", "type=", "order=update")) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            // Pencarian memakai halaman `?s=` milik situs; parameter filter diikutkan
            // (kalau tidak didukung, WordPress mengabaikannya).
            val param = paramDari(filters)
            val alamat = if (page <= 1) {
                "$PROYEK/?s=$q" + if (param.isEmpty()) "" else "&" + param.joinToString("&")
            } else {
                "$PROYEK/page/$page/?s=$q" + if (param.isEmpty()) "" else "&" + param.joinToString("&")
            }
            val isi = dariKartu(doc(alamat))
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(
        listOf<Filter<*>>(
            Pilih("Urutkan", "order", URUTAN),
            Pilih("Status", "status", STATUS),
            Pilih("Tipe", "type", TIPE),
            Pilih("Sub", "sub", SUB),
            Pilih("Genre", "genre[]", GENRE),
        ),
    )

    // --- Detail & daftar episode -------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val alamat = "$PROYEK${manga.url}"
            val halaman = teks(alamat)
            val d = Jsoup.parse(halaman, alamat)
            manga.apply {
                d.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.ifBlank { null }?.let { title = bersih(it) }
                val og = d.selectFirst("meta[property=og:image]")?.attr("content")
                thumbnail_url = og?.ifBlank { null } ?: thumbnail_url
                val ket = d.selectFirst("div.entry-content p, div.animefull p")?.text()?.ifBlank { null }
                    ?: d.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
                ket?.let { description = bersih(it) }
                status = SManga.ONGOING
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val d = doc("$PROYEK${manga.url}")
            // Semua episode satu seri ada di blok ini, jadi dari episode mana pun
            // pengguna bisa lanjut ke episode lain tanpa kembali ke katalog.
            val isi = d.select("div.episodelist li a[href], div.episodelist a[href]")
                .map { a ->
                    val url = a.absUrl("href").ifBlank { a.attr("href") }.removePrefix(PROYEK)
                    val nomor = Regex("""(?i)episode[- ]?(\d+(?:\.\d+)?)""")
                        .find(url)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    SChapter.create().apply {
                        this.url = url
                        name = bersih(a.attr("title")).ifBlank {
                            if (nomor >= 0) "Episode ${nomor.toInt()}" else url
                        }
                        chapter_number = if (nomor >= 0) nomor else -1f
                    }
                }
                .filter { it.url.contains("-episode-") }
                .distinctBy { it.url }
                .sortedBy { it.chapter_number }

            if (isi.isNotEmpty()) return@fromCallable isi

            val nomor = Regex("""(?i)episode[- ]?(\d+(?:\.\d+)?)""").find(manga.url)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            // Jangan pakai judul seri sebagai nama bab: aplikasi membuang judul
            // seri dari nama bab sehingga barisnya tampak kosong.
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

    private fun mirrorDari(d: Document): List<String> {
        val hasil = LinkedHashSet<String>()

        // 1) pilihan server: base64 berisi potongan HTML berisi src=
        d.select("select.mirror option[value]").forEach { o ->
            val v = o.attr("value").trim()
            if (v.length < 20) return@forEach
            val teks = runCatching {
                String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(teks)
                ?.groupValues?.get(1)?.let { hasil += it }
        }

        // 2) iframe yang tampil langsung
        d.select("iframe[src]").forEach { f ->
            val u = f.absUrl("src").ifBlank { f.attr("src") }
            if (u.startsWith("http")) hasil += u
        }
        return hasil.toList()
    }

    /** ok.ru: ambil HLS (hlsManifestUrl) atau MP4 (videos[].url) dari data-options. */
    private fun dariOkru(embed: String, referer: String): String? = runCatching {
        val d = Jsoup.parse(teks(embed, referer))
        val el = d.selectFirst("div[data-module=OKVideo][data-options], div[data-options]") ?: return null
        val o = JSONObject(el.attr("data-options"))
        val fv = o.optJSONObject("flashvars") ?: return null
        val md = when (val m = fv.opt("metadata")) {
            is JSONObject -> m
            is String -> runCatching { JSONObject(m) }.getOrNull()
            else -> null
        } ?: return null

        md.optString("hlsManifestUrl").ifBlank { null }?.let { return it }
        val vids: JSONArray = md.optJSONArray("videos") ?: return null
        for (i in 0 until vids.length()) {
            val u = vids.optJSONObject(i)?.optString("url").orEmpty()
            if (u.startsWith("http")) return u
        }
        null
    }.getOrNull()

    /** Embed yang memuat tautan .m3u8/.mp4 apa adanya (mis. Streamruby). */
    private fun dariEmbedBiasa(embed: String, referer: String): String? = runCatching {
        val h = teks(embed, referer)
        val m = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(h) ?: return null
        Parser.unescapeEntities(m.value, false)
    }.getOrNull()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val referer = "$PROYEK${chapter.url}"
            val d = doc(referer)
            val mirrors = mirrorDari(d)

            val hasil = LinkedHashSet<String>()
            // ok.ru lebih dahulu (paling andal), lalu sisanya sebagai cadangan.
            val urut = mirrors.sortedBy { if (it.contains("ok.ru")) 0 else 1 }
            for (m in urut) {
                val u = when {
                    m.contains("ok.ru") -> dariOkru(m, referer)
                    else -> dariEmbedBiasa(m, referer)
                }
                if (!u.isNullOrBlank()) hasil += u
                if (hasil.size >= 3) break // cukup beberapa pilihan
            }
            hasil.mapIndexed { i, u -> Page(i, u) }.ifEmpty {
                // Sejak mirror Dailymotion (player.nunadrama.sbs) balas 404, tinggal
                // ok.ru/Streamruby yang hidup. Kalau tak satu pun menghasilkan tautan,
                // lemparkan kesalahan supaya aplikasi menampilkan pesan alih-alih
                // membuka pemutar kosong.
                throw Exception("Tidak ada server yang bisa diputar untuk episode ini.")
            }
        }
        .subscribeOn(Schedulers.io())
}