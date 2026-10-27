package app.e6reels.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** How much space liked media may take on the phone. */
enum class OfflineLimit(val label: String, val bytes: Long) {
    OFF("Off", 0),
    MB500("500 MB", 500L * 1024 * 1024),
    GB2("2 GB", 2L * 1024 * 1024 * 1024),
    UNLIMITED("No limit", Long.MAX_VALUE),
}

/**
 * Keeps a copy of liked media in the app's private storage, so a like keeps working even if the post is
 * deleted from e621 or the phone is offline. Files are named by post id: `<id>.<ext>` and `<id>_thumb.jpg`.
 */
class LikedStore(context: Context, private val client: OkHttpClient) {
    private val dir = File(context.filesDir, "liked").apply { mkdirs() }

    private val _version = MutableStateFlow(0)

    /** Bumps whenever a file is added or removed, so lists that show local copies can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun mediaFile(id: Int): File? = dir.listFiles { f -> f.name.startsWith("$id.") }?.firstOrNull()
    private fun thumbFile(id: Int) = File(dir, "${id}_thumb.jpg")

    fun has(id: Int): Boolean = mediaFile(id) != null

    /** The post pointing at the local copies when they exist. Call off the main thread. */
    fun resolve(post: Post): Post {
        val media = mediaFile(post.id) ?: return post
        val local = media.toURI().toString()
        val thumb = thumbFile(post.id).takeIf { it.exists() }?.toURI()?.toString() ?: post.thumbUrl
        return if (post.isVideo) post.copy(mediaUrl = local, thumbUrl = thumb)
        else post.copy(mediaUrl = local, posterUrl = local, thumbUrl = thumb)
    }

    suspend fun usageBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Downloads a liked post's media and thumbnail. Returns false when it couldn't (offline, gone...). */
    suspend fun download(post: Post): Boolean = withContext(Dispatchers.IO) {
        if (post.mediaUrl.startsWith("file:")) return@withContext true
        try {
            val ext = post.mediaUrl.substringAfterLast('.', "bin").substringBefore('?').take(5)
            fetch(post.thumbUrl, thumbFile(post.id))
            fetch(post.mediaUrl, File(dir, "${post.id}.$ext"))
            _version.value++
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun fetch(url: String, target: File) {
        if (target.exists()) return
        val tmp = File(target.path + ".part")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        mediaFile(id)?.delete()
        thumbFile(id).delete()
        _version.value++
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        _version.value++
    }
}

/**
 * Saves a post's file to the phone's gallery (Pictures/Penumbra or Movies/Penumbra). On Android 9 and older,
 * where the gallery needs a storage permission, it goes to the app's own Pictures folder instead.
 */
suspend fun saveToDevice(context: Context, client: OkHttpClient, url: String, postId: Int): String =
    withContext(Dispatchers.IO) {
        val ext = url.substringAfterLast('.', "bin").substringBefore('?').lowercase().take(5)
        val isVideo = ext == "mp4" || ext == "webm"
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        val name = "penumbra_$postId.$ext"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty")
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Penumbra")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IOException("no gallery access")
                try {
                    resolver.openOutputStream(uri).use { out: OutputStream? -> body.byteStream().copyTo(out ?: throw IOException("no access")) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                if (isVideo) "Movies/Penumbra" else "Pictures/Penumbra"
            } else {
                val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Penumbra").apply { mkdirs() }
                File(folder, name).outputStream().use { body.byteStream().copyTo(it) }
                "the app's folder"
            }
        }
    }
