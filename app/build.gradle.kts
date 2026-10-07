plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.haruhi.haruhibox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.haruhi.haruhibox"
        // minSdk 26：JM 阅读器需要 BitmapRegionDecoder + 现代 WebP 解码，
        // 且 Android 8 起前台服务/通知渠道语义稳定。
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    buildTypes {
        debug {
            // 调试包与正式包使用不同的私有目录，避免装了两个包时互相踩数据
            buildConfigField("String", "LIBRARY_DIR_NAME", "\"library-debug\"")
        }
        release {
            buildConfigField("String", "LIBRARY_DIR_NAME", "\"library\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 保持 false：设为 true 会把 `android.jar` 换成打桩版本（见 docs/p0/README.md 的 D-5）。
            isReturnDefaultValues = false

            all {
                // 每个测试类单独一个 JVM。
                // 原因（见 docs/p0/README.md 的 D-6）：Robolectric 的沙箱会把 Conscrypt 的
                // OpenSSL provider 泄漏到后续测试，导致 JDK 的 javax.crypto.JarVerifier
                // 初始化失败，纯 JVM 的 AES 用例集体报 NoClassDefFoundError。
                // 目前测试量很小（~50 个），这个代价可以接受；测试变多后应改为
                // 把 Robolectric 用例拆到独立的 test source set。
                it.forkEvery = 1
            }
        }
    }

    lint {
        // P0 起把 lint 当 CI 门禁：错误必须修。
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            // 依赖版本是我们刻意固定的（见 libs.versions.toml 顶部说明），
            // 让 lint 每次联网报"有更新版本"只会制造噪声
            "NewerVersionAvailable",
            "GradleDependency",
            "AndroidGradlePluginVersion",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // --- 数据库 ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- 后台任务 / 存储 ---
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // --- 媒体 ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.common)

    // --- 网络 / 序列化 ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // --- 图片 ---
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- 打包 / PDF ---
    implementation(libs.zip4j)
    implementation(libs.pdfbox.android)

    // --- 日志 ---
    implementation(libs.timber)

    // --- 单元测试 ---
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))

    // --- 仪器测试 ---
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
