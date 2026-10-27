package app.e6reels.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A liked post, with enough of a snapshot to play it back later without the API. */
@Serializable
@Entity(tableName = "liked")
data class LikedEntity(
    @PrimaryKey val id: Int,
    val likedAt: Long,
    val mediaUrl: String,
    val posterUrl: String,
    val thumbUrl: String,
    val ext: String,
    val width: Int,
    val height: Int,
    val score: Int,
    val rating: String,
    val artists: String,
    val keyTags: String,
    val tags: String,
)

private fun String.words() = if (isBlank()) emptyList() else split(' ')

fun LikedEntity.toPost() = Post(
    id = id, mediaUrl = mediaUrl, posterUrl = posterUrl, thumbUrl = thumbUrl, ext = ext,
    width = width, height = height, score = score, rating = rating,
    artists = artists.words(), keyTags = keyTags.words(), tags = tags.words(),
)

fun Post.toEntity(likedAt: Long = System.currentTimeMillis()) = LikedEntity(
    id = id, likedAt = likedAt, mediaUrl = mediaUrl, posterUrl = posterUrl, thumbUrl = thumbUrl,
    ext = ext, width = width, height = height, score = score, rating = rating,
    artists = artists.joinToString(" "), keyTags = keyTags.joinToString(" "), tags = tags.joinToString(" "),
)

@Dao
interface LikedDao {
    @Query("SELECT * FROM liked ORDER BY likedAt DESC")
    fun all(): Flow<List<LikedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LikedEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LikedEntity>)

    @Query("DELETE FROM liked WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM liked")
    suspend fun deleteAll()

    @Query("SELECT * FROM liked ORDER BY likedAt DESC")
    suspend fun snapshot(): List<LikedEntity>
}

/** A named group of tags. The feed shows whichever list is active. */
@Entity(tableName = "tag_list")
data class TagListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val include: String,
    val blocked: String,
    /** true = posts with ANY of the tags (e621 "~tag"), false = posts with ALL of them. */
    val anyMode: Boolean,
    val position: Long,
)

/** How a tag acts on a list: ignored, required/wanted, or excluded. */
enum class TagMark { NONE, INCLUDE, BLOCK }

data class TagList(
    val id: Long = 0,
    val name: String,
    val include: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val anyMode: Boolean = true,
) {
    fun withMark(tag: String, mark: TagMark): TagList = when (mark) {
        TagMark.NONE -> copy(include = include - tag, blocked = blocked - tag)
        TagMark.INCLUDE -> withTag(tag, block = false)
        TagMark.BLOCK -> withTag(tag, block = true)
    }

    fun markOf(tag: String): TagMark = when (tag) {
        in blocked -> TagMark.BLOCK
        in include -> TagMark.INCLUDE
        else -> TagMark.NONE
    }

    fun withTag(tag: String, block: Boolean): TagList =
        if (block) copy(include = include - tag, blocked = (blocked + tag).distinct())
        else copy(blocked = blocked - tag, include = (include + tag).distinct())
}

fun TagListEntity.toModel() = TagList(id, name, include.words(), blocked.words(), anyMode)
fun TagList.toEntity(position: Long = System.currentTimeMillis()) = TagListEntity(
    id = id, name = name, include = include.joinToString(" "), blocked = blocked.joinToString(" "),
    anyMode = anyMode, position = position,
)

@Dao
interface TagListDao {
    @Query("SELECT * FROM tag_list ORDER BY position")
    fun all(): Flow<List<TagListEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TagListEntity): Long

    @Query("DELETE FROM tag_list WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT position FROM tag_list WHERE id = :id")
    suspend fun positionOf(id: Long): Long?
}

/** Every post that has been on screen, by id only: lets the feed skip what was already seen. */
@Entity(tableName = "seen")
data class SeenEntity(@PrimaryKey val id: Int, val seenAt: Long)

