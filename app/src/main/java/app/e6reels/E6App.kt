package app.e6reels

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import app.e6reels.data.AppDb
import app.e6reels.data.E621Api
import app.e6reels.data.LikedStore
import app.e6reels.data.MIGRATION_1_2
import app.e6reels.data.MIGRATION_2_3
import androidx.media3.datasource.DefaultDataSource
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import app.e6reels.data.Prefs
import app.e6reels.data.buildMediaClient
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File

@UnstableApi
class Container(context: Context) {
    val mediaClient = buildMediaClient()
    val api = E621Api(mediaClient)
    val prefs = Prefs(context)
    val db = Room.databaseBuilder(context, AppDb::class.java, "e6reels.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    /**
     * Videos get their own HTTP/1.1 client with its own connection pool. On the shared HTTP/2 connection,
     * the preloaded videos that stop reading (their buffer is full) keep their streams open, and their unread
     * bytes count against the one flow-control window of the whole connection: enough of them and the video
     * on screen (and every image) stops receiving data. Separate connections can't block each other.
     */
    val videoClient: OkHttpClient = mediaClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
        .build()

    /** Liked media kept on the phone, so likes survive the post being deleted on e621. */
    val likedStore = LikedStore(context, mediaClient)

    private val videoCache = SimpleCache(
        File(context.cacheDir, "video"),
        LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
        StandaloneDatabaseProvider(context),
    )

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(videoCache)
        // DefaultDataSource also opens file:// (liked media kept on the phone); http goes to the video client
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(videoClient)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

@androidx.annotation.OptIn(UnstableApi::class)
class E6App : Application(), ImageLoaderFactory {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.mediaClient)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
