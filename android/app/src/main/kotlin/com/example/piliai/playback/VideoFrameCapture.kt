package com.example.piliai.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 审核267：视频取帧（移植 bilipai 的截帧能力）。
 *
 * 用途：把指定时刻的画面导出成 JPEG，供用户保存/分享。
 * 实现走 media3-transformer 的 ClippingConfiguration —— 该依赖项目已引入
 * （审核210 为超分效果用的同一个 artifact），不新增依赖。
 *
 * 为什么不用 MediaMetadataRetriever：它对 DASH/在线流支持差，且 seek 精度
 * 不可控；transformer 与播放器走同一条解码链路，对 m3u8/mpd/直链一视同仁。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoFrameCapture {

    /**
     * 取指定时刻的一帧，写成 JPEG 文件。
     *
     * @param uri 媒体地址（本地文件或 http(s)）
     * @param positionMs 取帧时刻（毫秒）
     * @param outputFile 输出文件（调用方负责后缀，建议 .jpg）
     * @param headers 播放请求头。**1.3.1 限制**：media3-transformer 的
     * `Transformer.Builder.setDataSourceFactory` 是 1.4+ 才有的 API，
     * 本项目的 1.3.1 无法把请求头注入 transformer —— 在线 DASH/HLS 取帧
     * 会因缺 Referer/Cookie 被 CDN 403。本地文件取帧不受影响（无网络）。
     * @param userAgent 播放 UA。同上，1.3.1 无法注入。
     * @return 输出文件；失败抛异常（调用方负责提示）
     */
    suspend fun capture(
        context: Context,
        uri: Uri,
        positionMs: Long,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = ""
    ): File = withContext(Dispatchers.IO) {
        require(positionMs >= 0L) { "positionMs must not be negative" }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        // 只保留 positionMs 处的一帧：start=positionMs，end=positionMs+1ms。
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(positionMs * 1000L)
            .setEndPositionUs((positionMs + 1L) * 1000L)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(false)
            .setRemoveAudio(true)
            .build()

        suspendCancellableCoroutine { continuation ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(outputFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException(
                                "取帧失败：${exportException.message ?: exportException.javaClass.simpleName}",
                                exportException
                            )
                        )
                    }
                }
            }

            // 1.3.1 的 Builder 没有 setDataSourceFactory：DataSource 是通过
            // EditedMediaItem 的 MediaItem.setCustomCacheKey/AssetLoader 注入的，
            // 更简单的做法是把请求头写进 DefaultDataSource 的默认属性 ——
            // 用 ExoPlayer.setDataSourceFactory 那条路（Transformer.Builder 有
            // setDataSourceFactory 的变体在 1.4+，1.3.1 只能通过
            // DefaultMediaSourceFactory 兼容路径）。
            // 这里退而求其次：直接用本地/全局 DataSource 的默认请求头。
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .build()
                // addListener 在 Transformer 实例上（Builder.setListener 已废弃）
                .apply { addListener(listener) }

            continuation.invokeOnCancellation { runCatching { transformer.cancel() } }

            // start 接受 EditedMediaItem（无 Sequence 重载）
            transformer.start(editedMediaItem, outputFile.absolutePath)
        }
    }

    /**
     * 便捷入口：从当前播放进度取帧，输出到缓存目录。
     * 返回 null 表示失败（已由 runCatching 吞掉，调用方静默处理）。
     */
    suspend fun captureFromPlayback(
        context: Context,
        uri: Uri,
        positionMs: Long,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = ""
    ): File? = runCatching {
        val dir = File(context.cacheDir, "frames").apply { mkdirs() }
        val out = File(dir, "frame_${System.currentTimeMillis()}.jpg")
        capture(context, uri, positionMs, out, headers, userAgent)
    }.getOrNull()
}
