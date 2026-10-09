package com.haruhi.haruhibox.feature.jm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    /** 标题；在收藏夹内时为收藏夹名字（用户要求第 7 点：显示当前收藏夹名称）。 */
    title: String? = null,
    /** 是否显示「收藏」快捷筛选。收藏夹内不需要（用户要求第 7 点）。 */
    showCollectedChip: Boolean = true,
    /** 「继续阅读」卡片被点击（计划 §9 第 4 条）。 */
    onContinueReading: ((Long) -> Unit)? = null,
    /** 批量删除本地文件（图片 + zip/pdf，不动封面与记录）。 */
    onDeleteFiles: ((List<Long>) -> Unit)? = null,
    /** 批量从书库移除（记录 + 文件 + 成品 + 封面）。 */
    onRemoveFromLibrary: ((List<Long>) -> Unit)? = null,
    /**
     * 批量"修改收藏夹"：勾选集合就是这些漫画**最终所在的收藏夹**（可多选，
     * 空集合 = 移出所有收藏夹）。点面板里的「确认」才会调用。
     */
    onModifyFavorites: ((List<Long>, Set<Long>) -> Unit)? = null,
    /** 批量面板里"新建收藏夹"：成功返回新夹 id（自动勾上），失败返回 null。 */
    onCreateFavorite: (suspend (String) -> Long?)? = null,
    /** 批量操作条上"修改收藏夹"按钮的文案（收藏夹内改成"修改收藏夹"，书库页保持"收藏夹"）。 */
    moveFavoriteLabel: String = "收藏夹",
    /** 打开"修改收藏夹"面板时预先勾上的收藏夹（收藏夹内打开时把当前夹勾上）。 */
    preselectedFavoriteIds: Set<Long> = emptySet(),
) {
    // 多选状态是纯界面状态：长按进入、退出即清空，不需要活过页面重建
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var confirmDeleteFiles by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val selectedAlbums = state.albums.filter { it.album.id in selection }

    // 返回键先退出多选，而不是直接退出页面
    BackHandler(enabled = selecting) { selection = emptySet() }

    Column(modifier = modifier.fillMaxSize()) {
        if (selecting) {
            MultiSelectBar(
                selectedCount = selection.size,
                totalCount = state.albums.size,
                onSelectAll = { selection = state.albums.map { it.album.id }.toSet() },
                onClear = { selection = emptySet() },
                onDeleteFiles = { confirmDeleteFiles = true },
                onRemoveFromLibrary = { confirmRemove = true },
                onMoveToFavorite = { showMoveSheet = true },
                canDeleteFiles = onDeleteFiles != null,
                canRemove = onRemoveFromLibrary != null,
                canMove = onModifyFavorites != null,
                moveLabel = moveFavoriteLabel,
            )
        } else {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }

            // 有阅读记录时显示紧凑的"继续阅读"卡片（没有就不占地方）。
            // 只在书库首页显示：收藏夹里是"这一夹有什么"，塞一张别的本子会让人困惑。
            if (title == null) {
                state.continueReading?.let { card ->
                    ContinueReadingCardRow(
                        card = card,
                        onOpen = { onContinueReading?.invoke(card.albumId) ?: onOpenAlbum(card.albumId) },
                    )
                }
            }

            LibraryFilterBar(
                filter = state.filter,
                showCollectedChip = showCollectedChip,
                onFilterChange = onFilterChange,
                onClearFilter = onClearFilter,
            )
        }

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
                        selecting = selecting,
                        selected = card.album.id in selection,
                        onOpen = {
                            // 多选模式下点击是"勾选/取消"，不再进详情
                            if (selecting) {
                                selection = selection.toggle(card.album.id)
                            } else {
                                onOpenAlbum(card.album.id)
                            }
                        },
                        onLongPress = { selection = selection + card.album.id },
                        onToggleCollected = { onToggleCollected(card.album.id, card.album.collected != true) },
                    )
                }
            }
        }
    }

    if (showMoveSheet) {
        FavoritePickerForSelection(
            favorites = state.favorites,
            initiallyChecked = preselectedFavoriteIds,
            onConfirm = { chosen ->
                onModifyFavorites?.invoke(selection.toList(), chosen)
                showMoveSheet = false
                selection = emptySet()
            },
            onCreate = onCreateFavorite,
            onDismiss = { showMoveSheet = false },
        )
    }

    if (confirmDeleteFiles) {
        ConfirmDialog(
            title = "删除这 ${selection.size} 本的本地文件？",
            text = "会删除已下载的图片、导出的 zip/pdf（约 ${formatBytes(selectedAlbums.sumOf { it.localSizeBytes })}）。" +
                "书库记录、收藏与封面都保留。",
            confirmText = "删除文件",
            onConfirm = {
                confirmDeleteFiles = false
                onDeleteFiles?.invoke(selection.toList())
                selection = emptySet()
            },
            onDismiss = { confirmDeleteFiles = false },
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "从书库移除这 ${selection.size} 本？",
            text = "会删除书库记录、章节清单、收藏关联、阅读位置，" +
                "以及本地图片与导出的 zip/pdf。最近浏览记录会保留（封面也还在）。此操作不可撤销。",
            confirmText = "移除并删除",
            onConfirm = {
                confirmRemove = false
                onRemoveFromLibrary?.invoke(selection.toList())
                selection = emptySet()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * 多选操作条。
 *
 * 放在原筛选栏的位置（不浮在列表上），所以不会挡住内容；
 * 左侧显示已选数量与"全选/取消"，右侧是批量操作（书库页三个：
 * 删除文件 / 移出书库 / 收藏夹；收藏夹内只留「修改收藏夹」）。
 */
@Composable
private fun MultiSelectBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDeleteFiles: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onMoveToFavorite: () -> Unit,
    canDeleteFiles: Boolean,
    canRemove: Boolean,
    canMove: Boolean,
    moveLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "退出多选")
            }
            Text(
                text = "已选 $selectedCount 本",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (selectedCount < totalCount) {
                TextButton(onClick = onSelectAll) { Text("全选") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canDeleteFiles) {
                TextButton(onClick = onDeleteFiles) { Text("删除文件") }
            }
            if (canRemove) {
                TextButton(onClick = onRemoveFromLibrary) { Text("移出书库") }
            }
            if (canMove) {
                TextButton(onClick = onMoveToFavorite) { Text(moveLabel) }
            }
        }
    }
}

