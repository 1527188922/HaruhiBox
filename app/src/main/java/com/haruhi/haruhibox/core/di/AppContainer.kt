package com.haruhi.haruhibox.core.di

import android.content.Context
import com.haruhi.haruhibox.BuildConfig
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.network.NetworkClientFactory
import com.haruhi.haruhibox.core.settings.SecretStore
import com.haruhi.haruhibox.core.settings.SettingsRepository
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/**
 * 手写依赖注入容器（P0 决策 D-3）。
 *
 * 为什么不用 Hilt：AGP 9 的内置 Kotlin 禁止第三方 Gradle 插件用
 * `kotlin.sourceSets` 添加生成目录，Hilt 的 Gradle 插件正是这么做的，应用即失败；
 * KSP 2.3.x 已经改用 Variant API 所以可用，但 Hilt 不行。详见 `docs/p0/README.md`。
 *
 * 手写容器的代价是没有编译期注入校验；收益是**依赖关系完全显式**、构建可用、
 * 没有代码生成。本工程的依赖图很浅（设置 → 网络 → 仓库 → ViewModel），规模可控。
 *
 * 生命周期：由 `HaruhiBoxApp` 创建一次，存活到进程结束。
 * **每个模式的 OkHttp 客户端是懒加载 + 可失效的**：
 * 计划 §9.2 要求「线程数、超时、线路等对新任务或下次重试生效，不中途替换运行任务配置」，
 * 所以改设置后调 [invalidateNetworkClients] 重建客户端，已在跑的任务继续用旧实例。
 */
class AppContainer(private val appContext: Context) {

    // ---------------- 存储 ----------------

    val libraryDirs: LibraryDirs = LibraryDirs(
        File(appContext.filesDir, BuildConfig.LIBRARY_DIR_NAME),
    ).ensureAll()

    val database: HaruhiBoxDatabase by lazy { HaruhiBoxDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    // ---------------- 网络 ----------------

    @Volatile
    private var jmClient: OkHttpClient? = null

    @Volatile
    private var biliClient: OkHttpClient? = null

    /** JM 模式的网络客户端。首次访问时按当前设置构建。 */
    val jmHttpClient: OkHttpClient
        get() = jmClient ?: synchronized(this) {
            jmClient ?: buildClient(NetworkProfile.JM).also { jmClient = it }
        }

    /** B站模式的网络客户端。 */
    val biliHttpClient: OkHttpClient
        get() = biliClient ?: synchronized(this) {
            biliClient ?: buildClient(NetworkProfile.BILI).also { biliClient = it }
        }

    /**
     * 设置变更后调用：丢掉缓存的客户端，让新任务用新配置。
     * 已在执行的任务持有旧实例的引用，不受影响（符合 §9.2 的生效时机要求）。
     */
    fun invalidateNetworkClients() {
        synchronized(this) {
            jmClient = null
            biliClient = null
        }
    }

    private fun buildClient(profile: NetworkProfile): OkHttpClient {
        val config = runBlocking {
            when (profile) {
                NetworkProfile.JM -> settings.resolveJmNetwork(secrets)
                NetworkProfile.BILI -> settings.resolveBiliNetwork(secrets)
            }
        }
        return NetworkClientFactory.create(
            config = config,
            userAgent = when (profile) {
                NetworkProfile.JM -> JmDefaults.USER_AGENT
                NetworkProfile.BILI -> BILI_USER_AGENT
            },
            extraHeaders = when (profile) {
                NetworkProfile.JM -> emptyMap()
                // 参考实现 BilibiliService 第 62～67 行的固定请求头
                NetworkProfile.BILI -> mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                )
            },
            enableLogging = BuildConfig.DEBUG,
        )
    }

    private enum class NetworkProfile { JM, BILI }

    companion object {
        /** 参考实现 `BilibiliService.HEADERS` 的 User-Agent。 */
        const val BILI_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0"
    }
}
