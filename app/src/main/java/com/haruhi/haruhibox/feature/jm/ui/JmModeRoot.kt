package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 漫画模式的导航壳。
 *
 * 底部导航：**书库 / 最近 / 发现 / 收藏**
 * （计划 §9 第 2 条的底栏，加上用户要求第 3 点的"最近浏览"）。
 *
 * 「任务」页在 P3 接入下载任务后才有内容，按 §9.5「**未实现的功能不展示空页面或占位开关**」
 * 的要求现在**不放**这个 tab；顶部齿轮进当前模式设置在 P6 接，同样不占位。
 */
object JmRoutes {
    const val LIBRARY = "jm/library"
    const val RECENT = "jm/recent"
    const val DISCOVER = "jm/discover"
    const val FAVORITES = "jm/favorites"
    const val FAVORITE_ALBUMS = "jm/favorites/{favoriteId}?name={name}"
    const val DETAIL = "jm/album/{albumId}"

    fun favoriteAlbums(id: Long, name: String) =
        "jm/favorites/$id?name=${URLEncoder.encode(name, Charsets.UTF_8.name())}"

    fun detail(albumId: Long) = "jm/album/$albumId"
}

private enum class JmTab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY(JmRoutes.LIBRARY, "书库", Icons.Default.Home),
    RECENT(JmRoutes.RECENT, "最近", Icons.AutoMirrored.Filled.List),
    DISCOVER(JmRoutes.DISCOVER, "发现", Icons.Default.Search),
    FAVORITES(JmRoutes.FAVORITES, "收藏", Icons.Default.Favorite),
}

@Composable
fun JmModeRoot(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val factory = remember(container) { JmViewModelFactory(container) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 详情页隐藏底部导航（计划 §9 第 2 条：详情页有返回按钮）
    val showBottomBar = currentRoute in JmTab.entries.map { it.route }

    val snackbar = remember { SnackbarHostState() }

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
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
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
            ) {
                composable(JmRoutes.LIBRARY) { entry ->
                    val vm: JmLibraryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    JmLibraryScreen(
                        state = state,
                        columns = 3,
                        onFilterChange = vm::updateFilter,
                        onClearFilter = vm::clearFilter,
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onToggleCollected = vm::setCollected,
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
                        // 阅读器在 P3 接；现在先把章节图片清单拉下来
                        onOpenChapter = { vm.ensureChapterImages(it) },
                        onFetchChapterImages = vm::ensureChapterImages,
                        onDeleteAlbumFiles = vm::deleteAlbumFiles,
                        onDeleteAlbumRecord = {
                            vm.deleteAlbumRecord()
                            navController.popBackStack()
                        },
                        onConsumeMessage = vm::consumeMessage,
                    )
                }
            }
        }
    }
}

/**
 * 手写 ViewModel 工厂（P0 决策 D-3：不用 Hilt）。
 *
 * `albumId` 只在详情页需要，所以做成可选参数；其它 ViewModel 都只依赖容器。
 */
class JmViewModelFactory(
    private val container: AppContainer,
    private val albumId: Long? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(JmLibraryViewModel::class.java) ->
            JmLibraryViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmRecentViewModel::class.java) ->
            JmRecentViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmOnlineSearchViewModel::class.java) ->
            JmOnlineSearchViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmFavoritesViewModel::class.java) ->
            JmFavoritesViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(JmAlbumDetailViewModel::class.java) ->
            JmAlbumDetailViewModel(
                container.jmRepository,
                albumId ?: error("详情页必须传 albumId"),
            ) as T

        else -> error("未知的 ViewModel：${modelClass.name}")
    }
}
