plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.skipflow.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.skipflow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // OCR 模型带原生库，去掉 x86 能省一半体积
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets {
        // 规则 JSON 放在仓库根的 rules/ 下，安卓和以后的 iOS/桌面端共用一份
        getByName("main").assets.directories.add("../../rules")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 唯一的运行时依赖：ML Kit 中文 OCR，模型打包在 APK 里，离线运行、不依赖 Google Play 服务
    implementation(libs.mlkit.text.chinese)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // 本地单测里 android.jar 的 org.json 是空桩
}
