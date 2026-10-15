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
 *
 * 审核315（在线取帧打通）：原实现注释说「1.3.1 无法注入请求头，在线取帧
 * 必然 403」，所以取帧按钮对在线视频直接显示「不支持」。实测 javap 验证：
 * - Transformer.Builder 确实没有 setDataSourceFactory（1.4+ 才有）
 * - 但有 setAssetLoaderFactory，且 ExoPlayerAssetLoader.Factory 接受
 *   MediaSource.Factory —— 那里可以挂 OkHttpDataSource.Factory 注入请求头
 * 见 [buildAssetLoaderFactory]。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoFrameCapture {

    /**
     * 取指定时刻的一帧，写成 JPEG 文件。
     *
     * @param uri 媒体地址（本地文件或 http(s)）
     * @param positionMs 取帧时刻（毫秒）
     * @param outputFile 输出文件（调用方负责后缀，建议 .jpg）
     * @param headers 播放请求头（Referer/Cookie）。经
     *   [buildAssetLoaderFactory] 走 ExoPlayerAssetLoader.Factory 的
     *   MediaSource.Factory 注入（审核315：javap 验证这是 1.3.1 上唯一可行路径）
     * @param userAgent 播放 UA。同上。
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

            // 审核315：经 ExoPlayerAssetLoader.Factory 注入请求头
            // （1.3.1 的 Transformer.Builder 没有 setDataSourceFactory，
            // 但 setAssetLoaderFactory + ExoPlayerAssetLoader.Factory 的
            // MediaSource.Factory 参数可以达到同样效果）。
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAssetLoaderFactory(buildAssetLoaderFactory(context, headers, userAgent))
                .build()
                // addListener 在 Transformer 实例上（Builder.setListener 已废弃）
                .apply { addListener(listener) }

            continuation.invokeOnCancellation { runCatching { transformer.cancel() } }

            // start 接受 EditedMediaItem（无 Sequence 重载）
            transformer.start(editedMediaItem, outputFile.absolutePath)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildAssetLoaderFactory(
        context: Context,
        headers: Map<String, String>,
        userAgent: String,
    ): androidx.media3.transformer.AssetLoader.Factory {
        // OkHttpDataSource.Factory 是 media3-datasource-okhttp 提供的
        // （项目已依赖，见 build.gradle.kts）——请求头在这里注入。
        val okHttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            okhttp3.OkHttpClient.Builder().build()
        )
            .setUserAgent(userAgent.ifBlank { DEFAULT_UA })
            .setDefaultRequestProperties(headers)
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, okHttpFactory)
        // ExoPlayerAssetLoader.Factory 的第 5 个参数就是 MediaSource.Factory ——
        // 这是 1.3.1 上给 transformer 注入请求头的唯一路径（javap 验证）。
        return androidx.media3.transformer.ExoPlayerAssetLoader.Factory(
            context,
            androidx.media3.transformer.DefaultDecoderFactory(context),
            false,
            androidx.media3.common.util.Clock.DEFAULT,
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
        )
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
