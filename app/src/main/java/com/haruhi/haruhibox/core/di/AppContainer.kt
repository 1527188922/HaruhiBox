package com.haruhi.haruhibox.core.di

import android.content.Context
import com.haruhi.haruhibox.BuildConfig
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.network.NetworkClientFactory
import com.haruhi.haruhibox.core.settings.SecretStore
import com.haruhi.haruhibox.core.settings.SettingKeys
import com.haruhi.haruhibox.core.settings.SettingsRepository
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.flow.first
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

    /**
     * 本地目录规划。
     *
     * debug 与 release 的 `applicationId` 不同（`.debug` 后缀），所以两者的 `filesDir`
     * 天然隔离，这里不需要再靠目录名区分（原先的 `LIBRARY_DIR_NAME` 因此已移除）。
     */
    val libraryDirs: LibraryDirs = LibraryDirs(
        File(appContext.filesDir, LIBRARY_DIR_NAME),
    ).ensureAll()

    val database: HaruhiBoxDatabase by lazy { HaruhiBoxDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    // ---------------- 下载 ----------------

    /**
     * 下载引擎。**每次取用都会按当前网络设置新建**：
     * 计划 §9.2 要求"线程数、超时、线路等对新任务或下次重试生效"，
     * 所以引擎不缓存客户端，而是持有 [jmHttpClient] / [biliHttpClient] 的引用。
     */
    val fileDownloader: com.haruhi.haruhibox.core.download.FileDownloader by lazy {
        com.haruhi.haruhibox.core.download.FileDownloader(
            client = jmHttpClient,
            dirs = libraryDirs,
        )
    }

    /** 带 B站请求头的下载器（B站 CDN 对 Referer 有校验）。 */
    val biliFileDownloader: com.haruhi.haruhibox.core.download.FileDownloader by lazy {
        com.haruhi.haruhibox.core.download.FileDownloader(
            client = biliHttpClient,
            dirs = libraryDirs,
        )
    }

    /** 持久化下载任务仓库。 */
    val downloadTasks: com.haruhi.haruhibox.core.download.DownloadTaskRepository by lazy {
        com.haruhi.haruhibox.core.download.DownloadTaskRepository(
            dao = database.downloadTaskDao(),
            dirs = libraryDirs,
        )
    }

    // ---------------- JM ----------------

    /**
     * JM 接口客户端。
     *
     * **每次取用都重新构造**：它只持有 OkHttp 客户端与几个"读设置"的闭包，
     * 构造成本可以忽略；而这样能保证改完设置（线路、重试次数、超时）后
     * 新任务立刻用上新值（计划 §9.2 的生效时机要求）。
     */
    val jmApi: com.haruhi.haruhibox.feature.jm.api.JmApiClient
        get() = com.haruhi.haruhibox.feature.jm.api.JmApiClient(
            client = jmHttpClient,
            config = { runBlocking { settings.resolveJmNetwork(secrets) } },
            retryProvider = {
                com.haruhi.haruhibox.feature.jm.api.JmApiClient.JmRetrySettings(
                    album = runBlocking { jmRetry(SettingKeys.JM_RETRY_ALBUM) },
                    search = runBlocking { jmRetry(SettingKeys.JM_RETRY_SEARCH) },
                    chapter = runBlocking { jmRetry(SettingKeys.JM_RETRY_CHAPTER) },
                )
            },
            apiDomainProvider = { runBlocking { jmApiDomain() } },
        )

    private suspend fun jmRetry(key: String): Int = settings
        .jmInt(key, com.haruhi.haruhibox.core.settings.SettingDefaults.JM_RETRY)
        .first()

    /** 接口线路：取设置里列表的第一项；未配置时用参考实现的默认域名。 */
    private suspend fun jmApiDomain(): String = settings
        .jmString(SettingKeys.JM_API_DOMAINS)
        .first()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: com.haruhi.haruhibox.core.jm.JmDefaults.DEFAULT_API_DOMAIN

    /** JM 仓库（在线数据 ↔ 本地库）。 */
    val jmRepository: com.haruhi.haruhibox.feature.jm.data.JmRepository by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmRepository(
            api = jmApi,
            albumDao = database.jmAlbumDao(),
            chapterDao = database.jmChapterDao(),
            favoriteDao = database.jmFavoriteDao(),
            searchHistoryDao = database.jmSearchHistoryDao(),
            dirs = libraryDirs,
        )
    }

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

        /**
         * 私有库目录名。
         *
         * debug 与 release 的 `applicationId` 不同（`.debug` 后缀），各自的 `filesDir`
         * 本来就隔离，所以这里两种构建用同一个名字即可。
         */
        const val LIBRARY_DIR_NAME = "library"
    }
}
