package com.haruhi.haruhibox.feature.jm.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 任务 tab 的图标（含角标）。
 *
 * 计划 §9 第 9 条：**角标只统计排队/执行任务**，失败另用提示点。
 * 所以"有失败但没有排队任务"时画一个空角标当提示点，不把失败数加进数字里。
 */
@Composable
private fun TaskTabIcon(badgeCount: Int, hasFailure: Boolean, icon: ImageVector) {
    val content: @Composable () -> Unit = { Icon(icon, contentDescription = "任务") }
    when {
        badgeCount > 0 -> BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { content() }
        hasFailure -> BadgedBox(badge = { Badge {} }) { content() }
        else -> content()
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

    /**
     * 阅读器。
     *
     * `chapterId <= 0` 表示"接着上次读的位置"；给了具体章节就**从那一话的第一页开始**。
     */
    fun reader(albumId: Long, chapterId: Long = 0L) = "jm/read/$albumId?chapterId=$chapterId"
}

/**
 * 底部导航的五个 tab。
 *
 * **声明顺序就是底栏顺序**，也是页面切换动画方向的依据（见 [JmTab.indexOfRoute]）。
 *
 * 图标只能用 `material-icons-core` 里有的：本工程**不引入 `material-icons-extended`**
 * （体积代价，见 `libs.versions.toml`）。所以"最近"用日历（`DateRange`，
 * 表达"按时间翻过的记录"），"任务"用清单（`List`，表达"一批待办的事"）。
 */
private enum class JmTab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY(JmRoutes.LIBRARY, "书库", Icons.Default.Home),
    RECENT(JmRoutes.RECENT, "最近", Icons.Default.DateRange),
    DISCOVER(JmRoutes.DISCOVER, "发现", Icons.Default.Search),
    FAVORITES(JmRoutes.FAVORITES, "收藏", Icons.Default.Favorite),
    TASKS(JmRoutes.TASKS, "任务", Icons.AutoMirrored.Filled.List),
    ;

    companion object {
        /** 底栏里的位置；不是 tab（详情页/阅读器）时返回 -1。 */
        fun indexOfRoute(route: String?): Int = entries.indexOfFirst { it.route == route }
    }
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
    // 一次性提示统一走根部：**先消费、再这里弹**（见 MessageEffect 的注释）。
    // 挂在根部的 scope 上，切 tab 时提示不会被正在销毁的页面协程一起取消。
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    // 导出成品的「打开至 / 发送至」要发系统 Intent（计划 §8 第 10 条）
    val context = LocalContext.current

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
                                            icon = tab.icon,
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
                // 页面切换动画。三种情形分开处理：
                // 1. **底栏 tab 之间**：按目标 tab 在底栏里的位置决定方向——
                //    往右边的 tab 切就从右侧滑入、往左边切就从左侧滑入，
                //    与"底栏本身是一条从左到右的轴"一致，手指和视线都不用重新找方向；
                // 2. **进入详情页/阅读器**（push）：新页面从右侧滑入，返回时反向；
                // 3. 其余情况（例如从详情页返回底栏）走同一套反向动画。
                enterTransition = {
                    val shift = tabShift(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> if (shift < 0) -fullWidth else fullWidth },
                        animationSpec = tween(PAGE_TRANSITION_MS),
                    ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))
                },
                exitTransition = {
                    val shift = tabShift(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(
                        // 旧页面往反方向退，两页像一条传送带
                        targetOffsetX = { fullWidth -> if (shift < 0) fullWidth / 4 else -fullWidth / 4 },
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
                    // 提示语先消费再弹；切 tab 也不会重放（用户反馈的 bug，见 MessageEffect）
                    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                    JmLibraryScreen(
                        state = state,
                        columns = 3,
                        onFilterChange = vm::updateFilter,
                        onClearFilter = vm::clearFilter,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onToggleCollected = vm::setCollected,
                        // 「继续阅读」卡片（§9 第 4 条）：直接回到保存的位置
                        onContinueReading = { albumId -> navController.navigate(JmRoutes.reader(albumId)) },
                        // 长按多选（用户要求第 1 点）
                        onDeleteFiles = vm::deleteFiles,
                        onRemoveFromLibrary = vm::removeFromLibrary,
                        // 批量"修改收藏夹"：多选 + 就地新建，点确认才执行（用户要求第 3 点）
                        onModifyFavorites = vm::modifyFavorites,
                        onCreateFavorite = vm::createFavoriteForSelection,
                        // 往下翻页（用户第十轮要求第 4 点：筛选也要能翻到后面的页）
                        onLoadMore = vm::loadMore,
                    )
                }

                composable(JmRoutes.RECENT) { entry ->
                    val vm: JmRecentViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    JmRecentScreen(
                        state = state,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onClearAll = vm::clearAll,
                        onRemoveAlbum = vm::remove,
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
                        // 本页自己没有 Snackbar 宿主，提示统一交给根部弹
                        onMessage = showMessage,
                        onConsumeMessage = vm::consumeMessage,
                    )
                }

                composable(JmRoutes.FAVORITES) { entry ->
                    val vm: JmFavoritesViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                    JmFavoritesScreen(
                        state = state,
                        onOpenFavorite = { id, name ->
                            navController.navigate(JmRoutes.favoriteAlbums(id, name))
                        },
                        onCreate = vm::create,
                        onRename = vm::rename,
                        onDelete = vm::delete,
                        onSortChange = vm::setSort,
                    )
                }

                composable(JmRoutes.TASKS) {
                    MessageEffect(
                        taskState.message,
                        onConsume = taskViewModel::consumeMessage,
                        onShow = showMessage,
                    )
                    JmTaskScreen(
                        state = taskState,
                        onToggleExpanded = taskViewModel::toggleExpanded,
                        onPause = taskViewModel::pause,
                        onResume = taskViewModel::resume,
                        onCancel = taskViewModel::cancel,
                        onRetry = taskViewModel::retry,
                        onRemove = taskViewModel::remove,
                        onClearFinished = taskViewModel::clearFinished,
                        // 点封面进详情页（用户要求第 1 点）
                        onOpenAlbum = { albumId -> navController.navigate(JmRoutes.detail(albumId)) },
                        // 导出成品的「打开至 / 发送至」（用户要求第 4 点）
                        onOpenOutput = { name ->
                            launchOutputIntent(context, name, share = false, snackbar, scope) {
                                taskViewModel.outputUri(name)
                            }
                        },
                        onSendOutput = { name ->
                            launchOutputIntent(context, name, share = true, snackbar, scope) {
                                taskViewModel.outputUri(name)
                            }
                        },
                        onDeleteOutput = taskViewModel::deleteOutput,
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
                    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
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
                        // 夹内批量模式（用户要求第 3 点）：
                        // - 不提供「删除文件」「移出书库」；
                        // - 按钮文案改成「修改收藏夹」；
                        // - 打开面板时把当前夹预先勾上（选中的本子本来都在这夹里）。
                        moveFavoriteLabel = "修改收藏夹",
                        preselectedFavoriteIds = setOf(favoriteId),
                        onModifyFavorites = vm::modifyFavorites,
                        onCreateFavorite = vm::createFavoriteForSelection,
                        onLoadMore = vm::loadMore,
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
                    // 移除要删图片、导出成品、封面缓存和五张表，是异步的；
                    // 立刻 pop 会把 ViewModel 的协程一起取消（删一半就没了），所以等它做完再退。
                    // 还要等提示语被消费掉（state.message 变回 null），否则用户什么都看不到。
                    LaunchedEffect(state.removedFromLibrary, state.message) {
                        if (state.removedFromLibrary && state.message == null) {
                            navController.popBackStack()
                        }
                    }
                    JmAlbumDetailScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onRefresh = { vm.refresh() },
                        onToggleReversed = vm::toggleReversed,
                        onToggleDescription = vm::toggleDescription,
                        onAddToLibrary = vm::addToLibrary,
                        onMoveToFavorite = vm::moveToFavorite,
                        onAddToFavorite = vm::addToFavorite,
                        onRemoveFromFavorite = vm::removeFromFavorite,
                        onCreateFavoriteAndAdd = vm::createFavoriteAndAdd,
                        // 点章节进阅读器：**先把这个本子的进度改写成这一话**，
                        // 阅读器再按"从这一话第一张开始"打开（用户第十一轮要求第 1 点）
                        onReadChapter = { chapterId ->
                            vm.onChapterOpened(chapterId)
                            navController.navigate(JmRoutes.reader(albumId, chapterId))
                        },
                        // 主按钮：有进度就按存下来的位置续读（不带章节号 = 续读），
                        // 没进度才从第一话第一张开始（用户第十一轮要求第 1 点）
                        onReadFromStart = {
                            val position = state.progress
                            if (position?.isUsable == true) {
                                navController.navigate(JmRoutes.reader(albumId))
                            } else {
                                val first = state.chapters.firstOrNull()
                                if (first != null) {
                                    vm.onChapterOpened(first.chapterId)
                                    navController.navigate(JmRoutes.reader(albumId, first.chapterId))
                                }
                            }
                        },
                        onDownloadChapters = vm::downloadChapters,
                        onDeleteChapterFiles = vm::deleteChapterFiles,
                        onDeleteAlbumFiles = vm::deleteAlbumFiles,
                        onExport = vm::startExport,
                        onDownloadMissing = vm::downloadMissingChapters,
                        onDismissExportPrecheck = vm::dismissExportPrecheck,
                        onConfirmExport = vm::confirmExport,
                        onExportWithoutPassword = vm::exportWithoutPassword,
                        onCancelExport = vm::cancelExport,
                        // 详情页「导出文件」区块的三个操作（用户第九轮要求第 2 点）
                        onOpenOutput = { name ->
                            launchOutputIntent(context, name, share = false, snackbar, scope) {
                                vm.outputUri(name)
                            }
                        },
                        onSendOutput = { name ->
                            launchOutputIntent(context, name, share = true, snackbar, scope) {
                                vm.outputUri(name)
                            }
                        },
                        onDeleteExport = vm::deleteExport,
                        // 提示交给根 Scaffold 弹，否则会被收藏夹底部面板挡住（用户反馈）；
                        // 页面内部的 MessageEffect 会先把消息消费掉，所以切页不会重放
                        onMessage = showMessage,
                        // 页面在 state.removedFromLibrary 变 true 后自己退出（见上面的 LaunchedEffect）
                        onDeleteAlbumRecord = vm::removeFromLibrary,
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
                    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                    // 从任务页/详情页回到阅读器时重新扫一遍本地缓存：
                    // 刚下好的页要立刻显示出来（用户要求第 2 点）
                    OnResumeEffect { vm.refreshFromDisk() }
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
                        onRequestPage = vm::requestPage,
                        onDownloadChapter = vm::downloadCurrentChapter,
                        onToggleCollected = vm::toggleCollected,
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
 * 两个底栏 tab 之间的"移动方向"：正数表示往右切（目标在右边），负数表示往左切。
 *
 * 有一端不是底栏 tab（进详情页/阅读器、从它们返回）时返回正数，
 * 于是走的还是原来那套"新页面从右侧滑入"。
 */
internal fun tabShift(fromRoute: String?, toRoute: String?): Int {
    val from = JmTab.indexOfRoute(fromRoute)
    val to = JmTab.indexOfRoute(toRoute)
    if (from < 0 || to < 0) return 1
    return to - from
}

/**
 * 导出成品的「打开至 / 发送至」。
 *
 * 不假设用户装了哪个阅读器：
 * - 打开至 → `ACTION_VIEW`：系统自己弹"用什么打开"（有默认应用就直接开）；
 * - 发送至 → `ACTION_SEND` + 选择器：分享给微信/邮箱等任意 App。
 *
 * Uri 要用 MediaStore 查（成品在系统下载目录，不是应用的私有文件），所以是挂起操作；
 * 任务页与详情页都要用，所以 Uri 由调用方给（[resolveUri]），这里只负责发 Intent。
 */
private fun launchOutputIntent(
    context: Context,
    displayName: String,
    share: Boolean,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    resolveUri: suspend () -> android.net.Uri?,
) {
    scope.launch {
        val uri = resolveUri()
        if (uri == null) {
            snackbar.showSnackbar("找不到这个文件，可能已经被删除")
            return@launch
        }
        val mime = if (displayName.endsWith(".pdf", ignoreCase = true)) {
            "application/pdf"
        } else {
            "application/zip"
        }
        runCatching {
            if (share) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "发送至"))
            } else {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(view)
            }
        }.onFailure {
            snackbar.showSnackbar("没有可以打开这个文件的应用")
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
            JmLibraryViewModel(
                repo = container.jmRepository,
                reading = container.jmReading,
                cleaner = container.jmAlbumCleaner,
                exports = container.jmExports,
            ) as T

        modelClass.isAssignableFrom(JmRecentViewModel::class.java) ->
            JmRecentViewModel(container.jmRepository, container.jmAlbumCleaner) as T

        modelClass.isAssignableFrom(JmOnlineSearchViewModel::class.java) ->
            JmOnlineSearchViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmFavoritesViewModel::class.java) ->
            JmFavoritesViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmTaskViewModel::class.java) ->
            JmTaskViewModel(container.downloadTasks, container.jmDownloads, container.jmExports) as T

        modelClass.isAssignableFrom(JmReaderViewModel::class.java) ->
            JmReaderViewModel(
                repo = container.jmRepository,
                reading = container.jmReading,
                store = container.jmImageStore,
                settings = container.settings,
                downloads = container.jmDownloads,
                albumId = albumId ?: error("阅读器必须传 albumId"),
                startChapterId = chapterId,
            ) as T

        modelClass.isAssignableFrom(JmAlbumDetailViewModel::class.java) ->
            JmAlbumDetailViewModel(
                repo = container.jmRepository,
                downloads = container.jmDownloads,
                exports = container.jmExports,
                tasks = container.downloadTasks,
                secrets = container.secrets,
                cleaner = container.jmAlbumCleaner,
                reading = container.jmReading,
                albumId = albumId ?: error("详情页必须传 albumId"),
            ) as T

        else -> error("未知的 ViewModel：${modelClass.name}")
    }
}
