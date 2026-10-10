# ===== PiliNara R8/ProGuard 规则（审核87：启用 minify + shrinkResources）=====
# Kotlin 序列化 / 反射保留
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keep,includedescriptorclasses class com.example.piliai.**$$serializer { *; }
-keepclassmembers class com.example.piliai.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.piliai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor（反射/插件加载）
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# Gson（DownloadManager 弹幕 JSON）
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson TypeToken 泛型（弹幕 Map 反序列化）
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# JNI：Rust native 方法，包名+类名+方法名参与 JNI 符号绑定，不可混淆/改名
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.example.piliai.PlayUrlNativeLib { *; }
-keep class com.example.piliai.danmaku.DanmakuNativeLib { *; }
-keep class com.example.piliai.AudioNativeLib { *; }
-keep class com.example.piliai.WebpNativeLib { *; }
-keep class com.example.piliai.playback.backend.RustGpuNextBridge { *; }

# 兼容性抑制
-dontwarn com.google.android.play.core.**
-dontwarn javax.annotation.Nullable
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.OpenSSLProvider
