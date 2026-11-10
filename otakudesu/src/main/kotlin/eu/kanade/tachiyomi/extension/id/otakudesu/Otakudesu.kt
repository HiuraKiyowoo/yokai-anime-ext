package eu.kanade.tachiyomi.extension.id.otakudesu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Otakudesu (otakudesu.blog) — situs HTML biasa, tanpa API.
 *
 * Tautan video:
 *  halaman episode -> <iframe src="https://desustream.net/dstream/odcdn/?id=...">
 *  -> HTML player memuat satu MP4 langsung di cdn.odcloud.net
 *
 * PERINGATAN: tautan `link.desustream.com/?id=...` di halaman episode BUKAN stream —
 * itu mirror unduhan yang berakhir di vikingfile.com. Jangan dipakai.
 */
private const val PROYEK = "https://otakudesu.blog"
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

class Otakudesu : HttpSource() {

    override val name = "Otakudesu"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)

    // --- HTTP ---------------------------------------------------------------

    private fun html(url: String, referer: String? = null): Document {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        val teks = client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Otakudesu: HTTP ${r.code} untuk $url")
            r.body.string()
        }
        return Jsoup.parse(teks, url)
    }

    // --- Pemetaan -----------------------------------------------------------

    private fun Element.keManga(): SManga? {
        val a = selectFirst("div.thumb a[href*=/anime/]")
            ?: selectFirst("a[href*=/anime/]")
            ?: return null
        val tautan = a.absUrl("href").ifBlank { a.attr("href") }
        val slug = tautan.trimEnd('/').substringAfterLast("/")
        if (slug.isBlank()) return null
        return SManga.create().apply {
            url = "/anime/$slug"
            title = selectFirst("h2.jdlflm")?.text()
                ?: a.attr("title").ifBlank { slug }
            thumbnail_url = selectFirst("img")?.let { it.absUrl("src").ifBlank { it.attr("src") } }
            initialized = false
        }
    }

    private fun daftar(doc: Document): List<SManga> =
        doc.select("div.detpost, li > div.detpost, article")
            .mapNotNull { it.keManga() }
            .distinctBy { it.url }

    private fun halaman(path: String, page: Int): Document =
        html(if (page <= 1) "$PROYEK$path" else "$PROYEK$path/page/$page/")

    // --- Katalog ------------------------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            val isi = daftar(halaman("/anime/", page))
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            val isi = daftar(halaman("/ongoing-anime/", page))
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = query.trim()
            val genre = (filters.find { it is GenreFilter } as? GenreFilter)
                ?.takeIf { it.state > 0 }
                ?.let { it.values[it.state].lowercase() }
            when {
                genre != null -> {
                    val isi = daftar(halaman("/genres/$genre/", page))
                    MangasPage(isi, isi.isNotEmpty())
                }
                kunci.isNotEmpty() -> {
                    // pencarian situs dibatasi Cloudflare (403 utk sebagian UA);
                    // pakai pencarian lewat query ?s= dengan UA browser
                    val doc = html("$PROYEK/?s=${java.net.URLEncoder.encode(kunci, "UTF-8")}&post_type=anime")
                    val isi = daftar(doc)
                    MangasPage(isi, false)
                }
                else -> fetchPopularManga(1).toBlocking().first()
            }
        }
        .subscribeOn(Schedulers.io())

    // --- Filter -------------------------------------------------------------

    private class GenreFilter(opsi: Array<String>) : Filter.Select<String>("Genre", opsi)

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(arrayOf("Semua") + GENRE),
    )

    // --- Detail & episode ---------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val doc = html("$PROYEK${manga.url}")
            val info = doc.select("div.infozingle p").associate { p ->
                val lab = p.selectFirst("b")?.text()?.removeSuffix(":")?.trim() ?: ""
                lab to p.text().removePrefix(p.selectFirst("b")?.text() ?: "").trim().removePrefix(":").trim()
            }
            manga.apply {
                title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: title
                thumbnail_url = doc.selectFirst("div.fotoanime img")?.let {
                    it.absUrl("src").ifBlank { it.attr("src") }
                } ?: thumbnail_url
                description = doc.selectFirst("div.sinopc")?.text()?.trim()
                author = info["Studio"] ?: info["Produser"]
                artist = info["Studio"]
                genre = doc.select("div.infozingle a[href*=/genres/]").joinToString(", ") { it.text() }
                status = when (info["Status"]?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed", "complete" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val doc = html("$PROYEK${manga.url}")
            doc.select("div.episodelist a[href*=/episode/]")
                .mapNotNull { a ->
                    val tautan = a.absUrl("href").ifBlank { a.attr("href") }
                    val slug = tautan.trimEnd('/').substringAfterLast("/")
                    if (slug.isBlank()) return@mapNotNull null
                    val judul = a.text().trim().ifBlank { slug }
                    SChapter.create().apply {
                        url = "/episode/$slug"
                        name = judul
                        chapter_number = Regex("(\\d+(?:\\.\\d+)?)").find(judul)
                            ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    }
                }
                .distinctBy { it.url }
                .sortedBy { it.chapter_number }
        }
        .subscribeOn(Schedulers.io())

    // --- Tautan video -------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val doc = html("$PROYEK${chapter.url}")
            val iframes = doc.select("iframe[src]")
                .map { it.absUrl("src").ifBlank { it.attr("src") } }
                .filter { it.startsWith("http") }

            val mp4 = LinkedHashSet<String>()
            for (f in iframes) {
                val isi = try {
                    html(f, referer = "https://desustream.net/")
                } catch (_: Exception) {
                    continue
                }
                isi.select("video source[src], video[src], a[href$=.mp4]").forEach { el ->
                    val u = el.absUrl("src").ifBlank { el.absUrl("href") }
                    if (u.contains(".mp4") || u.contains(".m3u8")) mp4 += u
                }
                Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""")
                    .findAll(isi.outerHtml())
                    .forEach { mp4 += it.value }
            }
            mp4.mapIndexed { i, u -> Page(i, u) }
        }
        .subscribeOn(Schedulers.io())
}

/** Genre yang benar-benar ada di otakudesu.blog/genre-list. */
private val GENRE = arrayOf(
    "Action", "Adventure", "Comedy", "Demons", "Drama", "Ecchi", "Fantasy", "Game",
    "Harem", "Historical", "Horror", "Isekai", "Josei", "Kids", "Magic", "Martial Arts",
    "Mecha", "Military", "Music", "Mystery", "Parody", "Police", "Psychological",
    "Romance", "Samurai", "School", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai",
    "Shounen", "Shounen Ai", "Slice of Life", "Space", "Sports", "Super Power",
    "Supernatural", "Thriller", "Vampire",
)