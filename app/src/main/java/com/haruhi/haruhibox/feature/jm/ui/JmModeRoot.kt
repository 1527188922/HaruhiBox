package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.haruhi.haruhibox.core.di.AppContainer
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 任务 tab 的图标。
 *
 * 计划 §9 第 9 条：**角标只统计排队/执行任务**，失败另用提示点。
 * 所以"有失败但没有排队任务"时画一个空角标当提示点，不把失败数加进数字里。
 *
 * 图标用 `KeyboardArrowDown` 而不是"下载"图标：`material-icons-core` 里没有 Download，
 * 而本工程**不引入 `material-icons-extended`**（体积代价，见 `libs.versions.toml`）。
 */
@Composable
private fun TaskTabIcon(badgeCount: Int, hasFailure: Boolean) {
    val icon: @Composable () -> Unit = {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "任务")
    }
    when {
        badgeCount > 0 -> BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { icon() }
        hasFailure -> BadgedBox(badge = { Badge {} }) { icon() }
        else -> icon()
    }
}

object JmRoutes {
    const val LIBRARY = "jm/library"
    const val RECENT = "jm/recent"
    const val DISCOVER = "jm/discover"
    const val FAVORITES = "jm/favorites"
    const val TASKS = "jm/tasks"
    const val FAVORITE_ALBUMS = "jm/favorites/{favoriteId}?name={name}"
    const val DETAIL = "jm/album/{albumId}"
    const val READER = "jm/read/{albumId}?chapterId={chapterId}"

    fun favoriteAlbums(id: Long, name: String) =
        "jm/favorites/$id?name=${URLEncoder.encode(name, Charsets.UTF_8.name())}"

    fun detail(albumId: Long) = "jm/album/$albumId"

    /** 阅读器。`chapterId <= 0` 表示"接着上次读的位置"。 */
    fun reader(albumId: Long, chapterId: Long = 0L) = "jm/read/$albumId?chapterId=$chapterId"
}

private enum class JmTab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY(JmRoutes.LIBRARY, "书库", Icons.Default.Home),
    RECENT(JmRoutes.RECENT, "最近", Icons.AutoMirrored.Filled.List),
    DISCOVER(JmRoutes.DISCOVER, "发现", Icons.Default.Search),
    FAVORITES(JmRoutes.FAVORITES, "收藏", Icons.Default.Favorite),
    TASKS(JmRoutes.TASKS, "任务", Icons.Default.KeyboardArrowDown),
}

/** 页面切换动画时长（毫秒）。 */
private const val PAGE_TRANSITION_MS = 260

/**
 * 漫画模式的导航壳。
 *
 * 底部导航：**书库 / 最近 / 发现 / 收藏 / 任务**
 * （计划 §9 第 2 条的底栏「书库 / 发现 / 收藏 / 任务」，加上用户要求的"最近浏览"）。
 *
 * 详情页与阅读器隐藏底栏（§9 第 2 条：阅读器/全屏播放器隐藏主导航）。
 * 顶部齿轮进当前模式设置在 P6 接，按 §9.5「未实现的功能不展示空页面或占位开关」暂不占位。
 */
