package app.e6reels.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryTest {
    @Test
    fun `any-mode list uses OR tags, blocks and global filters`() {
        val s = FeedSettings(sort = Sort.SCORE, media = MediaMode.ANIMATED, ratings = setOf("s", "q"), minScore = 100)
        val list = TagList(name = "x", include = listOf("wolf", "fox"), blocked = listOf("gore"), anyMode = true)
        assertEquals("~wolf ~fox -gore -rating:e animated score:>=100 order:score -type:swf", buildQuery(s, list))
    }

    @Test
    fun `all-together list and a single rating`() {
        val s = FeedSettings(sort = Sort.NEWEST, ratings = setOf("e"), minScore = 0)
        val list = TagList(name = "x", include = listOf("wolf", "solo"), anyMode = false)
        assertEquals("wolf solo rating:e -type:swf", buildQuery(s, list))
    }

    @Test
    fun `one tag in any mode is not turned into OR`() {
        val s = FeedSettings(sort = Sort.NEWEST, minScore = 0)
        assertEquals("wolf -type:swf", buildQuery(s, TagList(name = "x", include = listOf("wolf"), anyMode = true)))
    }

    @Test
    fun `no list means only the filters`() {
        assertEquals("score:>=50 order:random -type:swf", buildQuery(FeedSettings(), null))
    }

    @Test
    fun `tags are normalised`() {
        assertEquals("wolf_girl" to false, normalizeTag("  Wolf Girl "))
        assertEquals("gore" to true, normalizeTag("-gore"))
        assertNull(normalizeTag("   "))
        assertNull(normalizeTag("-"))
    }

    @Test
    fun `marks move a tag between include and block`() {
        val l = TagList(name = "x", include = listOf("a"), blocked = listOf("b"))
        assertEquals(TagMark.INCLUDE, l.markOf("a"))
        assertEquals(TagMark.BLOCK, l.markOf("b"))
        val blockedA = l.withMark("a", TagMark.BLOCK)
        assertEquals(listOf<String>(), blockedA.include)
        assertEquals(listOf("b", "a"), blockedA.blocked)
        assertEquals(TagMark.NONE, blockedA.withMark("a", TagMark.NONE).markOf("a"))
    }
}
