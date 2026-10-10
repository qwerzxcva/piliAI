//! PiliNara native library
//!
//! Provides Rust implementations for:
//! - Animated WebP encoding (replacing Android Bitmap.compress)
//! - Audio normalization (dynaudnorm-style)
//! - Danmaku merging and filtering
//!
//! ## Architecture
//!
//! Android builds use cargo-ndk or manual NDK toolchain configuration.
//! The Android JNI interfaces are in `android_entry.rs` and `gpu_next.rs`; both
//! are compiled only for Android targets.

extern crate std;

// WebP encoding module
pub mod webp;
// Audio normalization module
pub mod audio;
// Danmaku merging module
pub mod danmaku;

pub mod dmfilter;

pub mod dmheat;
// Playurl DASH stream selection (stage 5)
pub mod playurl;

// GPU-next is Android-only because its JNI and libmpv client API require the
// locked Android ARM64 native dependency prefix.
#[cfg(target_os = "android")]
mod gpu_next;

#[cfg(target_os = "android")]
mod android_entry;

#[cfg(not(target_os = "android"))]
pub use audio::{AudioNormalizationConfig, AudioNormalizer};
#[cfg(not(target_os = "android"))]
pub use danmaku::{DanmakuEntry, DanmakuMergeConfig, DanmakuMerger, MergedDanmaku};
#[cfg(not(target_os = "android"))]
pub use webp::{AnimatedWebpEncoder, WebpError};
