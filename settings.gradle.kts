pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 说明：这里刻意不注册 org.gradle.toolchains.foojay-resolver-convention。
// 它会让 Gradle 在工具链不匹配时去 api.foojay.io 下载一整个 JDK（曾阻塞 P0 构建）。
// 本工程只用本机已安装的 JDK 21，见 gradle.properties 的 org.gradle.java.home。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HaruhiBox"
include(":app")
