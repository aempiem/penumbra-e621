package app.e6reels.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostMappingTest {
    private fun video(samples: Map<String, String>, variantMp4: String? = null, ext: String = "webm") = PostDto(
        id = 1,
        files = FilesDto(
            meta = FileMetaDto(ext = ext, size = 50_000_000, hasSample = true),
            original = OriginalDto(width = 3840, height = 2160, url = "https://x/orig.$ext"),
            preview = ImageDto("https://x/preview.jpg"),
            sample = ImageDto("https://x/sample.jpg"),
            video = VideoDto(
                variants = variantMp4?.let { mapOf("mp4" to VariantDto(it)) } ?: emptyMap(),
                samples = samples.mapValues { VariantDto(it.value) },
            ),
        ),
        tags = TagsDto(artist = listOf("someone", "conditional_dnp"), species = listOf("wolf"), general = listOf("solo"), meta = listOf("animated")),
    )

    private val both = mapOf("480p" to "https://x/480.mp4", "720p" to "https://x/720.mp4")

    @Test
    fun `quality picks the matching sample`() {
        assertEquals("https://x/480.mp4", video(both).toPost(VideoQuality.P480)!!.mediaUrl)
        assertEquals("https://x/720.mp4", video(both).toPost(VideoQuality.P720)!!.mediaUrl)
        assertEquals("https://x/orig.webm", video(both).toPost(VideoQuality.ORIGINAL)!!.mediaUrl)
    }

    @Test
    fun `a missing quality falls back to the closest sample, never the original first`() {
        assertEquals("https://x/480.mp4", video(mapOf("480p" to "https://x/480.mp4")).toPost(VideoQuality.P720)!!.mediaUrl)
        assertEquals("https://x/720.mp4", video(mapOf("720p" to "https://x/720.mp4")).toPost(VideoQuality.P480)!!.mediaUrl)
        assertEquals("https://x/v.mp4", video(emptyMap(), variantMp4 = "https://x/v.mp4").toPost(VideoQuality.P480)!!.mediaUrl)
        assertEquals("https://x/orig.webm", video(emptyMap()).toPost(VideoQuality.P480)!!.mediaUrl)
    }

    @Test
    fun `video keeps the still as poster, the original for saving, and drops non-artist tags`() {
        val p = video(both).toPost()!!
        assertEquals("https://x/sample.jpg", p.posterUrl)
        assertEquals("https://x/orig.webm", p.originalUrl)
        assertEquals(listOf("someone"), p.artists)
        assertEquals(listOf("wolf"), p.keyTags)
        assertEquals(listOf("wolf", "solo", "animated"), p.tags)
    }

    @Test
    fun `flash and hidden posts are skipped`() {
        fun post(id: Int, ext: String, url: String?) =
            PostDto(id = id, files = FilesDto(meta = FileMetaDto(ext = ext), original = OriginalDto(url = url)))
        assertNull(post(2, "swf", "https://x/a.swf").toPost())
        assertNull(post(3, "png", null).toPost())
    }

    @Test
    fun `big images use the sample, small ones the original`() {
        fun img(size: Long) = PostDto(
            id = 4,
            files = FilesDto(
                meta = FileMetaDto(ext = "png", size = size, hasSample = true),
                original = OriginalDto(url = "https://x/full.png"),
                sample = ImageDto("https://x/sample.jpg"),
            ),
        ).toPost()!!.mediaUrl
        assertEquals("https://x/full.png", img(300_000))
        assertEquals("https://x/sample.jpg", img(5_000_000))
    }

    @Test
    fun `parses a real posts response`() {
        val body = """[{"id":7,"files":{"meta":{"md5":"ab","ext":"webm","size":91995895,"duration":42.0,"has_sample":true},
            "original":{"width":2160,"height":3840,"url":"https://x/o.webm"},
            "preview":{"width":256,"height":455,"jpg":"https://x/p.jpg","webp":"https://x/p.webp"},
            "sample":{"width":850,"height":1511,"jpg":"https://x/s.jpg","webp":"https://x/s.webp"},
            "video":{"has":true,"original":{"url":"https://x/o.webm"},"variants":{"mp4":{"url":"https://x/alt.mp4"}},
            "samples":{"480p":{"url":"https://x/480p.mp4"},"720p":{"url":"https://x/720p.mp4"}}}},
            "stats":{"score":{"up":5,"down":-1,"total":4}},"rating":"s",
            "tags":{"general":["solo"],"artist":["someone"],"contributor":[],"copyright":[],"character":[],
            "species":["wolf"],"invalid":[],"meta":["animated"],"lore":[]}}]"""
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val p = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PostDto.serializer()), body)
            .single().toPost(VideoQuality.P720)!!
        assertEquals("https://x/720p.mp4", p.mediaUrl)
        assertEquals("https://x/s.jpg", p.posterUrl)
        assertEquals("https://x/p.jpg", p.thumbUrl)
        assertEquals(4, p.score)
        assertEquals(2160 to 3840, p.width to p.height)
        assertEquals(listOf("someone"), p.artists)
        assertEquals(listOf("wolf", "solo", "animated"), p.tags)
    }
}
