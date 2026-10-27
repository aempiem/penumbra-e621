package app.e6reels.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Sort(val label: String, val order: String?) {
    RANDOM("Random", "order:random"),
    NEWEST("Newest", null),
    SCORE("Top rated", "order:score"),
    HOT("Trending", "order:rank"),
}

enum class MediaMode(val label: String, val tag: String?) {
    ALL("Everything", null),
    ANIMATED("Videos & GIFs", "animated"),
    STILLS("Images only", "-animated"),
}

/** Which file to play for a video. e621 keeps the original plus smaller 480p/720p versions of it. */
enum class VideoQuality(val label: String) {
    P480("480p"),
    P720("720p"),
    ORIGINAL("Original"),
}

val ALL_RATINGS = listOf("s" to "Safe", "q" to "Questionable", "e" to "Explicit")

data class FeedSettings(
    val sort: Sort = Sort.RANDOM,
    val media: MediaMode = MediaMode.ALL,
    val ratings: Set<String> = setOf("s", "q", "e"),
    val minScore: Int = 50,
    val videoQuality: VideoQuality = VideoQuality.P480,
    /** Skip posts that were already on screen before (in any session). */
    val hideSeen: Boolean = true,
    /** The tags of the one-off search ([SEARCH_ID]); a leading "-" excludes a tag. */
    val searchTags: List<String> = emptyList(),
    /** [FOR_YOU_ID] = personalised list (default), 0 = no list: the whole site. */
    val activeListId: Long = FOR_YOU_ID,
)

private val Context.dataStore by preferencesDataStore("e6reels")

class Prefs(private val context: Context) {
    private object K {
        val activeList = longPreferencesKey("active_list")
        val sort = stringPreferencesKey("sort")
        val media = stringPreferencesKey("media")
        val ratings = stringPreferencesKey("ratings")
        val minScore = intPreferencesKey("min_score")
        val videoQuality = stringPreferencesKey("video_quality")
        val hideSeen = booleanPreferencesKey("hide_seen")
        val searchTags = stringPreferencesKey("search_tags")
        val offlineLimit = stringPreferencesKey("offline_limit")
        val name = stringPreferencesKey("profile_name")
        val adultOk = booleanPreferencesKey("adult_ok")
        val tagCounts = stringPreferencesKey("tag_counts")
        val forYouPinned = stringPreferencesKey("foryou_pinned")
        val forYouBlocked = stringPreferencesKey("foryou_blocked")
    }

    val settings: Flow<FeedSettings> = context.dataStore.data.map { p ->
        val d = FeedSettings()
        FeedSettings(
            sort = Sort.entries.firstOrNull { it.name == p[K.sort] } ?: d.sort,
            media = MediaMode.entries.firstOrNull { it.name == p[K.media] } ?: d.media,
            ratings = p[K.ratings]?.map { it.toString() }?.toSet()?.takeIf { it.isNotEmpty() } ?: d.ratings,
            minScore = p[K.minScore] ?: d.minScore,
            videoQuality = VideoQuality.entries.firstOrNull { it.name == p[K.videoQuality] } ?: d.videoQuality,
            hideSeen = p[K.hideSeen] ?: d.hideSeen,
            searchTags = p[K.searchTags].orEmpty().split(' ').filter { it.isNotBlank() },
            activeListId = p[K.activeList] ?: d.activeListId,
        )
    }

    suspend fun saveSettings(s: FeedSettings) {
        context.dataStore.edit {
            it[K.sort] = s.sort.name
            it[K.media] = s.media.name
            it[K.ratings] = s.ratings.joinToString("")
            it[K.minScore] = s.minScore
            it[K.videoQuality] = s.videoQuality.name
            it[K.hideSeen] = s.hideSeen
        }
    }

    suspend fun setActiveList(id: Long) {
        context.dataStore.edit { it[K.activeList] = id }
    }

    /** Starts a one-off search: stores its tags and makes it the feed. */
    suspend fun setSearch(tags: List<String>) {
        context.dataStore.edit {
            it[K.searchTags] = tags.joinToString(" ")
            it[K.activeList] = SEARCH_ID
        }
    }

    val offlineLimit: Flow<OfflineLimit> = context.dataStore.data.map { p ->
        OfflineLimit.entries.firstOrNull { it.name == p[K.offlineLimit] } ?: OfflineLimit.MB500
    }

    suspend fun setOfflineLimit(limit: OfflineLimit) {
        context.dataStore.edit { it[K.offlineLimit] = limit.name }
    }

    /** Post counts per tag on e621, cached so "For you" can tell specific tastes from broad ones. */
    val tagCounts: Flow<Map<String, Int>> = context.dataStore.data.map { p ->
        p[K.tagCounts].orEmpty().split(' ').mapNotNull { entry ->
            val i = entry.lastIndexOf(':')
            if (i <= 0) null else entry.substring(i + 1).toIntOrNull()?.let { entry.substring(0, i) to it }
        }.toMap()
    }

    suspend fun saveTagCounts(counts: Map<String, Int>) {
        context.dataStore.edit { p -> p[K.tagCounts] = counts.entries.joinToString(" ") { "${it.key}:${it.value}" } }
    }

    /** The user's own tweaks to "For you": always-included (include) and always-excluded (blocked) tags. */
    val forYouAdjust: Flow<TagList> = context.dataStore.data.map { p ->
        fun words(k: Preferences.Key<String>) = p[k].orEmpty().split(' ').filter { it.isNotBlank() }
        TagList(id = FOR_YOU_ID, name = "For you", include = words(K.forYouPinned), blocked = words(K.forYouBlocked))
    }

    suspend fun saveForYouAdjust(adjust: TagList) {
        context.dataStore.edit {
            it[K.forYouPinned] = adjust.include.joinToString(" ")
            it[K.forYouBlocked] = adjust.blocked.joinToString(" ")
        }
    }

    val profileName: Flow<String> = context.dataStore.data.map { it[K.name] ?: "Guest" }
    suspend fun setProfileName(name: String) {
        context.dataStore.edit { it[K.name] = name.trim().ifBlank { "Guest" } }
    }

    val adultOk: Flow<Boolean> = context.dataStore.data.map { it[K.adultOk] ?: false }
    suspend fun setAdultOk() {
        context.dataStore.edit { it[K.adultOk] = true }
    }
}

/** "  Wolf Girl " -> ("wolf_girl", false); "-gore" -> ("gore", true). */
fun normalizeTag(raw: String): Pair<String, Boolean>? {
    var t = raw.trim().lowercase().replace(Regex("\\s+"), "_")
    val negative = t.startsWith("-")
    t = t.trimStart('-', '~')
    return if (t.isBlank()) null else t to negative
}
