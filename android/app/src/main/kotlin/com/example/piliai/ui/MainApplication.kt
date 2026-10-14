package com.example.piliai

import android.app.Application
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.database.DatabaseInitializer
import kotlinx.coroutines.launch

/**
 * Main Application class
 * Initializes all native and database components
 */
class MainApplication : Application() {

    // 审核5：Application 级常驻作用域（SupervisorJob 防子协程异常互相取消）
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()

        // 审核（真卡顿根因）：原 DatabaseInitializer.initialize 在主线程同步
        // getDatabase()——Room 首次建库 + 执行 MIGRATION_2_3 是重 IO，
        // 数据量大时阻塞主线程数百 ms~秒级，表现为启动图卡死/黑屏/“按钮无反应”。
        // 挪到后台协程预热；UI 侧首次访问 Room 均有协程包裹，懒加载不受影响。
        appScope.launch {
            DatabaseInitializer().initialize(this@MainApplication)
        }

        // 批次审核40：全局兜底——未捕获协程异常记日志防静默崩溃
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            android.util.Log.e("PiliNaraCrash", "Uncaught on ${t.name}", e)
            if (previousHandler != null) {
                previousHandler.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        // 进程级 Application 上下文，供无 Context 层（LoginRepository 等）取 Room
        AppContext.init(this)

        // Kazumi 特性：预热渲染器偏好缓存（异步，不阻塞主线程）
        com.example.piliai.utils.RendererPrefs.init(this)

        // 审核260：预热图片质量档位。此前只有 SettingsViewModel 写它，
        // 用户没进过设置页时 qualityPercent 停在默认 100（不压缩），
        // 而首页/播放器的封面早于 SettingsViewModel 加载 → 设置对那些页无效。
        com.example.piliai.utils.ImageQuality.warmUp(this)

        // Animeko 特性：恢复 Bangumi 登录态（Bearer Token，与 B站 Cookie 独立）
        com.example.piliai.data.remote.BangumiSession.restore(this)

        // 弹幕屏蔽规则启动 warmup（缓存到内存供渲染过滤）
        com.example.piliai.ui.settings.DanmakuBlockViewModel.warmup(
            com.example.piliai.database.PiliNaraDatabase.getDatabase(this)
        )

        // 匿名启动即取 buvid3（风控接口强依赖），协程后台执行
        // 审核291：先恢复上次持久化的指纹，取不到才请求 finger/spi。
        // 这样重启 App 后 B 站看到的仍是同一设备，不会每次冷启动都当新设备。
        // 审核303：登录态也要在启动时恢复。原来 restoreSession() 只在
        // MineScreen 调用——用户冷启动直接进首页点视频，AccountSession
        // 还是空的，playurl 走匿名请求：拿不到高画质、容易被风控。
        // 这就是「首页第一个视频播放不了 / 只有低清晰度」的高概率根因。
        appScope.launch {
            AccountSession.restoreBuvidFromStorage()
            AccountSession.ensureBuvid()
            runCatching {
                // LoginRepository 默认参数自己取 AppContext，无需显式传 db
                com.example.piliai.data.repository.LoginRepository().restoreSession()
            }
        }

        // 批次审核61：Coil 全局 ImageLoader——全 App 共享内存/磁盘缓存，避免各页各建
        val imageLoader = coil.ImageLoader.Builder(this)
            .memoryCache(
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            )
            .diskCache(
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            )
            .crossfade(true)
            .allowRgb565(true)  // 审核234：列表封面无透明通道，RGB_565 内存减半（渲染更快更省电）
            .build()
        coil.Coil.setImageLoader(imageLoader)

        // Load native libraries
        try {
            System.loadLibrary("pilinara_native")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PiliNaraApp", "Failed to load native library: ${e.message}")
        }
    }
}

/** 全局 Application 上下文（ViewModel 之外的地方需要 Context 时使用） */
object AppContext {
    @Volatile
    private var app: Application? = null

    fun init(application: Application) { app = application }

    fun get(): Application =
        app ?: error("AppContext not initialized — MainApplication.onCreate 未执行")
}
