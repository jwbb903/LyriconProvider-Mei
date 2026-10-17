/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * Mei Provider Build Script
 */

import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
}

configure<ApplicationExtension> {
    namespace = "io.github.proify.lyricon.meiprovider"
    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        applicationId = "io.github.proify.lyricon.meiprovider"
        minSdk = 28
        targetSdk = rootProject.extra.get("targetSdkVersion") as Int
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // 只有明确提供了 keystore 才启用自定义签名配置。
    // 否则（例如直接 ./gradlew 而没走 build.sh）storeFile 会落到不存在的
    // release.jks，导致 packageDebug 直接失败。
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")?.let { file(it) }
    val hasReleaseStore = releaseStoreFile != null && releaseStoreFile.exists()

    signingConfigs {
        if (hasReleaseStore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasReleaseStore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            if (hasReleaseStore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // extensions-android 声明了 yukihookapi / kavaref, 但它的源码根本没用到
    // (grep 无任何引用)。这些依赖会被打包进 APK, 既撑大体积又让 dex 里
    // 出现 de.robv.android.xposed.XSharedPreferences 这类 legacy 符号。
    // 本模块已迁移到 libxposed API, 直接排除。
    implementation(project(":share:extensions-android")) {
        exclude(group = "com.highcapable.yukihookapi")
        exclude(group = "com.highcapable.kavaref")
    }
    implementation(project(":share:lrckit"))
    implementation(project(":share:yrckit"))
    implementation(project(":share:qrckit"))
    implementation(project(":share:cloudlyric"))

    implementation(libs.lyricon.provider)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)

    implementation(libs.androidx.core.ktx)

    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)

    // TtmlParser / 歌词选择逻辑的单元测试 (纯 Kotlin, 不依赖 Android)
    testImplementation(libs.junit)
}
