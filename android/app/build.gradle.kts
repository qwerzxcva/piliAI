import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    id("com.android.application") version "8.12.0"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
    id("androidx.room") version "2.8.5"
    kotlin("plugin.serialization") version "2.2.21"
}

android {
    namespace = "com.example.piliai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.piliai"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndkVersion = "30.0.16248370"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 审核87：启用 R8 混淆 + 资源压缩（此前 false 导致 APK 未优化、代码可逆）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    // 弹弹play (DanDan) 弹幕源 API 凭据（可选；从 https://www.dandanplay.com/api-docs/ 申请）。
    // 未配置时 DandanApi.isEnabled=false，弹幕源静默关闭，不影响 B 站原生弹幕。
    defaultConfig.buildConfigField(
        "String",
        "DANDAN_API_APPID",
        "\"${System.getenv("DANDAN_API_APPID") ?: ""}\"",
    )
    defaultConfig.buildConfigField(
        "String",
        "DANDAN_API_KEY",
        "\"${System.getenv("DANDAN_API_KEY") ?: ""}\"",
    )
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 审核45 + 审核99：Room schema 导出统一由 room 插件扩展配置（见文件末尾 room{} 块）。
// 注意：不可同时使用 ksp arg("room.schemaLocation")，Room Gradle 插件会报冲突——已移除。

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // 二维码生成（扫码登录）
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media:media:1.7.0")
    
    // Ktor for networking
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-okhttp:3.0.1")
    implementation("io.ktor:ktor-client-websockets:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation("io.ktor:ktor-client-encoding:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Animeko 网页刮削源（web-selector）：CSS 选择器引擎
    implementation("org.jsoup:jsoup:1.18.1")
    
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    
    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Media3 for video playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")  // for LanczosResample
    
    // Room for local storage
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Audio service (foreground playback)
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Room schema 导出：启用迁移校验，消除 "Schema export directory was not provided" 警告
room {
    schemaDirectory("$projectDir/schemas")
}
