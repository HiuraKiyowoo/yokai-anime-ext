package eu.kanade.tachiyomi.extension.id.animelovers

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rx.Observable
import rx.schedulers.Schedulers

/**
 * AnimeLovers (com.aniverseid.animelovers).
 *
 * Versi 1.3.1 di Play Store memakai API baru `api.animeverse.id` yang **dienkripsi**
 * (X-Enc-Ver: 3 + X-Enc-Session, badan balasan AES-CBC). Akan tetapi API lama
 * `apps.animekita.org/api/v1.2.5` masih melayani seluruh data baca **tanpa enkripsi
 * dan tanpa token** — hanya login yang sudah ditutup ("versi tidak didukung").
 * Modul ini memakai jalur lama itu.
 *
 * Bentuk tautan yang dijumpai:
 *  - provide 871 → `s3.animeverse.id/...mp4` (MP4 langsung, Range 206)
 *  - provide 11/99 → `pixeldrain.com/api/file/<id>?download` (MP4, Range 206)
 *  - provide 15 → `sjkt.animekita.org/hls/AnimeLovers_<Judul>--<ep>_<reso>/output.m3u8`
 *    (HLS; sebagian besar sudah 404, jadi diperiksa dulu sebelum ditampilkan)
 */
private const val PROYEK = "https://apps.animekita.org/api/v1.2.5"
private const val UA_GET = "Dart/3.9 (dart:io)"
private const val UA_POST = "Flutter/2.5.3"
private const val JENIS_JSON = "text/plain; charset=utf-8"

class Animelovers : HttpSource() {

    override val name = "AnimeLovers"
    override val lang = "id"
    override val baseUrl = "https://animekita.org"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Sengaja tanpa User-Agent khusus: header sumber juga dipakai mengunduh cover,
    // dan host cover (i0.wp.com, myanimelist) tidak suka UA "Dart".
    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json")

    // --- HTTP ---------------------------------------------------------------

