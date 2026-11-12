package eu.kanade.tachiyomi.extension.id.kuramanime

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import rx.Observable
import rx.schedulers.Schedulers
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Kuramanime (v20.kuramanime.ing) — anime sub Indo.
 *
 * Katalog dirender AJAX: daftar di `/quick/ongoing?page=N` (header X-Requested-With),
 * pencarian di `/quicksearch/get?q=` (balas JSON {html: "..."}).
 *
 * STREAM — Halaman episode tidak memuat tautan video di HTML awal. Alur yang terbukti
 * (tiga langkah, WAJIB satu sesi cookie yang sama):
 *
 *   1. GET halaman episode -> cookie sesi + token CSRF dari `<meta name="csrf-token">`
 *   2. GET `$baseUrl/assets/<BERKAS_TOKEN>.txt` dengan header `X-Fuck-ID: <kunci>:<token>`
 *      -> balas token halaman (10 karakter)
 *   3. POST halaman episode yang sama dengan query
 *      `?<PARAM_TOKEN>=<token>&<PARAM_SERVER>=kuramadrive&page=1`,
 *      header `X-CSRF-TOKEN` dari langkah 1, body form `authorization=<AUTH>`
 *      -> HTML memuat `<source id="source720|480|360" src="https://<host>.my.id/kdrive/...mp4?...">`
 *
 * Tautan `.my.id/kdrive/...` itu bisa diminta langsung oleh pemutar (uji: HTTP 206,
 * `video/mp4`, `accept-ranges: bytes`) — tidak butuh Referer, token, maupun service worker.
 * Bentuk `/0:/...` (tanpa `/kdrive/`) adalah URL UNDUHAN, jangan dipakai.
 */
/**
 * Jalur cadangan DNS lewat DNS-over-HTTPS.
 *
 * Sebagian ISP Indonesia memblokir `*.kuramanime.ing` pada resolver operator, sehingga
 * aplikasi membalas `No address associated with hostname` (UnknownHostException) padahal
 * situsnya hidup — terukur: DNS sistem gagal, 8.8.8.8 & DoH `dns.google` menjawab
 * `104.21.66.92` / `172.67.158.107` dan situsnya balas HTTP 200.
 *
 * Urutannya: coba resolver sistem dulu (cepat & normal), baru tanya DoH kalau gagal.
 * Hasilnya disimpan sebentar supaya tidak ada satu kueri DoH per permintaan.
 * Catatan: ini hanya menolong permintaan yang lewat `client` sumber (katalog, detail,
 * episode, stream). Poster yang diunduh aplikasi dan pemutar video memakai DNS sistem —
 * untuk keduanya jalannya adalah Private DNS di setelan ponsel.
 */
private class DohKuramanime : Dns {
    private val kue = LinkedHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val sekarang = System.currentTimeMillis()
        kue[hostname]?.takeIf { sekarang - it.first < 5 * 60_000L }?.let { return it.second }

        val alamat = runCatching { tanyaDoh(hostname) }.getOrNull().orEmpty()
        if (alamat.isEmpty()) throw UnknownHostException("Kuramanime: $hostname tidak bisa di-resolve")
        kue[hostname] = sekarang to alamat
        return alamat
    }

    /** Pertanyaan DoH memakai klien terpisah tanpa Dns kustom supaya tidak rekursif. */
    private fun tanyaDoh(host: String): List<InetAddress> {
        val klien = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val permintaan = Request.Builder()
            .url("https://dns.google/resolve?name=${URLEncoder.encode(host, "UTF-8")}&type=A")
            .header("accept", "application/dns-json")
            .build()
        val isi = klien.newCall(permintaan).execute().use { it.body?.string().orEmpty() }
        val jawab = JSONObject(isi).optJSONArray("Answer") ?: return emptyList()
        return (0 until jawab.length()).mapNotNull { i ->
            val data = jawab.optJSONObject(i)?.optString("data").orEmpty()
            if (data.isBlank()) null else runCatching { InetAddress.getByName(data) }.getOrNull()
        }
    }
}

class Kuramanime : HttpSource() {

    override val name = "Kuramanime"

    override val lang = "id"

    override val baseUrl = "https://v20.kuramanime.ing"

    override val supportsLatest = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", ua)
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")

    /** Semua permintaan sumber lewat klien ini, jadi DoH-nya ikut ke katalog & episode. */
    override val client: OkHttpClient = super.client.newBuilder()
        .dns(DohKuramanime())
        .build()

