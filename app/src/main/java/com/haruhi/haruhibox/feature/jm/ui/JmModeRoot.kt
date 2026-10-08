package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * 漫画模式的导航壳：底部导航 **书库 / 发现 / 收藏 / 任务**（计划 §9 第 2 条）。
 *
 * 「任务」页在 P3 接入下载任务后才有内容，按 §9.5「**未实现的功能不展示空页面或占位开关**」
 * 的要求，现在**不放**这个 tab —— 等 P3 做完再加入。
 * 顶部齿轮进当前模式设置，也在 P6 接（同样不占位）。
 */
object JmRoutes {
    const val LIBRARY = "jm/library"
    const val DISCOVER = "jm/discover"
    const val FAVORITES = "jm/favorites"
    const val FAVORITE_ALBUMS = "jm/favorites/{favoriteId}?name={name}"
    const val DETAIL = "jm/album/{albumId}"

    fun favoriteAlbums(id: Long, name: String) =
        "jm/favorites/$id?name=${java.net.URLEncoder.encode(name, "UTF-8")}"

    fun detail(albumId: Long) = "jm/album/$albumId"
}

private enum class JmTab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY(JmRoutes.LIBRARY, "书库", Icons.Default.Home),
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
    // 详情页隐藏底部导航（计划 §9 第 2 条：详情页有返回按钮，阅读器/全屏播放器隐藏主导航）
    val showBottomBar = currentRoute in JmTab.entries.map { it.route }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    JmTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    // 各模式保留独立返回栈（§9 第 2 条）
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
                val vm: JmLibraryViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = factory,
                )
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

            composable(JmRoutes.DISCOVER) { entry ->
                val vm: JmOnlineSearchViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = factory,
                )
                val state by vm.state.collectAsStateWithLifecycle()
                JmOnlineSearchScreen(
                    state = state,
                    onKeywordChange = vm::onKeywordChange,
                    onSearch = vm::search,
                    onSortChange = vm::onSortChange,
                    onLoadMore = vm::loadMore,
                    onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                    onAddToLibrary = vm::addToLibrary,
                    onOpenHistory = vm::openHistory,
                    onDeleteHistory = vm::deleteHistory,
                    onClearHistory = vm::clearHistory,
                )
            }

            composable(JmRoutes.FAVORITES) { entry ->
                val vm: JmFavoritesViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = factory,
                )
                val state by vm.state.collectAsStateWithLifecycle()
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
                val name = entry.arguments?.getString("name").orEmpty()
                // 夹内复用书库布局（计划 §9 第 6 条）
                val vm: JmLibraryViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = factory,
                )
                androidx.compose.runtime.LaunchedEffect(favoriteId) {
                    vm.updateFilter { it.copy(favoriteId = favoriteId) }
                }
                val state by vm.uiState.collectAsStateWithLifecycle()
                Column(modifier = Modifier.fillMaxSize()) {
                    JmLibraryScreen(
                        state = state,
                        columns = 3,
                        onFilterChange = vm::updateFilter,
                        onClearFilter = { vm.updateFilter { it.copy(favoriteId = favoriteId) } },
                        onOpenAlbum = { navController.navigate(JmRoutes.detail(it)) },
                        onToggleCollected = vm::setCollected,
                    )
                }
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
