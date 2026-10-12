package com.example.piliai.playback.backend

import android.view.Surface

/**
 * GPU-next 桥接接口（libmpv 控制）。
 * RustGpuNextBridge 实现此接口，通过 JNI 调用 Rust/C 层的 libmpv。
 */
interface GpuNextBridge {
    /** Creates mpv before pre-initialize options are applied. */
    fun open()
    /** Initializes mpv after renderer and Android context options are set. */
    fun initialize()
    fun setOption(name: String, value: String)
    fun setProperty(name: String, value: String)
    fun stringProperty(name: String): String
    fun longProperty(name: String): Double
    fun command(vararg args: String)
    /**
     * `mpv_observe_property`：只有注册过的属性变化才会进入事件队列。
     * 必须在 [initialize] 之后调用。format 用 client.h 的 MPV_FORMAT_*。
     */
    fun observeProperty(name: String, format: Int)
    /** One bounded `mpv_wait_event`. timeout=0 only drains queued events. */
    fun pollEvent(timeoutMs: Int): Int
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun release()
}
