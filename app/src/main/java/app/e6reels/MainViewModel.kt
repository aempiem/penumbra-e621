package app.e6reels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.e6reels.data.ApiException
import app.e6reels.data.DislikedEntity
import app.e6reels.data.FOR_YOU_ID
import app.e6reels.data.OfflineLimit
import app.e6reels.data.SEARCH_ID
import app.e6reels.data.SeenEntity
import app.e6reels.data.saveToDevice
import app.e6reels.data.toHistory
import app.e6reels.data.FeedSettings
import app.e6reels.data.LikedEntity
import app.e6reels.data.Post
import app.e6reels.data.TagList
import app.e6reels.data.TagMark
import app.e6reels.data.TagSuggestion
import app.e6reels.data.autoBlocked
import app.e6reels.data.VideoQuality
import app.e6reels.data.normalizeTag
import app.e6reels.data.toPost
import app.e6reels.data.buildForYou
import app.e6reels.data.rankTags
import app.e6reels.data.tagsMissingCounts
import app.e6reels.data.buildQuery
import app.e6reels.data.toEntity
import app.e6reels.data.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class FeedState(
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    /** Bumps every time the feed restarts (filters changed), so the UI can jump back to the top. */
    val generation: Int = 0,
)

/** A short message for the snackbar; [undo], when present, becomes its "Undo" action. */
data class UiMessage(val text: String, val undo: (() -> Unit)? = null)

@Serializable
private data class BackupList(val name: String, val include: List<String>, val blocked: List<String>, val anyMode: Boolean)

/** Everything that is the user's: likes, lists and the "For you" tweaks (v1 files only had likes). */
@Serializable
private data class BackupFile(
    val version: Int = 2,
    val name: String = "",
    val likes: List<LikedEntity>,
    val lists: List<BackupList> = emptyList(),
    val forYouPinned: List<String> = emptyList(),
    val forYouBlocked: List<String> = emptyList(),
)

/** The list tags go to when none is active. Older versions named it in Portuguese; it is renamed once. */
private const val DEFAULT_LIST = "My feed"
private const val OLD_DEFAULT_LIST = "Meu feed"

private const val SEEN_KEEP = 20_000
private const val HISTORY_KEEP = 500

