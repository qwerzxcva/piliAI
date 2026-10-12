//! Danmaku merging and filtering algorithms
//!
//! High-performance Rust implementation for:
//! - Danmaku merging across multiple streams
//! - Pinyin-based filtering
//! - Collision detection and avoidance

use std::collections::HashMap;
use std::time::Instant;

/// Represents a single danmaku comment
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DanmakuEntry {
    pub id: u64,
    pub mode: i32, // B站协议：1-3=滚动, 4=底部, 5=顶部, 6=逆向, 7=精准, 8=大会员
    pub fontsize: i32,
    pub color: u32,
    pub timestamp: f64, // seconds
    pub pool: i32,
    pub content: String,
    pub creator_mid: Option<u64>,
    pub uid: u64,
}

/// Configuration for danmaku merging
#[derive(Debug, Clone)]
pub struct DanmakuMergeConfig {
    pub window_seconds: f64,
    pub max_distance: f64,
    pub max_cosine: f64,
    pub use_pinyin: bool,
    pub skip_subtitle: bool,
    pub skip_advanced: bool,
    pub skip_bottom: bool,
    pub mark_position: bool,
    pub mark_threshold: f64,
}

impl Default for DanmakuMergeConfig {
    fn default() -> Self {
        Self {
            window_seconds: default_window_seconds(),
            max_distance: 1.5,
            max_cosine: 0.95,
            use_pinyin: false,
            // 审核轮189（真bug）：B站协议 mode 4=底部 5=顶部（本文件旧注释 1=scroll/2=top/3=bottom
            // 是错误语义）。原 default skip_subtitle/skip_advanced=true 会把用户底部+顶部
            // 弹幕在 Rust 合并阶段整类静默丢弃。现默认不过滤，类型开关由 Kotlin UI 控制（审核167）。
            skip_subtitle: false,
            skip_advanced: false,
            skip_bottom: false,
            mark_position: false,
            mark_threshold: 0.8,
        }
    }
}

/// 审核242：把窗口从 5s 提到 30s（bv `VodDanmakuMerger.kt:440` 的
/// `MERGE_WINDOW_SECONDS = 30f`）。B 站高能时段同一句话常在十几秒内
/// 被反复刷，5s 窗口只能合到一小部分。
fn default_window_seconds() -> f64 {
    30.0
}

///
/// 审核242：文本归一化（移植 bv `VodDanmakuMerger.kt:582-675`）。
/// 目标是把「2333 / ２３３３ / 23333 / 哈哈哈 / 哈 哈 哈」归到同一形态，
/// 让下面的编辑距离能判定相似。
///
/// 规则（按 bv 实现逐条对齐）：
/// 1. 全角数字/字母 → 半角
/// 2. 小写化（只影响 ASCII，中文不受影响）
/// 3. 压缩连续空白为单个空格
/// 4. 去掉汉字之间的空格（中文弹幕的空格几乎都是误输入）
/// 5. 连续重复数字压缩：`23{2,}` → `23333`、`6{3,}` → `66666`
///    （刷屏党靠加长重复表示强度，不压缩则编辑距离必然超阈值）
/// 6. 剥掉尾部标点
fn normalize_text(input: &str) -> String {
    let mut out: String = input
        .chars()
        .map(|c| {
            // 全角数字 ０-９ → 半角
            match c {
                '０'..='９' => char::from_u32(c as u32 - 0xFEE0).unwrap_or(c),
                // 全角大写 A-Z
                'Ａ'..='Ｚ' => char::from_u32(c as u32 - 0xFEE0).unwrap_or(c),
                // 全角小写 a-z
                'ａ'..='ｚ' => char::from_u32(c as u32 - 0xFEE0).unwrap_or(c),
                _ => c,
            }
        })
        .collect();
    out.make_ascii_lowercase();

    // 压缩空白
    let mut collapsed = String::with_capacity(out.len());
    let mut prev_space = false;
    for c in out.chars() {
        if c.is_whitespace() {
            if !prev_space {
                collapsed.push(' ');
            }
            prev_space = true;
        } else {
            collapsed.push(c);
            prev_space = false;
        }
    }
    let trimmed = collapsed.trim();

    // 去汉字间空格 + 重复数字压缩
    let mut result = String::with_capacity(trimmed.len());
    let chars: Vec<char> = trimmed.chars().collect();
    let mut i = 0usize;
    while i < chars.len() {
        let c = chars[i];
        // 汉字之间单个空格直接丢弃（首尾已在 trim 处理）
        if c == ' '
            && i > 0
            && i + 1 < chars.len()
            && chars[i - 1] as u32 > 0x2E80
            && chars[i + 1] as u32 > 0x2E80
        {
            i += 1;
            continue;
        }
        if c.is_ascii_digit() {
            // 收集连续同数字段
            let start = i;
            while i < chars.len() && chars[i] == c {
                i += 1;
            }
            let run_len = i - start;
            let emit = if (c == '2' || c == '3' || c == '6') && run_len >= 2 {
                5
            } else {
                run_len
            };
            for _ in 0..emit {
                result.push(c);
            }
            continue;
        }
        result.push(c);
        i += 1;
    }

    // 剥尾部标点（中英文常见标点）
    while let Some(last) = result.chars().last() {
        if matches!(
            last,
            '。' | '，' | '、' | '！' | '？' | '；' | '：' | '…' | '.' | ',' | '!' | '?' | ';' | ':'
        ) {
            result.pop();
        } else {
            break;
        }
    }
    result
}

