package eu.kanade.tachiyomi.extension.id.nontonhentai

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import rx.Observable
import rx.schedulers.Schedulers

/**
 * NontonHentai (nontonhentai.org) — NSFW.
 *
 * Struktur: WordPress dengan REST API terbuka. Satu post = satu episode.
 * Poster tidak ada di API (featured_media = 0), diambil dari <meta og:image>.
 *
 * Stream: halaman post -> iframe GDPlayer (nh.abhihi.xyz/embed/<uuid>)
 *         -> objek config jwplayer memuat "sources":[{"file":"...m3u8"}]
 *         -> HLS di jv.abplay.store, segmen *.html yang isinya MPEG-TS asli.
 * PERINGATAN: /<nama>.m3u8 pada domain embed BUKAN stream (302 ke /administrator/).
 */
private const val PROYEK = "https://nontonhentai.org"
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val PER_HALAMAN = 25

/** Satu dropdown; nilainya dipakai sebagai parameter/jalur filter. */
private class Pilih(
    nama: String,
    val pasangan: List<Pair<String, String>>,
    bawaan: Int = 0,
) : eu.kanade.tachiyomi.source.model.Filter.Select<String>(
    nama,
    pasangan.map { it.second }.toTypedArray(),
    bawaan,
) {
    val nilai: String get() = pasangan.getOrNull(state)?.first.orEmpty()
}

/** Genre asli situs: jalur `/genres/<slug>/` (42 genre, terukur 12 Nov 2026). */
private val GENRE = listOf(
    "ahegao", "anal", "armpit", "bdsm", "big-oppai", "blowjob", "bondage", "creampie",
    "dark-skin", "dilf", "femdom", "footjob", "forced", "harem", "incest", "josei",
    "lactation", "maid", "masturbation", "milf", "mind-break", "netorare", "nurse", "office-lady",
    "paizuri", "pregnant", "rape", "romance", "school", "schoolgirl", "sister", "slave",
    "stocking", "succubus", "swimsuit", "teacher", "tentacle", "threesome", "tsundere", "ugly-bastard",
    "vanilla", "yuri",
).map { it to it.replace("-", " ").replaceFirstChar { c -> c.uppercase() } }

class NontonHentai : HttpSource() {

    override val name = "NontonHentai"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("User-Agent", UA)

    // --- HTTP ---------------------------------------------------------------