private const val MAX_PAGE = 750
private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as E6App).container
    private val dao = c.db.liked()

    // ---- local account ----
    val profileName: StateFlow<String> = c.prefs.profileName.stateIn(viewModelScope, SharingStarted.Eagerly, "Guest")

    /** null while DataStore is loading, so the age gate doesn't flash. */
    val adultOk: StateFlow<Boolean?> =
        c.prefs.adultOk.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<FeedSettings> =
        c.prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, FeedSettings())

    val lists: StateFlow<List<TagList>> = c.db.lists().all()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The list the feed is built from; null = whole site. */
    val activeList: StateFlow<TagList?> = combine(lists, settings) { all, s ->
        when (s.activeListId) {
            FOR_YOU_ID -> TagList(FOR_YOU_ID, "For you")
            SEARCH_ID -> searchList(s.searchTags)
            else -> all.firstOrNull { it.id == s.activeListId }
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Liked posts, pointing at the copies kept on the phone when they exist. */
    val likes: StateFlow<List<Post>> = combine(dao.all(), c.likedStore.version) { list, _ -> list }
        .map { list -> list.map { c.likedStore.resolve(it.toPost()) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The last posts that were on screen in the feed, newest first. */
    val history: StateFlow<List<Post>> = c.db.history().all()
        .map { list -> list.map { it.toPost() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val seenCount: StateFlow<Int> = c.db.seen().count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val dislikes: StateFlow<List<Post>> = c.db.disliked().all()
        .map { list -> list.map { it.toPost() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val offlineLimit: StateFlow<OfflineLimit> = c.prefs.offlineLimit.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineLimit.MB500)

    private val _offlineUsage = MutableStateFlow(0L)

    /** Bytes taken by liked media kept on the phone. */
    val offlineUsage: StateFlow<Long> = _offlineUsage.asStateFlow()

    val likedIds: StateFlow<Set<Int>> = likes
        .map { list -> list.mapTo(HashSet()) { it.id } as Set<Int> }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Post counts per tag (cached), used to weigh specific tastes above broad ones. */
    private val tagCounts: StateFlow<Map<String, Int>> =
        c.prefs.tagCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The user's tastes, best first: counted over their likes, rarer tags weigh more. */
    val topTags: StateFlow<List<String>> = combine(likes, tagCounts, dislikes) { list, counts, bad ->
        rankTags(list, counts, bad).take(12).map { it.first }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The user's own tweaks to "For you" (pinned = include, blocked). */
    val forYouAdjust: StateFlow<TagList> = c.prefs.forYouAdjust
        .stateIn(viewModelScope, SharingStarted.Eagerly, TagList(FOR_YOU_ID, "For you"))

    /** Every blocked tag: the ones you blocked ("For you" and your lists) plus the ones "For you" blocked by itself. */
    val blockedTags: StateFlow<List<String>> = combine(forYouAdjust, lists, dislikes, likes) { adjust, all, bad, liked ->
        (adjust.blocked + all.flatMap { it.blocked } + autoBlocked(liked, bad)).distinct()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    // ---- feed ----
    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()

    private var page = 1
    private val seen = HashSet<Int>() // this feed session, for de-duplication

    /** Every post ever shown (loaded from the database at start), to skip repeats across sessions. */
    private val seenBefore = HashSet<Int>()
    private var seenLoaded = false
    private var hideSeen = true
    private var seenInserts = 0
    private var job: Job? = null
    private var query = ""
    private var excludeLiked = false
    private var videoQuality = VideoQuality.P480

    private val _forYouNow = MutableStateFlow<List<String>>(emptyList())

    /** The tags "For you" drew for the current feed, so the user can see why they see what they see. */
    val forYouNow: StateFlow<List<String>> = _forYouNow.asStateFlow()

    /**
     * "For you" is re-drawn from the likes only when the feed (re)starts, never live:
     * liking a post must not rebuild the feed under the user's finger.
     */
    private suspend fun computeQuery(s: FeedSettings, all: List<TagList>): String {
        val forYou = s.activeListId == FOR_YOU_ID
        videoQuality = s.videoQuality
        hideSeen = s.hideSeen
        excludeLiked = forYou
        if (!seenLoaded) {
            seenBefore += c.db.seen().ids()
            seenLoaded = true
        }
        val list = when (s.activeListId) {
            FOR_YOU_ID -> {
                val adjust = c.prefs.forYouAdjust.first()
                buildForYou(
                    dao.snapshot().map { it.toPost() },
                    c.prefs.tagCounts.first(),
                    adjust.include,
                    adjust.blocked,
                    c.db.disliked().snapshot().map { it.toPost() },
                ).also { _forYouNow.value = it.include }
            }
            SEARCH_ID -> searchList(s.searchTags)
            else -> all.firstOrNull { it.id == s.activeListId }
        }
        return buildQuery(s, list)
    }

    init {
        viewModelScope.launch {
            combine(c.prefs.settings, c.db.lists().all()) { s, all ->
                s to all.map { it.toModel() }
            }.distinctUntilChanged { old, new ->
                // For You depends on the likes, not on the lists table: ignore list edits while it is active
                old.first == new.first && (old.first.activeListId == FOR_YOU_ID || old.second == new.second)
            }.collect { (s, all) ->
                query = computeQuery(s, all)
                restartFeed()
            }
        }
    }

    init {
        // Learn how broad each liked tag is (one cheap request per tag, cached forever).
        // Runs quietly after the feed has started; the next feed refresh benefits.
        viewModelScope.launch {
            combine(likes, tagCounts) { l, counts -> tagsMissingCounts(l, counts) }
                .distinctUntilChanged()
                .collectLatest { missing ->
                    if (missing.isEmpty()) return@collectLatest
                    delay(4_000)
                    val updated = tagCounts.value.toMutableMap()
                    for (tag in missing) {
                        val found = try {
                            c.api.autocomplete(tag).firstOrNull { it.name == tag }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            break // offline or rate limited: try again next time
                        }
                        updated[tag] = found?.postCount ?: -1
                    }
                    c.prefs.saveTagCounts(updated)
                }
        }
    }

    init {
        // the app is in English now: rename the list older versions created as "Meu feed"
        viewModelScope.launch {
            val old = c.db.lists().all().first().firstOrNull { it.name == OLD_DEFAULT_LIST } ?: return@launch
            c.db.lists().upsert(old.copy(name = DEFAULT_LIST))
        }
    }

    init {
        // Keep a copy of liked media on the phone, newest likes first, within the chosen space limit.
        viewModelScope.launch {
            combine(dao.all(), c.prefs.offlineLimit) { l, limit -> l to limit }.collectLatest { (liked, limit) ->
                _offlineUsage.value = c.likedStore.usageBytes()
                if (limit == OfflineLimit.OFF) return@collectLatest
                delay(2_000) // let the feed's first requests go first
                for (entity in liked) {
                    if (c.likedStore.has(entity.id)) continue
                    if (c.likedStore.usageBytes() >= limit.bytes) break
                    c.likedStore.download(entity.toPost())
                    _offlineUsage.value = c.likedStore.usageBytes()
                }
            }
        }
    }

    /** Restarts the feed; for "For you" this draws a fresh sample of the user's tags. */
    fun refreshFeed() {
        viewModelScope.launch {
            query = computeQuery(settings.value, lists.value)
            restartFeed()
        }
    }

    private fun restartFeed() {
        job?.cancel()
        page = 1
        seen.clear()
        _feed.update { FeedState(generation = it.generation + 1) }
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true || _feed.value.endReached) return
        job = viewModelScope.launch {
            _feed.update { it.copy(loading = true, error = null) }
            try {
                var added = emptyList<Post>()
                var end = false
                var tries = 0
                // Some pages are fully filtered out (hidden posts, duplicates): keep going a few pages.
                // skipping already-seen posts can empty whole pages, so allow a few more tries
                while (added.isEmpty() && !end && tries < (if (hideSeen) 6 else 3)) {
                    tries++
                    val dtos = c.api.posts(query, page)
                    page++
                    end = dtos.isEmpty() || page > MAX_PAGE
                    added = dtos.mapNotNull { it.toPost(videoQuality) }
                        .filter { seen.add(it.id) && !(excludeLiked && it.id in likedIds.value) && !(hideSeen && it.id in seenBefore) }
                }
                _feed.update { it.copy(posts = it.posts + added, loading = false, endReached = end) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = errorText(e)
                val hadPosts = _feed.value.posts.isNotEmpty()
                _feed.update { it.copy(loading = false, error = text) }
                if (hadPosts) _messages.tryEmit(UiMessage(text))
            }
        }
    }

    private fun errorText(e: Exception): String = when {
        e is ApiException && e.code == 429 -> "Too many requests, wait a moment"
        e is ApiException && e.code >= 500 -> "e621 is down"
        e is ApiException -> "e621 returned ${e.code}"
        e is UnknownHostException || e is SocketTimeoutException || e is ConnectException -> "No connection"
        else -> "Something went wrong"
    }

    // ---- feed customisation ----
    fun applySettings(s: FeedSettings) {
        viewModelScope.launch { c.prefs.saveSettings(s) }
    }

    fun selectList(id: Long) {
        // picking the list that is already active = "give me a fresh feed"
        if (id == settings.value.activeListId) refreshFeed()
        else viewModelScope.launch { c.prefs.setActiveList(id) }
    }

    /** Creates or updates a list; the "For you" tweaks are stored separately. */
    fun saveList(list: TagList, activate: Boolean = true) {
        viewModelScope.launch {
            if (list.id == FOR_YOU_ID) {
                c.prefs.saveForYouAdjust(list)
                if (settings.value.activeListId == FOR_YOU_ID) refreshFeed()
            } else {
                upsertList(list, activate)
            }
        }
    }

    private suspend fun upsertList(list: TagList, activate: Boolean): Long {
        val position = if (list.id != 0L) c.db.lists().positionOf(list.id) else null
        val id = c.db.lists().upsert(list.toEntity(position ?: System.currentTimeMillis()))
        if (activate) c.prefs.setActiveList(id)
        return id
    }

    fun deleteList(id: Long) {
        viewModelScope.launch {
            c.db.lists().delete(id)
            if (settings.value.activeListId == id) c.prefs.setActiveList(FOR_YOU_ID)
        }
    }

    /** Adds (or blocks) one tag, e.g. from a profile chip. */
    fun addTag(raw: String, block: Boolean = false) {
        val (tag, negative) = normalizeTag(raw) ?: return
        val blockIt = block || negative
        applyTagMarks(
            mapOf(tag to if (blockIt) TagMark.BLOCK else TagMark.INCLUDE),
            (if (blockIt) "− " else "+ ") + tag,
        )
    }

    /**
     * Applies several tag changes at once, so the feed restarts a single time. In "For you" (generated)
     * the changes are remembered and take effect at the next refresh, so the feed doesn't jump; otherwise
     * they go to the active list. Always undoable.
     */
    fun applyTagMarks(changes: Map<String, TagMark>, message: String = "Tags updated") {
        if (changes.isEmpty()) return
        viewModelScope.launch {
            val active = activeList.value
            if (active?.id == FOR_YOU_ID) {
                val before = c.prefs.forYouAdjust.first()
                c.prefs.saveForYouAdjust(changes.entries.fold(before) { l, (tag, mark) -> l.withMark(tag, mark) })
                _messages.tryEmit(
                    UiMessage(message) {
                        viewModelScope.launch { c.prefs.saveForYouAdjust(before) }
                    },
                )
            } else {
                val before = active?.takeIf { it.id > 0 } ?: lists.value.firstOrNull { it.name == DEFAULT_LIST }
                val activeBefore = settings.value.activeListId
                val updated = changes.entries.fold(before ?: TagList(name = DEFAULT_LIST)) { l, (tag, mark) -> l.withMark(tag, mark) }
                val id = upsertList(updated, activate = true)
                _messages.tryEmit(
                    UiMessage(message) {
                        viewModelScope.launch {
                            if (before != null) upsertList(before, activate = false) else c.db.lists().delete(id)
                            c.prefs.setActiveList(activeBefore)
                        }
                    },
                )
            }
        }
    }

    /** A one-off search: the feed shows these tags (all together) until another list is picked. */
    fun search(raw: String) {
        val tags = raw.split(' ').mapNotNull { normalizeTag(it) }.map { (t, neg) -> if (neg) "-$t" else t }.distinct()
        if (tags.isEmpty()) return
        viewModelScope.launch { c.prefs.setSearch(tags) }
    }

    private fun searchList(tokens: List<String>) = TagList(
        id = SEARCH_ID,
        name = tokens.joinToString(" ") { if (it.startsWith("-")) it else "#$it" },
        include = tokens.filterNot { it.startsWith("-") },
        blocked = tokens.filter { it.startsWith("-") }.map { it.drop(1) },
        anyMode = false,
    )

    /** Called when a post settles on screen in the feed: remembers it (skip later, show in history). */
    fun markSeen(post: Post) {
        seenBefore += post.id
        viewModelScope.launch {
            c.db.seen().insert(SeenEntity(post.id, System.currentTimeMillis()))
            c.db.history().insert(post.toHistory())
            if (++seenInserts % 50 == 0) {
                c.db.seen().trim(SEEN_KEEP)
                c.db.history().trim(HISTORY_KEEP)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { c.db.history().clear() }
    }

    /** Forgets every post marked as seen, so they can show up in the feed again. */
    fun forgetSeen() {
        viewModelScope.launch {
            c.db.seen().clear()
            seenBefore.clear()
            _messages.tryEmit(UiMessage("Seen posts forgotten"))
        }
    }

    /** "Less like this": this post's key tags and artists weigh against them in "For you". Undoable. */
    fun dislike(post: Post) {
        viewModelScope.launch {
            c.db.disliked().insert(
                DislikedEntity(post.id, System.currentTimeMillis(), post.artists.joinToString(" "), post.keyTags.joinToString(" ")),
            )
            _messages.tryEmit(
                UiMessage("Less of this in For you") { viewModelScope.launch { c.db.disliked().delete(post.id) } },
            )
        }
    }

    fun setOfflineLimit(limit: OfflineLimit) {
        viewModelScope.launch {
            c.prefs.setOfflineLimit(limit)
            if (limit == OfflineLimit.OFF) c.likedStore.clear()
        }
    }

    /** Saves the post's original file to the phone's gallery. */
    fun saveToPhone(post: Post) {
        viewModelScope.launch {
            _messages.tryEmit(UiMessage("Downloading…"))
            try {
                val where = saveToDevice(getApplication(), c.mediaClient, post.originalUrl ?: post.mediaUrl, post.id)
                _messages.tryEmit(UiMessage("Saved to $where"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage("Couldn't save: ${e.message}"))
            }
        }
    }

    /** Takes a tag off every block list; an automatic block is lifted by forgetting the posts that caused it. */
    fun unblock(tag: String) {
        viewModelScope.launch {
            val adjust = c.prefs.forYouAdjust.first()
            if (tag in adjust.blocked) c.prefs.saveForYouAdjust(adjust.withMark(tag, TagMark.NONE))
            lists.value.filter { tag in it.blocked }.forEach { upsertList(it.withMark(tag, TagMark.NONE), activate = false) }
            c.db.disliked().deleteWithTag(tag)
            _messages.tryEmit(UiMessage("Unblocked $tag"))
        }
    }

    suspend fun suggest(query: String): List<TagSuggestion> = try {
        c.api.autocomplete(query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    // ---- likes ----
    fun toggleLike(post: Post) {
        viewModelScope.launch {
            if (post.id in likedIds.value) {
                dao.delete(post.id)
                c.likedStore.delete(post.id)
            } else {
                dao.insert(post.toEntity())
            }
        }
    }

    fun like(post: Post) {
        if (post.id in likedIds.value) return
        viewModelScope.launch { dao.insert(post.toEntity()) }
    }

    fun clearLikes() {
        viewModelScope.launch {
            dao.deleteAll()
            c.likedStore.clear()
        }
    }

    // ---- profile ----
    fun setName(name: String) {
        viewModelScope.launch { c.prefs.setProfileName(name) }
    }

    fun confirmAdult() {
        viewModelScope.launch { c.prefs.setAdultOk() }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val adjust = c.prefs.forYouAdjust.first()
                val file = BackupFile(
                    name = profileName.value,
                    likes = dao.snapshot(),
                    lists = lists.value.map { BackupList(it.name, it.include, it.blocked, it.anyMode) },
                    forYouPinned = adjust.include,
                    forYouBlocked = adjust.blocked,
                )
                val text = backupJson.encodeToString(BackupFile.serializer(), file)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
                _messages.tryEmit(UiMessage("Exported ${file.likes.size} likes and ${file.lists.size} lists"))
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage("Export failed: ${e.message}"))
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                } ?: error("empty file")
                val file = backupJson.decodeFromString(BackupFile.serializer(), text)
                dao.insertAll(file.likes)
                if (file.name.isNotBlank() && profileName.value == "Guest") c.prefs.setProfileName(file.name)
                // lists are added unless one with the same name already exists
                val existing = lists.value.map { it.name }.toSet()
                val newLists = file.lists.filter { it.name !in existing }
                newLists.forEach { upsertList(TagList(name = it.name, include = it.include, blocked = it.blocked, anyMode = it.anyMode), activate = false) }
                val adjust = c.prefs.forYouAdjust.first()
                if (adjust.include.isEmpty() && adjust.blocked.isEmpty() && (file.forYouPinned.isNotEmpty() || file.forYouBlocked.isNotEmpty())) {
                    c.prefs.saveForYouAdjust(adjust.copy(include = file.forYouPinned, blocked = file.forYouBlocked))
                }
                _messages.tryEmit(UiMessage("Imported ${file.likes.size} likes and ${newLists.size} lists"))
            } catch (e: Exception) {
                _messages.tryEmit(UiMessage("Invalid file: ${e.message}"))
            }
        }
    }
}
