package com.example.piliai.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.piliai.data.download.DownloadWorker
import com.example.piliai.database.DownloadItemEntity
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.data.model.toParsed
import com.example.piliai.database.PiliNaraDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * 离线下载管理器（批次I）：
 * 详情取 cid → playurl(wbi+Rust 选流取 video/audio baseUrl) → 双流下载到
 * PiliNara/Downloads/{bvid}/ → Room 记录进度/状态 → 离线播放页直接播本地文件。
 *
 * 审核318：调度改 WorkManager。此前 [download]/[downloadPart]/[downloadPgcPart]
 * 各自 scope.launch 一个协程，进程一死（退出应用 / 系统回收）任务即断，Room
 * 永远停在中间进度、半成品文件留在磁盘上。现在这三个入口只负责**入队**，
 * 真正干活的 [runDownload]/[runDownloadPart]/[runDownloadPgc] 由
 * [DownloadWorker] 调用，系统会在任务被中断后重新调度。
 *
 * [cancel]/[pause] 用 cancelUniqueWork 取消在队/在跑的任务；协程内部感知
 * CancellationException 后把记录置为 PAUSED（保留半成品供续传）。
 */
object DownloadManager {

    // 审核318：scope 只保留给 delete() 的 fire-and-forget 清理（DAO + 文件）。
    // 下载任务本身已全部移交 WorkManager（DownloadWorker），不再有 running map。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 审核77：Gson 线程安全，复用实例避免每次下载重建
    private val gson = com.google.gson.Gson()

    private fun dao(ctx: Context) = PiliNaraDatabase.getDatabase(ctx).downloadItemDao()

    fun observeAll(ctx: Context): Flow<List<DownloadItemEntity>> =
        dao(ctx).observeAll()

    fun observeDone(ctx: Context): Flow<List<DownloadItemEntity>> =
        dao(ctx).observeAll().map { list -> list.filter { it.state == DownloadItemEntity.STATE_DONE } }

    suspend fun isDownloaded(ctx: Context, bvid: String): Boolean =
        dao(ctx).getByBvid(bvid)?.state == DownloadItemEntity.STATE_DONE

