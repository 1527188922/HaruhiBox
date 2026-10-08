package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.storage.FileNaming

/** 简介收起时显示的行数。 */
private const val DESCRIPTION_COLLAPSED_LINES = 3

/**
 * 漫画详情页（核心中转页，计划 §9 第 7 条）。
 *
 * 关键交互：
 * - **本地优先**：先展示本地（书库或最近浏览）的章节，联网刷新只作为增强；
 *   刷新失败不阻断本地阅读；
 * - **点详情不等于加入书库**（用户要求第 2 点）：未入库时显示「加入书库」按钮；
 * - 章节行显示"已下载 / 部分下载 x/y / 未下载"；
 * - 「收藏夹」面板支持加入/移出/移动（用户要求第 6 点）；
 * - 更多菜单承接 ZIP/PDF 导出（P4 接）、删除文件、从书库移除。
 *
 * ## 下载入口只有一个（用户要求第 3 点）
 *
 * 头部只有一颗「下载」按钮，点开面板选章节；面板里**已经在下载队列中的章节**
 * 用次要颜色标出来并且点不动，避免重复提交。章节行与章节区标题上都不再放下拉/按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmAlbumDetailScreen(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleReversed: () -> Unit,
    onToggleDescription: () -> Unit,
    onAddToLibrary: () -> Unit,
    onMoveToFavorite: (Long) -> Unit,
    onAddToFavorite: (Long) -> Unit,
    onRemoveFromFavorite: (Long) -> Unit,
    onCreateFavoriteAndAdd: (String) -> Unit,
    /** 点章节行进阅读器（读到哪一话由点的那一行为准）。 */
    onReadChapter: (Long) -> Unit,
    /** 头部「阅读」按钮：**从第一章开始读**（用户要求第 4 点）。 */
    onReadFromStart: () -> Unit,
    /** 下载面板提交（选中的章节 id）。 */
    onDownloadChapters: (List<Long>) -> Unit,
    onDeleteChapterFiles: (Long) -> Unit,
    onDeleteAlbumFiles: () -> Unit,
    onDeleteAlbumRecord: () -> Unit,
    onOpenTaskPage: () -> Unit,
    onConsumeMessage: () -> Unit,
    /**
     * 提示文案的**上层**回调。
     *
     * 为什么不在这里自己弹 Snackbar：收藏夹是 `ModalBottomSheet`，
     * 会盖住宿主 `Scaffold` 的 Snackbar（用户反馈"提示语被弹框挡住看不见"）。
     * 把提示交给根部的 Scaffold 弹，才能浮在面板之上。
     */
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteFilesDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var showFavoriteSheet by remember { mutableStateOf(false) }
    // 面板的展开状态由本页自己管：它是纯界面状态，不需要活过页面重建
    var showDownloadSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            onMessage(it)
            onConsumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // 显式声明 windowInsets：edge-to-edge 下如果让它与 Scaffold 的
                // 内容内边距叠加，标题下面会出现一大块空白（用户反馈）。
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    Text(
                        text = state.album?.name?.takeIf { it.isNotBlank() }
                            ?: state.album?.id?.let { "JM$it" }
                            ?: "漫画详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新详情")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("导出 ZIP（P4 接入）") },
                            enabled = false,
                            onClick = { menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 PDF（P4 接入）") },
                            enabled = false,
                            onClick = { menuExpanded = false },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("下载管理") },
                            onClick = { menuExpanded = false; onOpenTaskPage() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除本地文件") },
                            onClick = { menuExpanded = false; showDeleteFilesDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("从书库移除") },
                            enabled = state.inLibrary,
                            onClick = { menuExpanded = false; showDeleteRecordDialog = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.album == null -> EmptyBox(
                title = "本地没有这个本子的数据",
                hint = state.refreshError ?: "联网刷新失败，请检查网络",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item {
                    AlbumHeader(
                        state = state,
                        onAddToLibrary = onAddToLibrary,
                        onOpenFavoriteSheet = { showFavoriteSheet = true },
                        onToggleDescription = onToggleDescription,
                        onRead = onReadFromStart,
                        onDownload = { showDownloadSheet = true },
                    )
                }
                if (state.refreshing) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                // 离线/刷新失败只提示，不阻断本地阅读（计划 §9 第 7 条）
                state.refreshError?.let { error ->
                    item { ErrorBanner(message = "在线刷新失败：$error", onRetry = onRefresh) }
                }
                item {
                    ChapterSectionHeader(
                        total = state.totalChapters,
                        downloaded = state.downloadedChapters,
                        reversed = state.reversed,
                        onToggleReversed = onToggleReversed,
                    )
                }
                if (state.chapters.isEmpty()) {
                    item {
                        EmptyBox(
                            title = "还没有章节数据",
                            hint = "点右上角刷新试试",
                            modifier = Modifier.height(160.dp),
                        )
                    }
                } else {
                    items(state.chapters, key = { it.chapterId }) { chapter ->
                        ChapterRowItem(
                            chapter = chapter,
                            onOpen = { onReadChapter(chapter.chapterId) },
                            onDeleteFiles = { onDeleteChapterFiles(chapter.chapterId) },
                        )
                    }
                }
            }
        }
    }

    if (showDownloadSheet) {
        DownloadSheet(
            state = state,
            onDownload = { chapterIds ->
                showDownloadSheet = false
                onDownloadChapters(chapterIds)
            },
            onDismiss = { showDownloadSheet = false },
        )
    }

    if (showFavoriteSheet) {
        FavoritePickerSheet(
            allFavorites = state.allFavorites,
            currentFavoriteIds = state.favoriteIds,
            onDismiss = { showFavoriteSheet = false },
            onMoveTo = { onMoveToFavorite(it); showFavoriteSheet = false },
            onAdd = { onAddToFavorite(it) },
            onRemove = { onRemoveFromFavorite(it) },
            onCreate = { onCreateFavoriteAndAdd(it) },
        )
    }
    if (showDeleteFilesDialog) {
        ConfirmDialog(
            title = "删除本地文件？",
            text = "会删除这本漫画在本机的全部图片（约 ${formatBytes(state.localSizeBytes)}），" +
                "书库记录与收藏会保留。",
            confirmText = "删除文件",
            onConfirm = { showDeleteFilesDialog = false; onDeleteAlbumFiles() },
            onDismiss = { showDeleteFilesDialog = false },
        )
    }
    if (showDeleteRecordDialog) {
        ConfirmDialog(
            title = "从书库移除？",
            text = "只会移除书库中的这条记录与章节清单；本地已下载的图片与最近浏览记录都保留。",
            confirmText = "移除",
            onConfirm = { showDeleteRecordDialog = false; onDeleteAlbumRecord() },
            onDismiss = { showDeleteRecordDialog = false },
        )
    }
}