/// 审核242：纯噪声判定（bv `VodDanmakuMerger.kt:605-629`）。
/// 归一化后只剩标点/空白/重复单字符（长度 1）时视为噪声，不参与合并。
fn is_pure_noise(normalized: &str) -> bool {
    if normalized.is_empty() {
        return true;
    }
    normalized.chars().all(|c| {
        c.is_whitespace()
            || matches!(
                c,
                '。' | '，' | '、' | '！' | '？' | '；' | '：' | '…' | '.' | ',' | '!' | '?' | ';' | ':'
            )
    })
}

/// 审核242：编辑距离阈值表（bv `VodDanmakuMerger.kt:789-801`）。
/// 长度 2..4 允许 1 处差异，5..12 允许 2 处，更长允许 `min(3, len/6)`。
fn edit_distance_threshold(a_len: usize, b_len: usize) -> usize {
    let longest = a_len.max(b_len);
    match longest {
        0 => 0,
        1..=4 => 1,
        5..=12 => 2,
        n => (n / 6).min(3),
    }
}

///
/// 审核242：有界 Levenshtein 距离（移植 bv `VodDanmakuMerger.kt:803-825`）。
/// 超过 `max` 立即返回 `max + 1`，避免长弹幕做完整 DP。
fn bounded_edit_distance(a: &str, b: &str, max: usize) -> usize {
    let a_chars: Vec<char> = a.chars().collect();
    let b_chars: Vec<char> = b.chars().collect();
    let (a_len, b_len) = (a_chars.len(), b_chars.len());
    if a_len == 0 {
        return b_len;
    }
    if b_len == 0 {
        return a_len;
    }
    if a_len.abs_diff(b_len) > max {
        return max + 1;
    }
    let mut prev: Vec<usize> = (0..=b_len).collect();
    let mut curr: Vec<usize> = vec![0usize; b_len + 1];
    for i in 1..=a_len {
        curr[0] = i;
        let mut row_min = curr[0];
        for j in 1..=b_len {
            let cost = if a_chars[i - 1] == b_chars[j - 1] { 0 } else { 1 };
            let value = (prev[j] + 1).min(curr[j - 1] + 1).min(prev[j - 1] + cost);
            curr[j] = value;
            row_min = row_min.min(value);
        }
        // 整行最小值已超上限，后续不可能降回来
        if row_min > max {
            return max + 1;
        }
        std::mem::swap(&mut prev, &mut curr);
    }
    prev[b_len]
}

/// Result of danmaku merging
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MergedDanmaku {
    pub entries: Vec<DanmakuEntry>,
    pub filtered_count: usize,
    pub merged_count: usize,
    pub elapsed_ms: u64,
}

/// Danmaku merger processor
pub struct DanmakuMerger {
    config: DanmakuMergeConfig,
    pinyin_dict: Option<HashMap<String, String>>,
}

impl DanmakuMerger {
    pub fn new(config: DanmakuMergeConfig) -> Self {
        Self {
            config,
            pinyin_dict: None,
        }
    }

    /// Load pinyin dictionary for filtering
    pub fn load_pinyin_dict(&mut self, _dict_data: &[u8]) -> Result<(), String> {
        // Parse packed pinyin dictionary
        // Simplified for now - in production would parse the .pakku file
        self.pinyin_dict = Some(HashMap::new());
        Ok(())
    }

