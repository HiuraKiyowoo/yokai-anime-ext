package eu.kanade.tachiyomi.extension.id.animein

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Sumber anime di atas API AnimeIn (backend yang dipakai aplikasi Nefusoft).
 *
 * Envelope semua route: {"status":200,"error":false,"data":{...}}
 *
 * Catatan penting soal server ini:
 *  - Wajib User-Agent HP; tanpa itu Cloudflare membalas 403 "Just a moment".
 *  - `/3/2/explore/movie` mengabaikan semua nilai `sort` kecuali `views`.
 *    Karena itu Populer memakai views, dan tab Terbaru dimatikan
 *    (`supportsLatest = false`) daripada menampilkan urutan acak sebagai "terbaru".
 *  - Daftar episode selalu 30 per halaman, urut TERBARU dulu. Parameter `limit`
 *    diabaikan; karena itu jumlah halaman dibatasi supaya membuka detail tidak
 *    menembak puluhan permintaan.
 */
private const val PROYEK = "https://xyz-api.animein.net"
private const val UA = "Mozilla/5.0 (Linux; Android 14; Infinix X6833B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** Batas halaman episode yang diambil (30 per halaman). */
private const val HALAMAN_EPISODE_MAKS = 5

class Animein : HttpSource() {

    override val name = "AnimeIn"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Hanya User-Agent. Jangan taruh token/Authorization di sini: header sumber
    // juga dipakai saat mengunduh poster, dan host gambar akan menolaknya.
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Accept", "application/json")

    // --- HTTP kecil ---------------------------------------------------------

    private fun ambil(path: String): JsonObject {
        val req = Request.Builder()
            .url("$PROYEK$path")
            .headers(headers)
            .build()
        val teks = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("AnimeIn: HTTP ${r.code} untuk $path")
            r.body.string()
        }
        return json.parseToJsonElement(teks).jsonObject
    }

    private fun data(path: String): JsonObject =
        runCatching { ambil(path)["data"]?.jsonObject ?: JsonObject(emptyMap()) }
            .getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.str(kunci: String): String? =
        this[kunci]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.daftar(kunci: String): JsonArray =
        this[kunci]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())

    // --- Pemetaan -----------------------------------------------------------

    private fun keManga(o: JsonObject): SManga = SManga.create().apply {
        val id = o.str("id") ?: ""
        url = "/anime/$id"
        title = o.str("title") ?: id
        thumbnail_url = o.str("image_poster")
        description = o.str("synopsis")
        author = o.str("studio")
        genre = o.str("genre")
        status = when (o.str("status")?.uppercase()) {
            "ONGOING", "AIRING" -> SManga.ONGOING
            "COMPLETED", "COMPLETE", "FINISHED" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun halamanManga(page: Int, sort: String?, kataKunci: String?): MangasPage {
        val q = buildString {
            append("/3/2/explore/movie?page=").append(page - 1)
            sort?.let { append("&sort=").append(it) }
            kataKunci?.takeIf { it.isNotBlank() }?.let { append("&keyword=").append(it) }
        }
        val baris = data(q).daftar("movie")
        return MangasPage(baris.map { keManga(it.jsonObject) }, baris.size >= 60)
    }

    // --- Katalog ------------------------------------------------------------

    /** Populer memakai jumlah tayangan sungguhan (satu-satunya sort yang dihormati server). */
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halamanManga(page, "views", null) }
        .subscribeOn(Schedulers.io())

    /** Server tidak menyediakan urutan "baru diperbarui"; lihat catatan di atas. */
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchPopularManga(page)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable { halamanManga(page, null, query.trim()) }
        .subscribeOn(Schedulers.io())

    // --- Detail & episode ---------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val id = manga.url.removePrefix("/anime/")
            val movie = data("/3/2/movie/detail/$id")["movie"]?.jsonObject ?: return@fromCallable manga
            keManga(movie)
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val id = manga.url.removePrefix("/anime/")
            val semua = mutableListOf<SChapter>()
            var halaman = 0
            while (halaman < HALAMAN_EPISODE_MAKS) {
                val baris = data("/3/2/movie/episode/$id?page=$halaman").daftar("episode")
                if (baris.isEmpty()) break
                baris.forEach { el ->
                    val o = el.jsonObject
                    val epId = o.str("id") ?: return@forEach
                    val nomor = o.str("index")?.toFloatOrNull() ?: 0f
                    semua += SChapter.create().apply {
                        url = "/episode/$epId"
                        name = o.str("title")?.takeIf { it.isNotBlank() } ?: "Episode ${nomor.toInt()}"
                        chapter_number = nomor
                        date_upload = 0L
                    }
                }
                if (baris.size < 30) break
                halaman++
            }
            semua.sortedBy { it.chapter_number }
        }
        .subscribeOn(Schedulers.io())

    // --- Tautan video -------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val epId = chapter.url.removePrefix("/episode/")
            val server = data("/3/2/episode/streamnew/$epId").daftar("server")
            server
                .map { it.jsonObject }
                .filter { it.str("type")?.lowercase() == "direct" }
                .mapNotNull { o -> o.str("link")?.takeIf { it.startsWith("http") }?.let { (o.str("quality") ?: "") to it } }
                .distinctBy { it.second }
                .sortedByDescending { tingkat(it.first) }
                .mapIndexed { i, (_, link) -> Page(i, link) }
        }
        .subscribeOn(Schedulers.io())

    private fun tingkat(kualitas: String): Int =
        kualitas.removeSuffix("p").toIntOrNull() ?: 0
}