@Composable
private fun AlbumHeader(
    state: AlbumDetailUiState,
    onAddToLibrary: () -> Unit,
    onOpenFavoriteSheet: () -> Unit,
    onToggleDescription: () -> Unit,
    onRead: () -> Unit,
    onDownload: () -> Unit,
) {
    val album = state.album ?: return
    Column(modifier = Modifier.padding(12.dp)) {
        Row {
            JmCover(
                albumId = album.id,
                contentDescription = album.name,
                modifier = Modifier
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "JM${album.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                parseStringArray(album.author).takeIf { it.isNotEmpty() }?.let { authors ->
                    Text(
                        text = authors.joinToString(" / "),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("共 ${state.totalChapters} 话")
                        append(" · 已下载 ${state.downloadedChapters} 话")
                        if (state.localSizeBytes > 0) append(" · 占用 ${formatBytes(state.localSizeBytes)}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 「阅读」= 从第一章开始读（用户要求第 4 点）；
                    // 「下载」是**唯一**的下载入口，点开面板选章节（用户要求第 3 点）。
                    Button(onClick = onRead, enabled = state.chapters.isNotEmpty()) {
                        Text("阅读")
                    }
                    // 即使所有章节都已在队列里也保持可点：面板要能把"已在下载队列"
                    // 这个状态显示给用户，按钮变灰反而让人不知道为什么。
                    OutlinedButton(onClick = onDownload, enabled = state.chapters.isNotEmpty()) {
                        Text("下载")
                    }
                    // 「收藏」直接打开收藏夹面板选夹（用户要求第 5 点：不再单独放「收藏夹」按钮）
                    OutlinedButton(onClick = onOpenFavoriteSheet) {
                        Text(if (album.collected == true) "已收藏" else "收藏")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 用户要求第 2 点：**点详情不等于加入书库**，所以必须有显式入口
                    if (!state.inLibrary) {
                        TextButton(onClick = onAddToLibrary) { Text("加入书库") }
                    } else {
                        Text(
                            text = "已在书库",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (state.favoriteIds.isEmpty()) {
                            "未收藏"
                        } else {
                            "已加入 ${state.favoriteIds.size} 个收藏夹"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        parseStringArray(album.tags).takeIf { it.isNotEmpty() }?.let { tags ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        album.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(8.dp))
            // 只有真的被截断时才显示"展开简介"（用户反馈：内容本来就能全部显示时
            // 按钮是多余的）。判断方式是用 onTextLayout 看行数是否被 maxLines 限制。
            var descriptionOverflows by remember(description) { mutableStateOf(false) }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (state.descriptionExpanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    // hasVisualOverflow 表示文本被 maxLines 截断了
                    if (!state.descriptionExpanded) {
                        descriptionOverflows = result.hasVisualOverflow ||
                            result.lineCount > DESCRIPTION_COLLAPSED_LINES
                    }
                },
            )
            if (descriptionOverflows || state.descriptionExpanded) {
                TextButton(onClick = onToggleDescription) {
                    Text(if (state.descriptionExpanded) "收起简介" else "展开简介")
                }
            }
        }
    }
}

/**
 * 收藏夹面板（用户要求第 6 点：支持修改收藏夹）。
 *
 * - 点未加入的夹 -> 加入；点已加入的夹 -> 移出（支持一个本子属于多个夹）；
 * - 「移动到」把本子只保留在该夹里，对应"修改收藏夹"的直觉用法；
 * - 底部可直接新建夹并加入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritePickerSheet(
    allFavorites: List<JmFavoriteEntity>,
    currentFavoriteIds: List<Long>,
    onDismiss: () -> Unit,
    onMoveTo: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点一下加入，再点一下移出；「移动到」会让本子只保留在该收藏夹里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            allFavorites.forEach { favorite ->
                val joined = favorite.id in currentFavoriteIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (joined) onRemove(favorite.id) else onAdd(favorite.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (joined) "✓ ${favorite.name}" else favorite.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!joined) {
                        TextButton(onClick = { onMoveTo(favorite.id) }) { Text("移动到") }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            if (showCreate) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("新收藏夹名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showCreate = false; newName = "" }) { Text("取消") }
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank()) onCreate(newName.trim())
                            showCreate = false
                            newName = ""
                        },
                    ) { Text("创建并加入") }
                }
            } else {
                TextButton(onClick = { showCreate = true }) { Text("新建收藏夹") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChapterSectionHeader(
    total: Int,
    downloaded: Int,
    reversed: Boolean,
    onToggleReversed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (total == 0) "章节" else "章节 · $downloaded/$total 已下载",
            style = MaterialTheme.typography.titleSmall,
        )
        // 下载入口只留头部那一个（用户要求第 3 点），这里只负责正序/倒序切换
        TextButton(onClick = onToggleReversed) {
            Text(if (reversed) "倒序" else "正序")
        }
    }
}

/**
 * 章节行。
 *
 * 整行**只做一件事：进阅读器**。行尾菜单只留"删除本话文件"——
 * 下载入口统一收到头部那颗按钮里（用户要求第 3 点：同一个动作不要有四个入口）。
 */
@Composable
private fun ChapterRowItem(
    chapter: ChapterRow,
    onOpen: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapter.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        chapter.isDownloaded -> MaterialTheme.colorScheme.primary
                        chapter.isPartial -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (chapter.downloadedImages > 0) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "章节操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("删除本话文件") },
                        onClick = { menuExpanded = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除「${chapter.title}」的本地文件？",
            text = "只删除这一话已经下载的图片，章节清单、书库记录与收藏都保留。" +
                "之后可以重新下载。",
            confirmText = "删除文件",
            onConfirm = { confirmDelete = false; onDeleteFiles() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * 下载面板（计划 §9 第 7 条）。
 *
 * - **默认勾选未完成的章节**；支持"全选未下载"和逐话自选；
 * - 已经下完的章节默认不勾，避免用户重复提交；
 * - 提交后不强制跳任务页，只给短提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(
    state: AlbumDetailUiState,
    onDownload: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(state.chapters) {
        mutableStateOf(state.defaultDownloadSelection)
    }
    val queued = state.queuedChapterIds

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "下载章节",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "默认勾选还没下完的章节；已经在下载队列里的章节不能再选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { selected = state.defaultDownloadSelection }) {
                    Text("全选可下载")
                }
                TextButton(onClick = { selected = emptySet() }) { Text("全不选") }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(state.chapters, key = { it.chapterId }) { chapter ->
                    val inQueue = chapter.chapterId in queued
                    val checked = !inQueue && chapter.chapterId in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 已在队列里的章节**不可选**：重复提交只会得到"已有任务"的提示
                            .clickable(enabled = !inQueue) {
                                selected = if (checked) {
                                    selected - chapter.chapterId
                                } else {
                                    selected + chapter.chapterId
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !inQueue)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                // 队列中的章节用次要颜色区分（用户要求第 3 点）
                                color = if (inQueue) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (inQueue) "已在下载队列" else chapter.statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (inQueue) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onDownload(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selected.isEmpty()) "请选择章节" else "下载选中的 ${selected.size} 话")
                }
                OutlinedButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.trim().isNotEmpty(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把库里存的 JSON 字符串数组还原成列表；解析失败返回空列表（不抛）。 */
fun parseStringArray(json: String?): List<String> {
    if (json.isNullOrBlank() || json == "[]") return emptyList()
    return runCatching {
        com.haruhi.haruhibox.feature.jm.api.decodeStringList(json)
    }.getOrDefault(emptyList())
}

/** 人类可读的占用体积。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

/** 本子目录名（详情页展示用）。 */
fun albumFolderLabel(state: AlbumDetailUiState): String =
    state.album?.albumFolderName?.takeIf { it.isNotBlank() }
        ?: state.album?.let { FileNaming.albumFolderName(it.name, it.id.toString()) }
        ?: ""
