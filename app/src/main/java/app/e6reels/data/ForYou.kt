package app.e6reels.data

import kotlin.math.ln
import kotlin.random.Random

/** Id of the built-in "For you" list. It isn't stored in the database. */
const val FOR_YOU_ID = -1L

/** Id of the one-off search, which acts as a list while it is active. */
const val SEARCH_ID = -2L

/** A tag that showed up in this many "menos disso" posts (and never in a like) is left out of "For you". */
private const val AUTO_BLOCK_AFTER = 3
private const val MAX_AUTO_BLOCKS = 6

private const val POOL_SIZE = 12
private const val PICK_COUNT = 5
private const val RECENT_LIKES = 200
private const val FETCH_CANDIDATES = 16

/** Rough size of e621; only used to turn a tag's post count into a rarity weight. */
private const val TOTAL_POSTS = 7_000_000.0

/** Tags on more posts than this describe "a kind of animal", not a taste, so they never seed the feed. */
private const val TOO_BROAD = 1_500_000

/** Count assumed for a tag whose real count hasn't been fetched yet. */
private const val UNKNOWN_COUNT = 150_000

/** Taxonomy parents that are certainly too broad; lets the first run work before any count is fetched. */
private val BROAD_SEED = setOf("mammal", "canid", "canine", "felid", "feline", "human")

/** Where an artist posts or sells, not what anyone likes: counting these would seed the feed with "all of patreon". */
private val NOT_A_TASTE = setOf(
    "patreon", "subscribestar", "fanbox", "pixiv", "twitter", "x_(social_media)", "bluesky", "reddit", "tumblr",
    "instagram", "youtube", "deviantart", "furaffinity", "newgrounds", "kofi", "ko-fi", "gumroad", "discord", "telegram",
)

private fun rawScores(likes: List<Post>): Map<String, Double> {
    val score = HashMap<String, Double>()
    likes.take(RECENT_LIKES).forEachIndexed { i, post ->
        val recency = 0.5 + 0.5 * Math.pow(0.97, i.toDouble())
        post.keyTags.distinct().filter { it !in NOT_A_TASTE }.forEach { score.merge(it, recency, Double::plus) }
        // artists are the most specific signal
        post.artists.distinct().filter { it !in NOT_A_TASTE }.forEach { score.merge(it, recency * 1.5, Double::plus) }
    }
    return score
}

/**
 * The user's tastes, best first. A tag earns points for every liked post that has it
 * (newer likes count more), multiplied by how rare it is on e621: "mammal" and "canine"
 * appear on everything a furry likes, so they say nothing, while "dimwitdog" or "pokemon" do.
 *
 * [counts] maps tag -> post count on e621 (negative = unknown).
 */
fun rankTags(
    likes: List<Post>,
    counts: Map<String, Int>,
    dislikes: List<Post> = emptyList(),
): List<Pair<String, Double>> {
    val penalty = rawScores(dislikes)
    return rawScores(likes).mapNotNull { (tag, score) ->
        val count = counts[tag]?.takeIf { it >= 0 } ?: if (tag in BROAD_SEED) 3_000_000 else null
        if (count != null && count > TOO_BROAD) return@mapNotNull null
        // every "menos disso" post weighs against its tags, at the same strength as a like
        val net = score - (penalty[tag] ?: 0.0)
        if (net <= 0.0) return@mapNotNull null
        val rarity = ln(TOTAL_POSTS / (count ?: UNKNOWN_COUNT).coerceAtLeast(1))
        tag to net * rarity.coerceAtLeast(0.5)
    }.sortedByDescending { it.second }
}

/** Tags the user keeps rejecting: in [AUTO_BLOCK_AFTER]+ "menos disso" posts and in no liked post. */
fun autoBlocked(likes: List<Post>, dislikes: List<Post>): List<String> {
    val liked = likes.flatMap { it.keyTags + it.artists }.toSet()
    return dislikes.flatMap { (it.keyTags + it.artists).distinct() }
        .groupingBy { it }.eachCount()
        .filter { (tag, n) -> n >= AUTO_BLOCK_AFTER && tag !in liked && tag !in NOT_A_TASTE }
        .entries.sortedByDescending { it.value }
        .take(MAX_AUTO_BLOCKS)
        .map { it.key }
}

/** The strongest candidates whose post count we still don't know (fetched in the background). */
fun tagsMissingCounts(likes: List<Post>, counts: Map<String, Int>): List<String> =
    rawScores(likes).entries.sortedByDescending { it.value }
        .map { it.key }
        .filter { it !in counts && it !in BROAD_SEED }
        .take(FETCH_CANDIDATES)

/**
 * Builds the personalised list. [PICK_COUNT] of the best [POOL_SIZE] tastes are drawn at
 * random, weighted by score, so each time the feed restarts it explores a different slice
 * of what the user likes. [pinned] tags are always included and [blocked] always excluded.
 */
fun buildForYou(
    likes: List<Post>,
    counts: Map<String, Int>,
    pinned: List<String> = emptyList(),
    blocked: List<String> = emptyList(),
    dislikes: List<Post> = emptyList(),
    random: Random = Random,
): TagList {
    val allBlocked = (blocked + autoBlocked(likes, dislikes)).distinct()
    val pool = rankTags(likes, counts, dislikes).filter { it.first !in allBlocked }.take(POOL_SIZE).toMutableList()
    val picked = mutableListOf<String>()
    repeat(minOf(PICK_COUNT, pool.size)) {
        var r = random.nextDouble() * pool.sumOf { it.second }
        val choice = pool.firstOrNull { e -> (r - e.second).also { r = it } <= 0 } ?: pool.last()
        picked += choice.first
        pool.remove(choice)
    }
    return TagList(
        id = FOR_YOU_ID, name = "For you",
        include = (pinned + picked).distinct(), blocked = allBlocked, anyMode = true,
    )
}
