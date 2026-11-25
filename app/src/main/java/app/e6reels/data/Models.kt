package app.e6reels.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- e621 API DTOs (only the fields we use) ----
// The current post format (`mode=extended`): a bare array of posts, every file under `files`,
// the score under `stats` and the tags grouped by category. See https://e621.wiki/post-files/

@Serializable
data class PostDto(
    val id: Int,
    val files: FilesDto? = null,
    val stats: StatsDto? = null,
    val tags: TagsDto? = null,
    val rating: String = "e",
)

@Serializable
data class FilesDto(
    val meta: FileMetaDto? = null,
    val original: OriginalDto? = null,
    val preview: ImageDto? = null,
    val sample: ImageDto? = null,
    /** Only on videos. */
    val video: VideoDto? = null,
)

@Serializable
data class FileMetaDto(
    val ext: String = "",
    val size: Long = 0,
    @SerialName("has_sample") val hasSample: Boolean = false,
)

@Serializable
data class OriginalDto(
    val width: Int = 0,
    val height: Int = 0,
    /** Null when the post is hidden from anonymous users. */
    val url: String? = null,
)

@Serializable
data class ImageDto(val jpg: String? = null)

@Serializable
data class VideoDto(
    val variants: Map<String, VariantDto> = emptyMap(),
    val samples: Map<String, VariantDto> = emptyMap(),
)

@Serializable
data class VariantDto(val url: String? = null)

@Serializable
data class StatsDto(val score: ScoreDto? = null)

@Serializable
data class ScoreDto(val total: Int = 0)

@Serializable
data class TagsDto(
    val general: List<String> = emptyList(),
    val species: List<String> = emptyList(),
    val character: List<String> = emptyList(),
    val copyright: List<String> = emptyList(),
    val artist: List<String> = emptyList(),
    /** animated, sound, short_playtime, loop...: the tags that describe a video. */
    val meta: List<String> = emptyList(),
    val lore: List<String> = emptyList(),
)

@Serializable
data class TagSuggestion(
    val name: String,
    @SerialName("post_count") val postCount: Int = 0,
    val category: Int = 0,
    @SerialName("antecedent_name") val antecedent: String? = null,
)

// ---- App model ----

data class Post(
    val id: Int,
    val mediaUrl: String,
    val posterUrl: String,
    val thumbUrl: String,
    val ext: String,
    val width: Int,
    val height: Int,
    val score: Int,
    val rating: String,
    val artists: List<String>,
    /** character + copyright + species: the tags that say most about a post's taste. */
    val keyTags: List<String>,
    /** key tags first, then general, meta and lore; what we show in the overlay. */
    val tags: List<String>,
    /** The full-size original on e621 (null for posts restored from the database). */
    val originalUrl: String? = null,
) {
    val isVideo: Boolean get() = ext == "webm" || ext == "mp4"
    val pageUrl: String get() = "https://e621.net/posts/$id"
}

private val hiddenArtistTags = setOf(
    "conditional_dnp", "avoid_posting", "unknown_artist", "unknown_artist_signature", "sound_warning",
)
private const val MAX_ORIGINAL_IMAGE_BYTES = 800_000L

/** Generous cap (a post can have hundreds): keeps liked-post snapshots small without hiding anything real. */
private const val MAX_TAGS = 250

fun PostDto.toPost(quality: VideoQuality = VideoQuality.P480): Post? {
    val f = files ?: return null
    val meta = f.meta ?: return null
    val original = f.original ?: return null
    val fileUrl = original.url ?: return null // hidden/blocked for anonymous users
    val ext = meta.ext.lowercase()
    if (ext == "swf" || ext == "") return null

    val sampleUrl = f.sample?.jpg?.takeIf { meta.hasSample }
    val previewUrl = f.preview?.jpg ?: sampleUrl ?: fileUrl
    val isVideo = ext == "webm" || ext == "mp4"

    val mediaUrl: String
    val posterUrl: String
    if (isVideo) {
        val alt = f.video
        val s480 = alt?.samples?.get("480p")?.url
        val s720 = alt?.samples?.get("720p")?.url
        // the chosen quality if the post has it, else the closest sample; the original is the last resort
        // (unless asked for), because it can be 4K and take ages to start
        mediaUrl = when (quality) {
            VideoQuality.ORIGINAL -> fileUrl
            VideoQuality.P720 -> s720 ?: s480
            VideoQuality.P480 -> s480 ?: s720
        } ?: alt?.variants?.get("mp4")?.url ?: fileUrl
        posterUrl = sampleUrl ?: previewUrl
    } else {
        if (ext == "gif") {
            // big file: show a still first, the animation loads on top
            mediaUrl = fileUrl
            posterUrl = sampleUrl ?: previewUrl
        } else {
            mediaUrl = if (sampleUrl == null || meta.size in 1..MAX_ORIGINAL_IMAGE_BYTES) fileUrl else sampleUrl
            posterUrl = mediaUrl
        }
    }

    val t = tags ?: TagsDto()
    val key = (t.character + t.copyright + t.species).distinct()
    return Post(
        id = id,
        mediaUrl = mediaUrl,
        posterUrl = posterUrl,
        thumbUrl = previewUrl,
        ext = ext,
        width = original.width,
        height = original.height,
        score = stats?.score?.total ?: 0,
        rating = rating,
        artists = t.artist.filter { it !in hiddenArtistTags },
        keyTags = key,
        tags = (key + t.general + t.meta + t.lore).distinct().take(MAX_TAGS),
        originalUrl = fileUrl,
    )
}