    private fun teks(url: String, referer: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        return client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("NontonHentai: HTTP ${r.code} untuk $url")
            r.body.string()
        }
    }

    private fun daftar(path: String): JSONArray = JSONArray(teks("$PROYEK$path"))

    // --- Pemetaan -----------------------------------------------------------

    private fun slugDari(tautan: String): String = tautan.trimEnd('/').substringAfterLast("/")

    private fun keManga(o: JSONObject, tautan: String): SManga = SManga.create().apply {
        url = "/$tautan"
        title = o.optJSONObject("title")?.optString("rendered")
            ?.replace(Regex("<[^>]+>"), "")?.trim()
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            ?: tautan
        initialized = false
    }

    private fun halaman(path: String, page: Int): MangasPage {
        val arr = daftar("$path${if (path.contains("?")) "&" else "?"}per_page=$PER_HALAMAN&page=$page")
        val isi = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug").ifBlank { slugDari(o.optString("link")) }
            if (slug.isBlank()) null else keManga(o, slug)
        }
        return MangasPage(isi, isi.isNotEmpty())
    }

    // --- Katalog ------------------------------------------------------------

    /**
     * Populer: blok "Trending Minggu Ini" di beranda (server-rendered).
     *
     * Sebelumnya Populer dan Terbaru memakai URL yang persis sama
     * (`/wp-json/wp/v2/posts?orderby=date`) sehingga dua tab itu isinya kembar.
     * (Catatan: `orderby=rand` DITOLAK 400 di situs ini, jadi bukan pilihan.)
     *
     * Tautan di slider campur: `/series/<slug>/` (halaman seri) dan
     * `/<slug>-episode-N-.../` (post episode). Tautan episode ditukar jadi objek post
     * lewat SATU permintaan REST `slug=a,b,c` supaya bentuk entri tetap sama seperti katalog.
     */
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            if (page > 1) return@fromCallable MangasPage(emptyList(), false)
            val doc = Jsoup.parse(teks("$PROYEK/"), PROYEK)
            val tautan = doc.select("a:has(span.numa), a:has(.bghover)")
                .map { it.absUrl("href") }
                .filter { it.startsWith(PROYEK) && !it.contains("/genres/") }
                .distinct()
                .take(PER_HALAMAN)
            val seri = tautan.filterNot { it.contains("-episode-") }
                .map { path ->
                    val jalur = path.removePrefix(PROYEK)
                    SManga.create().apply {
                        url = jalur
                        title = jalur.trim('/').substringAfterLast("/").replace('-', ' ')
                            .split(" ").joinToString(" ") { s -> s.replaceFirstChar { c -> c.uppercase() } }
                        initialized = false
                    }
                }
            val slugEpisode = tautan.filter { it.contains("-episode-") }.map { slugDari(it) }
            val dariPost = if (slugEpisode.isEmpty()) {
                emptyList()
            } else {
                runCatching {
                    val arr = daftar(
                        "/wp-json/wp/v2/posts?slug=${slugEpisode.joinToString(",")}" +
                            "&per_page=${slugEpisode.size}&_fields=slug,link,title",
                    )
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val slug = o.optString("slug").ifBlank { slugDari(o.optString("link")) }
                        if (slug.isBlank()) null else keManga(o, slug)
                    }
                }.getOrDefault(emptyList())
            }
            val urut = seri + dariPost
            MangasPage(urut, false)
        }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halaman("/wp-json/wp/v2/posts?orderby=date&order=desc", page) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = query.trim()
            val genre = filters.filterIsInstance<Pilih>().firstOrNull()?.nilai.orEmpty()

            // Genre situs hanya tersedia sebagai halaman HTML /genres/<slug>/page/N/.
            if (genre.isNotBlank()) {
                val alamat = if (page <= 1) "$PROYEK/genres/$genre/" else "$PROYEK/genres/$genre/page/$page/"
                val doc = Jsoup.parse(teks(alamat), alamat)
                val isi = doc.select("article.bs a[href]")
                    .mapNotNull { a ->
                        val url = a.absUrl("href").ifBlank { a.attr("href") }
                        if (!url.startsWith(PROYEK)) return@mapNotNull null
                        val judul = a.attr("title").ifBlank { a.selectFirst("h2, h3, .tt")?.text().orEmpty() }
                        if (judul.isBlank()) return@mapNotNull null
                        if (kunci.isNotBlank() && !judul.contains(kunci, ignoreCase = true)) return@mapNotNull null
                        SManga.create().apply {
                            this.url = url.removePrefix(PROYEK)
                            title = org.jsoup.parser.Parser.unescapeEntities(judul, false).trim()
                            val img = a.selectFirst("img")
                            thumbnail_url = img?.absUrl("src")?.ifBlank { img.attr("data-src") }?.ifBlank { null }
                            initialized = false
                        }
                    }
                    .distinctBy { it.url }
                return@fromCallable MangasPage(isi, isi.isNotEmpty())
            }

            if (kunci.isBlank()) return@fromCallable fetchPopularManga(page).toBlocking().first()
            val kunciEnc = java.net.URLEncoder.encode(kunci, "UTF-8")
            val arr = daftar("/wp-json/wp/v2/search?search=$kunciEnc&per_page=20&page=$page&subtype=post")
            val isi = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                // hasil pencarian bisa berupa /series/<nama>/ (halaman seri) atau
                // /<slug-episode>/. Simpan path apa adanya — jangan dianggap slug.
                val path = o.optString("url").removePrefix(PROYEK).ifBlank { return@mapNotNull null }
                SManga.create().apply {
                    url = if (path.startsWith("/")) path else "/$path"
                    title = (o.optString("title").ifBlank { path })
                        .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
                    initialized = false
                }
            }
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun getFilterList(): eu.kanade.tachiyomi.source.model.FilterList =
        eu.kanade.tachiyomi.source.model.FilterList(
            listOf<eu.kanade.tachiyomi.source.model.Filter<*>>(
                Pilih("Genre", listOf("" to "Semua") + GENRE),
            ),
        )

    // --- Detail & episode ---------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            // HTML lebih andal: berlaku untuk /series/<nama>/ maupun /<slug-episode>/.
            // REST API hanya dipakai sebagai pelengkap (featured_media selalu 0).
            val doc = runCatching { Jsoup.parse(teks("$PROYEK${manga.url}")) }.getOrNull()
            val poster = doc?.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            val judulHtml = doc?.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }

            val ket = doc?.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
                ?: doc?.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }

            manga.apply {
                judulHtml?.let { title = org.jsoup.parser.Parser.unescapeEntities(it, false) }
                ket?.let { description = org.jsoup.parser.Parser.unescapeEntities(it, false) }
                thumbnail_url = poster ?: thumbnail_url
                status = SManga.COMPLETED
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val doc = Jsoup.parse(teks("$PROYEK${manga.url}"))
            // Halaman /series/... memuat tautan semua episode.
            // Halaman episode tunggal tidak punya daftar -> jatuh ke satu entri.
            val eps = doc.select("a[href$=/]")
                .map { it.absUrl("href").ifBlank { it.attr("href") } }
                .filter { it.startsWith(PROYEK) && Regex("""-episode-\d+""").containsMatchIn(it) }
                .distinct()

            if (eps.isNotEmpty()) {
                return@fromCallable eps.map { tautan ->
                    val slug = tautan.trimEnd('/').substringAfterLast("/")
                    val judul = Regex("""(?i)episode\s*(\d+(?:\.\d+)?)""")
                        .find(slug)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    SChapter.create().apply {
                        url = "/$slug"
                        name = "Episode ${if (judul >= 0) judul.toInt() else slug}"
                        chapter_number = if (judul >= 0) judul else -1f
                    }
                }.sortedBy { it.chapter_number }
            }

            val nomor = Regex("""(?i)episode\s*(\d+(?:\.\d+)?)""").find(manga.title)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            // Nama bab jangan judul seri: aplikasi membuang judul seri dari nama
            // bab (ChapterRecognition), jadi barisnya akan tampak kosong.
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
            val doc = Jsoup.parse(teks("$PROYEK${chapter.url}"))
            val iframes = doc.select("iframe[src]")
                .map { it.absUrl("src").ifBlank { it.attr("src") } }
                .filter { it.startsWith("http") }

            val hasil = LinkedHashSet<String>()
            for (f in iframes) {
                val embed = runCatching { teks(f, referer = PROYEK) }.getOrNull() ?: continue
                // "sources":[{"file":"https:\/\/...m3u8"}] — slash ter-escape
                Regex(""""file"\s*:\s*"([^"]+)"""")
                    .findAll(embed)
                    .map { it.groupValues[1].replace("\\/", "/") }
                    .filter { it.startsWith("http") }
                    .forEach { hasil += it }
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .findAll(embed)
                    .forEach { hasil += it.value }
            }
            hasil.mapIndexed { i, u -> Page(i, u) }
        }
        .subscribeOn(Schedulers.io())
}