    // ---------- HTTP ----------

    private fun ambil(alamat: String, ajax: Boolean = false): String {
        val p = Request.Builder().url(alamat)
            .header("User-Agent", ua)
            .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "$baseUrl/")
        if (ajax) p.header("X-Requested-With", "XMLHttpRequest")
        client.newCall(p.build()).execute().use { r -> return r.body?.string().orEmpty() }
    }

    private fun doc(alamat: String, ajax: Boolean = false): Document =
        Jsoup.parse(ambil(alamat, ajax), alamat)

    private fun bersih(t: String) = Parser.unescapeEntities(t, false).trim()

    // ---------- Katalog ----------

    /** Kartu daftar: `div.filter__gallery a[href]` — judul `h5`, poster `div[data-setbg]`. */
    private fun dariKartu(d: Document): List<SManga> = d.select("div.filter__gallery a[href]")
        .mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { a.attr("href") }
            if (!url.contains("/anime/")) return@mapNotNull null
            val judul = a.selectFirst("h5")?.text()?.trim()?.removePrefix("#")
            if (judul.isNullOrBlank()) {
                // sebagian kartu tidak punya <h5>; pakai slug sebagai cadangan
                val slug = url.substringBefore("/episode/").trimEnd('/').substringAfterLast("/")
                if (slug.isBlank()) return@mapNotNull null
                SManga.create().apply {
                    this.url = seriDari(url)
                    title = slug.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    thumbnail_url = a.selectFirst("div[data-setbg]")?.attr("data-setbg")
                }
            } else {
                SManga.create().apply {
                    this.url = seriDari(url)
                    title = judul
                    thumbnail_url = a.selectFirst("div[data-setbg]")?.attr("data-setbg")
                }
            }
        }
        .distinctBy { it.url }

    /** Halaman kartu menunjuk ke episode; manga disimpan sebagai URL seri. */
    private fun seriDari(url: String): String =
        url.substringBefore("/episode/").trimEnd('/') + "/"

    private fun alamatList(page: Int) = "$baseUrl/quick/ongoing?page=$page"

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            // Kuramanime hanya punya satu urutan daftar (menurut pembaruan) — dipakai untuk keduanya.
            val isi = dariKartu(doc(alamatList(page), ajax = true))
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable
        .fromCallable {
            val isi = dariKartu(doc(alamatList(page), ajax = true))
            MangasPage(isi, isi.isNotEmpty())
        }
        .subscribeOn(Schedulers.io())

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = Observable
        .fromCallable {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val mentah = ambil("$baseUrl/quicksearch/get?q=$q", ajax = true)
            val html = runCatching { JSONObject(mentah).optString("html") }.getOrDefault("")
            val d = Jsoup.parse(html, baseUrl)
            val isi = d.select("a.search__result__anchor[href], a[href*=/anime/]")
                .mapNotNull { a ->
                    val url = a.absUrl("href").ifBlank { a.attr("href") }
                    if (!url.contains("/anime/")) return@mapNotNull null
                    val judul = a.selectFirst("h5, h4, .search__result__title")?.text()?.trim()?.removePrefix("#")
                        ?: url.substringBefore("/episode/").trimEnd('/').substringAfterLast("/")
                            .split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    SManga.create().apply {
                        this.url = seriDari(url)
                        title = judul
                        thumbnail_url = a.selectFirst("div[data-setbg]")?.attr("data-setbg")
                    }
                }
                .distinctBy { it.url }
            MangasPage(isi, false)
        }
        .subscribeOn(Schedulers.io())

    // ---------- Detail ----------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            val alamat = "$baseUrl${manga.url}"
            val d = doc(alamat)
            SManga.create().apply {
                url = manga.url
                title = d.selectFirst("div.anime__details__title h3")
                    ?.text()?.trim()?.removePrefix("#") ?: manga.title
                thumbnail_url = d.selectFirst("meta[property=og:image]")?.attr("content")
                description = d.selectFirst("p#synopsisField")?.text()?.trim()
                    ?: d.selectFirst("meta[name=description]")?.attr("content")?.let { bersih(it) }
                author = d.select("div.anime__details__widget a[href*=/properties/studio/]")
                    .joinToString(", ") { it.text().trim() }.ifBlank { null }
                genre = d.select("div.anime__details__widget a[href*=/properties/genre/]")
                    .joinToString(", ") { it.text().trim() }.ifBlank { null }
                initialized = true
            }
        }
        .subscribeOn(Schedulers.io())

    // ---------- Daftar episode ----------

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            val alamat = "$baseUrl${manga.url}"
            val d = doc(alamat)
            val hasil = LinkedHashMap<String, SChapter>()
            d.select("div#episodeListsSection a[href], div#episodeBatchListsSection a[href]").forEach { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                val nomor = Regex("""/episode/(\d+)""").find(href)?.groupValues?.get(1)
                if (nomor == null) return@forEach
                val url = href.removePrefix(baseUrl)
                hasil.putIfAbsent(
                    url,
                    SChapter.create().apply {
                        this.url = url
                        // WAJIB beda dari judul seri: ChapterRecognition membuang judul seri
                        // dari nama bab, jadi nama yang sama akan tampil kosong.
                        name = "Episode $nomor"
                        chapter_number = nomor.toFloatOrNull() ?: -1f
                        date_upload = 0L
                    },
                )
            }
            hasil.values.sortedBy { it.chapter_number }
        }
        .subscribeOn(Schedulers.io())

    // ---------- Tautan video (alur 3 langkah) ----------

    private class JarKue : CookieJar {
        private val simpan = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val daftar = simpan.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { baru ->
                daftar.removeAll { it.name == baru.name }
                daftar.add(baru)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = simpan[url.host].orEmpty()
    }

    private fun klienSesi(): okhttp3.OkHttpClient = client.newBuilder()
        .cookieJar(JarKue())
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sumberDari(html: String, alamat: String): List<Pair<String, String>> {
        val d = Jsoup.parse(html, alamat)
        val kandidat = LinkedHashMap<String, String>() // url -> kualitas
        d.select("source[id^=source], video#player source, source[src]").forEach { s ->
            val u = bersih(s.attr("src"))
            if (u.isBlank() || !u.startsWith("http")) return@forEach
            val q = s.attr("size").ifBlank { s.id().removePrefix("source") }
            kandidat.putIfAbsent(u, q)
        }
        if (kandidat.isEmpty()) {
            val v = d.selectFirst("video[src], video#player")
            val u = v?.attr("src").orEmpty()
            if (u.startsWith("http")) kandidat.putIfAbsent(bersih(u), "")
        }
        return kandidat.entries.map { it.key to it.value }
    }

    /**
     * DoodStream (server cadangan): halaman /e/<id> memuat `/pass_md5/<token>`;
     * meminta jalur itu (Referer = halaman embed) mengembalikan dasar URL MP4,
     * lalu tinggal ditempeli 10 karakter acak + token. Terbukti balas 206 video/mp4.
     */
    /** GET dengan Referer khusus (dipakai alur embed DoodStream). */
    private fun teksReferer(url: String, referer: String): String = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "id-ID,id;q=0.9")
            .header("Referer", referer)
            .build(),
    ).execute().use { r ->
        if (!r.isSuccessful) throw Exception("Kuramanime: HTTP ${r.code} untuk $url")
        r.body?.string().orEmpty()
    }

    private fun dariDood(embed: String): String? {
        val host = embed.substringBefore("/e").ifBlank { return null }
        val html = runCatching { teksReferer(embed, "$baseUrl/") }.getOrNull() ?: return null
        val jalur = Regex("""/pass_md5/[^"'<>\\]+""").find(html)?.value ?: return null
        val dasar = runCatching { teksReferer("$host$jalur", embed).trim() }.getOrNull() ?: return null
        if (dasar.length < 30 || !dasar.startsWith("http")) return null
        val token = jalur.substringAfterLast('-')
        val acak = (1..10)
            .map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }
            .joinToString("")
        return "$dasar$acak?token=$token&expiry=${System.currentTimeMillis()}"
    }

    /** Server yang ditawarkan halaman episode; kuramadrive dulu (paling bersih). */
    private fun serverDari(html: String): List<String> {
        val d = Jsoup.parse(html, baseUrl)
        val opsi = d.select("select#changeServer option")
            .map { it.attr("value").trim() }
            .filter { it.isNotBlank() }
        return (listOf("kuramadrive") + opsi).distinct()
    }

    /** Satu permintaan ke satu server; balik daftar (url, kualitas). */
    private fun kandidatServer(
        klien: okhttp3.OkHttpClient,
        alamat: String,
        csrf: String,
        token: String,
        server: String,
    ): List<Pair<String, String>> {
        val alamatPost = "$alamat?$PARAM_TOKEN=${URLEncoder.encode(token, "UTF-8")}" +
            "&$PARAM_SERVER=${URLEncoder.encode(server, "UTF-8")}&page=1"
        val html = klien.newCall(
            Request.Builder().url(alamatPost)
                .post(FormBody.Builder().add("authorization", AUTH).build())
                .header("User-Agent", ua)
                .header("Accept-Language", "id-ID,id;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Request-ID", "a1b2c3")
                .header("Referer", alamat)
                .build(),
        ).execute().use { it.body?.string().orEmpty() }

        val hasil = LinkedHashMap<String, String>()
        sumberDari(html, alamat).forEach { (u, q) -> hasil.putIfAbsent(u, q) }
        if (hasil.isEmpty()) {
            // server lain memakai <iframe> pemutar pihak ketiga
            Jsoup.parse(html, alamat).select("iframe[src]").forEach { f ->
                val u = bersih(f.attr("src"))
                val dood = u.contains("playmogo") || u.contains("/dood") || u.contains("doodstream")
                val lurus = if (dood) runCatching { dariDood(u) }.getOrNull() else null
                if (!lurus.isNullOrBlank()) hasil.putIfAbsent(lurus, "")
            }
        }
        return hasil.entries.map { it.key to it.value }
    }

    /** Probe ringan: 200/206/416 = berkas ada. Host yang sudah mati akan gagal di sini. */
    private fun hidup(url: String): Boolean = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", ua)
                .header("Accept-Language", "id-ID,id;q=0.9")
                .header("Referer", "$baseUrl/")
                .header("Range", "bytes=0-1023")
                .build(),
        ).execute().use { it.isSuccessful || it.code == 416 }
    }.getOrDefault(false)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val alamat = "$baseUrl${chapter.url}"
            val klien = klienSesi()

            // 1) cookie + CSRF
            val halaman = klien.newCall(
                Request.Builder().url(alamat)
                    .header("User-Agent", ua)
                    .header("Accept-Language", "id-ID,id;q=0.9")
                    .header("Referer", "$baseUrl/")
                    .build(),
            ).execute().use { it.body?.string().orEmpty() }

            val csrf = Regex("""name="csrf-token" content="([^"]+)"""").find(halaman)
                ?.groupValues?.get(1).orEmpty()

            // 2) token halaman
            val token = klien.newCall(
                Request.Builder().url("$baseUrl/assets/$BERKAS_TOKEN")
                    .header("User-Agent", ua)
                    .header("X-Fuck-ID", "$KUNCI_TOKEN:$TOKEN_TOKEN")
                    .header("X-Request-ID", "a1b2c3")
                    .header("X-Request-Index", "0")
                    .header("Referer", "$baseUrl/")
                    .build(),
            ).execute().use { it.body?.string().orEmpty() }.trim()

            if (token.isBlank()) return@fromCallable emptyList()

            // 3) tanya tiap server sampai dapat yang hidup
            val semua = LinkedHashMap<String, String>()
            for (srv in serverDari(halaman)) {
                val kandidat = runCatching { kandidatServer(klien, alamat, csrf, token, srv) }
                    .getOrDefault(emptyList())
                kandidat.forEach { (u, q) -> semua.putIfAbsent(u, if (q.isBlank()) srv else q) }
                if (semua.keys.any { hidup(it) }) break
            }

            val daftar = semua.entries.map { it.key to it.value }
            if (daftar.size <= 1) return@fromCallable daftar.mapIndexed { i, e -> Page(i, e.first) }

            // tautan hidup dulu + kualitas tertinggi lebih awal
            val (nyala, mati) = daftar.partition { hidup(it.first) }
            val urut = { e: Pair<String, String> -> e.second.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            (nyala.sortedByDescending(urut) + mati.sortedByDescending(urut))
                .mapIndexed { i, e -> Page(i, e.first) }
        }
        .subscribeOn(Schedulers.io())

    override fun getFilterList(): FilterList = FilterList()

    companion object {
        // Nilai dari env situs (MIX_*) — statis per deploy. Kalau situs ganti versi,
        // ambil ulang dari halaman episode/player.
        private const val BERKAS_TOKEN = "Ks6sqSgloPTlHMl.txt"
        private const val PARAM_TOKEN = "Ub3BzhijicHXZdv"
        private const val PARAM_SERVER = "C2XAPerzX1BM7V9"
        private const val KUNCI_TOKEN = "rFj8fp1nxMuNfKq"
        private const val TOKEN_TOKEN = "ijjAwj6Jze0kscx"
        private const val AUTH = "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur"
    }
}