@Composable
fun JmModeRoot(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val factory = remember(container) { JmViewModelFactory(container) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 详情页与阅读器隐藏底部导航（计划 §9 第 2 条）
    val showBottomBar = currentRoute in JmTab.entries.map { it.route }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 任务角标要在任意 tab 都显示，所以这个 ViewModel 挂在 Activity 上（不在 NavHost 内部），
    // 任务页复用同一个实例，两处看到的状态一定一致。
    val taskViewModel: JmTaskViewModel = viewModel(factory = factory)
    val taskState by taskViewModel.state.collectAsStateWithLifecycle()

    // 封面统一走应用级 ImageLoader：正确的 UA/代理 + files/covers 磁盘缓存
    // （用户要求第 8 点：加载过的封面优先读本地）
    CompositionLocalProvider(LocalImageLoader provides container.imageLoader) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        JmTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    if (currentRoute != tab.route) {
                                        // 各 tab 保留独立滚动位置与筛选（§9 第 2 条）
                                        navController.navigate(tab.route) {
                                            popUpTo(JmRoutes.LIBRARY) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    if (tab == JmTab.TASKS) {
                                        TaskTabIcon(
                                            badgeCount = taskState.badgeCount,
                                            hasFailure = taskState.failedCount > 0,
                                        )
                                    } else {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = JmRoutes.LIBRARY,
                modifier = Modifier.padding(padding),
                // 页面切换改成"新页面从右侧划入"（用户反馈：默认淡入淡出太弱）。
                // 返回时反向滑出。底部 tab 之间共用这套动画，方向一致、不突兀。
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(PAGE_TRANSITION_MS),
                    ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = tween(PAGE_TRANSITION_MS),
                    ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = tween(PAGE_TRANSITION_MS),
                    ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(PAGE_TRANSITION_MS),
                    ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))
                },
            ) {
                composable(JmRoutes.LIBRARY) { entry ->
                    val vm: JmLibraryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    // 从详情页/阅读器回来时重算"已下载 x/y 话"（下载完成后数字必须跟着变）
                    OnResumeEffect { vm.onScreenResumed() }
                    JmLibraryScreen(
                        state = state,
                        columns = 3,
                        onFilterChange = vm::updateFilter,
                        onClearFilter = vm::clearFilter,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onToggleCollected = vm::setCollected,
                        // 「继续阅读」卡片（§9 第 4 条）：直接回到保存的位置
                        onContinueReading = { albumId -> navController.navigate(JmRoutes.reader(albumId)) },
                    )
                }

                composable(JmRoutes.RECENT) { entry ->
                    val vm: JmRecentViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    JmRecentScreen(
                        state = state,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onClearAll = vm::clearAll,
                    )
                }

                composable(JmRoutes.DISCOVER) { entry ->
                    val vm: JmOnlineSearchViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    JmOnlineSearchScreen(
                        state = state,
                        onKeywordChange = vm::onKeywordChange,
                        onSearch = vm::search,
                        onSortChange = vm::onSortChange,
                        onLoadMore = vm::loadMore,
                        onRevealMore = vm::showMoreItems,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onAddToLibrary = vm::addToLibrary,
                        onOpenHistory = vm::openHistory,
                        onDeleteHistory = vm::deleteHistory,
                        onClearHistory = vm::clearHistory,
                        onConsumeMessage = vm::consumeMessage,
                    )
                }

                composable(JmRoutes.FAVORITES) { entry ->
                    val vm: JmFavoritesViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.message) {
                        state.message?.let {
                            snackbar.showSnackbar(it)
                            vm.consumeMessage()
                        }
                    }
                    JmFavoritesScreen(
                        state = state,
                        onOpenFavorite = { id, name ->
                            navController.navigate(JmRoutes.favoriteAlbums(id, name))
                        },
                        onCreate = vm::create,
                        onRename = vm::rename,
                        onDelete = vm::delete,
                    )
                }

                composable(JmRoutes.TASKS) {
                    LaunchedEffect(taskState.message) {
                        taskState.message?.let {
                            snackbar.showSnackbar(it)
                            taskViewModel.consumeMessage()
                        }
                    }
                    JmTaskScreen(
                        state = taskState,
                        onToggleExpanded = taskViewModel::toggleExpanded,
                        onPause = taskViewModel::pause,
                        onResume = taskViewModel::resume,
                        onCancel = taskViewModel::cancel,
                        onRetry = taskViewModel::retry,
                        onRemove = taskViewModel::remove,
                        onClearFinished = taskViewModel::clearFinished,
                    )
                }

                composable(
                    route = JmRoutes.FAVORITE_ALBUMS,
                    arguments = listOf(
                        navArgument("favoriteId") { type = NavType.LongType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val favoriteId = entry.arguments?.getLong("favoriteId") ?: return@composable
                    val rawName = entry.arguments?.getString("name").orEmpty()
                    val favoriteName = remember(rawName) {
                        runCatching { URLDecoder.decode(rawName, Charsets.UTF_8.name()) }
                            .getOrDefault(rawName)
                    }
                    // 夹内复用书库布局（计划 §9 第 6 条）
                    val vm: JmLibraryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    LaunchedEffect(favoriteId) {
                        vm.updateFilter { it.copy(favoriteId = favoriteId) }
                    }
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    JmLibraryScreen(
                        state = state,
                        columns = 3,
                        // 用户要求第 7 点：显示当前收藏夹名称
                        title = favoriteName.ifBlank { "收藏夹" },
                        // 用户要求第 7 点：夹内只保留「已下载 / 未下载」
                        showCollectedChip = false,
                        onFilterChange = vm::updateFilter,
                        onClearFilter = { vm.updateFilter { it.copy(favoriteId = favoriteId) } },
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onToggleCollected = vm::setCollected,
                    )
                }

                composable(
                    route = JmRoutes.DETAIL,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong("albumId") ?: return@composable
                    val vm: JmAlbumDetailViewModel = viewModel(
                        viewModelStoreOwner = entry,
                        factory = JmViewModelFactory(container, albumId),
                    )
                    val state by vm.state.collectAsStateWithLifecycle()
                    // 从任务页/阅读器回来时重算章节状态（刚下完的章节要立刻显示"已下载"）
                    OnResumeEffect { vm.refreshLocalStats() }
                    JmAlbumDetailScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onRefresh = { vm.refresh() },
                        onToggleReversed = vm::toggleReversed,
                        onToggleDescription = vm::toggleDescription,
                        onToggleCollected = vm::toggleCollected,
                        onAddToLibrary = vm::addToLibrary,
                        onMoveToFavorite = vm::moveToFavorite,
                        onAddToFavorite = vm::addToFavorite,
                        onRemoveFromFavorite = vm::removeFromFavorite,
                        onCreateFavoriteAndAdd = vm::createFavoriteAndAdd,
                        // 点章节进阅读器；阅读器按磁盘现状区分"可读的页"与"缺失的页"
                        onReadChapter = { chapterId ->
                            navController.navigate(JmRoutes.reader(albumId, chapterId))
                        },
                        onContinueReading = { navController.navigate(JmRoutes.reader(albumId)) },
                        onDownloadChapters = vm::downloadChapters,
                        onDismissDownloadSheet = vm::dismissDownloadSheet,
                        onDeleteChapterFiles = vm::deleteChapterFiles,
                        onFetchChapterImages = vm::ensureChapterImages,
                        onDeleteAlbumFiles = vm::deleteAlbumFiles,
                        onOpenTaskPage = { navController.navigate(JmRoutes.TASKS) },
                        // 提示交给根 Scaffold 弹，否则会被收藏夹底部面板挡住（用户反馈）
                        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        onDeleteAlbumRecord = {
                            vm.deleteAlbumRecord()
                            navController.popBackStack()
                        },
                        onConsumeMessage = vm::consumeMessage,
                    )
                }

                composable(
                    route = JmRoutes.READER,
                    arguments = listOf(
                        navArgument("albumId") { type = NavType.LongType },
                        navArgument("chapterId") { type = NavType.LongType; defaultValue = 0L },
                    ),
                ) { entry ->
                    val albumId = entry.arguments?.getLong("albumId") ?: return@composable
                    val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
                    val vm: JmReaderViewModel = viewModel(
                        viewModelStoreOwner = entry,
                        factory = JmViewModelFactory(container, albumId, chapterId.takeIf { it > 0 }),
                    )
                    val state by vm.state.collectAsStateWithLifecycle()
                    val message = state.message
                    LaunchedEffect(message) {
                        if (message != null) {
                            snackbar.showSnackbar(message)
                            vm.consumeMessage()
                        }
                    }
                    JmReaderScreen(
                        state = state,
                        // 退出前把去抖窗口里的位置立刻落库，重启后才能接着读
                        onBack = {
                            vm.flushPosition()
                            navController.popBackStack()
                        },
                        onPositionChanged = vm::onPositionChanged,
                        onToggleControls = vm::toggleControls,
                        onPreviousChapter = vm::previousChapter,
                        onNextChapter = vm::nextChapter,
                        onOpenChapter = vm::openChapterAt,
                        onShowChapterPicker = vm::showChapterPicker,
                        onShowSettings = vm::showSettings,
                        onDismissSheets = vm::dismissSheets,
                        onRetryPage = vm::retryPage,
                        onDownloadChapter = vm::downloadCurrentChapter,
                        onSetReadingMode = vm::setReadingMode,
                        onSetDirection = vm::setReadingDirection,
                        onSetBackground = vm::setBackground,
                        onSetBrightnessFollowSystem = vm::setBrightnessFollowSystem,
                        onSetBrightness = vm::setBrightness,
                        onSetKeepScreenOn = vm::setKeepScreenOn,
                    )
                }
            }
        }
    }
}