/** The most recent posts seen, with a snapshot so they can be reopened (the "History" tab). */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: Int,
    val seenAt: Long,
    val mediaUrl: String,
    val posterUrl: String,
    val thumbUrl: String,
    val ext: String,
    val width: Int,
    val height: Int,
    val score: Int,
    val rating: String,
    val artists: String,
    val keyTags: String,
    val tags: String,
)

fun HistoryEntity.toPost() = Post(
    id = id, mediaUrl = mediaUrl, posterUrl = posterUrl, thumbUrl = thumbUrl, ext = ext,
    width = width, height = height, score = score, rating = rating,
    artists = artists.words(), keyTags = keyTags.words(), tags = tags.words(),
)

fun Post.toHistory(seenAt: Long = System.currentTimeMillis()) = HistoryEntity(
    id = id, seenAt = seenAt, mediaUrl = mediaUrl, posterUrl = posterUrl, thumbUrl = thumbUrl,
    ext = ext, width = width, height = height, score = score, rating = rating,
    artists = artists.joinToString(" "), keyTags = keyTags.joinToString(" "), tags = tags.take(60).joinToString(" "),
)

/** A post the user marked "menos disso": its key tags and artists count against them in "For you". */
@Entity(tableName = "disliked")
data class DislikedEntity(@PrimaryKey val id: Int, val dislikedAt: Long, val artists: String, val keyTags: String)

fun DislikedEntity.toPost() = Post(
    id = id, mediaUrl = "", posterUrl = "", thumbUrl = "", ext = "", width = 0, height = 0, score = 0, rating = "",
    artists = artists.words(), keyTags = keyTags.words(), tags = emptyList(),
)

@Dao
interface SeenDao {
    @Query("SELECT id FROM seen")
    suspend fun ids(): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SeenEntity)

    /** Keeps only the newest [keep] ids so the table can't grow forever. */
    @Query("DELETE FROM seen WHERE id NOT IN (SELECT id FROM seen ORDER BY seenAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM seen")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM seen")
    fun count(): Flow<Int>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY seenAt DESC")
    fun all(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY seenAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface DislikedDao {
    @Query("SELECT * FROM disliked ORDER BY dislikedAt DESC")
    suspend fun snapshot(): List<DislikedEntity>

    @Query("SELECT * FROM disliked ORDER BY dislikedAt DESC")
    fun all(): Flow<List<DislikedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DislikedEntity)

    @Query("DELETE FROM disliked WHERE id = :id")
    suspend fun delete(id: Int)

    /** Forgets every "less like this" post that has [tag] (whole word) among its tags or artists. */
    @Query("DELETE FROM disliked WHERE instr(' ' || keyTags || ' ' || artists || ' ', ' ' || :tag || ' ') > 0")
    suspend fun deleteWithTag(tag: String)

    @Query("DELETE FROM disliked")
    suspend fun clear()
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `seen` (`id` INTEGER NOT NULL, `seenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER NOT NULL, `seenAt` INTEGER NOT NULL, " +
                "`mediaUrl` TEXT NOT NULL, `posterUrl` TEXT NOT NULL, `thumbUrl` TEXT NOT NULL, `ext` TEXT NOT NULL, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `score` INTEGER NOT NULL, `rating` TEXT NOT NULL, " +
                "`artists` TEXT NOT NULL, `keyTags` TEXT NOT NULL, `tags` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `disliked` (`id` INTEGER NOT NULL, `dislikedAt` INTEGER NOT NULL, " +
                "`artists` TEXT NOT NULL, `keyTags` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_list` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `include` TEXT NOT NULL, `blocked` TEXT NOT NULL, " +
                "`anyMode` INTEGER NOT NULL, `position` INTEGER NOT NULL)",
        )
    }
}

@Database(
    entities = [LikedEntity::class, TagListEntity::class, SeenEntity::class, HistoryEntity::class, DislikedEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun liked(): LikedDao
    abstract fun lists(): TagListDao
    abstract fun seen(): SeenDao
    abstract fun history(): HistoryDao
    abstract fun disliked(): DislikedDao
}
