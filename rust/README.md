# PiliNara Native (Rust)

Native Rust library for PiliNara Android app. Provides high-performance implementations of:

- **Animated WebP encoding** — replaces Android's `Bitmap.compress()` with a pure Rust implementation
- **Audio normalization** — dynaudnorm-style loudness normalization for audio playback

## Building

### Prerequisites

- Rust toolchain (`rustup`)
- Android NDK 30.0.16248370
- Only the Android ARM64 target is supported:
  ```bash
  rustup target add aarch64-linux-android
  ```

### Build for Android (manual)

```bash
# Set up environment
export ANDROID_NDK_HOME=/path/to/android-ndk-30.0.16248370
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android36-clang"

# Build only the Android ARM64 target after the locked mpv/libplacebo build
export MPV_PREFIX=/path/to/prefix/arm64
cargo build --release --target aarch64-linux-android
```

### Build via CI

Push to `main` or open a pull request targeting `main` to trigger the CI build.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Android (Kotlin + Compose)                       │
│  ┌─────────────────────────────────────────────┐   │
│  │ Media3 player and subscription UI            │   │
│  └──────────────────┬──────────────────────────┘   │
│                     │ JNI                          │
├─────────────────────┼───────────────────────────────┤
│  Native (Rust + pinned ARM64 dependencies)       │
│  ┌─────────────────────────────────────────────┐   │
│  │ Kotlin playback and native media contracts   │   │
│  └──────────────────┬──────────────────────────┘   │
├─────────────────────┼───────────────────────────────┤
│  Android (Kotlin)                                  │
│  ┌─────────────────────────────────────────────┐   │
│  │ Media3 + RustGpuNextBridge                  │   │
│  │  - WebpNativeLib.encode()                   │   │
│  │  - AudioNativeLib.normalizeAudio()          │   │
│  └──────────────────┬──────────────────────────┘   │
│                     │ JNI                          │
├─────────────────────┼───────────────────────────────┤
│  Native (Rust)                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │ pilinara_native crate                       │   │
│  │  - webp::AnimatedWebpEncoder                │   │
│  │  - audio::AudioNormalizer                   │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## JNI Interface

| Function | Parameters | Returns | Description |
|----------|-----------|---------|-------------|
| `WebpNativeLib_create` | width, height | jlong (pointer) | Create encoder, -1 on error |
| `WebpNativeLib_addFrame` | ptr, data, durationMs, x, y | jint (0=ok) | Validate and add RGBA frame data |
| `WebpNativeLib_finalize` | ptr | ByteArray | Finalize and return WebP bytes |
| `AudioNativeLib_normalize` | input, channels | ByteArray | Normalize PCM samples |

## Testing

```bash
# Run unit tests (host target)
cd rust
cargo test

# Cross-compile check (Android ARM64 target; requires MPV_PREFIX from the locked native build)
MPV_PREFIX=/path/to/prefix/arm64 cargo check --target aarch64-linux-android
```
