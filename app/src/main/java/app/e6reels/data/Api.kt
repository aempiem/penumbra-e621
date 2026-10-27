package app.e6reels.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class ApiException(val code: Int) : IOException("e621 returned HTTP $code")

const val USER_AGENT = "Penumbra/1.0 (personal Android client)"

/** e621 asks for at most ~1-2 requests per second. */
private class RateLimitInterceptor(private val minIntervalMs: Long) : Interceptor {
    private var last = 0L

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        return chain.proceed(chain.request())
    }
}

private class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
}

/** Client for images/videos from the CDN (no rate limit needed). */
fun buildMediaClient(): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(UserAgentInterceptor()).build()

class E621Api(mediaClient: OkHttpClient) {
    private val client = mediaClient.newBuilder().addInterceptor(RateLimitInterceptor(700)).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code)
            resp.body?.string() ?: throw IOException("Empty response")
        }
    }

    suspend fun posts(tags: String, page: Int, limit: Int = 30): List<PostDto> {
        val url = "https://e621.net/posts.json".toHttpUrl().newBuilder()
            .addQueryParameter("tags", tags)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return json.decodeFromString(PostsResponse.serializer(), get(url.toString())).posts
    }

    suspend fun autocomplete(query: String): List<TagSuggestion> {
        val url = "https://e621.net/tags/autocomplete.json".toHttpUrl().newBuilder()
            .addQueryParameter("search[name_matches]", query)
            .addQueryParameter("expiry", "7")
            .build()
        return json.decodeFromString(ListSerializer(TagSuggestion.serializer()), get(url.toString()))
    }
}

/** Turns the active list + global filters into an e621 search string. */
fun buildQuery(s: FeedSettings, list: TagList?): String {
    val parts = mutableListOf<String>()
    if (list != null) {
        val any = list.anyMode && list.include.size > 1
        parts += list.include.map { if (any) "~$it" else it }
        parts += list.blocked.map { "-$it" }
    }
    when (s.ratings.size) {
        1 -> parts += "rating:${s.ratings.first()}"
        2 -> parts += "-rating:${listOf("s", "q", "e").first { it !in s.ratings }}"
    }
    s.media.tag?.let { parts += it }
    if (s.minScore > 0) parts += "score:>=${s.minScore}"
    s.sort.order?.let { parts += it }
    parts += "-type:swf"
    return parts.joinToString(" ")
}
