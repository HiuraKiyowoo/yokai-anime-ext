package eu.kanade.tachiyomi.extension.id.donghive

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
 * Donghive (donghive.vip) — donghua sub Indo. WordPress 7.1.4 + tema "animestream".
 *
 * MODEL DATA (terukur 12 Nov 2026):
 *  - setiap SERI = satu kategori WordPress (mis. `jade-dynasty`), halaman seri ada di `/jade-dynasty/`
 *  - setiap EPISODE = satu post, mis. `/jade-dynasty-episode-93-subtitle-indonesia/`
 *  - `/wp-json/wp/v2/posts?_embed=wp:term` memetakan episode -> kategori (slug seri) dalam satu
 *    permintaan, jadi tab Terbaru bisa menampilkan SERI (bukan episode) tanpa permintaan tambahan
 *  - populer: sidebar beranda `#wpop-items .wpop-weekly a.series[href]` — berisi SERI + peringkat,
 *    dengan tab mingguan/bulanan/seluruh waktu
 *  - genre: `/genres/<slug>/` (halaman kartu seri, paginasi `/genres/<slug>/page/N/`)
 *  - cari: `/?s=<q>` (hasilnya campur seri & episode — hanya tautan seri yang dipakai)
 *
 * STREAM: halaman episode memuat `<select class="mirror">` yang isinya base64 dari potongan
 * HTML. Label opsi TIDAK bisa dipercaya (label "Dailymotion" bisa berisi ok.ru, mega, sampai
 * widget countdown iklan) — jadi isinya yang diperiksa, bukan labelnya.
 *
 * Jalur yang terbukti bisa diputar (ok.ru):
 *   https://ok.ru/videoembed/<id> -> `div[data-options]` (JSON) ->
 *   `flashvars.metadata.hlsManifestUrl` -> master HLS, uji: HTTP 200 `application/x-mpegURL`
 *   (URL terikat `srcIp=` + `expires=`, jadi WAJIB diambil saat itu juga, bukan disimpan).
 *
 * Jalur yang TERBUKTI TIDAK jalan dari perangkat ini (jangan dipakai sebagai andalan):
 *  - Dailymotion: m3u8 dari metadata API maupun halaman player geo selalu **403**, dan di
 *    Chromium asli manifest-nya balas **204** (pemutar berhenti di "Loading").
 *  - MEGA (`mega.nz/embed/...`): butuh unduhan, tidak bisa diputar.
 */
private const val PROYEK = "https://donghive.vip"
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val AL = "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
private const val PER_HALAMAN = 24

/** Satu dropdown = satu parameter (mis. `genre` lewat jalur /genres/<slug>/). */
private class Pilih(
    nama: String,
    val pasangan: List<Pair<String, String>>,
    bawaan: Int = 0,
) : Filter.Select<String>(nama, pasangan.map { it.second }.toTypedArray(), bawaan) {
    val nilai: String get() = pasangan.getOrNull(state)?.first.orEmpty()
}

class Donghive : HttpSource() {

    override val name = "Donghive"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Accept-Language", AL)
        .add("Referer", "$PROYEK/")

    // --- HTTP ---------------------------------------------------------------