    /** Balasan sebagian endpoint berupa array langsung, sebagian objek — jangan dipaksa. */
    private fun ambilMentah(path: String): JsonElement {
        val req = Request.Builder()
            .url("$PROYEK$path")
            .header("User-Agent", UA_GET)
            .header("Accept", "application/json")
            .build()
        val teks = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("AnimeLovers: HTTP ${r.code} untuk $path")
            r.body.string()
        }
        return json.parseToJsonElement(teks)
    }

    private fun ambil(path: String): JsonObject = ambilMentah(path).jsonObject

    private fun kirim(path: String, muatan: String): JsonObject {
        val req = Request.Builder()
            .url("$PROYEK$path")
            .header("User-Agent", UA_POST)
            .header("Accept", "application/json")
            .post(muatan.toRequestBody(JENIS_JSON.toMediaType()))
            .build()
        val teks = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("AnimeLovers: HTTP ${r.code} untuk $path")
            r.body.string()
        }
        return json.parseToJsonElement(teks).jsonObject
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.angka(k: String): Int? = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.daftar(k: String): JsonArray =
        this[k]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())

    private fun JsonObject.dataPertama(): JsonObject? =
        this["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()?.jsonObject

    // --- Pemetaan -----------------------------------------------------------

    private fun keManga(o: JsonObject): SManga = SManga.create().apply {
        url = o.str("url") ?: o.str("series_id") ?: ""
        title = o.str("judul") ?: url
        thumbnail_url = o.str("cover")
        description = o.str("sinopsis")
        author = o.str("studio") ?: o.str("author")
        genre = o.daftar("genre").joinToString(", ") { it.jsonPrimitive.contentOrNull ?: "" }
        status = when (o.str("status")?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed", "complete", "end" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun halaman(path: String, halamanIni: Int, penuh: Int): MangasPage {
        val isi = ambilMentah(path)
        val baris = if (isi is JsonArray) isi else isi.jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
        val manga = baris.map { keManga(it.jsonObject) }.filter { it.url.isNotBlank() }
        return MangasPage(manga, manga.size >= penuh)
    }

    // --- Katalog ------------------------------------------------------------

    /** Server tidak punya endpoint "populer"; `/rekomendasi.php` berisi 10 pilihan redaksi. */
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            val isi = ambilMentah("/rekomendasi.php").jsonArray.map { it.jsonObject }
            MangasPage(isi.map { keManga(it) }, false)
        }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable { halaman("/baruupload.php?page=$page", page, 25) }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val kunci = query.trim()
            if (kunci.isEmpty()) {
                fetchPopularManga(1).toBlocking().first()
            } else {
                val bungkus = ambil("/search.php?keyword=${java.net.URLEncoder.encode(kunci, "UTF-8")}" +
                    "&page=$page&per_page=20").dataPertama()
                val hasil = bungkus?.daftar("result")?.map { keManga(it.jsonObject) }
                    ?: emptyList()
                val total = bungkus?.angka("jumlah") ?: hasil.size
                MangasPage(hasil, page * 20 < total)
            }
        }
        .subscribeOn(Schedulers.io())

    // --- Detail & episode ---------------------------------------------------

    private fun detail(slug: String): JsonObject? =
        kirim(
            "/series.php?url=$slug",
            """{"get":"top","post_type":"1","post_id":"$slug","token":""}""",
        ).dataPertama()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val rinci = detail(manga.url) ?: return@fromCallable manga
            keManga(rinci).apply {
                url = manga.url
                // sinopsis pada daftar kadang terpotong; versi detail lebih lengkap
                description = rinci.str("sinopsis") ?: description
                genre = rinci.daftar("genre").joinToString(", ") { it.jsonPrimitive.contentOrNull ?: "" }
                author = rinci.str("author") ?: author
            }
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val rinci = detail(manga.url) ?: return@fromCallable emptyList()
            val daftar = rinci.daftar("chapter")
            var urut = 0
            daftar.map { el ->
                val o = el.jsonObject
                val ch = o.str("ch") ?: ""
                val kode = o.str("url") ?: ""
                val nomor = ch.toFloatOrNull()
                urut++
                SChapter.create().apply {
                    // slug + nomor diselipkan karena API stream meminta keduanya
                    url = "$kode|${manga.url}|$ch"
                    name = if (nomor != null) "Episode ${nomor.toInt()}" else "Episode $ch"
                    chapter_number = nomor ?: urut.toFloat()
                }
            }.sortedBy { it.chapter_number }
        }
        .subscribeOn(Schedulers.io())

    // --- Tautan video -------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val (kode, slug, ch) = chapter.url.split("|").let {
                Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" })
            }
            val muatan = """{"post_type":"2","post_id":"$kode","series_id":"$slug",""" +
                """"series_url":"$slug","episode":"$ch"}"""
            val streams = kirim("/series/episode/data.php?url=$kode", muatan)
                .dataPertama()?.let { runCatching { it["streams"]?.jsonObject }.getOrNull() }
                ?: return@fromCallable emptyList()

            val kandidat = streams.entries
                .flatMap { (reso, arr) ->
                    runCatching { arr.jsonArray }.getOrDefault(JsonArray(emptyList()))
                        .mapNotNull { s ->
                            val link = s.jsonObject.str("link") ?: return@mapNotNull null
                            if (!link.startsWith("http")) null else reso to link
                        }
                }
                .distinctBy { it.second }
                .sortedByDescending { tingkat(it.first) }

            // Sebagian tautan HLS sudah mati; buang yang tidak menjawab sebelum ditampilkan.
            val hidup = kandidat.filter { cepatHidup(it.second) }
            val dipakai = if (hidup.isNotEmpty()) hidup else kandidat
            dipakai.mapIndexed { i, (_, link) -> Page(i, link) }
        }
        .subscribeOn(Schedulers.io())

    private fun tingkat(reso: String): Int = reso.removeSuffix("p").toIntOrNull() ?: 0

    private fun cepatHidup(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA_POST)
            .header("Range", "bytes=0-1")
            .build()
        client.newCall(req).execute().use { it.isSuccessful || it.code == 416 }
    } catch (_: Exception) {
        false
    }
}