import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 本地 release 签名。
// 没有 keystore.properties 时 release 包不签名（CI 与只跑单测的场景不需要它）。
// 详见 docs/BUILD.md。
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // 侧载自用：开启 v1+v2 以兼容旧机型
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 调试包与正式包使用不同的私有目录，避免装了两个包时互相踩数据
            buildConfigField("String", "LIBRARY_DIR_NAME", "\"library-debug\"")
        }
        release {
            buildConfigField("String", "LIBRARY_DIR_NAME", "\"library\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ⚠ 不要在这里配 optimization.packageScope。
            // AGP 9 的 `optimization { packageScope = setOf("kotlin.**", ...) }` 会让 R8 把
            // Kotlin 标准库类重打包到别的包，而 Kotlin 的 internal 类在字节码里是 public，
            // 重打包后 R8 会把它们收窄成包私有 —— 结果任何继承它们的类（包括 R8 自己合成的类）
            // 都会在运行期抛：
            //   IllegalAccessError: Class kotlin.text.k extended by class v02 is inaccessible
            // 表现为 androidx.startup.InitializationProvider 初始化失败、应用启动即闪退。
            // 这条配置曾出现在 P0 的脚手架上，P1 加签名/混淆时被证实有害，已移除。
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
                // ---- 体积优化（见 docs/BUILD.md）----
                // pdfbox-android 传递引入 org.bouncycastle:bcprov-jdk15to18，
                // 而 bcprov 体积最大的部分是**后量子密码**的查找表，纯数据文件、
                // 不是类，R8 不会删：
                //   lowmc.properties 3.6MB、sike/p751 1.9MB、p610 1.2MB、p503 765KB…
                // PDFBox 只用 BouncyCastle 做标准 CMS/证书解析，完全用不到 PQC。
                // 注意：这不是安全功能开关，被排除的只是 PQC 算法的参数表。
                "org/bouncycastle/pqc/**",
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
    // ⚠ 不要引 material-icons-extended：它是一个包含几千个矢量图标的模块，
    // 即使 R8 能删掉未使用的图标，也会显著拖慢构建、并且 debug 包里全量保留。
    // 需要图标时用 material-icons-core（随 material3 传递引入）里的常用集，
    // 或者自己画 ImageVector。
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
