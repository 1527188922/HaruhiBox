package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * JM 漫画模式的四个页面：书库 / 发现（在线搜索）/ 收藏 / 漫画详情。
 *
 * 交互规范见计划 §9 第 4～7 条：
 * - 书库：**状态优先展示下载章节数**，不堆叠徽标；标题最多两行；
 * - 发现：搜索框明确写"搜索在线漫画"，空态显示搜索历史；
 * - 详情：本地优先展示章节、在线刷新增强、主按钮按状态变化。
 *
 * 图标只用 `material-icons-core`（随 material3 传递引入）里有的常用集，
 * 需要别的图标时自己画 `ImageVector` —— **不要引 `material-icons-extended`**
 * （几千个图标，debug 包会全量保留，见 `docs/BUILD.md`）。
 */

// ======================================================================
// 书库
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmLibraryScreen(
    state: LibraryUiState,
    columns: Int,
    onFilterChange: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onClearFilter: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onToggleCollected: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LibraryFilterBar(
            filter = state.filter,
            onFilterChange = onFilterChange,
            onClearFilter = onClearFilter,
        )

        when {
            state.loading -> LoadingBox()
            state.albums.isEmpty() -> EmptyBox(
                title = if (state.filter.isActive) "没有符合条件的漫画" else "书库还是空的",
                hint = if (state.filter.isActive) {
                    "试着放宽筛选条件"
                } else {
                    "去「发现」搜索并加入书库"
                },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = rememberLazyGridState(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.albums, key = { it.album.id }) { card ->
                    AlbumGridCard(
                        card = card,
                        onOpen = { onOpenAlbum(card.album.id) },
                        onToggleCollected = { onToggleCollected(card.album.id, card.album.collected != true) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterBar(
    filter: LibraryFilter,
    onFilterChange: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = filter.keyword,
            onValueChange = { value -> onFilterChange { it.copy(keyword = value) } },
            singleLine = true,
            placeholder = { Text("搜索本地书库") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.keyword.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange { it.copy(keyword = "") } }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空关键字")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            FilterChip(
                selected = filter.downloadedOnly == true,
                onClick = {
                    onFilterChange {
                        it.copy(downloadedOnly = if (it.downloadedOnly == true) null else true)
                    }
                },
                label = { Text("已下载") },
            )
            FilterChip(
                selected = filter.downloadedOnly == false,
                onClick = {
                    onFilterChange {
                        it.copy(downloadedOnly = if (it.downloadedOnly == false) null else false)
                    }
                },
                label = { Text("未下载") },
            )
            FilterChip(
                selected = filter.collectedOnly == true,
                onClick = {
                    onFilterChange {
                        it.copy(collectedOnly = if (it.collectedOnly == true) null else true)
                    }
                },
                label = { Text("收藏") },
            )
            // 生效条件数量 + 清除（计划 §9.1「筛选生效后显示条件标签或数量，支持清除」）
            if (filter.isActive) {
                TextButton(onClick = onClearFilter) {
                    Text("清除 ${filter.activeCount} 项")
                }
            }
        }
    }
}

@Composable
private fun AlbumGridCard(
    card: AlbumCard,
    onOpen: () -> Unit,
    onToggleCollected: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AsyncImage(
                model = card.album.id.let { "https://${com.haruhi.haruhibox.core.jm.JmDefaults.IMAGE_DOMAIN}/media/albums/$it.jpg" },
                contentDescription = card.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (card.album.collected == true) {
                IconButton(onClick = onToggleCollected, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("★", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 状态优先展示下载章节数，避免堆叠徽标（计划 §9 第 4 条）
            Text(
                text = downloadSummary(card),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 书库卡片的状态文案。
 *
 * 计划 §9 第 4 条：**状态优先展示下载章节数**，避免堆叠徽标。
 * 公开（而不是 private）是因为它有独立的单元测试
 * （`JmDisplayLogicTest`），而这些文案是用户直接看到的内容。
 */
fun downloadSummary(card: AlbumCard): String = when {
    card.totalChapters == 0 -> "未获取章节"
    card.downloadedChapters == 0 -> "未下载 · 共 ${card.totalChapters} 话"
    card.downloadedChapters >= card.totalChapters -> "已下载全 ${card.totalChapters} 话"
    else -> "已下载 ${card.downloadedChapters}/${card.totalChapters} 话"
}

// ======================================================================
// 发现（在线搜索）
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmOnlineSearchScreen(
    state: OnlineSearchUiState,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSortChange: (JmSearchSort) -> Unit,
    onLoadMore: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onAddToLibrary: (OnlineSearchItem) -> Unit,
    onOpenHistory: (JmSearchHistoryEntity) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeywordChange,
            singleLine = true,
            // 明确区别于"搜索本地书库"（计划 §9 第 5 条）
            placeholder = { Text("搜索在线漫画") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            JmSearchSort.entries.forEach { sort ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { onSortChange(sort) },
                    label = { Text(sort.label) },
                )
            }
        }

        if (state.error != null) {
            ErrorBanner(message = state.error, onRetry = onSearch)
        }

        val result = state.result
        when {
            state.searching -> LoadingBox()
            result == null -> SearchHistoryList(
                history = state.history,
                historyEnabled = state.historyEnabled,
                onOpen = onOpenHistory,
                onDelete = onDeleteHistory,
                onClearAll = onClearHistory,
            )
            result.items.isEmpty() -> EmptyBox(
                title = "没有搜到结果",
                hint = "换个关键字试试",
            )
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            text = if (result.fromHistory) {
                                "搜索历史快照 · 共 ${result.total} 条"
                            } else {
                                "共 ${result.total} 条 · 第 ${result.page}/${result.totalPages} 页"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(result.items, key = { it.id }) { item ->
                        OnlineResultRow(
                            item = item,
                            onOpen = { onOpenAlbum(item.id) },
                            onAddToLibrary = { onAddToLibrary(item) },
                        )
                    }
                    if (state.loadingMore) {
                        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    } else if (result.page < result.totalPages && !result.fromHistory) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加载下一页") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<JmSearchHistoryEntity>,
    historyEnabled: Boolean,
    onOpen: (JmSearchHistoryEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
) {
    if (!historyEnabled) {
        // 计划 §9.3：关闭保存后"已有历史保留"，所以这里区分"关闭"与"没有历史"
        EmptyBox(title = "搜索历史已关闭", hint = "可在漫画设置里重新开启")
        return
    }
    if (history.isEmpty()) {
        EmptyBox(title = "搜点什么吧", hint = "输入关键字搜索在线漫画")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClearAll) { Text("清空") }
            }
        }
        items(history, key = { it.id }) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onOpen(row) },
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = row.keyword,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${JmSearchSort.fromCode(row.sort).label} · ${row.resultCount} 条 · " +
                                formatTime(row.updateTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onDelete(row.id) }) {
                    Icon(Icons.Default.Clear, contentDescription = "删除这条历史")
                }
            }
        }
    }
}

@Composable
private fun OnlineResultRow(
    item: OnlineSearchItem,
    onOpen: () -> Unit,
    onAddToLibrary: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.orEmpty().ifBlank { "JM${item.id}" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "JM${item.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.existsLocal) {
                        Text(
                            text = "已入库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        TextButton(onClick = onAddToLibrary) { Text("加入书库") }
                    }
                }
            }
        }
    }
}

// ======================================================================
// 收藏
// ======================================================================

@Composable
fun JmFavoritesScreen(
    state: FavoritesUiState,
    onOpenFavorite: (Long, String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FavoritesUiState.FavoriteRow?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showCreate = true }) { Text("新建") }
        }

        when {
            state.loading -> LoadingBox()
            state.favorites.isEmpty() -> EmptyBox("还没有收藏夹", "点「新建」创建一个")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.favorites, key = { it.id }) { row ->
                    Card(
                        onClick = { onOpenFavorite(row.id, row.name) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (row.isDefault) {
                                        "${row.albumCount} 本 · 默认收藏夹不可删除或重命名"
                                    } else {
                                        "${row.albumCount} 本"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!row.isDefault) {
                                TextButton(onClick = { renameTarget = row }) { Text("改名") }
                                TextButton(onClick = { onDelete(row.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameInputDialog(
            title = "新建收藏夹",
            initial = "",
            onConfirm = { name ->
                onCreate(name)
                showCreate = false
                newName = ""
            },
            onDismiss = { showCreate = false; newName = "" },
        )
    }
    renameTarget?.let { target ->
        NameInputDialog(
            title = "重命名收藏夹",
            initial = target.name,
            onConfirm = { name -> onRename(target.id, name); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

// ======================================================================
// 通用小组件
// ======================================================================

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyBox(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            hint?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 24.dp),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun formatTime(epochMillis: Long): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** 让 `LazyVerticalGrid` 在滚动到底部时触发回调（"连续加载"）。 */
@Composable
fun LoadMoreOnScroll(
    shouldLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
}

/** 供页面观察"是否需要加载下一页"的小工具。 */
fun requireAlbum(albums: List<JmAlbumEntity>, id: Long): JmAlbumEntity? = albums.firstOrNull { it.id == id }