    private fun teks(url: String, referer: String? = null): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", AL)
        b.header("Referer", referer ?: "$PROYEK/")
        return client.newCall(b.build()).execute().use { r ->
            if (!r.successful) throw Exception("Donghive: HTTP ${r.code} untuk $url")
            r.body?.string().orEmpty()
        }
    }

    private fun doc(url: String): Document = Jsoup.parse(teks(url), url)

    private fun bersih(t: String): String =
        Parser.unescapeEntities(t.replace(Regex("<[^>]+>"), " "), false)
            .replace(Regex("\\s+"), " ").trim()

    // --- Kartu seri ---------------------------------------------------------

    /** Kartu seri: `div.bsx > a[href][title]` (dipakai beranda, genre, dan hasil cari). */
    private fun dariKartu(d: Document): List<SManga> = d.select("div.bsx a[href][title]")
        .mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { a.attr("href") }
            if (url.contains("/episode")) return@mapNotNull null
            if (!url.startsWith(PROYEK)) return@mapNotNull null
            val jalur = url.removePrefix(PROYEK)
            if (jalur.length < 3 || !jalur.endsWith("/")) return@mapNotNull null
            SManga.create().apply {
                this.url = jalur
                title = bersih(a.attr("title")).ifBlank { jalur.trim('/') }
                val img = a.selectFirst("img")
                thumbnail_url = img?.absUrl("src")?.ifBlank { img.attr("data-src") }?.ifBlank { null }
                initialized = false
            }
        }
        .distinctBy { it.url }

    /**
     * Populer: daftar seri di sidebar beranda (tab mingguan — bawaan situs).
     * Situs tidak menyediakan halaman berikutnya, jadi halaman > 1 dikembalikan kosong.
     */
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            if (page > 1) return@fromCallable MangasPage(emptyList(), false)
            val d = doc("$PROYEK/")
            val isi = d.select("#wpop-items .wpop-weekly a.series[href], #wpop-items a.series[href]")
                .mapNotNull { a ->
                    val url = a.absUrl("href").ifBlank { a.attr("href") }
                    if (!url.startsWith(PROYEK)) return@mapNotNull null
                    val jalur = url.removePrefix(PROYEK)
                    val wadah = a.parent()?.parent()
                    SManga.create().apply {
                        this.url = jalur
                        title = bersih(wadah?.selectFirst("a.series[title]")?.attr("title").orEmpty())
                            .ifBlank { jalur.trim('/') }
                        val img = a.selectFirst("img")
                        thumbnail_url = img?.absUrl("src")?.ifBlank { img.attr("data-src") }?.ifBlank { null }
                        initialized = false
                    }
                }
                .distinctBy { it.url }
                .take(PER_HALAMAN)
            MangasPage(isi, false)
        }
        .subscribeOn(Schedulers.io())

    /**
     * Terbaru: episode terbaru dari REST, lalu dipetakan ke SERI lewat kategori
     * (`_embed=wp:term`) supaya yang tampil nama seri, bukan judul episode.
     */
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            val url = "$PROYEK/wp-json/wp/v2/posts?per_page=$PER_HALAMAN&page=$page" +
                "&orderby=date&order=desc&_embed=wp:term&_fields=link,title,categories,_embedded"
            val arr = JSONArray(teks(url))
            val lihat = LinkedHashMap<String, SManga>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = seriDari(o)
                if (slug.isBlank()) continue
                if (lihat.containsKey("/$slug/")) continue
                lihat["/$slug/"] = SManga.create().apply {
                    url = "/$slug/"
                    title = judulSeri(o).ifBlank { slug }
                    initialized = false
                }
            }
            MangasPage(lihat.values.toList(), arr.length() >= PER_HALAMAN)
        }
        .subscribeOn(Schedulers.io())

    /** Slug kategori (= slug seri) dari `_embedded.wp:term`. */
    private fun seriDari(o: JSONObject): String {
        val grup = o.optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return ""
        for (g in 0 until grup.length()) {
            val arr = grup.optJSONArray(g) ?: continue
            for (t in 0 until arr.length()) {
                val term = arr.optJSONObject(t) ?: continue
                if (term.optString("taxonomy") == "category") return term.optString("slug")
            }
        }
        return ""
    }

    /** Nama seri = label kategori (judul episode tidak dipakai). */
    private fun judulSeri(o: JSONObject): String {
        val grup = o.optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return ""
        for (g in 0 until grup.length()) {
            val arr = grup.optJSONArray(g) ?: continue
            for (t in 0 until arr.length()) {
                val term = arr.optJSONObject(t) ?: continue
                if (term.optString("taxonomy") == "category") return bersih(term.optString("name"))
            }
        }
        return ""
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val genre = filters.filterIsInstance<Pilih>().firstOrNull()?.nilai.orEmpty()
            val alamat = when {
                genre.isNotBlank() && page > 1 -> "$PROYEK/genres/$genre/page/$page/?s=$q"
                genre.isNotBlank() -> "$PROYEK/genres/$genre/?s=$q"
                page > 1 -> "$PROYEK/page/$page/?s=$q"
                else -> "$PROYEK/?s=$q"
            }
            val isi = dariKartu(doc(alamat))
            MangasPage(isi, isi.size >= 10)
        }
        .subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList(
        listOf<Filter<*>>(Pilih("Genre", GENRE)),
    )

    // --- Detail & daftar episode -------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val alamat = "$PROYEK${manga.url}"
            val halaman = teks(alamat)
            val d = Jsoup.parse(halaman, alamat)
            manga.apply {
                d.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.ifBlank { null }?.let { title = bersih(it).removeSuffix(" - Donghive") }
                d.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.ifBlank { null }?.let { thumbnail_url = it }
                val sinopsis = d.selectFirst("div.bixbox.synp p, div.synp p, div.desc p, div.entry-content p")
                    ?.text()?.ifBlank { null }
                    ?: d.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
                sinopsis?.let { description = bersih(it) }
                status = SManga.ONGOING
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val d = doc("$PROYEK${manga.url}")
            val daftar = d.select("div.eplister ul li, div.eplister li")
                .mapNotNull { li ->
                    val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                    val url = a.absUrl("href").ifBlank { a.attr("href") }
                    if (url.isBlank()) return@mapNotNull null
                    val nomorTeks = li.selectFirst(".epl-num")?.text().orEmpty()
                    val judul = li.selectFirst(".epl-title")?.text().orEmpty()
                    val nomor = Regex("\\d+(?:\\.\\d+)?").find(nomorTeks)?.value?.toFloatOrNull() ?: 0f
                    SChapter.create().apply {
                        this.url = url.removePrefix(PROYEK)
                        // jangan pakai nama seri sebagai nama bab: ChapterRecognition membuangnya
                        name = "Episode ${nomorTeks.ifBlank { if (nomor > 0f) nomor.toInt().toString() else judul }}"
                        chapter_number = nomor
                    }
                }
                .distinctBy { it.url }
            if (daftar.isEmpty()) throw Exception("Donghive: daftar episode kosong")
            // situs menampilkan episode terbaru dulu; aplikasi mengharapkan urutan naik
            daftar.sortedBy { it.chapter_number }
        }
        .subscribeOn(Schedulers.io())

    // --- Halaman: tautan video ---------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val alamat = "$PROYEK${chapter.url}"
            val d = Jsoup.parse(teks(alamat), alamat)
            val opsi = d.select("select.mirror option").mapNotNull { o ->
                val nilai = o.attr("value")
                if (nilai.isBlank()) null else opsiBase64(nilai)
            }

            val hasil = LinkedHashSet<String>()
            for (isi in opsi) {
                // ok.ru: hlsManifestUrl dari data-options (jalur yang terbukti diputar)
                val okId = Regex("""ok\.ru/(?:videoembed|video)/(\d+)""").find(isi)?.groupValues?.get(1)
                if (okId != null) {
                    runCatching { hlsOkru(okId) }.getOrNull()?.let { hasil += it }
                    continue
                }
                // Dailymotion geo player: m3u8 ada di halaman pemutarnya (sering 403, tetap dicoba)
                val dm = Regex("""geo\.dailymotion\.com/player/[^"'<>\s]+""").find(isi)?.value
                if (dm != null) {
                    runCatching {
                        Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
                            .find(teks("https:$dm", alamat))?.value
                    }.getOrNull()?.let { hasil += it }
                }
            }

            if (hasil.isEmpty()) throw Exception("Donghive: tidak ada server video yang bisa diputar di episode ini")
            hasil.toList().mapIndexed { i, u -> Page(i, u) }
        }
        .subscribeOn(Schedulers.io())

    /** Isi `<option>` adalah base64 dari potongan HTML (kadang ber-escape). */
    private fun opsiBase64(nilai: String): String? = runCatching {
        val bersihkan = nilai.replace("\\s".toRegex(), "")
        String(android.util.Base64.decode(bersihkan, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    /**
     * ok.ru: HLS di `flashvars.metadata.hlsManifestUrl`; `videos[]` sebagai cadangan.
     * URL-nya ber-`expires` + `srcIp`, jadi harus dipakai langsung (tidak disimpan).
     */
    private fun hlsOkru(id: String): String? {
        val d = Jsoup.parse(teks("https://ok.ru/videoembed/$id", "https://ok.ru/"))
        val el = d.selectFirst("div[data-module=OKVideo][data-options], div[data-options]") ?: return null
        val o = JSONObject(el.attr("data-options"))
        val fl = o.optJSONObject("flashvars") ?: return null
        val md = fl.optJSONObject("metadata") ?: return null
        md.optString("hlsManifestUrl").ifBlank { null }?.let { return it }
        val vids = md.optJSONObject("movie")?.optJSONArray("videos") ?: return null
        for (i in 0 until vids.length()) {
            val u = vids.optJSONObject(i)?.optString("url").orEmpty()
            if (u.startsWith("http")) return u
        }
        return null
    }

    companion object {
        /** Genre asli dari bagian "Genre" di beranda (jalur `/genres/<slug>/`) — terukur 12 Nov 2026. */
        private val GENRE: List<Pair<String, String>> = listOf("" to "Semua") + listOf(
            "2d", "action", "adventure", "cheat-system", "comedy", "cultivation", "demons", "drama",
            "fantasy", "game", "game-world", "historical", "horor", "isekai", "martial-arts", "mystery",
            "op-mc", "psychological", "reincarnation", "romance", "sci-fi", "super-power", "supranatural",
            "urban-fantasy", "war", "wuxia",
        ).map { it to it.replace("-", " ").replaceFirstChar { c -> c.uppercase() } }
    }
}
