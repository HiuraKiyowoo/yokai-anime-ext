package eu.kanade.tachiyomi.extension.id.dongha

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Dongha — katalog donghua/anime lewat gateway terenkripsi (bukan HTML scraping).
 *
 * Butuh akun. Isi email & sandi di Pengaturan ekstensi (ikon gerigi pada ekstensi ini);
 * extension akan login sendiri lalu menyegarkan token secara otomatis.
 */
class Dongha : HttpSource(), ConfigurableSource {

    override val name = "Dongha"
    override val lang = "id"
    override val baseUrl = "https://dongha.id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", Gateway.UA)
                .header("Accept", "application/json, */*")
                .build()
            chain.proceed(req)
        }
        .build()

    // --- Kredensial & sesi ---------------------------------------------------

    private val prefs: SharedPreferences
        get() = getSourcePreferences()

    private fun email() = prefs.getString(KEY_EMAIL, null)?.trim().orEmpty()
    private fun sandi() = prefs.getString(KEY_PASSWORD, null)?.trim().orEmpty()

    @Volatile private var tokenMemori: String? = null
    @Volatile private var kedaluwarsa: Long = 0L

    private fun pastikanKredensial() {
        if (email().isEmpty() || sandi().isEmpty()) {
            throw Exception(
                "Isi email & sandi Dongha dulu: Buka daftar ekstensi → Dongha → ikon gerigi.",
            )
        }
    }

    /** Token akses yang masih sah; login/refresh otomatis bila perlu. */
    @Synchronized
    private fun tokenAktif(): String {
        pastikanKredensial()
        tokenMemori?.let { if (System.currentTimeMillis() < kedaluwarsa - 60_000L) return it }
        prefs.getString(KEY_TOKEN, null)?.let {
            if (System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0L) - 60_000L) {
                tokenMemori = it
                kedaluwarsa = prefs.getLong(KEY_EXPIRES, 0L)
                return it
            }
        }
        // 1) coba refresh token
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (!refresh.isNullOrBlank()) {
            try {
                val data = gateway(null).post("/auth/refresh", JSONObject().put("refresh_token", refresh).toString())
                return simpanSesi(data)
            } catch (_: Exception) {
                // jatuh ke login ulang
            }
        }
        // 2) login ulang dengan email + sandi
        val data = gateway(null).post(
            "/auth-login",
            JSONObject().put("email", email()).put("password", sandi()).toString(),
        )
        return simpanSesi(data)
    }

    private fun simpanSesi(balasan: JSONObject): String {
        val data = balasan.optJSONObject("data") ?: balasan
        val access = data.optString("access_token")
        if (access.isBlank()) throw Exception("Login Dongha gagal: token tidak diterima")
        val refresh = data.optString("refresh_token")
        val umur = data.optLong("expires", 86_400_000L)
        val sampai = System.currentTimeMillis() + umur
        prefs.edit()
            .putString(KEY_TOKEN, access)
            .putString(KEY_REFRESH, refresh.ifBlank { prefs.getString(KEY_REFRESH, null) })
            .putLong(KEY_EXPIRES, sampai)
            .apply()
        tokenMemori = access
        kedaluwarsa = sampai
        return access
    }

    private fun gateway(token: String?) = Gateway(client, token)

    /** Satu panggilan gateway terautentikasi; mengembalikan objek JSON lengkap. */
    private fun ambil(path: String): JSONObject {
        val g = gateway(tokenAktif())
        return JSONObject(g.get(path))
    }

    private fun data(path: String): JSONObject = ambil(path).optJSONObject("data") ?: JSONObject()

    private fun daftar(path: String): JSONArray = ambil(path).optJSONArray("data") ?: JSONArray()

    // --- Pemetaan ------------------------------------------------------------

    private fun JSONObject.str(kunci: String): String? =
        optString(kunci).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.bentukSeriesSlug(): String =
        str("slug") ?: error("Series tanpa slug tidak bisa dibuka")

    private fun keManga(o: JSONObject): SManga = SManga.create().apply {
        url = "/series/${o.bentukSeriesSlug()}"
        title = o.str("title") ?: o.str("title_english") ?: "(tanpa judul)"
        thumbnail_url = Gateway.aset(o.str("poster_image"))
        genre = listOfNotNull(
            o.optJSONObject("category")?.str("name"),
            o.optString("original_language").takeIf { it.isNotBlank() }?.uppercase(),
            o.str("content_rating"),
        ).joinToString(", ").takeIf { it.isNotBlank() }
        status = when (o.optString("status").lowercase()) {
            "published", "ongoing", "airing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            "hiatus", "on_hold" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = false
    }

    // --- Populer / terbaru / cari -------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val arr = daftar("/explore?category=donghua&sort=popular&limit=24&page=$page")
        MangasPage(arr.map { keManga(it as JSONObject) }, arr.length() >= 24)
    }.subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val arr = daftar("/latest-episodes?category=donghua&limit=24&page=$page")
        val hasil = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // Baris "terbaru" berbentuk ringkasan series + latest_episode; ubah ke bentuk series.
            if (o.has("series_slug")) {
                keManga(JSONObject().apply {
                    put("slug", o.str("series_slug"))
                    put("title", o.str("series_title"))
                    put("poster_image", o.str("poster_image"))
                    put("category", JSONObject().put("name", "Donghua"))
                })
            } else {
                keManga(o)
            }
        }.distinctBy { it.url }
        MangasPage(hasil, arr.length() >= 24)
    }.subscribeOn(Schedulers.io())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val offset = (page - 1) * 24
            val urutan = (filters.find { it is Urutkan } as? Urutkan)?.kunci() ?: "popular"
            val genre = (filters.find { it is Genre } as? Genre)?.nilai()?.takeIf { it.isNotBlank() }
            val path = buildString {
                append("/items/series?limit=24&offset=$offset&sort=")
                append(if (urutan == "latest") "-date_created" else "-total_views")
                append("&fields=id,title,slug,poster_image.filename_disk,status,content_rating,original_language,category.name,category.slug")
                if (query.isNotBlank()) append("&search=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                if (genre != null) append("&filter[category][slug][_eq]=").append(genre)
            }
            val arr = daftar(path)
            MangasPage(arr.map { keManga(it as JSONObject) }, arr.length() >= 24)
        }.subscribeOn(Schedulers.io())

    // --- Detail --------------------------------------------------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val slug = manga.url.substringAfterLast("/")
        val arr = daftar("/items/series?limit=1&filter[slug][_eq]=$slug&fields=id,title,title_english,slug,status,synopsis,content_rating,original_language,poster_image.filename_disk,backdrop_image.filename_disk,category.name,category.slug,genres.genres_id.name")
        val o = arr.optJSONObject(0) ?: throw Exception("Series tidak ditemukan: $slug")
        keManga(o).apply {
            description = buildString {
                o.str("synopsis")?.let { append(it.trim()).append("\n\n") }
                val genre = o.optJSONArray("genres")
                if (genre != null && genre.length() > 0) {
                    val nama = (0 until genre.length()).mapNotNull { i ->
                        genre.optJSONObject(i)?.optJSONObject("genres_id")?.str("name")
                    }
                    if (nama.isNotEmpty()) append("Genre: ").append(nama.joinToString(", ")).append("\n")
                }
                append("Sumber: Dongha")
            }
            initialized = true
        }
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.url.substringAfterLast("/")
        val series = daftar(
            "/items/series?limit=1&filter[slug][_eq]=$slug&fields=id,title,slug,seasons.id,seasons.season_number,seasons.title",
        ).optJSONObject(0) ?: throw Exception("Series tidak ditemukan: $slug")

        val musim = series.optJSONArray("seasons") ?: JSONArray()
        val semua = mutableListOf<SChapter>()
        for (i in 0 until musim.length()) {
            val s = musim.optJSONObject(i) ?: continue
            val sid = s.str("id") ?: continue
            val nomorMusim = s.optInt("season_number", 1)
            val eps = daftar(
                "/items/episodes?limit=500&sort=number&filter[season_id][_eq]=$sid" +
                    "&fields=id,number,title,duration,air_date,is_premium",
            )
            for (j in 0 until eps.length()) {
                val e = eps.optJSONObject(j) ?: continue
                val id = e.str("id") ?: continue
                val nomor = e.optString("number").removeSuffix(".0").ifBlank { "${j + 1}" }
                semua += SChapter.create().apply {
                    url = "/episode/$id"
                    name = buildString {
                        if (musim.length() > 1) append("S$nomorMusim ")
                        append("E").append(nomor)
                        e.str("title")?.let { if (it.isNotBlank() && it != "Episode $nomor") append(" — $it") }
                    }
                    chapter_number = nomor.toFloatOrNull() ?: (j + 1f)
                    date_upload = tanggal(e.str("air_date"))
                }
            }
        }
        semua.sortedBy { it.chapter_number }
    }.subscribeOn(Schedulers.io())

    private fun tanggal(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(iso.take(19))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // --- Halaman (tautan video) ---------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val id = chapter.url.substringAfterLast("/")
        val data = data("/media/episodes/$id/sources")
        val videos = data.optJSONArray("videos") ?: JSONArray()
        val halaman = (0 until videos.length()).mapNotNull { i ->
            val v = videos.optJSONObject(i) ?: return@mapNotNull null
            val url = Gateway.video(
                v.str("hls_url") ?: v.str("r2_url") ?: v.str("streaming_url") ?: v.str("backup_url"),
            ) ?: return@mapNotNull null
            val kualitas = v.str("quality") ?: "?"
            "$kualitas|$url"
        }
            .distinctBy { it.substringAfter("|") }
            .sortedByDescending { it.substringBefore("|").removeSuffix("p").toIntOrNull() ?: 0 }
            .mapIndexed { i, gabung -> Page(i, gabung.substringAfter("|")) }

        if (halaman.isEmpty()) throw Exception("Tidak ada tautan video untuk episode ini.")
        halaman
    }.subscribeOn(Schedulers.io())

    // --- Filter --------------------------------------------------------------

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Tekan 'Filter' untuk memakai genre; kosongkan untuk semua"),
        Urutkan(),
        Genre(),
    )

    private class Urutkan : Filter.Select<String>(
        "Urutkan",
        arrayOf("Populer", "Terbaru"),
    ) {
        fun kunci() = if (state == 1) "latest" else "popular"
    }

    private class Genre : Filter.Text("Genre (slug, mis. action)") {
        fun nilai() = state.trim()
    }

    // --- Pengaturan ----------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = KEY_EMAIL
            title = "Email akun Dongha"
            summary = "Email yang dipakai untuk login"
            setDefaultValue(prefs.getString(KEY_EMAIL, ""))
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = KEY_PASSWORD
            title = "Sandi akun Dongha"
            summary = "Disimpan hanya di perangkat ini"
            setDefaultValue(prefs.getString(KEY_PASSWORD, ""))
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = KEY_TOKEN
            title = "Token (opsional)"
            summary = "Kosongkan saja; diisi otomatis setelah login berhasil"
        }.also(screen::addPreference)
    }

    companion object {
        private const val KEY_EMAIL = "dongha_email"
        private const val KEY_PASSWORD = "dongha_password"
        private const val KEY_TOKEN = "dongha_token"
        private const val KEY_REFRESH = "dongha_refresh"
        private const val KEY_EXPIRES = "dongha_expires"
    }
}