/**
 * 手写 ViewModel 工厂（P0 决策 D-3：不用 Hilt）。
 *
 * `albumId` / `chapterId` 只有详情页与阅读器需要，所以做成可选参数。
 */
class JmViewModelFactory(
    private val container: AppContainer,
    private val albumId: Long? = null,
    private val chapterId: Long? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(JmLibraryViewModel::class.java) ->
            JmLibraryViewModel(container.jmRepository, container.jmReading) as T

        modelClass.isAssignableFrom(JmRecentViewModel::class.java) ->
            JmRecentViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmOnlineSearchViewModel::class.java) ->
            JmOnlineSearchViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmFavoritesViewModel::class.java) ->
            JmFavoritesViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmTaskViewModel::class.java) ->
            JmTaskViewModel(container.downloadTasks, container.jmDownloads) as T

        modelClass.isAssignableFrom(JmReaderViewModel::class.java) ->
            JmReaderViewModel(
                repo = container.jmRepository,
                reading = container.jmReading,
                settings = container.settings,
                downloads = container.jmDownloads,
                albumId = albumId ?: error("阅读器必须传 albumId"),
                startChapterId = chapterId,
            ) as T

        modelClass.isAssignableFrom(JmAlbumDetailViewModel::class.java) ->
            JmAlbumDetailViewModel(
                container.jmRepository,
                container.jmDownloads,
                albumId ?: error("详情页必须传 albumId"),
            ) as T

        else -> error("未知的 ViewModel：${modelClass.name}")
    }
}
