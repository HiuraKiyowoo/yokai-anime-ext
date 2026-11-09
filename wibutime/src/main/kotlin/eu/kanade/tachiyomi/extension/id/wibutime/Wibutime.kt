package eu.kanade.tachiyomi.extension.id.wibutime

import eu.kanade.tachiyomi.source.model.Filter
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
 * Sumber anime di atas backend Supabase milik aplikasi Wibutime.
 *
 * Pemetaan ke model Yokai:
 *   SManga   = satu anime        (url = /anime/<slug>)
 *   SChapter = satu episode      (url = /episode/<id>)
 *   Page     = satu tautan video (aplikasi membukanya di pemutar video)
 *
 * Kunci di bawah adalah kunci `anon` yang memang tertanam di APK Wibutime dan
 * hanya bisa membaca.
 */
private const val PROYEK = "https://paivcxbhxyytczbpyzla.supabase.co"
private const val KUNCI_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBhaXZjeGJoeHl5dGN6YnB5emxhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk1MDM0NjIsImV4cCI6MjEwNTA3OTQ2Mn0.UzXU9_Z8wBj_nE7FVukW1UB64j5mI2CoFKb0dTLssfc"

private const val KOLOM = "slug,title,synopsis,poster_url,genres,type,status,rating,year," +
    "studio,latest_episode_number,total_episodes"
private const val PER_HALAMAN = 30

/** Genre yang benar-benar dipakai katalog Wibutime (case-sensitive). */
private val GENRE = arrayOf(
    "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Harem", "Horror",
    "Isekai", "Mecha", "Military", "Music", "Mystery", "Psychological", "Romance",
    "School", "Sci-Fi", "Seinen", "Shounen", "Slice of Life", "Sports", "Supernatural",
    "Thriller", "OVA", "Movie", "Donghua",
)

class Wibutime : HttpSource() {

    override val name = "Wibutime"
    override val lang = "id"
    override val baseUrl = PROYEK
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // --- HTTP kecil ---------------------------------------------------------

