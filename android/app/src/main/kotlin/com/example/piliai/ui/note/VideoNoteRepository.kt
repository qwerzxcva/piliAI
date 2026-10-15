package com.example.piliai.ui.note

import android.content.Context
import com.example.piliai.database.PiliNaraDatabase
import com.example.piliai.database.VideoNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * 审核319：视频笔记仓库（纯本地私有笔记）。
 *
 * 不做 B 站的公开笔记发布——公开笔记发布后对外可见、不可随意撤回，
 * 需要完整鉴权与内容语义评估，风险与收益不成正比。因此这里只提供
 * 本地 CRUD，诚实标注为「私有笔记」。
 */
class VideoNoteRepository(private val context: Context) {

    private fun dao() = PiliNaraDatabase.getDatabase(context).videoNoteDao()

    fun observeAll(): Flow<List<VideoNoteEntity>> = dao().observeAll()

    suspend fun load(bvid: String, cid: Long): VideoNoteEntity? = dao().get(bvid, cid)

    /** 该视频是否已有笔记（详情页入口据此显示） */
    suspend fun hasNote(bvid: String): Boolean = dao().countForVideo(bvid) > 0

    /**
     * 保存笔记。content 是 Quill delta JSON（由 [VideoNoteCodec.encode] 产出），
     * summary/tags/contentLength 一并物化，避免列表页每次解码全部内容。
     */
    suspend fun save(
        bvid: String,
        cid: Long,
        title: String,
        content: String,
        summary: String,
        tags: String,
        contentLength: Int,
        videoTitle: String,
        cover: String,
    ) {
        val existing = dao().get(bvid, cid)
        dao().upsert(
            VideoNoteEntity(
                bvid = bvid,
                cid = cid,
                title = title,
                content = content,
                summary = summary,
                tags = tags,
                contentLength = contentLength,
                videoTitle = videoTitle,
                cover = cover,
                // 保留首次创建时间语义：updatedAt 由 Entity 默认值取当前时间
                updatedAt = System.currentTimeMillis(),
            )
        )
        // existing 仅用于将来扩展（如保留 createdAt），当前无字段需要合并
        if (existing != null) Unit
    }

    suspend fun delete(bvid: String, cid: Long) = dao().delete(bvid, cid)
}