/**
 * 批量"修改收藏夹"面板：勾选目标收藏夹，点「确认」才执行（用户要求第 3 点）。
 *
 * 语义：
 * - 勾选集合 = 这些漫画**最终所在的收藏夹**，支持多选（"移动到多个收藏夹"）；
 * - 一个都没勾就点「确认」= **移出所有收藏夹**；
 * - 列表里没有想要的可以就地新建，新建成功自动勾上（新建本身立即生效，
 *   移动仍然等「确认」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritePickerForSelection(
    favorites: List<JmFavoriteEntity>,
    initiallyChecked: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onCreate: (suspend (String) -> Long?)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var checked by remember { mutableStateOf(initiallyChecked) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val createFavorite = onCreate

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "修改收藏夹",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "勾选这些漫画要保留的收藏夹（可多选）；一个都不勾选点「确认」= 移出所有收藏夹。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(favorites, key = { it.id }) { favorite ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checked = checked.toggle(favorite.id) }
                            .padding(end = 16.dp),
                    ) {
                        Checkbox(
                            checked = favorite.id in checked,
                            onCheckedChange = { checked = checked.toggle(favorite.id) },
                        )
                        Text(favorite.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                if (createFavorite != null) {
                    TextButton(onClick = { showCreate = true }) { Text("新建收藏夹") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(checked) }) { Text("确认") }
            }
        }
    }

    if (showCreate && createFavorite != null) {
        NameInputDialog(
            title = "新建收藏夹",
            initial = "",
            onConfirm = { name ->
                showCreate = false
                scope.launch {
                    // 建出来的夹直接勾上，用户接着点「确认」才会真正移动
                    createFavorite(name)?.let { id -> checked = checked + id }
                }
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** 书库顶部的"继续阅读"卡片：一行封面 + 标题 + 上次读到哪一话。 */
@Composable
private fun ContinueReadingCardRow(
    card: ContinueReadingCard,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
        ) {
            JmCover(
                albumId = card.albumId,
                contentDescription = card.albumTitle,
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.albumTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 最近浏览页（用户要求第 3 点）。
 *
 * 只按浏览时间倒序展示、点击进详情、可单条删除或整体清空；
 * **不显示下载章节数**——这张表的用途是"回到刚看过的本子"，
 * 为 60 条记录逐条查章节统计会明显拖慢页面。
 */
@Composable
fun JmRecentScreen(
    state: RecentUiState,
    onOpenAlbum: (Long) -> Unit,
    onClearAll: () -> Unit,
    /** 长按单条"从最近浏览移除"（用户第九轮要求第 3 点：删记录时按书库情况处理封面）。 */
    onRemoveAlbum: (Long) -> Unit,
    columns: Int = 3,
    modifier: Modifier = Modifier,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("最近浏览", style = MaterialTheme.typography.titleMedium)
            if (state.albums.isNotEmpty()) {
                TextButton(onClick = { showClearDialog = true }) { Text("清空") }
            }
        }

        when {
            state.loading -> LoadingBox()
            state.error != null -> EmptyBox("读取最近浏览失败", state.error)
            state.albums.isEmpty() -> EmptyBox(
                title = "还没有浏览记录",
                hint = "点开任意漫画详情后会出现在这里",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.albums, key = { it.album.id }) { card ->
                    AlbumGridCard(
                        card = card,
                        // 最近浏览页不做多选（它的用途是"回到刚看过的本子"）
                        selecting = false,
                        selected = false,
                        onOpen = { onOpenAlbum(card.album.id) },
                        // 长按只处理这一条，不进多选
                        onLongPress = { pendingRemove = card.album.id },
                        // 最近浏览里没有收藏语义，不显示星标
                        onToggleCollected = null,
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清空最近浏览？",
            text = "只清空浏览记录，书库、收藏夹与本地文件都不受影响；" +
                "不在书库里的那几本，封面缓存会一起清掉（下次显示重新联网取）。",
            confirmText = "清空",
            onConfirm = { showClearDialog = false; onClearAll() },
            onDismiss = { showClearDialog = false },
        )
    }

    pendingRemove?.let { albumId ->
        ConfirmDialog(
            title = "从最近浏览移除？",
            text = "只移除这条浏览记录；书库、收藏夹与本地文件都不受影响。" +
                "若这本已经不在书库，它的封面缓存也会一起清掉。",
            confirmText = "移除",
            onConfirm = {
                pendingRemove = null
                onRemoveAlbum(albumId)
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterBar(
    filter: LibraryFilter,
    showCollectedChip: Boolean,
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

        // 条件会换行（窄屏上"已下载/未下载/收藏/有 ZIP/有 PDF"一行放不下），
        // 所以用 FlowRow：全部条件都看得见，不需要横向滚才知道还有筛选
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
            // 收藏夹内不需要这个按钮（用户要求第 7 点：夹内只留已下载/未下载）
            if (showCollectedChip) {
                FilterChip(
                    selected = filter.collectedOnly == true,
                    onClick = {
                        onFilterChange {
                            it.copy(collectedOnly = if (it.collectedOnly == true) null else true)
                        }
                    },
                    label = { Text("收藏") },
                )
            }
            // 按"系统下载目录里有没有这本的成品"筛选（用户第九轮要求第 1 点）。
            // 两个条件互相独立，都选上就是"两种都导出过"。
            FilterChip(
                selected = filter.hasZipOnly,
                onClick = { onFilterChange { it.copy(hasZipOnly = !it.hasZipOnly) } },
                label = { Text("有 ZIP") },
            )
            FilterChip(
                selected = filter.hasPdfOnly,
                onClick = { onFilterChange { it.copy(hasPdfOnly = !it.hasPdfOnly) } },
                label = { Text("有 PDF") },
            )
            // 生效条件数量 + 清除（计划 §9.1「筛选生效后显示条件标签或数量，支持清除」）。
            // 只数**用户自己加的**条件：收藏夹内的 favoriteId 是页面上下文，
            // 算进去会一直显示"清除 1 项"（用户反馈）。
            if (filter.hasUserFilter) {
                TextButton(onClick = onClearFilter) {
                    Text("清除 ${filter.userActiveCount} 项")
                }
            }
        }
    }
}

/**
 * 书库/收藏夹里的一张卡片。
 *
 * 交互：单击进详情；**长按进入多选**（用户要求第 1、2 点）。多选模式下单击变成勾选，
 * 卡片右上角显示勾选标记、并整体加一层淡色底，一眼能看出"现在在选择"。
 *
 * 勾选效果必须是**一眼可见**的（用户反馈第 2 点："看不清哪些被勾选了"），所以三处叠加：
 * 1. 卡片加一圈主题色描边；
 * 2. 封面盖一层半透明主题色；
 * 3. 右上角的圆形标记：选中是实心圆 + 白色对勾，未选中是深色半透明空圈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridCard(
    card: AlbumCard,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleCollected: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            JmCover(
                albumId = card.album.id,
                contentDescription = card.displayName,
                modifier = Modifier.fillMaxSize(),
            )
            if (selecting) {
                if (selected) {
                    // 封面上盖一层主题色，和未选中的卡片拉开肉眼可见的差距
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Black.copy(alpha = 0.35f)
                            },
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else if (card.album.collected == true && onToggleCollected != null) {
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
            // 状态优先展示下载章节数，避免堆叠徽标（计划 §9 第 4 条）。
            // 最近浏览页没有章节统计，此时不显示这一行，避免出现误导性的"未获取章节"。
            if (card.totalChapters > 0 || card.downloadedChapters > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = downloadSummary(card),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * JM 封面。
 *
 * 用户要求第 8 点（封面本地缓存）由 Coil 的**磁盘缓存**实现：
 * `AppContainer.imageLoader` 把缓存目录指到 `files/covers`，
 * Coil 的默认策略就是"先查磁盘缓存，没有再请求网络"。
 *
 * 走应用级 ImageLoader 的关键原因是网络层：JM 的封面域名对 UA 有校验，
 * 必须复用带正确 UA / 代理的 OkHttp。
 */
@Composable
fun JmCover(
    albumId: Long,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val imageLoader = currentImageLoader()
    val model = remember(albumId) { JmDefaults.buildCoverUrl(albumId) }
    // imageLoader 为 null（Preview / 测试未安装）时交给 Coil 的单例加载器
    if (imageLoader != null) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            imageLoader = imageLoader,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
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
    onRevealMore: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onAddToLibrary: (OnlineSearchItem) -> Unit,
    onOpenHistory: (JmSearchHistoryEntity) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onConsumeMessage: () -> Unit = {},
) {
    // 提示语走根部 Scaffold 的 Snackbar（本页自己没有宿主，之前写着 showSnackbar 却没人显示）
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)

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
                val listState = rememberLazyListState()
                // key 必须带上 `result.items` 本身：加入书库后 item 的 `existsLocal` 会变，
                // 而"已显示条数"和"总条数"都不变——只按这两个 key 缓存的话，
                // 拿到的永远是加库之前那一份列表，表现为"点了加入书库，按钮不变，
                // 切走再回来才对"（用户反馈第 5 点）。
                val visibleItems = remember(state.visibleCount, result.items) {
                    result.items.take(state.visibleCount)
                }

                // 用户要求第 5 点：**最后一条进入可视范围就继续追加**，
                // 而不是等用户滑到底再点按钮。这样列表是"连续滚不完"的观感，
                // 同时首屏只渲染 10 条、不会一次性发起几十个封面请求。
                //
                // 两个坑，都已踩过：
                // 1. 不要用 `derivedStateOf { listState.layoutInfo... }`：读 layoutInfo 拿到的
                //    是**过期快照**，只有别的重组顺带读到它时才重算，表现为"必须先滑回顶部
                //    再滑到底才加载"；
                // 2. `LaunchedEffect` 的 key **不能包含当前批次条数**：每次追加都会取消并
                //    重启这个 effect，正在进行的滚动检测被打断，于是永远停在某一批。
                //    所以 key 只放"列表身份"，判定条件通过 rememberUpdatedState 读实时值，
                //    判定本身用 [OnlineSearchUiState.shouldRevealMore]（有单测）。
                val currentState by rememberUpdatedState(state)
                LaunchedEffect(result.query, result.page, result.fromHistory) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .distinctUntilChanged()
                        .collect { lastVisibleIndex ->
                            if (currentState.shouldRevealMore(lastVisibleIndex)) onRevealMore()
                        }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            text = buildString {
                                if (result.fromHistory) {
                                    append("搜索历史快照 · 共 ${result.total} 条")
                                } else {
                                    append("共 ${result.total} 条 · 第 ${result.page}/${result.totalPages} 页")
                                }
                                // 让用户知道下面还有多少没展开
                                if (state.remainingCount > 0) {
                                    append(" · 已显示 ${visibleItems.size}/${result.items.size}")
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(visibleItems, key = { it.id }) { item ->
                        OnlineResultRow(
                            item = item,
                            onOpen = { onOpenAlbum(item.id) },
                            onAddToLibrary = { onAddToLibrary(item) },
                        )
                    }
                    when {
                        state.loadingMore -> item {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        // 本页还有没展示完的：继续下滑会自动追加
                        state.remainingCount > 0 -> item {
                            Text(
                                text = "继续下滑显示更多（还有 ${state.remainingCount} 条）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            )
                        }

                        // 本页全部展示完，才出现"加载下一页"（用户要求第 5 点）
                        !result.fromHistory && result.page < result.totalPages -> item {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加载下一页（第 ${result.page + 1} 页）") }
                        }

                        else -> item {
                            Text(
                                text = if (result.fromHistory) {
                                    "历史快照已全部显示"
                                } else {
                                    "已显示全部结果"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            )
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
                    // 用户输入纯数字时由 /album 详情包装出来的那一条，标出来与普通搜索区分
                    if (item.isExactIdMatch) {
                        Text(
                            text = "精确匹配",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
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

/**
 * 收藏夹的封面：夹内**最近加入**的那本漫画。
 *
 * 空夹在位图位置给一个同等大小的占位方块（带一个淡淡的星形），
 * 保证列表每一行高度一致——如果空夹不占位，列表会因为有没有封面而跳动。
 */
@Composable
private fun FavoriteCover(row: FavoritesUiState.FavoriteRow) {
    val shape = RoundedCornerShape(8.dp)
    val size = Modifier.size(width = 52.dp, height = 72.dp)
    val albumId = row.latestAlbumId
    if (albumId != null) {
        JmCover(
            albumId = albumId,
            contentDescription = row.name,
            modifier = size.clip(shape).background(MaterialTheme.colorScheme.surface),
        )
    } else {
        Box(
            modifier = size
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "☆",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 收藏页。
 *
 * 每个收藏夹左侧是**夹内最近加入的那本漫画的封面**（用户要求第 1 点）：
 * 走的还是 `JmCover` 那一条路——应用级 Coil 加载器 + `files/covers` 磁盘缓存，
 * 所以看过的封面优先读本地、没有才联网，且下次不再请求。
 * 空夹在同一个位置给一个占位方块，保证列表高度一致、不会因为有没有封面而跳动。
 *
 * 排序：右上角「排序」按钮打开一个三选一菜单（创建时间 / 漫画数量 / 最近加入，
 * 都是降序，默认创建时间）。**默认收藏夹永远置顶**，不参与排序，
 * 这样用户换排序时"默认夹在哪"这件事永远不用重新找。
 */
@Composable
fun JmFavoritesScreen(
    state: FavoritesUiState,
    onOpenFavorite: (Long, String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSortChange: (FavoriteSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FavoritesUiState.FavoriteRow?>(null) }
    // 删除要二次确认，并在确认框里写清影响（计划 §9 第 6 条）
    var deleteTarget by remember { mutableStateOf<FavoritesUiState.FavoriteRow?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }

    // 排序在展示层做：数据库只负责"有什么"，顺序是纯粹的界面选择
    val sorted = remember(state.favorites, state.sort) { sortFavorites(state.favorites, state.sort) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortExpanded = true }) {
                        Text("排序：${state.sort.label}")
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "选择排序方式",
                        )
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        FavoriteSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (option == state.sort) "✓ ${option.label}" else option.label,
                                        color = if (option == state.sort) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    sortExpanded = false
                                    onSortChange(option)
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { showCreate = true }) { Text("新建") }
            }
        }

        when {
            state.loading -> LoadingBox()
            state.favorites.isEmpty() -> EmptyBox("还没有收藏夹", "点「新建」创建一个")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sorted, key = { it.id }) { row ->
                    Card(
                        onClick = { onOpenFavorite(row.id, row.name) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FavoriteCover(row)
                            Spacer(Modifier.width(12.dp))
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
                                TextButton(onClick = { deleteTarget = row }) { Text("删除") }
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

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除「${target.name}」？",
            text = "只会删除这个收藏夹和里面的归类（当前 ${target.albumCount} 本）。" +
                "漫画仍然留在书库，已下载的图片也不会被删掉。",
            confirmText = "删除收藏夹",
            onConfirm = { onDelete(target.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
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

/**
 * 一次性提示的统一出口：**先把消息消费掉，再展示**。
 *
 * 之前的写法是"showSnackbar 挂起几秒结束后才消费"，于是用户在提示语还在时切走页面，
 * 组合被销毁、协程被取消，消费那一行永远执行不到；再切回来时
 * `LaunchedEffect(message)` 又以同一条消息重启，提示语被重放一次（用户反馈的 bug）。
 *
 * 先消费、后展示，状态里立刻回到 null：无论用户什么时候切页都不会再弹第二遍。
 * 展示交给调用方（根部用 `rememberCoroutineScope` 弹 Snackbar），
 * 这样切页时提示不会被一起取消。
 */
@Composable
fun MessageEffect(
    message: String?,
    onConsume: () -> Unit,
    onShow: (String) -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            onConsume()
            onShow(message)
        }
    }
}

/**
 * 页面重新回到前台时执行一次（导航返回也算）。
 *
 * 为什么需要它：书库的"已下载 x/y 话"、详情页的章节状态都只反映**进入页面那一刻**的磁盘情况。
 * 下载完成后回到这些页面，数字必须跟着变，否则用户会以为没下成功。
 * 用 `LocalLifecycleOwner`（在导航目标里就是 NavBackStackEntry）的 ON_RESUME，
 * 而不是 `LaunchedEffect(Unit)`——后者只在首次进入时触发一次，返回时不会重来。
 */
@Composable
fun OnResumeEffect(key: Any? = Unit, block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    DisposableEffect(lifecycleOwner, key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentBlock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 时间戳 → 用户能看懂的短格式。 */
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