    /**
     * Header API dipasang PER PERMINTAAN, bukan lewat headersBuilder().
     *
     * Aplikasi memakai `source.headers` juga saat mengunduh poster
     * (MangaCoverFetcher). Kalau Authorization ikut di header global, host
     * gambar menolak dengan 400 (Backblaze) dan semua poster tampil kosong.
     */
    private fun ambil(path: String): String {
        val req = Request.Builder()
            .url("$PROYEK/rest/v1/$path")
            .header("apikey", KUNCI_ANON)
            .header("Authorization", "Bearer $KUNCI_ANON")
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Wibutime: HTTP ${r.code} untuk $path")
            r.body.string()
        }
    }

    private fun ambilArray(path: String): JsonArray = json.parseToJsonElement(ambil(path)).jsonArray

    private fun JsonObject.str(kunci: String): String? = this[kunci]?.jsonPrimitive?.contentOrNull

    // --- Pemetaan baris -> model Yokai -------------------------------------

    private fun keManga(row: JsonObject): SManga = SManga.create().apply {
        val slug = row.str("slug") ?: ""
        url = "/anime/$slug"
        title = row.str("title") ?: slug
        thumbnail_url = row.str("poster_url")
        description = row.str("synopsis")
        author = row.str("studio")
        genre = row["genres"]?.let { el ->
            runCatching { el.jsonArray.joinToString(", ") { it.jsonPrimitive.contentOrNull ?: "" } }.getOrNull()
        }
        status = when (row.str("status")?.lowercase()) {
            "completed", "complete", "selesai" -> SManga.COMPLETED
            "ongoing", "airing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun daftar(path: String): MangasPage {
        val rows = ambilArray(path)
        return MangasPage(rows.map { keManga(it.jsonObject) }, rows.size >= PER_HALAMAN)
    }

    private fun berhalaman(pathDasar: String, halaman: Int): String =
        "$pathDasar&limit=$PER_HALAMAN&offset=${(halaman - 1) * PER_HALAMAN}"

    // --- Katalog ------------------------------------------------------------

    /**
     * Populer: urut rating tertinggi.
     *
     * Tabel `animes` tidak punya kolom jumlah tayangan; `views` hanya ada di RPC
     * get_trending_series yang berhenti di 10 besar dan tidak berpaginasi. Jadi
     * rating dipakai sebagai proksi popularitas supaya tetap bisa berhalaman.
     */
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable { daftar(berhalaman("animes?select=$KOLOM&order=rating.desc.nullslast", page)) }
        .subscribeOn(Schedulers.io())

    /** Terbaru: yang paling baru merilis episode. */
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            daftar(berhalaman("animes?select=$KOLOM&order=latest_episode_created_at.desc.nullslast", page))
        }
        .subscribeOn(Schedulers.io())

    // --- Filter -------------------------------------------------------------

    private class GenreFilter(opsi: Array<String>) : Filter.Select<String>("Genre", opsi)
    private class UrutFilter(opsi: Array<String>) : Filter.Select<String>("Urutan", opsi)

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(arrayOf("Semua") + GENRE),
        UrutFilter(arrayOf("Terbaru", "Rating tertinggi")),
    )

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable
        .fromCallable {
            val bersih = query.replace(Regex("[*,()]"), " ").trim()
            val genre = (filters.find { it is GenreFilter } as? GenreFilter)
                ?.takeIf { it.state > 0 }
                ?.let { it.values[it.state] }
            val urut = (filters.find { it is UrutFilter } as? UrutFilter)?.state ?: 0
            val urutan = if (urut == 1) "rating.desc.nullslast" else "latest_episode_created_at.desc.nullslast"

            val dasar = if (bersih.isEmpty()) {
                "animes?select=$KOLOM"
            } else {
                // alt_titles bertipe array -> ilike ditolak server (42883); hanya kolom teks
                "animes?select=$KOLOM&or=(title.ilike.*$bersih*,synopsis.ilike.*$bersih*)"
            }
            // genre bertipe array -> operator `cs` (contains)
            val saring = genre?.let { "&genres=cs.{$it}" } ?: ""
            daftar(berhalaman("$dasar$saring&order=$urutan", page))
        }
        .subscribeOn(Schedulers.io())

    // --- Detail & daftar episode -------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val slug = manga.url.removePrefix("/anime/")
            ambilArray("animes?slug=eq.$slug&select=$KOLOM&limit=1")
                .firstOrNull()
                ?.let { keManga(it.jsonObject) }
                ?: manga
        }
        .subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val slug = manga.url.removePrefix("/anime/")
            ambilArray(
                "episodes?anime_id=eq.$slug" +
                    "&select=id,episode_number,title,source_label,duration_minutes" +
                    "&order=episode_number.asc&limit=2000",
            ).mapIndexed { i, el ->
                val row = el.jsonObject
                val id = row.str("id") ?: ""
                val nomor = row.str("episode_number")?.toFloatOrNull() ?: (i + 1).toFloat()
                SChapter.create().apply {
                    url = "/episode/$id"
                    name = buildString {
                        append("Episode ${nomor.toInt()}")
                        row.str("title")?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    }
                    chapter_number = nomor
                    date_upload = 0L
                }
            }
        }
        .subscribeOn(Schedulers.io())

    // --- Tautan video -------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val id = chapter.url.removePrefix("/episode/")
            val row = ambilArray(
                "episodes?id=eq.$id&select=video_embed_url,video_qualities,video_qualities_fallback&limit=1",
            ).firstOrNull()?.jsonObject ?: return@fromCallable emptyList<Page>()

            val semua = LinkedHashMap<String, String>()
            row.str("video_embed_url")?.let { semua["embed"] = it }
            row["video_qualities"]?.let { el ->
                runCatching {
                    el.jsonObject.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { semua[k] = it } }
                }
            }

            // MEGA ditaruh paling akhir: butuh dekripsi AES di sisi klien
            semua.entries
                .filter { it.value.startsWith("http") }
                .sortedWith(compareBy({ it.value.contains("mega.nz") }, { -tingkat(it.key) }))
                .mapIndexed { i, e -> Page(i, e.value) }
        }
        .subscribeOn(Schedulers.io())

    private fun tingkat(kualitas: String): Int = when (kualitas.lowercase()) {
        "embed" -> 1080
        else -> kualitas.removeSuffix("p").toIntOrNull() ?: 0
    }
}