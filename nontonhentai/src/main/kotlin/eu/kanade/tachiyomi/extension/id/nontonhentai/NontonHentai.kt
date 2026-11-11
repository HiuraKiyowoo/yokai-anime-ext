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

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        // orderby=comment_count DITOLAK WP ini (400 rest_invalid_param);
        // pilihan yang sah: date, id, title, modified, slug, include, rand.
        .fromCallable { halaman("/wp-json/wp/v2/posts?orderby=date&order=desc", page) }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halaman("/wp-json/wp/v2/posts?orderby=date&order=desc", page) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            if (kunci.isBlank()) return@fromCallable fetchPopularManga(page).toBlocking().first()
            val arr = daftar("/wp-json/wp/v2/search?search=$kunci&per_page=20&page=$page&subtype=post")
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

    // --- Detail & episode ---------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            // HTML lebih andal: berlaku untuk /series/<nama>/ maupun /<slug-episode>/.
            // REST API hanya dipakai sebagai pelengkap (featured_media selalu 0).
            val doc = runCatching { Jsoup.parse(teks("$PROYEK${manga.url}")) }.getOrNull()
            val poster = doc?.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            val judulHtml = doc?.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }

            manga.apply {
                judulHtml?.let { title = org.jsoup.parser.Parser.unescapeEntities(it, false) }
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
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = manga.title
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