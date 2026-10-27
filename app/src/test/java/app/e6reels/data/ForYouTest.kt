package app.e6reels.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ForYouTest {
    private fun post(id: Int, keyTags: List<String>, artists: List<String> = emptyList()) = Post(
        id = id, mediaUrl = "", posterUrl = "", thumbUrl = "", ext = "png", width = 1, height = 1, score = 0,
        rating = "e", artists = artists, keyTags = keyTags, tags = keyTags,
    )

    @Test
    fun `broad and platform tags never count as tastes`() {
        val likes = List(5) { post(it, listOf("mammal", "canine", "patreon", "dimwitdog")) }
        val counts = mapOf("canine" to 2_000_000, "dimwitdog" to 900)
        val ranked = rankTags(likes, counts).map { it.first }
        assertEquals(listOf("dimwitdog"), ranked)
    }

    @Test
    fun `a rarer tag outranks a common one liked as often`() {
        val likes = List(4) { post(it, listOf("pokemon", "luxray")) }
        val counts = mapOf("pokemon" to 400_000, "luxray" to 6_000)
        assertEquals("luxray", rankTags(likes, counts).first().first)
    }

    @Test
    fun `dislikes cancel a taste`() {
        val likes = listOf(post(1, listOf("a", "b")))
        val dislikes = listOf(post(2, listOf("b")), post(3, listOf("b")))
        val ranked = rankTags(likes, mapOf("a" to 100, "b" to 100), dislikes).map { it.first }
        assertTrue("a" in ranked)
        assertFalse("b" in ranked)
    }

    @Test
    fun `a tag rejected three times and never liked is blocked automatically`() {
        val likes = listOf(post(1, listOf("liked_tag")))
        val dislikes = (2..4).map { post(it, listOf("bad_tag", "liked_tag")) }
        assertEquals(listOf("bad_tag"), autoBlocked(likes, dislikes))
    }

    @Test
    fun `the personal list keeps pinned tags, picks at most five, and blocks what it should`() {
        val likes = (1..20).map { post(it, listOf("t$it", "t${it + 1}")) }
        val list = buildForYou(likes, emptyMap(), pinned = listOf("pinned"), blocked = listOf("t3"), random = Random(7))
        assertEquals("pinned", list.include.first())
        assertTrue(list.include.size <= 6)
        assertFalse("t3" in list.include)
        assertTrue("t3" in list.blocked)
        assertTrue(list.anyMode)
    }
}