    /// Merge danmaku entries from multiple sources
    pub fn merge(&self, entries: Vec<Vec<DanmakuEntry>>) -> MergedDanmaku {
        let start = Instant::now();

        // Flatten and sort by timestamp
        let mut all_entries: Vec<DanmakuEntry> = entries.into_iter().flatten().collect();
        all_entries.sort_by(|a, b| {
            a.timestamp
                .partial_cmp(&b.timestamp)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        let initial_count = all_entries.len();

        // Apply filters
        let filtered: Vec<DanmakuEntry> = all_entries
            .into_iter()
            .filter(|e| !self.should_skip(e))
            .collect();

        let filtered_count = initial_count - filtered.len();

        // Merge similar danmaku
        let (merged, merged_count) = self.merge_similar(filtered);

        let elapsed = start.elapsed();
        eprintln!(
            "Danmaku merge: {} -> {} (filtered {}, merged {}), took {:?}",
            initial_count,
            merged.len(),
            filtered_count,
            merged_count,
            elapsed
        );

        MergedDanmaku {
            entries: merged,
            filtered_count,
            merged_count,
            elapsed_ms: elapsed.as_millis() as u64,
        }
    }

    fn should_skip(&self, entry: &DanmakuEntry) -> bool {
        // 审核轮189：按 B站协议 mode 语义（1-3 滚动 / 4 底部 / 5 顶部 / 6 逆向 / 7 精准）。
        // 旧实现把 4 当"字幕"、5 当"高级"、3 当"底部"，与协议不符 → 误删用户弹幕。
        if self.config.skip_subtitle && entry.mode == 8 {
            return true; // 大会员专属弹幕
        }
        if self.config.skip_advanced && entry.mode == 7 {
            return true; // 精准定位弹幕（特殊模式）
        }
        if self.config.skip_bottom && entry.mode == 4 {
            return true; // 底部弹幕
        }
        false
    }

    fn merge_similar(&self, entries: Vec<DanmakuEntry>) -> (Vec<DanmakuEntry>, usize) {
        if entries.len() <= 1 {
            return (entries, 0);
        }

        let mut result = Vec::new();
        let mut current = entries[0].clone();
        let mut run = 1usize; // 审核轮190：累积计数（原来硬编码 2，最多只能合并 2 条）
        let mut merge_count = 0;

        for entry in entries.into_iter().skip(1) {
            // 审核轮190：先按"原始内容"判断相似（current.content 可能已被加上 ×N 后缀，
            // 旧实现直接比较导致第 3 条起永远无法合并）
            if self.is_similar_raw(&current, &entry) {
                run += 1;
                current.content = format!("{}×{}", entry.content, run);
                current.timestamp = entry.timestamp;
                merge_count += 1;
            } else {
                result.push(current);
                current = entry;
                run = 1;
            }
        }
        result.push(current);

        (result, merge_count)
    }

    /// 审核轮190：剥离合并后缀 "×N" 后的原始内容
    fn raw_content(s: &str) -> &str {
        match s.rfind('×') {
            Some(i) if s[i + '×'.len_utf8()..].chars().all(|c| c.is_ascii_digit()) => &s[..i],
            _ => s,
        }
    }

    fn is_similar_raw(&self, a: &DanmakuEntry, b: &DanmakuEntry) -> bool {
        let a_raw = Self::raw_content(&a.content);
        let b_raw = Self::raw_content(&b.content);
        // 审核242：先按原始字符串全等快速判定（绝大多数重复弹幕走这条路），
        // 全等不命中再做归一化 + 有界编辑距离（bv VodDanmakuMerger.kt:789-825）。
        if a_raw == b_raw {
            return Self::merge_window_ok(a, b, self.config.window_seconds);
        }
        let a_norm = normalize_text(a_raw);
        let b_norm = normalize_text(b_raw);
        if a_norm.is_empty() || b_norm.is_empty() {
            return false;
        }
        if !Self::merge_window_ok(a, b, self.config.window_seconds) {
            return false;
        }
        if a.mode != b.mode {
            return false;
        }
        // 纯噪声内容不参与相似合并（bv VodDanmakuMerger.kt:605-629）
        if is_pure_noise(&a_norm) || is_pure_noise(&b_norm) {
            return false;
        }
        let distance_threshold = edit_distance_threshold(a_norm.chars().count(), b_norm.chars().count());
        bounded_edit_distance(&a_norm, &b_norm, distance_threshold) <= distance_threshold
    }

    fn merge_window_ok(a: &DanmakuEntry, b: &DanmakuEntry, window_seconds: f64) -> bool {
        let delta = b.timestamp - a.timestamp;
        delta >= 0.0 && delta <= window_seconds
    }

    #[allow(dead_code)]
    fn is_similar(&self, a: &DanmakuEntry, b: &DanmakuEntry) -> bool {
        if a.content != b.content {
            return false;
        }
        if (b.timestamp - a.timestamp) > self.config.window_seconds {
            return false;
        }
        if a.mode != b.mode {
            return false;
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_danmaku_merge() {
        let config = DanmakuMergeConfig::default();
        let merger = DanmakuMerger::new(config);

        let entries = vec![
            vec![
                DanmakuEntry {
                    id: 1,
                    mode: 1,
                    fontsize: 25,
                    color: 0xFFFFFF,
                    timestamp: 1.0,
                    pool: 0,
                    content: "test".to_string(),
                    creator_mid: None,
                    uid: 1,
                },
                DanmakuEntry {
                    id: 2,
                    mode: 1,
                    fontsize: 25,
                    color: 0xFFFFFF,
                    timestamp: 2.0,
                    pool: 0,
                    content: "test".to_string(),
                    creator_mid: None,
                    uid: 2,
                },
            ],
            vec![DanmakuEntry {
                id: 3,
                mode: 1,
                fontsize: 25,
                color: 0xFFFFFF,
                timestamp: 2.1,
                pool: 0,
                content: "test".to_string(),
                creator_mid: None,
                uid: 3,
            }],
        ];

        let result = merger.merge(entries);
        assert!(!result.entries.is_empty());
    }

    // ===== 审核242：相似合并（归一化 + 编辑距离）=====

    fn entry(ts: f64, content: &str) -> DanmakuEntry {
        DanmakuEntry {
            id: 0,
            mode: 1,
            fontsize: 25,
            color: 0xFFFFFF,
            timestamp: ts,
            pool: 0,
            content: content.to_string(),
            creator_mid: None,
            uid: 0,
        }
    }

    #[test]
    fn test_normalize_folds_repeated_digits_and_spacing() {
        assert_eq!(normalize_text("2333"), normalize_text("23333"));
        assert_eq!(normalize_text("哈哈哈"), normalize_text("哈 哈 哈"));
        assert_eq!(normalize_text("２３３３"), normalize_text("23333"));
        assert_eq!(normalize_text("66666"), normalize_text("666"));
        assert_eq!(normalize_text("好耶。"), "好耶");
    }

    #[test]
    fn test_bounded_edit_distance_matches_plain_levenshtein() {
        assert_eq!(bounded_edit_distance("kitten", "sitting", 3), 3);
        assert_eq!(bounded_edit_distance("abc", "abc", 2), 0);
        assert_eq!(bounded_edit_distance("abc", "abd", 1), 1);
        // 超过上限立即返回 max+1
        assert_eq!(bounded_edit_distance("abcdef", "azzzzz", 2), 3);
        assert_eq!(bounded_edit_distance("", "abc", 5), 3);
    }

    #[test]
    fn test_edit_distance_threshold_table() {
        assert_eq!(edit_distance_threshold(2, 4), 1);
        assert_eq!(edit_distance_threshold(5, 12), 2);
        assert_eq!(edit_distance_threshold(20, 24), 3);
        assert_eq!(edit_distance_threshold(200, 240), 3);
    }

    #[test]
    fn test_similar_danmaku_merge_within_window() {
        let merger = DanmakuMerger::new(DanmakuMergeConfig::default());
        let entries = vec![entry(10.0, "2333"), entry(12.0, "23333"), entry(14.0, "哈哈哈")];
        let (merged, count) = merger.merge_similar(entries);
        // 「2333」与「23333」归一化后相同 → 合并；「哈哈哈」不相似 → 保留
        assert_eq!(merged.len(), 2, "merged: {:?}", merged);
        assert_eq!(count, 1);
        assert!(merged[0].content.contains("×2"));
    }

    #[test]
    fn test_similar_merge_respects_window_boundary() {
        let merger = DanmakuMerger::new(DanmakuMergeConfig::default());
        // 窗口默认 30s：40s 间隔不应合并
        let entries = vec![entry(0.0, "2333"), entry(40.0, "23333")];
        let (merged, count) = merger.merge_similar(entries);
        assert_eq!(merged.len(), 2);
        assert_eq!(count, 0);
        // 25s 间隔应合并
        let entries = vec![entry(0.0, "2333"), entry(25.0, "23333")];
        let (merged, count) = merger.merge_similar(entries);
        assert_eq!(merged.len(), 1);
        assert_eq!(count, 1);
    }

    #[test]
    fn test_pure_noise_is_not_merged() {
        let merger = DanmakuMerger::new(DanmakuMergeConfig::default());
        let entries = vec![entry(0.0, "。。。"), entry(0.5, "..."), entry(1.0, "。。。")];
        let (merged, count) = merger.merge_similar(entries);
        assert_eq!(merged.len(), 3, "noise must stay separate: {:?}", merged);
        assert_eq!(count, 0);
    }

    #[test]
    fn test_mode_mismatch_blocks_merge() {
        let merger = DanmakuMerger::new(DanmakuMergeConfig::default());
        let mut a = entry(0.0, "2333");
        let mut b = entry(1.0, "23333");
        b.mode = 4;
        a.mode = 1;
        let (merged, _) = merger.merge_similar(vec![a, b]);
        assert_eq!(merged.len(), 2);
    }
}
