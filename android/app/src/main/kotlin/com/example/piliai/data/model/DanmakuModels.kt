package com.example.piliai.data.model

import kotlinx.serialization.Serializable

/**
 * Danmaku models for Bilibili API
 *
 * 审核293：原来的 `DanmakuResponse`（JSON 包装）已删除——`dm/list.so` 实际
 * 返回的是 deflate 压缩的 XML，不是 JSON，那个模型从头到尾没被正确填过。
 */
@Serializable
data class DanmakuElement(
    val p: String = "",  // 保留字段（历史兼容，现由 c 承载）
    val m: String = "",  // 弹幕文本
    val c: String = ""   // XML <d p="..."> 的属性原文（逗号分隔参数）
)

data class ParsedDanmaku(
    val mode: Int = 1,      // 1=scroll, 2=top, 3=bottom, 4=subtitle, 5=reverse
    val fontSize: Int = 25,
    val color: Int = 0xFFFFFF,
    val timestamp: Float = 0f,
    val pool: Int = 0,
    val uid: Long = 0L,
    val content: String = ""
)

/**
 * 弹幕 XML `<d p="...">` 属性 → ParsedDanmaku。
 *
 * 审核293：字段顺序按 B 站标准协议（实测首条
 * `0.60100,4,25,15138834,1604742895,0,9e5adeaa,40685126698926083,10` 验证）：
 *   0 出现时间(秒) 1 模式 2 字号 3 颜色(十进制RGB) 4 发送时间戳
 *   5 弹幕池 6 用户hash 7 数据库rowid 8 重量
 *
 * 原实现把索引 5 当 uid——实际取到的是弹幕池（几乎总是 0），
 * 导致弹幕 uid 全是 0，按用户过滤/统计功能失效。用户 hash 在索引 6。
 */
fun DanmakuElement.toParsed(): ParsedDanmaku {
    val parts = c.split(";")
    fun i(idx: Int, def: Int) = parts.getOrNull(idx)?.trim()?.toIntOrNull() ?: def
    fun l(idx: Int, def: Long) = parts.getOrNull(idx)?.trim()?.toLongOrNull() ?: def
    fun f(idx: Int, def: Float) = parts.getOrNull(idx)?.trim()?.toFloatOrNull() ?: def
    return ParsedDanmaku(
        mode = i(1, 1),
        fontSize = i(2, 25),
        color = i(3, 0xFFFFFF),
        timestamp = f(0, 0f),
        pool = i(5, 0),
        // 索引 6 是用户 hash（字符串），转成稳定的 Long 供按用户聚合
        uid = parts.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() }?.hashCode()?.toLong() ?: 0L,
        content = m
    )
}
