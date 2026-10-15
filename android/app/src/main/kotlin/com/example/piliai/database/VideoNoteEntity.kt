package com.example.piliai.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 审核319：视频笔记（私有）本地存储。
 *
 * 与 BiliPai 的差异：BiliPai 的笔记同时支持私有与**公开**（发布到 B 站笔记
 * 接口）。本轮只做私有——公开笔记需要 B 站笔记发布接口的完整鉴权与内容审核
 * 语义，且一旦发布就对外可见、不可随意撤回，风险与收益不成正比。因此
 * 诚实标注：piliai 的笔记是纯本地的私有笔记。
 *
 * 存储键 (bvid, cid)：一个视频可能有多个分P，每 P 一份独立笔记。
 */
@Entity(tableName = "video_note", primaryKeys = ["bvid", "cid"])
data class VideoNoteEntity(
    val bvid: String,
    val cid: Long,
    val title: String = "",
    /** Quill delta JSON（与 BiliPai 同格式，便于将来互通） */
    val content: String = "[]",
    /** 从内容提取的纯文本摘要（列表预览用，避免每次解码） */
    val summary: String = "",
    /** 标签（空格分隔，Quill 的 #tag 属性） */
    val tags: String = "",
    /** 纯文本长度 */
    val contentLength: Int = 0,
    val videoTitle: String = "",
    val cover: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface VideoNoteDao {

    @Query("SELECT * FROM video_note ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<VideoNoteEntity>>

    @Query("SELECT * FROM video_note WHERE bvid = :bvid AND cid = :cid")
    suspend fun get(bvid: String, cid: Long): VideoNoteEntity?

    /** 该视频是否已有任意分P 的笔记（详情页入口据此显示） */
    @Query("SELECT COUNT(*) FROM video_note WHERE bvid = :bvid")
    suspend fun countForVideo(bvid: String): Int

    @Upsert
    suspend fun upsert(note: VideoNoteEntity)

    @Query("DELETE FROM video_note WHERE bvid = :bvid AND cid = :cid")
    suspend fun delete(bvid: String, cid: Long)

    @Query("DELETE FROM video_note WHERE bvid = :bvid")
    suspend fun deleteForVideo(bvid: String)
}
