use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=MPV_PREFIX");

    let target = env::var("TARGET").unwrap_or_default();
    if !target.contains("android") {
        return;
    }

    let prefix = PathBuf::from(env::var("MPV_PREFIX").unwrap_or_else(|_| {
        panic!("MPV_PREFIX is required for Android GPU-next builds")
    }));
    let lib_dir = prefix.join("lib");
    let mpv = lib_dir.join("libmpv.so");
    if !prefix.is_dir() {
        panic!("MPV_PREFIX does not exist: {}", prefix.display());
    }
    if !mpv.is_file() {
        panic!("MPV_PREFIX is missing libmpv.so: {}", mpv.display());
    }

    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-lib=dylib=mpv");
    println!("cargo:rustc-link-lib=dylib=android");
    println!("cargo:rustc-link-lib=dylib=c");
    println!("cargo:rustc-link-lib=dylib=log");
    println!("cargo:rustc-link-lib=dylib=dl");
    println!("cargo:rustc-env=TARGET_ARCH=arm64-v8a");
}
