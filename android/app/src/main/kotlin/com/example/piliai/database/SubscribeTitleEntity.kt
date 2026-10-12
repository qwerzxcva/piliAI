package com.example.piliai.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 追番条目（animeko「收藏」页移植，审核240）
 *
 * 与 [SubscribeSourceEntity]（数据源）/ [SubscribeItemEntity]（数据源解析出的
 * 条目快照）是三层：本表记录用户**在追的番**，一条 = 一部作品 + 收藏状态 + 进度。
 *
 * 设计要点：
 * - `bangumiId` 与 Bangumi 条目的 subject_id 对应；`sourceKey` 是订阅源内的
 *   稳定标识（web-selector 源没有全局 id，用「源名 + 作品名」归一化）。
 * - 唯一索引 `(sourceId, sourceKey)`：同一源内同一作品只占一行，刷新时
 *   不会因为标题微调而重复成行。
 * - `latestEpisode`/`lastWatchedEpisode` 用于「只看更新」：有新集且未看到。
 * - 删除用户数据必须显式 delete，不用 destructive migration。
 */
@Entity(
    tableName = "subscribe_title",
    indices = [
        Index(value = ["sourceId", "sourceKey"], unique = true),
        Index(value = ["bangumiId"]),
    ]
)
data class SubscribeTitleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceId: Long = 0L,              // 所属订阅源（删源时级联清理）
    val sourceKey: String = "",           // 源内稳定标识（作品 URL 或 归一化标题）
    val name: String = "",                // 作品名（展示用，允许随源更新）
    val cover: String = "",               // 封面 URL
    val intro: String = "",               // 简介
    val bangumiId: Long = 0L,             // Bangumi subject_id（0=未关联）
    val collectionType: Int = COLLECTION_DOING,  // 收藏状态
    val latestEpisode: Int = 0,           // 源侧最新集数（0=未知）
    val lastWatchedEpisode: Int = 0,      // 用户看到第几集
    val rating: Double = 0.0,             // Bangumi 评分（0=未取到）
    val airWeekday: Int = 0,              // 放送星期（1-7，0=未知）
    /** 上次与 Bangumi 同步成功的时间（0=从未同步，审核240） */
    val bangumiSyncedAt: Long = 0L,
    /** Bangumi 侧是否为私有收藏（同步时避免把私有条目公开，审核240） */
    val bangumiPrivate: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // 与 Bangumi 官方 SubjectCollectionType 一一对应。
        // ⚠️ 顺序反常（官方 openapi + api.bgm.tv 实测）：1=想看、2=看过、3=在看。
        // 早期实现按 2=在看/3=看过 写库，MIGRATION_4_5 已做数据纠正。
        const val COLLECTION_WISH = 1      // 想看
        const val COLLECTION_DONE = 2      // 看过
        const val COLLECTION_DOING = 3     // 在看
        const val COLLECTION_ON_HOLD = 4   // 搁置
        const val COLLECTION_DROPPED = 5   // 抛弃

        fun fromBangumiType(type: Int): Int = when (type) {
            COLLECTION_WISH, COLLECTION_DONE, COLLECTION_DOING,
            COLLECTION_ON_HOLD, COLLECTION_DROPPED -> type
            else -> COLLECTION_DOING
        }
    }

    /** 是否有未看的新集（「只看更新」筛选用） */
    val hasUnwatched: Boolean
        get() = latestEpisode > 0 && lastWatchedEpisode < latestEpisode

    /** 未看集数（0=无新增） */
    val unwatchedCount: Int
        get() = if (latestEpisode > 0) (latestEpisode - lastWatchedEpisode).coerceAtLeast(0) else 0
}

@Dao
interface SubscribeTitleDao {

    @Query("SELECT * FROM subscribe_title ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SubscribeTitleEntity>>

    @Query("SELECT * FROM subscribe_title WHERE collectionType = :type ORDER BY updatedAt DESC")
    fun observeByType(type: Int): Flow<List<SubscribeTitleEntity>>

    @Query(
        "SELECT * FROM subscribe_title WHERE latestEpisode > lastWatchedEpisode " +
            "ORDER BY updatedAt DESC"
    )
    fun observeUnwatched(): Flow<List<SubscribeTitleEntity>>

    @Query("SELECT * FROM subscribe_title WHERE id = :id")
    suspend fun getById(id: Long): SubscribeTitleEntity?

    @Query("SELECT * FROM subscribe_title WHERE sourceId = :sourceId AND sourceKey = :sourceKey LIMIT 1")
    suspend fun getByKey(sourceId: Long, sourceKey: String): SubscribeTitleEntity?

    @Query("SELECT * FROM subscribe_title WHERE bangumiId = :bangumiId LIMIT 1")
    suspend fun getByBangumiId(bangumiId: Long): SubscribeTitleEntity?

    /** 一次性取某源全部追番（同步时比对主键用，避免重复成行） */
    @Query("SELECT * FROM subscribe_title WHERE sourceId = :sourceId")
    suspend fun getBySourceOnce(sourceId: Long): List<SubscribeTitleEntity>

    @Upsert
    suspend fun upsert(entity: SubscribeTitleEntity): Long

    @Upsert
    suspend fun upsertAll(list: List<SubscribeTitleEntity>)

    @Query("UPDATE subscribe_title SET collectionType = :type WHERE id = :id")
    suspend fun setCollectionType(id: Long, type: Int)

    @Query("UPDATE subscribe_title SET lastWatchedEpisode = :episode WHERE id = :id")
    suspend fun setWatchedEpisode(id: Long, episode: Int)

    @Query("UPDATE subscribe_title SET latestEpisode = :episode WHERE id = :id")
    suspend fun setLatestEpisode(id: Long, episode: Int)

    @Query("UPDATE subscribe_title SET bangumiId = :bangumiId WHERE id = :id")
    suspend fun setBangumiId(id: Long, bangumiId: Long)

    @Query("UPDATE subscribe_title SET bangumiSyncedAt = :at WHERE id = :id")
    suspend fun setBangumiSyncedAt(id: Long, at: Long)

    @Query("DELETE FROM subscribe_title WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 删除订阅源时同步清掉它的追番，避免孤儿行 */
    @Query("DELETE FROM subscribe_title WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)
}
