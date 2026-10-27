package app.e6reels.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostMappingTest {
    private fun video(samples: Map<String, String>, variantMp4: String? = null, ext: String = "webm") = PostDto(
        id = 1,
        file = FileDto(width = 3840, height = 2160, ext = ext, size = 50_000_000, url = "https://x/orig.$ext"),
        preview = PreviewDto("https://x/preview.jpg"),
        sample = SampleDto(
            has = true,
            url = "https://x/sample.jpg",
            alternates = AlternatesDto(
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
        assertNull(PostDto(id = 2, file = FileDto(ext = "swf", url = "https://x/a.swf")).toPost())
        assertNull(PostDto(id = 3, file = FileDto(ext = "png", url = null)).toPost())
    }

    @Test
    fun `big images use the sample, small ones the original`() {
        fun img(size: Long) = PostDto(
            id = 4,
            file = FileDto(ext = "png", size = size, url = "https://x/full.png"),
            sample = SampleDto(has = true, url = "https://x/sample.jpg"),
        ).toPost()!!.mediaUrl
        assertEquals("https://x/full.png", img(300_000))
        assertEquals("https://x/sample.jpg", img(5_000_000))
    }
}
