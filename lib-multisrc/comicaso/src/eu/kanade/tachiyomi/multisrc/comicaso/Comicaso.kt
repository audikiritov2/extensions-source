package eu.kanade.tachiyomi.multisrc.comicaso

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

abstract class Comicaso(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    // FIX #1: pakai parameter `source` eksplisit, bukan name.lowercase()
    // supaya "Medusa Scans" tidak jadi "medusa scans" (ada spasi)
    protected open val source: String,
) : HttpSource() {

    override val supportsLatest = true

    private val staticUrl = "https://static.comicaso.pro/static/$source"
    private val frontendUrl = "https://v3.comicaso.pro"

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        )
        .add("Accept", "application/json, */*")
        .add("Referer", "$frontendUrl/")

    // ── 1. POPULAR & LATEST ───────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET("$staticUrl/manga/index.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = json.parseToJsonElement(response.body.string()).jsonArray
        return MangasPage(arr.map { parseMangaFromIndex(it.jsonObject) }, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val arr = json.parseToJsonElement(response.body.string()).jsonArray
        // Sort by updated_at desc (field berisi Unix timestamp)
        val sorted = arr.sortedByDescending {
            it.jsonObject["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L
        }
        return MangasPage(sorted.map { parseMangaFromIndex(it.jsonObject) }, hasNextPage = false)
    }

    // ── 2. SEARCH + FILTER ────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$staticUrl/manga/index.json?query=$query"
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter   -> url += "&Tipe=${filter.values[filter.state]}"
                is StatusFilter -> url += "&Status=${filter.values[filter.state]}"
                else -> {}
            }
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val reqUrl      = response.request.url
        val query       = reqUrl.queryParameter("query")?.lowercase().orEmpty()
        val tipeFilter  = reqUrl.queryParameter("Tipe") ?: "Semua"
        val statusFilter = reqUrl.queryParameter("Status") ?: "Semua"

        val arr = json.parseToJsonElement(response.body.string()).jsonArray

        val mangas = arr
            .map { it.jsonObject }
            .filter { obj ->
                val title  = obj["title"]?.jsonPrimitive?.content?.lowercase().orEmpty()
                val slug   = obj["slug"]?.jsonPrimitive?.content?.lowercase().orEmpty()
                val tipe   = obj["type"]?.jsonPrimitive?.content?.lowercase().orEmpty()
                val status = obj["status"]?.jsonPrimitive?.content?.lowercase().orEmpty()

                val okQuery  = query.isEmpty() || title.contains(query) || slug.contains(query)
                val okType   = tipeFilter == "Semua" || tipe == tipeFilter.lowercase()
                val okStatus = statusFilter == "Semua" || status == statusFilter.lowercase()

                okQuery && okType && okStatus
            }
            .map { parseMangaFromIndex(it) }

        return MangasPage(mangas, hasNextPage = false)
    }

    class TypeFilter : Filter.Select<String>(
        "Tipe",
        arrayOf("Semua", "Manga", "Manhua", "Manhwa"),
    )

    class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("Semua", "Ongoing", "Completed"),
    )

    override fun getFilterList() = FilterList(TypeFilter(), StatusFilter())

    // ── 3. MANGA DETAIL ───────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$staticUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        return SManga.create().apply {
            title         = obj["title"]?.jsonPrimitive?.content.orEmpty()
            thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content
            author        = obj["author"]?.jsonPrimitive?.content.orEmpty()
            artist        = obj["artist"]?.jsonPrimitive?.content.orEmpty()
            genre         = obj["genres"]?.jsonArray
                ?.joinToString(", ") { it.jsonPrimitive.content }
                .orEmpty()
            description   = obj["synopsis"]?.jsonPrimitive?.content
                ?.replace(Regex("<[^>]*>"), "")  // strip HTML tags
                .orEmpty()
            status = when (obj["status"]?.jsonPrimitive?.content?.lowercase()) {
                "ongoing", "berlangsung"        -> SManga.ONGOING
                "completed", "selesai", "tamat" -> SManga.COMPLETED
                "hiatus", "dropped"             -> SManga.ON_HIATUS
                else                            -> SManga.UNKNOWN
            }
        }
    }

    // ── 4. CHAPTER LIST ───────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request =
        mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject

        // Ambil mangaSlug dari URL request
        // URL: https://static.../static/comicazen/manga/{slug}.json
        val mangaSlug = response.request.url.pathSegments.last().removeSuffix(".json")

        val chaptersArr = obj["chapters"]?.jsonArray
            ?: obj["chapter_list"]?.jsonArray
            ?: return emptyList()  // FIX: jangan throw, kembalikan kosong

        return chaptersArr.map { item ->
            val ch = item.jsonObject
            // FIX: ambil slug (string), BUKAN id (angka)
            // Kalau pakai id numerik → URL jadi /chapter/slug/114480.json → 404 → crash
            val chSlug = ch["slug"]?.jsonPrimitive?.content
                ?: ch["chapter"]?.jsonPrimitive?.content
                ?: return@map null  // skip kalau tidak ada slug

            SChapter.create().apply {
                name = ch["title"]?.jsonPrimitive?.content
                    ?: ch["chapter_title"]?.jsonPrimitive?.content
                    ?: chSlug.replace("-", " ")
                        .replaceFirstChar { it.uppercaseChar() }

                url = "/chapter/$mangaSlug/$chSlug.json"

                // FIX #2: API return Unix timestamp (detik), BUKAN string tanggal
                // parseChapterDate lama crash karena coba parse "1779708399" sebagai "yyyy-MM-dd"
                date_upload = parseUnixOrDateString(
                    ch["date"]?.jsonPrimitive?.content
                        ?: ch["updated_at"]?.jsonPrimitive?.content
                        ?: ch["created_at"]?.jsonPrimitive?.content
                        ?: "",
                )

                chapter_number = chSlug.removePrefix("chapter-").toFloatOrNull() ?: -1f
            }
        }.filterNotNull().reversed()  // chapter terbaru di atas
    }

    // FIX #2: Handle dua format: Unix timestamp (angka) ATAU string tanggal
    private fun parseUnixOrDateString(raw: String): Long {
        if (raw.isEmpty()) return 0L
        // Coba sebagai Unix timestamp (angka detik) dulu
        raw.toLongOrNull()?.let { unix ->
            // Sanity check: Unix timestamp valid (antara tahun 2000–2100)
            if (unix in 946_684_800L..4_102_444_800L) {
                return unix * 1000L  // konversi detik → milidetik
            }
        }
        // Fallback: coba parse sebagai string tanggal "yyyy-MM-dd"
        return try {
            val clean = raw.substringBefore("T")
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(clean)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ── 5. PAGE LIST (GAMBAR) ─────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$staticUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val element = json.parseToJsonElement(response.body.string())

        // FIX #3: Urutan pengecekan yang benar
        // Response chapter selalu JsonObject dengan field "images"
        // Periksa JsonObject dulu, JsonArray hanya fallback
        val images: JsonArray? = when {
            element is JsonArray -> element
            else -> {
                val obj = element.jsonObject
                obj["images"]?.jsonArray
                    ?: obj["pages"]?.jsonArray
            }
        }

        if (images == null || images.isEmpty()) return emptyList()

        return images.mapIndexed { index, item ->
            Page(index = index, imageUrl = item.jsonPrimitive.content)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Tidak digunakan")

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun parseMangaFromIndex(obj: kotlinx.serialization.json.JsonObject) =
        SManga.create().apply {
            title         = obj["title"]?.jsonPrimitive?.content.orEmpty()
            url           = "/manga/${obj["slug"]?.jsonPrimitive?.content}.json"
            thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content
        }
}
