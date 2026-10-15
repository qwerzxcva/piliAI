package com.example.piliai.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.piliai.database.DownloadItemEntity
import com.example.piliai.database.PiliNaraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 审核318：离线下载 Worker。
 *
 * 背景：DownloadManager 原来用裸 CoroutineScope(SupervisorJob()) 起协程，
 * 进程一死（用户退出应用 / 系统回收）任务就断，Room 里永远停在中间进度，
 * 半成品文件留在磁盘上。改为 WorkManager 后：
 * - 系统会重新调度被中断的任务，退到后台也不被杀；
 * - 前台通知（setForeground）让用户看到进度，符合 Android 8+ 对后台任务的约束；
 * - 唯一任务名（unique work）保证同一 bvid 不会被并发下载两次。
 *
 * 本 Worker 只负责「执行一次下载」，取流逻辑仍复用 DownloadManager 暴露的
 * 内部函数（[com.example.piliai.data.repository.DownloadManager] 保留为
 * DAO 门面 + 取流实现），避免把上千行逻辑搬过来。
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_BVID = "bvid"
        const val KEY_CID = "cid"
        const val KEY_KIND = "kind"
        const val KEY_TITLE = "title"
        const val KEY_COVER = "cover"
        const val KEY_OWNER = "owner"
        const val KEY_PAGE = "page"
        const val KEY_PAGE_LABEL = "pageLabel"
        const val KEY_DURATION = "duration"

        /** 普通视频（单P） */
        const val KIND_AV = "av"
        /** 普通视频指定分P */
        const val KIND_PART = "part"
        /** 番剧分集（epId 走 pgc playurl） */
        const val KIND_PGC = "pgc"

        /** 进度通知的渠道 id（与通知构造保持同一字符串） */
        const val CHANNEL_ID = "download"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString(KEY_BVID)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()
        val kind = inputData.getString(KEY_KIND) ?: KIND_AV
        val dao = PiliNaraDatabase.getDatabase(applicationContext).downloadItemDao()

        // 前台化：立刻挂通知，避免系统在长任务中途杀掉本进程
        setForeground(foregroundInfo(0))

        try {
            when (kind) {
                KIND_AV -> com.example.piliai.data.repository.DownloadManager
                    .runDownload(applicationContext, key)
                KIND_PART -> com.example.piliai.data.repository.DownloadManager
                    .runDownloadPart(
                        applicationContext,
                        key,
                        inputData.getLong(KEY_CID, 0L),
                        inputData.getInt(KEY_PAGE, 1),
                        inputData.getString(KEY_PAGE_LABEL).orEmpty(),
                        inputData.getLong(KEY_DURATION, 0L),
                        inputData.getString(KEY_TITLE).orEmpty(),
                        inputData.getString(KEY_COVER).orEmpty(),
                        inputData.getString(KEY_OWNER).orEmpty(),
                    )
                KIND_PGC -> com.example.piliai.data.repository.DownloadManager
                    .runDownloadPgc(
                        applicationContext,
                        key,
                        inputData.getLong(KEY_CID, 0L),
                        inputData.getInt(KEY_PAGE, 1),
                        inputData.getString(KEY_PAGE_LABEL).orEmpty(),
                        inputData.getLong(KEY_DURATION, 0L),
                        inputData.getString(KEY_TITLE).orEmpty(),
                        inputData.getString(KEY_COVER).orEmpty(),
                        inputData.getString(KEY_OWNER).orEmpty(),
                    )
                else -> return@withContext Result.failure()
            }
            // 取消（用户主动 / 系统限制）在 DownloadManager 内把记录置为 PAUSED，
            // 这里据实返回 retry：下次点恢复时重新入队即可续传。
            val final = dao.getByBvid(key)
            when (final?.state) {
                DownloadItemEntity.STATE_DONE -> Result.success()
                DownloadItemEntity.STATE_PAUSED -> Result.success()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            dao.upsert(
                (dao.getByBvid(key) ?: DownloadItemEntity(bvid = key)).copy(
                    state = DownloadItemEntity.STATE_FAILED,
                    error = e.message,
                )
            )
            Result.retry()
        }
    }

    /** 进度通知。文案从 Room 读，标题为空时退化为 bvid。 */
    private suspend fun foregroundInfo(progress: Int): ForegroundInfo {
        val key = inputData.getString(KEY_BVID).orEmpty()
        val item = runCatching {
            PiliNaraDatabase.getDatabase(applicationContext).downloadItemDao().getByBvid(key)
        }.getOrNull()
        val title = item?.title?.takeIf { it.isNotBlank() } ?: key
        val notification = com.example.piliai.data.download.DownloadNotifier
            .progress(applicationContext, title, progress)
        return ForegroundInfo(key.hashCode(), notification)
    }
}