    /**
     * 审核318：入队一次下载。同 key 用 REPLACE 语义——排队中被再次调用会替换
     * 旧请求（而不是并发跑两个），运行中则被 WorkManager 的 unique work 挡住。
     */
    private fun enqueue(
        context: Context,
        key: String,
        kind: String,
        cid: Long = 0L,
        page: Int = 1,
        pageLabel: String = "",
        durationSec: Long = 0L,
        title: String = "",
        cover: String = "",
        ownerName: String = "",
    ) {
        val data = workDataOf(
            DownloadWorker.KEY_BVID to key,
            DownloadWorker.KEY_KIND to kind,
            DownloadWorker.KEY_CID to cid,
            DownloadWorker.KEY_PAGE to page,
            DownloadWorker.KEY_PAGE_LABEL to pageLabel,
            DownloadWorker.KEY_DURATION to durationSec,
            DownloadWorker.KEY_TITLE to title,
            DownloadWorker.KEY_COVER to cover,
            DownloadWorker.KEY_OWNER to ownerName,
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    // 下载必须联网；不要求 unmetered——用户可以在流量下主动下载
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .addTag("download")
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("download:$key", ExistingWorkPolicy.REPLACE, request)
    }

    /** 取消在队/在跑的任务（cancel/pause/delete 共用） */
    private fun cancelWork(context: Context, key: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("download:$key")
    }

    fun download(
        context: Context,
        bvid: String,
        title: String = "",
        cover: String = "",
        ownerName: String = ""
    ) {
        // 审核318：只入队，不再起裸协程（进程死则任务断）。
        // 已在队列/运行中的同 key 请求被 REPLACE 覆盖，不会并发下载两次。
        enqueue(
            context = context,
            key = bvid,
            kind = DownloadWorker.KIND_AV,
            title = title,
            cover = cover,
            ownerName = ownerName,
        )
    }

    /**
     * 审核318：普通视频下载的实际执行体，由 [DownloadWorker] 调用。
     * 挂起函数，进程内被取消时抛 CancellationException 并把记录置 PAUSED。
     */
    suspend fun runDownload(
        context: Context,
        bvid: String,
    ) {
        val appCtx = context.applicationContext
        val dao = dao(appCtx)
        try {
            val prev = dao.getByBvid(bvid)
            dao.upsert((prev ?: DownloadItemEntity(bvid = bvid)).copy(
                state = DownloadItemEntity.STATE_RUNNING, error = null
            ))

            val api = BiliApiClient()
            val repo = VideoRepository(api)
            // 1) 详情取 cid
            val detail = repo.getVideoDetail(bvid).getOrNull()?.data
                ?: throw IllegalStateException("详情获取失败")
            val cid = detail.cid
            val duration = detail.duration.toLong()
            // 2) playurl + Rust 选流（清晰度跟随 DataStore 设置）
            val qn = com.example.piliai.utils.StorageManager.getInstance(appCtx).videoQualityFlow.first()
                .let { when (it) { "1080p" -> 80; "720p" -> 64; "480p" -> 32; else -> 64 } }
            val play = repo.getPlayUrlWithCandidates(bvid, cid, qn = qn).getOrNull()
                ?: throw IllegalStateException("playurl 获取失败")
            val videoCand = play.second.first
            val audioCand = play.second.second
            val videoUrl = videoCand?.primary
            val audioUrl = audioCand?.primary
            if (videoUrl.isNullOrEmpty()) throw IllegalStateException("未取到视频流")

            // 3) 目录
            val dir = File(File(appCtx.getExternalFilesDir(null), "PiliNara/Downloads"), bvid)
            if (!dir.exists()) dir.mkdirs()

            // 审核56：下载前检查存储空间（不足则中止，避免损坏文件）
            val stat = android.os.StatFs(dir.absolutePath)
            val freeMb = stat.availableBytes / (1024 * 1024)
            if (freeMb < 200) throw IllegalStateException("存储空间不足（剩余 ${freeMb}MB，需至少 200MB）")

            // 4) 双流下载（video 必需，audio 可选）
            val videoFile = File(dir, "video.m4s")
            downloadTo(
                videoUrl, videoFile,
                candidates = videoCand?.backups.orEmpty(),
                onProgress = { p -> dao.upsert(current(dao, bvid).copy(progress = p * 0.8f)) }
            )
            val audioFile = File(dir, "audio.m4s")
            var audioSize = 0L
            if (!audioUrl.isNullOrEmpty()) {
                downloadTo(
                    audioUrl, audioFile,
                    candidates = audioCand?.backups.orEmpty(),
                    onProgress = { p -> dao.upsert(current(dao, bvid).copy(progress = 0.8f + p * 0.2f)) }
                )
                audioSize = audioFile.length()
            }

            dao.upsert(DownloadItemEntity(
                bvid = bvid, cid = cid, title = detail.title,
                cover = detail.pic, ownerName = detail.owner?.name.orEmpty(),
                durationSec = duration,
                videoPath = videoFile.absolutePath,
                audioPath = if (audioSize > 0) audioFile.absolutePath else "",
                videoSize = videoFile.length(), audioSize = audioSize,
                state = DownloadItemEntity.STATE_DONE, progress = 1f
            ))
            fetchOfflineDanmaku(dao, dir, cid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 审核318：取消不视为失败——保留半成品，用户点「恢复」时按 Range 续传
            dao.upsert(current(dao, bvid).copy(state = DownloadItemEntity.STATE_PAUSED))
            throw e
        } catch (e: Exception) {
            dao.upsert(current(dao, bvid).copy(
                state = DownloadItemEntity.STATE_FAILED, error = e.message
            ))
        }
    }

    /** 审核318：弹幕离线化的公共步骤（普通视频与分P/番剧共用） */
    private suspend fun fetchOfflineDanmaku(
        dao: com.example.piliai.database.DownloadItemDao,
        dir: File,
        cid: Long,
    ) {
        runCatching {
            if (cid <= 0L) return
            // 审核293：getDanmaku 现在直接返回弹幕列表（内部已解压 XML）
            val list = BiliApiClient().getDanmaku(cid).getOrNull().orEmpty().map { d ->
                val pp = d.toParsed()
                mapOf("t" to (pp.timestamp * 1000).toLong(), "c" to pp.content,
                    "col" to (pp.color or 0xFF000000.toInt()), "fs" to pp.fontSize)
            }
            File(dir, "danmaku.json").writeText(gson.toJson(list))
        }
    }


    private suspend fun current(dao: com.example.piliai.database.DownloadItemDao, bvid: String) =
        dao.getByBvid(bvid) ?: DownloadItemEntity(bvid = bvid)

    fun cancel(context: Context, bvid: String) {
        // 审核318：取消 WorkManager 任务；Worker 内感知取消后把记录置 PAUSED
        cancelWork(context, bvid)
    }

    /** 暂停：缓存当前流 URL 供续传（流 URL 有时效，resume 时若过期会重新 playurl） */
    fun pause(context: Context, bvid: String) {
        cancelWork(context, bvid)
    }

    /** 恢复：断点续传（HTTP Range 追加），URL 过期则重走 playurl */
    fun resume(context: Context, bvid: String) {
        // 审核318：重新入队即可。runDownload 内的 downloadTo 检测到半成品文件
        // 会自动发 Range 请求续传（downloadOne 的 already>0 分支）。
        enqueue(context = context, key = bvid, kind = DownloadWorker.KIND_AV)
    }

    /**
     * 批量下载指定分P（批次L10）：调用方已持有 cid/标题（分P面板/番剧选集）。
     * 存储键 bvid_pN；单P仍走 download()（键=bvid）。
     */
    fun downloadPart(
        context: Context,
        bvid: String,
        cid: Long,
        page: Int,
        pageLabel: String,
        durationSec: Long = 0L,
        title: String = "",
        cover: String = "",
        ownerName: String = ""
    ) {
        val key = "${bvid}_p$page"
        enqueue(
            context = context,
            key = key,
            kind = DownloadWorker.KIND_PART,
            cid = cid,
            page = page,
            pageLabel = pageLabel,
            durationSec = durationSec,
            title = title,
            cover = cover,
            ownerName = ownerName,
        )
    }

    /**
     * 审核318：批量下载指定分P的实际执行体，由 [DownloadWorker] 调用。
     * 存储键 bvid_pN；单P走 [runDownload]（键=bvid）。
     */
    suspend fun runDownloadPart(
        context: Context,
        key: String,
        cid: Long,
        page: Int,
        pageLabel: String,
        durationSec: Long = 0L,
        title: String = "",
        cover: String = "",
        ownerName: String = "",
    ) {
        val appCtx = context.applicationContext
        val dao = dao(appCtx)
        try {
            val prev = dao.getByBvid(key)
            dao.upsert((prev ?: DownloadItemEntity(bvid = key)).copy(
                cid = cid, title = title, cover = cover, ownerName = ownerName,
                durationSec = durationSec, pageLabel = pageLabel,
                state = DownloadItemEntity.STATE_RUNNING, error = null
            ))
            val qn = com.example.piliai.utils.StorageManager.getInstance(appCtx).videoQualityFlow.first()
                .let { when (it) { "1080p" -> 80; "720p" -> 64; "480p" -> 32; else -> 64 } }
            val play = VideoRepository(BiliApiClient()).getPlayUrlWithCandidates(key.substringBefore("_p"), cid, qn = qn).getOrNull()
                ?: throw IllegalStateException("playurl 获取失败")
            val videoCand = play.second.first
            val audioCand = play.second.second
            val videoUrl = videoCand?.primary
            val audioUrl = audioCand?.primary
            if (videoUrl.isNullOrEmpty()) throw IllegalStateException("未取到视频流")

            val dir = File(File(appCtx.getExternalFilesDir(null), "PiliNara/Downloads"), key)
            if (!dir.exists()) dir.mkdirs()

            // 审核56：下载前检查存储空间（不足则中止）
            val stat = android.os.StatFs(dir.absolutePath)
            val freeMb = stat.availableBytes / (1024 * 1024)
            if (freeMb < 200) throw IllegalStateException("存储空间不足（剩余 ${freeMb}MB，需至少 200MB）")

            val videoFile = File(dir, "video.m4s")
            downloadTo(videoUrl, videoFile) { p ->
                dao.upsert(current(dao, key).copy(progress = p * 0.8f))
            }
            val audioFile = File(dir, "audio.m4s")
            var audioSize = 0L
            if (!audioUrl.isNullOrEmpty()) {
                downloadTo(audioUrl, audioFile) { p ->
                    dao.upsert(current(dao, key).copy(progress = 0.8f + p * 0.2f))
                }
                audioSize = audioFile.length()
            }
            dao.upsert(current(dao, key).copy(
                videoPath = videoFile.absolutePath,
                audioPath = if (audioSize > 0) audioFile.absolutePath else "",
                videoSize = videoFile.length(), audioSize = audioSize,
                state = DownloadItemEntity.STATE_DONE, progress = 1f, error = null
            ))
            // 弹幕离线：下载完成后抓取弹幕存为本地 JSON
            fetchOfflineDanmaku(dao, dir, cid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            dao.upsert(current(dao, key).copy(state = DownloadItemEntity.STATE_PAUSED))
            throw e
        } catch (e: Exception) {
            dao.upsert(current(dao, key).copy(
                state = DownloadItemEntity.STATE_FAILED, error = e.message
            ))
        }
    }

    /**
     * 批量下载番剧分集（批次L13）：bvid 传 "ep{id}" 虚拟键，走 pgc playurl。
     * 存储键 ep{id}_p{page}。
     */
    fun downloadPgcPart(
        context: Context,
        epId: Long,
        cid: Long,
        page: Int,
        pageLabel: String,
        durationSec: Long = 0L,
        title: String = "",
        cover: String = "",
        ownerName: String = ""
    ) {
        val key = "ep${epId}_p$page"
        enqueue(
            context = context,
            key = key,
            kind = DownloadWorker.KIND_PGC,
            cid = cid,
            page = page,
            pageLabel = pageLabel,
            durationSec = durationSec,
            title = title,
            cover = cover,
            ownerName = ownerName,
        )
    }

    /**
     * 审核318：批量下载番剧分集的实际执行体，由 [DownloadWorker] 调用。
     * bvid 传 "ep{id}" 虚拟键，走 pgc playurl；存储键 ep{id}_p{page}。
     */
    suspend fun runDownloadPgc(
        context: Context,
        key: String,
        cid: Long,
        page: Int,
        pageLabel: String,
        durationSec: Long = 0L,
        title: String = "",
        cover: String = "",
        ownerName: String = "",
    ) {
        val appCtx = context.applicationContext
        val dao = dao(appCtx)
        try {
            val prev = dao.getByBvid(key)
            dao.upsert((prev ?: DownloadItemEntity(bvid = key)).copy(
                cid = cid, title = title, cover = cover, ownerName = ownerName,
                durationSec = durationSec, pageLabel = pageLabel,
                state = DownloadItemEntity.STATE_RUNNING, error = null
            ))
            val epId = key.removePrefix("ep").substringBefore("_p").toLongOrNull() ?: 0L
            val qn = com.example.piliai.utils.StorageManager.getInstance(appCtx).videoQualityFlow.first()
                .let { when (it) { "1080p" -> 80; "720p" -> 64; "480p" -> 32; else -> 64 } }
            val play = VideoRepository(BiliApiClient()).getPgcPlayUrlWithCandidates(epId, cid, qn = qn).getOrNull()
                ?: throw IllegalStateException("番剧 playurl 获取失败")
            val videoCand = play.second.first
            val audioCand = play.second.second
            val videoUrl = videoCand?.primary
            val audioUrl = audioCand?.primary
            if (videoUrl.isNullOrEmpty()) throw IllegalStateException("未取到视频流（可能为大会员专享）")

            val dir = File(File(appCtx.getExternalFilesDir(null), "PiliNara/Downloads"), key)
            if (!dir.exists()) dir.mkdirs()

            // 审核56：下载前检查存储空间（不足则中止）
            val stat = android.os.StatFs(dir.absolutePath)
            val freeMb = stat.availableBytes / (1024 * 1024)
            if (freeMb < 200) throw IllegalStateException("存储空间不足（剩余 ${freeMb}MB，需至少 200MB）")

            val videoFile = File(dir, "video.m4s")
            // 审核300：补 CDN 候选列表——番剧下载此前漏接（普通视频下载已接），
            // 一遇到抖动节点整个下载失败。
            downloadTo(
                videoUrl, videoFile,
                candidates = videoCand?.backups.orEmpty(),
                onProgress = { p -> dao.upsert(current(dao, key).copy(progress = p * 0.8f)) }
            )
            val audioFile = File(dir, "audio.m4s")
            var audioSize = 0L
            if (!audioUrl.isNullOrEmpty()) {
                downloadTo(
                    audioUrl, audioFile,
                    candidates = audioCand?.backups.orEmpty(),
                    onProgress = { p -> dao.upsert(current(dao, key).copy(progress = 0.8f + p * 0.2f)) }
                )
                audioSize = audioFile.length()
            }
            dao.upsert(current(dao, key).copy(
                videoPath = videoFile.absolutePath,
                audioPath = if (audioSize > 0) audioFile.absolutePath else "",
                videoSize = videoFile.length(), audioSize = audioSize,
                state = DownloadItemEntity.STATE_DONE, progress = 1f, error = null
            ))
            fetchOfflineDanmaku(dao, dir, cid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            dao.upsert(current(dao, key).copy(state = DownloadItemEntity.STATE_PAUSED))
            throw e
        } catch (e: Exception) {
            dao.upsert(current(dao, key).copy(
                state = DownloadItemEntity.STATE_FAILED, error = e.message
            ))
        }
    }

    /** 删除离线项（记录 + 文件） */
    fun delete(context: Context, bvid: String) {
        cancel(context, bvid)
        scope.launch {
            val d = dao(context)
            val item = d.getByBvid(bvid)
            d.delete(bvid)
            item?.videoPath?.let { File(it).parentFile?.deleteRecursively() }
        }
    }

    /** 离线播放用的本地 URI 对 */
    data class LocalPlayback(val videoPath: String, val audioPath: String)

    suspend fun getLocalPlayback(context: Context, bvid: String): LocalPlayback? {
        val item = dao(context).getByBvid(bvid) ?: return null
        if (item.state != DownloadItemEntity.STATE_DONE) return null
        return LocalPlayback(item.videoPath, item.audioPath)
    }

    /** 读取离线弹幕（批次L11：下载时缓存的 danmaku.json） */
    fun localDanmaku(videoPath: String): List<Map<String, Any?>> {
        val f = File(File(videoPath).parentFile, "danmaku.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                java.util.List::class.java,
                com.google.gson.reflect.TypeToken.getParameterized(
                    java.util.Map::class.java, String::class.java, Any::class.java
                ).type
            ).type
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<List<Map<String, Any?>>>(f.readText(), type)
        }.getOrDefault(emptyList())
    }

    // ---------- 基础下载（带进度回调 0..1） ----------

    /**
     * 审核290：下载单个流，支持 CDN 候选列表故障转移。
     *
     * B 站 playurl 返回的首个 CDN 节点可能 TLS 握手超时（实测遇到过 mcdn
     * 节点抖动，而同一流的 backupUrl 节点完全正常）。原实现只传单个 URL，
 * 一遇到这种节点整个下载就失败。现在按 candidates 顺序尝试，全部失败
     * 才抛错。
     */
    private suspend fun downloadTo(
        url: String,
        target: File,
        candidates: List<String> = emptyList(),
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val urls = (listOf(url) + candidates.filter { it != url }).distinct()
        var lastError: Exception? = null
        for ((index, attempt) in urls.withIndex()) {
            try {
                downloadOne(attempt, target, onProgress)
                return@withContext
            } catch (e: Exception) {
                lastError = e
                // 还有候选可试：清理半成品，换下一个节点
                if (index < urls.lastIndex) {
                    target.delete()
                    continue
                }
            }
        }
        throw lastError ?: IllegalStateException("下载失败（无可用 CDN 节点）")
    }

    /** 单次下载尝试（不含节点切换） */
    private suspend fun downloadOne(
        url: String,
        target: File,
        onProgress: suspend (Float) -> Unit
    ) {
        var already = if (target.exists()) target.length() else 0L
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Referer", "https://www.bilibili.com/")
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        var resumeMode = false
        if (already > 0) {
            conn.setRequestProperty("Range", "bytes=$already-")
            resumeMode = conn.responseCode == 206
            if (!resumeMode) already = 0L  // 服务器不支持 Range → 重下
        }
        val total = conn.contentLengthLong.takeIf { it > 0 }?.let { it + already } ?: -1L
        if (!resumeMode) target.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, resumeMode).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = already
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct / 100f)
                        }
                    }
                }
            }
        }
    }
}
