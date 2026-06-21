package eu.kanade.tachiyomi.extension.id.comicaso

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import java.text.SimpleDateFormat
import java.util.Locale

// ── Serializer fleksibel: Long ATAU String → Long ─────────────────────────────
// Fix: updated_at kadang angka (1234567890) kadang string ("2025-07-07 12:00:00")

object FlexibleDateSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleDate", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return 0L
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonPrimitive) {
            // Coba sebagai Long dulu (Unix timestamp)
            element.longOrNull?.let { unix ->
                if (unix in 946_684_800L..4_102_444_800L) return unix * 1000L
                if (unix > 0L) return unix
            }
            // Coba sebagai string tanggal
            val raw = element.content
            return parseDateString(raw)
        }
        return 0L
    }

    fun parseDateString(raw: String): Long {
        if (raw.isBlank()) return 0L
        // Coba Unix timestamp dalam string
        raw.toLongOrNull()?.let { unix ->
            if (unix in 946_684_800L..4_102_444_800L) return unix * 1000L
        }
        // Coba format tanggal
        val formats = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        for (fmt in formats) {
            runCatching {
                SimpleDateFormat(fmt, Locale.US).parse(raw)?.time
                    ?.let { return it }
            }
        }
        return 0L
    }
}

// ── Serializer fleksibel: Object ATAU String → String ────────────────────────
// Fix: beberapa field kadang String kadang nested Object — ambil isi "name" atau toString

object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) =
        encoder.encodeString(value ?: "")

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        return runCatching {
            val element = jsonDecoder.decodeJsonElement()
            when {
                element is JsonPrimitive -> element.content.takeIf { it != "null" }
                else -> {
                    // Object — coba ambil field "name" atau "title"
                    val obj = element.toString()
                    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(obj)
                    nameMatch?.groupValues?.get(1) ?: obj
                }
            }
        }.getOrNull()
    }
}

// ── API Wrapper ───────────────────────────────────────────────────────────────

@Serializable
data class ApiResponse<T>(
    val ok: Boolean = false,
    val data: T,
    @SerialName("mode_source") val modeSource: String? = null,
)

// ── home.php response ─────────────────────────────────────────────────────────

// Confirmed structure (Juni 2026):
// {"ok":true,"total":2749,"limit":60,"offset":0,"next_offset":60,
//  "has_more":true,"data":[...]}
@Serializable
data class HomeResponseDto(
    val ok: Boolean = false,
    val data: List<MangaIndexDto>? = null,
    val total: Int = 0,
    val limit: Int = 60,
    val offset: Int = 0,
    @SerialName("next_offset") val nextOffset: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

// ── Manga index item ──────────────────────────────────────────────────────────

@Serializable
data class MangaIndexDto(
    val slug: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    val status: String? = null,
    val type: String? = null,
    val source: String? = null,
    val genres: List<String>? = null,
    @SerialName("updated_at")
    @Serializable(with = FlexibleDateSerializer::class)
    val updatedAt: Long = 0L,
    @SerialName("manga_date")
    @Serializable(with = FlexibleDateSerializer::class)
    val mangaDate: Long = 0L,
) {
    fun toSManga(): SManga = SManga.create().apply {
        val src = this@MangaIndexDto.source ?: "comicazen"
        url           = "$src/${this@MangaIndexDto.slug}"
        title         = this@MangaIndexDto.title
        thumbnail_url = thumbnail
        this.status   = this@MangaIndexDto.status.toMangaStatus()
    }
}

// ── Manga detail dari /api/manga.php ─────────────────────────────────────────

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
    // FIX: author/artist bisa String atau Object {"id":1,"name":"..."}
    @Serializable(with = FlexibleStringSerializer::class)
    val author: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val artist: String? = null,
    val genres: List<String>? = null,
    val chapters: List<ChapterDto>? = null,
    // FIX: updated_at bisa Long atau String
    @SerialName("updated_at")
    @Serializable(with = FlexibleDateSerializer::class)
    val updatedAt: Long = 0L,
    @SerialName("manga_date")
    @Serializable(with = FlexibleDateSerializer::class)
    val mangaDate: Long = 0L,
)

// ── Chapter item ──────────────────────────────────────────────────────────────

@Serializable
data class ChapterDto(
    val id: Long = 0,
    val slug: String = "",
    val title: String? = null,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    // FIX: date bisa Long atau String
    @Serializable(with = FlexibleDateSerializer::class)
    val date: Long = 0L,
    @SerialName("updated_at")
    @Serializable(with = FlexibleDateSerializer::class)
    val updatedAt: Long = 0L,
    @SerialName("created_at")
    @Serializable(with = FlexibleDateSerializer::class)
    val createdAt: Long = 0L,
) {
    fun toSChapter(source: String, mangaSlug: String) = SChapter.create().apply {
        url  = "$source/$mangaSlug/$slug"
        name = title ?: chapterTitle
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercaseChar() }
        date_upload    = date.takeIf { it > 0 } ?: updatedAt.takeIf { it > 0 } ?: createdAt
        chapter_number = slug.removePrefix("chapter-").toFloatOrNull() ?: -1f
    }
}

// ── Chapter images dari /api/chapter.php ─────────────────────────────────────
// FIX: pages[0] bisa berupa Object {"url":"...", "page":1} atau String langsung
// Gunakan JsonArray + parse manual untuk handle keduanya

@Serializable
data class ChapterImagesDto(
    val id: Long = 0,
    val slug: String = "",
    val title: String? = null,
    @SerialName("manga_slug") val mangaSlug: String? = null,
    @SerialName("chapter_slug") val chapterSlug: String? = null,
    val thumbnail: String? = null,
    // images: selalu List<String> — aman
    val images: List<String> = emptyList(),
    // pages: bisa List<String> ATAU List<Object> — parse manual via JsonArray
    @Serializable(with = FlexiblePageListSerializer::class)
    val pages: List<String> = emptyList(),
) {
    fun getImageUrls(): List<String> = images.ifEmpty { pages }
}

// Serializer untuk pages[] yang bisa String atau Object
object FlexiblePageListSerializer : KSerializer<List<String>> {
    private val delegateSerializer = kotlinx.serialization.builtins.ListSerializer(
        kotlinx.serialization.json.JsonElement.serializer(),
    )

    override val descriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.serializer<String>(),
            ),
            value,
        )
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val elements = decoder.decodeSerializableValue(delegateSerializer)
        return elements.mapNotNull { element ->
            when {
                element is JsonPrimitive -> element.content.takeIf {
                    it.startsWith("http")
                }
                else -> {
                    // Object: coba ambil field url/src/image/link
                    val obj = element.toString()
                    Regex(""""(?:url|src|image|img|link)"\s*:\s*"(https?://[^"]+)"""")
                        .find(obj)?.groupValues?.get(1)
                }
            }
        }
    }
}

// ── Extension: String? → SManga status ───────────────────────────────────────

fun String?.toMangaStatus() = when (this?.lowercase()) {
    "on-going", "ongoing", "berlangsung"    -> SManga.ONGOING
    "end", "completed", "selesai", "tamat"  -> SManga.COMPLETED
    "hiatus", "dropped"                     -> SManga.ON_HIATUS
    else                                    -> SManga.UNKNOWN
}
