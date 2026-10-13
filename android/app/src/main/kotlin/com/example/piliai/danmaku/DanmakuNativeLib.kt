package com.example.piliai.danmaku

/**
 * Kotlin FFI bindings for Danmaku Native Library
 */
class DanmakuNativeLib {

    /** 审核实测：.so 缺失（x86_64 模拟器/老设备）时不崩溃，所有方法降级返回 null/0 */
    val loaded: Boolean = runCatching {
        System.loadLibrary("pilinara_native"); true
    }.getOrDefault(false)

    init {
        if (!loaded) android.util.Log.w("PiliNative", "pilinara_native.so 不可用，弹幕合并/屏蔽/热力图回退 Kotlin 实现")
    }
    
    
    
    data class DanmakuConfig(
        val windowSeconds: Double = 5.0,
        val maxDistance: Double = 1.5,
        val maxCosine: Double = 0.95,
        val usePinyin: Boolean = false
    )
    
    fun create(config: DanmakuConfig): Long {
        return nativeCreate(
            config.windowSeconds,
            config.maxDistance,
            config.maxCosine,
            if (config.usePinyin) 1 else 0
        )
    }
    
    fun destroy(ptr: Long) {
        if (ptr != 0L) {
            nativeDestroy(ptr)
        }
    }
    
    fun loadPinyinDict(ptr: Long, dictData: ByteArray): Int {
        return nativeLoadPinyinDict(ptr, dictData)
    }

    /** 批次L15：多源弹幕合并（JSON 进/出，失败返回 null） */
    fun merge(ptr: Long, sourcesJson: String): String? {
        if (ptr == 0L) return null
        return runCatching { nativeMerge(ptr, sourcesJson) }.getOrNull()
    }

    private external fun nativeMerge(ptr: Long, sourcesJson: String): String?

    /** 批次L39：弹幕屏蔽规则批量过滤（JSON 进/出，失败返回 null → Kotlin 回退逐条 shouldBlock） */
    fun filterBlock(entriesJson: String, rulesJson: String): String? =
        runCatching { nativeFilterBlock(entriesJson, rulesJson) }.getOrNull()

    private external fun nativeFilterBlock(entriesJson: String, rulesJson: String): String?

    /** 批次L42：弹幕密度热力曲线（返回归一化 buckets JSON；失败返回 null → Kotlin 回退空曲线） */
    fun heatMap(pointsJson: String, durationMs: Double, bucketCount: Int): String? =
        runCatching { nativeHeatMap(pointsJson, durationMs, bucketCount) }.getOrNull()

    private external fun nativeHeatMap(pointsJson: String, durationMs: Double, bucketCount: Int): String?
    
    private external fun nativeCreate(
        windowSeconds: Double,
        maxDistance: Double,
        maxCosine: Double,
        usePinyin: Int
    ): Long
    
    private external fun nativeDestroy(ptr: Long)
    
    private external fun nativeLoadPinyinDict(
        ptr: Long,
        dictData: ByteArray
    ): Int
}
