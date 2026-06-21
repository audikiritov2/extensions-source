package eu.kanade.tachiyomi.extension.id.comicaso

import eu.kanade.tachiyomi.network.GET
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

class Comicaso : HttpSource() {

    override val name = "Comicaso"
    override val baseUrl = "https://v3.comicaso.pro"
    override val lang = "id"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1) // 2 request per 1 detik — lebih longgar utk hindari throttle Cloudflare
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("X-Comicaso-Platform", "web")

    // ── Helper: build home.php URL ────────────────────────────────────────────

    private fun homeUrl(
        query: String = "",
        mode: String = "update",
        type: String = "all",
        genre: String = "",
        status: String = "",
        source: String = "all",
        limit: Int = 60,
        offset: Int = 0,
    ) = "$baseUrl/api/home.php".toHttpUrl().newBuilder()
        .addQueryParameter("source", source)
        .addQueryParameter("q", query)
        .addQueryParameter("mode", mode)
        .addQueryParameter("type", type)
        .apply { if (genre.isNotBlank()) addQueryParameter("genre", genre) }
        .apply { if (status.isNotBlank()) addQueryParameter("status", status) }
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .build()

    // ── Helper: parse home.php response ──────────────────────────────────────

    private fun Response.parseMangasPage(): MangasPage {
        val root = parseAs<HomeResponseDto>()
        val list = root.data ?: emptyList()
        // API confirmed punya field has_more — pakai langsung, akurat
        return MangasPage(list.map { it.toSManga() }, hasNextPage = root.hasMore)
    }

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET(homeUrl(mode = "update", limit = 60, offset = (page - 1) * 60), headers)

    override fun popularMangaParse(response: Response) = response.parseMangasPage()

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET(homeUrl(mode = "update", limit = 60, offset = (page - 1) * 60), headers)

    override fun latestUpdatesParse(response: Response) = response.parseMangasPage()

    // ── Search + Filter ───────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Support "url:" prefix — langsung buka dari URL situs
        if (query.startsWith(URL_SEARCH_PREFIX)) {
            val raw = query.removePrefix(URL_SEARCH_PREFIX).trim()
            runCatching {
                val httpUrl = raw.toHttpUrl()
                val src  = httpUrl.queryParameter("source") ?: return@runCatching
                val slug = httpUrl.queryParameter("slug") ?: return@runCatching
                return GET(
                    "$baseUrl/api/manga.php".toHttpUrl().newBuilder()
                        .addQueryParameter("source", src)
                        .addQueryParameter("slug", slug)
                        .addQueryParameter("platform", "web")
                        .build(),
                    headers,
                )
            }
        }

        var genre  = ""
        var type   = "all"
        var src    = "all"
        var status = ""
        filters.forEach { f ->
            when (f) {
                is SourceFilter -> if (f.state > 0) src    = f.slugs[f.state]
                is GenreFilter  -> if (f.state > 0) genre  = f.slugs[f.state]
                is StatusFilter -> if (f.state > 0) status = f.slugs[f.state]
                is TypeFilter   -> if (f.state > 0) type   = f.slugs[f.state]
                else -> {}
            }
        }

        val mode = if (query.isNotBlank()) "search" else "update"
        return GET(
            homeUrl(
                query  = query.trim(),
                mode   = mode,
                type   = type,
                genre  = genre,
                status = status,
                source = src,
                limit  = 60,
                offset = (page - 1) * 60,
            ),
            headers,
        )
    }

    override fun searchMangaParse(response: Response) = response.parseMangasPage()

    // ── Manga Details ─────────────────────────────────────────────────────────

    override fun getMangaUrl(manga: SManga): String {
        val (source, slug) = manga.url.split("/", limit = 2)
        return "$baseUrl/?page=manga&source=$source&slug=$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (source, slug) = manga.url.split("/", limit = 2)
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
            status = result.status.toMangaStatus()
        }
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val root   = response.parseAs<ApiResponse<MangaDetailDto>>()
        val result = root.data
        val source = response.request.url.queryParameter("source") ?: "comicazen"
        return result.chapters
            ?.map { it.toSChapter(source, result.slug) }
            ?.reversed()
            ?: emptyList()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val segments = chapter.url.split("/")
        val source   = segments.getOrElse(0) { "comicazen" }
        val manga    = segments.getOrElse(1) { "" }
        val slug     = segments.getOrElse(2) { "" }
        return "$baseUrl/?page=chapter&source=$source&manga=$manga&chapter=$slug"
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = chapter.url.split("/")
        val source   = segments.getOrElse(0) { "comicazen" }
        val manga    = segments.getOrElse(1) { "" }
        val slug     = segments.getOrElse(2) { "" }
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
        val root   = response.parseAs<ApiResponse<ChapterImagesDto>>()
        val result = root.data
        return result.getImageUrls().mapIndexed { index, imageUrl ->
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    override fun getFilterList() = FilterList(
        Filter.Header("Filter dapat dikombinasikan dengan pencarian teks."),
        Filter.Separator(),
        SourceFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        Filter.Separator(),
        Filter.Header("Jika genre tidak muncul, tekan 'Reset'."),
    )

    private class SourceFilter : Filter.Select<String>("Source", SOURCE_LABELS) {
        val slugs = SOURCE_SLUGS
    }
    private class GenreFilter : Filter.Select<String>("Genre", GENRE_LABELS) {
        // Mapping index -> slug genre yang dikirim ke API (lowercase-with-dash)
        val slugs = GENRE_SLUGS
    }
    private class StatusFilter : Filter.Select<String>("Status", STATUS_LABELS) {
        val slugs = STATUS_SLUGS
    }
    private class TypeFilter : Filter.Select<String>("Type", TYPE_LABELS) {
        val slugs = TYPE_SLUGS
    }

    companion object {
        const val URL_SEARCH_PREFIX = "url:"
        const val PAGE_SIZE = 60

        // Semua filter: Label = ditampilkan ke user, Slug = dikirim ke API
        // (lowercase-with-dash, persis format field "genre"/"type"/"status"/"source" di JSON)

        val SOURCE_LABELS = arrayOf("All", "Comicazen", "Medusa")
        val SOURCE_SLUGS  = arrayOf("all", "comicazen", "medusa")

        val STATUS_LABELS = arrayOf("All", "On-going", "End")
        val STATUS_SLUGS  = arrayOf("", "on-going", "end")

        val TYPE_LABELS = arrayOf("All", "Manga", "Manhua", "Manhwa")
        val TYPE_SLUGS  = arrayOf("all", "manga", "manhua", "manhwa")

        val GENRE_LABELS = arrayOf(
            "All", "Action", "Adventure", "Comedy", "Drama",
            "Fantasy", "Horror", "Isekai", "Romance",
            "Sci-Fi", "Slice of Life", "Sports", "Supernatural",
        )
        val GENRE_SLUGS = arrayOf(
            "", "action", "adventure", "comedy", "drama",
            "fantasy", "horror", "isekai", "romance",
            "sci-fi", "slice-of-life", "sports", "supernatural",
        )
    }
}
