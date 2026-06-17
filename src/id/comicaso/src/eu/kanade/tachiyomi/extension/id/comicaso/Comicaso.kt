package eu.kanade.tachiyomi.extension.id.comicaso

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable

class Comicaso : HttpSource() {

    override val name = "Comicaso"

    // FIX: baseUrl wajib v3.comicaso.pro
    override val baseUrl = "https://v3.comicaso.pro"

    override val lang = "id"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("X-Comicaso-Platform", "web")

    // ── Cache manga list ──────────────────────────────────────────────────────

    private var cachedMangaList: List<Pair<String, MangaDto>>? = null

    private fun getMangaList(): Observable<List<Pair<String, MangaDto>>> {
        if (cachedMangaList != null) return Observable.just(cachedMangaList)

        val sources = listOf("comicazen", "medusa")
        val observables = sources.map { source ->
            client.newCall(GET("$STATIC_URL/$source/manga/index.json", headers))
                .asObservableSuccess()
                .map { response ->
                    response.parseAs<List<MangaDto>>().map { source to it }
                }
                .onErrorReturn { emptyList() }
        }

        return Observable.zip(observables) { results ->
            results.flatMap { it as List<Pair<String, MangaDto>> }
        }.doOnNext { cachedMangaList = it }
    }

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return getMangaList().map { mangas ->
            val start = (page - 1) * PAGE_SIZE
            if (start >= mangas.size) return@map MangasPage(emptyList(), false)
            val end = minOf(start + PAGE_SIZE, mangas.size)
            MangasPage(
                mangas.subList(start, end).map { it.second.toSManga(it.first) },
                end < mangas.size,
            )
        }
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return getMangaList().map { mangas ->
            val sorted = mangas.sortedByDescending { it.second.updatedAt ?: it.second.mangaDate ?: 0L }
            val start = (page - 1) * PAGE_SIZE
            if (start >= sorted.size) return@map MangasPage(emptyList(), false)
            val end = minOf(start + PAGE_SIZE, sorted.size)
            MangasPage(
                sorted.subList(start, end).map { it.second.toSManga(it.first) },
                end < sorted.size,
            )
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ── Search ────────────────────────────────────────────────────────────────

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        // Support url: prefix untuk langsung buka manga dari URL
        if (query.isNotEmpty()) {
            val rawUrl = when {
                query.startsWith("https://") -> query.trim()
                query.startsWith(URL_SEARCH_PREFIX) -> query.removePrefix(URL_SEARCH_PREFIX).trim()
                else -> null
            }
            if (rawUrl != null) {
                val httpUrl = rawUrl.toHttpUrl()
                val pageParam   = httpUrl.queryParameter("page")
                val sourceParam = httpUrl.queryParameter("source")
                val slugParam   = httpUrl.queryParameter("slug")
                if (pageParam == "manga" && sourceParam != null && slugParam != null) {
                    return fetchMangaDetails(
                        SManga.create().apply { url = "$sourceParam/$slugParam" },
                    ).map { MangasPage(listOf(it), false) }
                }
            }
        }

        return getMangaList().map { mangas ->
            var filtered = mangas

            if (query.isNotEmpty()) {
                filtered = filtered.filter {
                    it.second.title.contains(query, ignoreCase = true)
                }
            }

            filters.forEach { filter ->
                when (filter) {
                    is SourceFilter -> {
                        if (filter.state > 0) {
                            val src = filter.values[filter.state].lowercase()
                            filtered = filtered.filter { it.first == src }
                        }
                    }
                    is GenreFilter -> {
                        if (filter.state > 0) {
                            val genre = filter.values[filter.state]
                            filtered = filtered.filter {
                                it.second.genres?.contains(genre) == true
                            }
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state > 0) {
                            val status = filter.values[filter.state].lowercase()
                            filtered = filtered.filter { it.second.status == status }
                        }
                    }
                    is TypeFilter -> {
                        if (filter.state > 0) {
                            val type = filter.values[filter.state].lowercase()
                            filtered = filtered.filter { it.second.type == type }
                        }
                    }
                    else -> {}
                }
            }

            val start = (page - 1) * PAGE_SIZE
            if (start >= filtered.size) return@map MangasPage(emptyList(), false)
            val end = minOf(start + PAGE_SIZE, filtered.size)
            MangasPage(
                filtered.subList(start, end).map { it.second.toSManga(it.first) },
                end < filtered.size,
            )
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ── Manga Details ─────────────────────────────────────────────────────────

    override fun getMangaUrl(manga: SManga): String {
        val (source, slug) = manga.url.split("/")
        // FIX: WebView buka v3.comicaso.pro bukan comicaso.com
        return "$baseUrl/?page=manga&source=$source&slug=$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (source, slug) = manga.url.split("/")
        // FIX: pakai /api/manga.php — static JSON sudah 404
        return GET(
            "$baseUrl/api/manga.php".toHttpUrl().newBuilder()
                .addQueryParameter("source", source)
                .addQueryParameter("slug", slug)
                .addQueryParameter("platform", "web")
                .build(),
            headers,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // FIX: response dibungkus {"ok":true,"data":{...},"mode_source":"..."}
        val root   = response.parseAs<ApiResponse<MangaDetailDto>>()
        val result = root.data
        val source = response.request.url.queryParameter("source") ?: "comicazen"

        return SManga.create().apply {
            url           = "$source/${result.slug}"
            title         = result.title
            thumbnail_url = result.thumbnail
            description   = buildString {
                result.synopsis?.let { append(Jsoup.parse(it).text()) }
                result.alternative?.takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative: $it")
                }
            }.trim()
            author = result.author
            artist = result.artist ?: result.author
            genre  = result.genres?.joinToString()
            status = when (result.status?.lowercase()) {
                "on-going", "ongoing", "berlangsung" -> SManga.ONGOING
                "end", "completed", "selesai", "tamat" -> SManga.COMPLETED
                "hiatus", "dropped" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    // Reuse mangaDetailsRequest — chapter list ada di response manga detail
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        // FIX: unwrap data wrapper
        val root   = response.parseAs<ApiResponse<MangaDetailDto>>()
        val result = root.data
        val source = response.request.url.queryParameter("source") ?: "comicazen"
        return result.chapters
            ?.map { it.toSChapter(source, result.slug) }
            ?.reversed()
            ?: emptyList()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url format: "{source}/{mangaSlug}/{chapterSlug}"
        val segments = chapter.url.split("/")
        val source   = segments.getOrElse(0) { "comicazen" }
        val manga    = segments.getOrElse(1) { "" }
        val slug     = segments.getOrElse(2) { "" }
        // FIX: WebView buka v3.comicaso.pro
        return "$baseUrl/?page=chapter&source=$source&manga=$manga&chapter=$slug"
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url format: "{source}/{mangaSlug}/{chapterSlug}"
        val segments = chapter.url.split("/")
        val source   = segments.getOrElse(0) { "comicazen" }
        val manga    = segments.getOrElse(1) { "" }
        val slug     = segments.getOrElse(2) { "" }

        // FIX: pakai /api/chapter.php — static JSON sudah 404
        return GET(
            "$baseUrl/api/chapter.php".toHttpUrl().newBuilder()
                .addQueryParameter("source", source)
                .addQueryParameter("manga", manga)
                .addQueryParameter("chapter", slug)
                .addQueryParameter("platform", "web")
                .build(),
            headers,
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        // FIX: unwrap {"ok":true,"data":{"images":[...]}}
        val root   = response.parseAs<ApiResponse<ChapterImagesDto>>()
        val result = root.data
        return result.getImageUrls().mapIndexed { index, imageUrl ->
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    override fun getFilterList(): FilterList {
        val genres = cachedMangaList
            ?.flatMap { it.second.genres ?: emptyList() }
            ?.distinct()
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

        return FilterList(
            Filter.Header("Filter dapat dikombinasikan dengan pencarian teks."),
            Filter.Separator(),
            SourceFilter(),
            StatusFilter(),
            TypeFilter(),
            GenreFilter(
                if (genres.isNullOrEmpty()) arrayOf("All")
                else arrayOf("All") + genres.toTypedArray(),
            ),
            Filter.Separator(),
            Filter.Header("Jika genre tidak muncul, tekan 'Reset' untuk memuat ulang."),
        )
    }

    private class SourceFilter : Filter.Select<String>("Source", arrayOf("All", "Comicazen", "Medusa"))
    private class GenreFilter(genres: Array<String>) : Filter.Select<String>("Genre", genres)
    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "On-going", "End"))
    private class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Manga", "Manhua", "Manhwa"))

    companion object {
        const val URL_SEARCH_PREFIX = "url:"
        // Index manga masih dari static (confirmed OK)
        private const val STATIC_URL = "https://static.comicaso.pro/static"
        private const val PAGE_SIZE = 60
    }
}

