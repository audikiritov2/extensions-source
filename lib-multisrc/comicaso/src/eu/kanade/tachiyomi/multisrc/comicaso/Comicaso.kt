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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

abstract class Comicaso(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource() {

    override val supportsLatest = true

    private val sourceName = name.lowercase()
    private val staticUrl = "https://static.comicaso.pro/static/$sourceName"
    private val frontendUrl = "https://v3.comicaso.pro"

    private val json: Json by injectLazy()

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json, */*")
        .add("Referer", "$frontendUrl/")

    // 1. POPULAR & LATEST MANGA
    override fun popularMangaRequest(page: Int): Request = GET("$staticUrl/manga/index.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonArray = json.parseToJsonElement(jsonString).jsonArray

        val mangas = jsonArray.map { item ->
            SManga.create().apply {
                val obj = item.jsonObject
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = "/manga/${obj["slug"]?.jsonPrimitive?.content}.json"
                thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // 2. FITUR PENCARIAN & FILTER CERDAS (SUDAH FIX)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var filterUrl = "$staticUrl/manga/index.json?query=$query"
        filters.forEach { filter ->
            if (filter is TypeFilter) {
                filterUrl += "&Tipe=${filter.values[filter.state]}"
            }
            if (filter is StatusFilter) {
                filterUrl += "&Status=${filter.values[filter.state]}"
            }
        }
        return GET(filterUrl, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url
        val query = requestUrl.queryParameter("query")?.lowercase() ?: ""
        val tipeFilter = requestUrl.queryParameter("Tipe") ?: "Semua"
        val statusFilter = requestUrl.queryParameter("Status") ?: "Semua"

        val jsonString = response.body.string()
        val jsonArray = json.parseToJsonElement(jsonString).jsonArray

        val mangas = jsonArray.map { item ->
            item.jsonObject
        }.filter { obj ->
            val title = obj["title"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val slug = obj["slug"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val tipe = obj["type"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val status = obj["status"]?.jsonPrimitive?.content?.lowercase() ?: ""

            val matchesQuery = query.isEmpty() || title.contains(query) || slug.contains(query)
            val matchesType = tipeFilter == "Semua" || tipe == tipeFilter.lowercase()
            val matchesStatus = statusFilter == "Semua" || status == statusFilter.lowercase()

            matchesQuery && matchesType && matchesStatus
        }.map { obj ->
            SManga.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = "/manga/${obj["slug"]?.jsonPrimitive?.content}.json"
                thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    // Kelas khusus pengganti abstract class Filter agar tidak error compiler
    class TypeFilter : Filter.Select<String>("Tipe", arrayOf("Semua", "Manga", "Manhua", "Manhwa"))
    class StatusFilter : Filter.Select<String>("Status", arrayOf("Semua", "Ongoing", "Completed"))

    override fun getFilterList() = FilterList(
        TypeFilter(),
        StatusFilter(),
    )

    // 3. DETAIL MANGA
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$staticUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonString = response.body.string()
        val obj = json.parseToJsonElement(jsonString).jsonObject

        return SManga.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            description = "Tipe: ${obj["type"]?.jsonPrimitive?.content ?: "-"}"
            status = when (obj["status"]?.jsonPrimitive?.content?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // 4. DAFTAR CHAPTER 
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonString = response.body.string()
        val obj = json.parseToJsonElement(jsonString).jsonObject
        val chaptersArray = obj["chapters"]?.jsonArray ?: obj["chapter_list"]?.jsonArray ?: return emptyList()

        val mangaSlug = response.request.url.pathSegments.last().removeSuffix(".json")

        return chaptersArray.map { item ->
            val chObj = item.jsonObject
            val chSlug = chObj["slug"]?.jsonPrimitive?.content ?: chObj["chapter"]?.jsonPrimitive?.content ?: ""
            
            val rawDate = chObj["date"]?.jsonPrimitive?.content 
                ?: chObj["updated_at"]?.jsonPrimitive?.content 
                ?: chObj["created_at"]?.jsonPrimitive?.content 
                ?: ""

            SChapter.create().apply {
                name = chObj["title"]?.jsonPrimitive?.content ?: chObj["chapter_title"]?.jsonPrimitive?.content ?: "Chapter"
                url = "/chapter/$mangaSlug/$chSlug.json"
                date_upload = parseChapterDate(rawDate)
            }
        }.reversed()
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val cleanDate = dateStr.substringBefore("T")
            dateFormat.parse(cleanDate)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // 5. DAFTAR HALAMAN GAMBAR
    override fun pageListRequest(chapter: SChapter): Request = GET("$staticUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val jsonString = response.body.string()
        val element = json.parseToJsonElement(jsonString)

        val rawImages = when {
            element is kotlinx.serialization.json.JsonArray -> element
            element.jsonObject.containsKey("images") -> element.jsonObject["images"]?.jsonArray
            element.jsonObject.containsKey("pages") -> element.jsonObject["pages"]?.jsonArray
            else -> return emptyList()
        } ?: return emptyList()

        return rawImages.mapIndexed { index, item ->
            val imageUrl = item.jsonPrimitive.content
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
