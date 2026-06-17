package eu.kanade.tachiyomi.extension.id.comicaso

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

// ── Wrapper semua response API /api/*.php ─────────────────────────────────────
// Format: { "ok": true, "data": T, "mode_source": "..." }

@Serializable
data class ApiResponse<T>(
    val ok: Boolean = false,
    val data: T,
    @SerialName("mode_source") val modeSource: String? = null,
)

// ── Index manga (dari static/manga/index.json) ────────────────────────────────

@Serializable
data class MangaDto(
    val slug: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genres: List<String>? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("manga_date") val mangaDate: Long? = null,
) {
    fun toSManga(source: String) = SManga.create().apply {
        // url format: "{source}/{slug}" — dipakai di mangaDetailsRequest & chapterListRequest
        url = "$source/$slug"
        title = this@MangaDto.title
        thumbnail_url = thumbnail
        this.status = when (this@MangaDto.status?.lowercase()) {
            "on-going", "ongoing", "berlangsung" -> SManga.ONGOING
            "end", "completed", "selesai", "tamat" -> SManga.COMPLETED
            "hiatus", "dropped" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}

// ── Detail manga (dari /api/manga.php) ────────────────────────────────────────

@Serializable
data class MangaDetailDto(
    val id: Long = 0,
    val slug: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    val synopsis: String? = null,
    val alternative: String? = null,
    val status: String? = null,
    val type: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String>? = null,
    val chapters: List<ChapterDto>? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("manga_date") val mangaDate: Long? = null,
    @SerialName("latest_chapter") val latestChapter: String? = null,
    @SerialName("last_chapter") val lastChapter: String? = null,
)

// ── Chapter item (di dalam MangaDetailDto.chapters) ──────────────────────────

@Serializable
data class ChapterDto(
    val id: Long = 0,
    // PENTING: pakai slug (string), bukan id (angka) untuk URL
    val slug: String = "",
    val title: String? = null,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    val date: Long? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toSChapter(source: String, mangaSlug: String) = SChapter.create().apply {
        // url format: "{source}/{mangaSlug}/{chapterSlug}"
        url = "$source/$mangaSlug/$slug"
        name = title ?: chapterTitle
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercaseChar() }

        date_upload = when {
            // API kadang return Unix timestamp (detik)
            date != null && date in 946_684_800L..4_102_444_800L -> date * 1000L
            date != null -> date
            // Atau string tanggal "yyyy-MM-dd HH:mm:ss"
            updatedAt != null -> parseDate(updatedAt)
            createdAt != null -> parseDate(createdAt)
            else -> 0L
        }

        chapter_number = slug.removePrefix("chapter-").toFloatOrNull() ?: -1f
    }

    private fun parseDate(raw: String): Long = try {
        val clean = raw.substringBefore("T").trim()
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(clean)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

// ── Chapter images (dari /api/chapter.php) ────────────────────────────────────

@Serializable
data class ChapterImagesDto(
    val id: Long = 0,
    val slug: String = "",
    val title: String? = null,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    @SerialName("manga_slug") val mangaSlug: String? = null,
    @SerialName("chapter_slug") val chapterSlug: String? = null,
    val thumbnail: String? = null,
    // Field utama yang kita butuhkan
    val images: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
) {
    // Ambil dari images, fallback ke pages
    fun getImageUrls(): List<String> = images.ifEmpty { pages }
}
