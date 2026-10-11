package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.storage.FileNaming
import com.haruhi.haruhibox.feature.jm.data.ExportedFormat
import com.haruhi.haruhibox.feature.jm.data.JmExportFormat
import com.haruhi.haruhibox.ui.theme.WarningOrangeDark
import com.haruhi.haruhibox.ui.theme.WarningOrangeLight

/** 简介收起时显示的行数。 */
private const val DESCRIPTION_COLLAPSED_LINES = 3

/**
 * 眼睛图标（观看数）。
 *
 * 工程**不引 `material-icons-extended`**（见 `app/build.gradle.kts`），
 * 而 `material-icons-core` 里没有眼睛，所以按 core 里那些图标的同一种写法自己画一个：
 * 外圈是上下两段三次贝塞尔围成的眼型，中间用**奇偶填充**挖出虹膜、
 * 再填一颗实心瞳孔——同一条 path 里三层嵌套正好是"实 → 空 → 实"。
 *
 * 尺寸按 24×24 视口画；实际显示多大由调用点的 `Icon(modifier = …size(…))` 决定，
 * 颜色走 `Icon` 的 tint（这里填什么色都会被覆盖）。
 */
private val EyeIcon: ImageVector = ImageVector.Builder(
    name = "Eye",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // 眼型：上眼皮 (2,12) → 顶点 (12,6.8) → (22,12)，下眼皮对称回来
        moveTo(2f, 12f)
        curveTo(7.33f, 5.07f, 16.67f, 5.07f, 22f, 12f)
        curveTo(16.67f, 18.93f, 7.33f, 18.93f, 2f, 12f)
        close()
        // 虹膜（挖空）：半径 4.5 的圆，用四段贝塞尔近似
        moveTo(12f, 7.5f)
        curveTo(14.49f, 7.5f, 16.5f, 9.51f, 16.5f, 12f)
        curveTo(16.5f, 14.49f, 14.49f, 16.5f, 12f, 16.5f)
        curveTo(9.51f, 16.5f, 7.5f, 14.49f, 7.5f, 12f)
        curveTo(7.5f, 9.51f, 9.51f, 7.5f, 12f, 7.5f)
        close()
        // 瞳孔（再填实）：半径 2.5 的圆
        moveTo(12f, 9.5f)
        curveTo(13.38f, 9.5f, 14.5f, 10.62f, 14.5f, 12f)
        curveTo(14.5f, 13.38f, 13.38f, 14.5f, 12f, 14.5f)
        curveTo(10.62f, 14.5f, 9.5f, 13.38f, 9.5f, 12f)
        curveTo(9.5f, 10.62f, 10.62f, 9.5f, 12f, 9.5f)
        close()
    }
}.build()

/**
 * 漫画详情页（核心中转页，计划 §9 第 7 条）。
 *
 * 关键交互：
 * - **本地优先**：先展示本地（书库或最近浏览）的章节，联网刷新只作为增强；
 *   刷新失败不阻断本地阅读；
 * - **点详情不等于加入书库**（用户要求第 2 点）：未入库时显示「加入书库」按钮；
 * - 封面区作者下面显示**点赞 / 观看数**（爱心 / 眼睛图标，`likes` / `total_views`），
 *   本地占用体积归到章节区标题（"章节 · 0/1 已下载（7.4 MB）"），同一屏里不重复说两遍；
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
    onConsumeMessage: () -> Unit,
    /** 点「导出 ZIP / PDF」：上层先做缺图预检，再决定弹缺图框还是密码框。 */
    onExport: (JmExportFormat) -> Unit,
    /** 缺图对话框里点「先补下载」。 */
    onDownloadMissing: () -> Unit,
    onDismissExportPrecheck: () -> Unit,
    /** 密码框：确认（留空等同不设密码）/ 不设密码 / 取消。 */
    onConfirmExport: (String) -> Unit,
    onExportWithoutPassword: () -> Unit,
    onCancelExport: () -> Unit,
    /** 导出成品的「打开至 / 发送至」：交给上层发系统 Intent（用户第九轮要求第 2 点）。 */
    onOpenOutput: (String) -> Unit,
    onSendOutput: (String) -> Unit,
    /** 导出成品的「删除」：删系统下载目录里的那个文件。 */
    onDeleteExport: (String) -> Unit,
    /** 「打开文件夹」：让系统文件管理器定位到成品目录（用户反馈第 2 点）。 */
    onOpenFolder: () -> Unit,
    /**
     * 点作者：跳到发现页，把作者名填进在线搜索框（**不自动搜**，由用户自己点搜索按钮）。
     */
    onSearchAuthor: (String) -> Unit = {},
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
    // 待删除的导出成品文件名（null = 没有待确认的删除）
    var pendingDeleteExport by remember { mutableStateOf<String?>(null) }

    // 一次性提示统一走 MessageEffect：先消费、再交给根部弹（切页不会重放）
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)

    Scaffold(
        modifier = modifier,
        // **不要再套一层系统栏内边距**：本页在 `JmModeRoot` 的 Scaffold 内容里，
        // 外层已经把状态栏高度作为 padding 给了 NavHost。这里若再让 Scaffold 与
        // TopAppBar 各加一次 windowInsets，标题上方就会空出约两倍状态栏的高度
        // （用户反馈第 5 点）。所以内层全部声明成 0。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                            text = { Text("导出 ZIP") },
                            enabled = state.chapters.isNotEmpty(),
                            onClick = { menuExpanded = false; onExport(JmExportFormat.ZIP) },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 PDF") },
                            enabled = state.chapters.isNotEmpty(),
                            onClick = { menuExpanded = false; onExport(JmExportFormat.PDF) },
                        )
                        HorizontalDivider()
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
                        onSearchAuthor = onSearchAuthor,
                    )
                }
                if (state.refreshing) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                // 已经导出过的 zip/pdf：放在章节前面——它属于"这本在我设备上有什么"，
                // 与章节区标题里的占用体积是同一类信息，不该被长长的章节列表压到最下面
                // （用户第九轮要求第 2 点）。正在导出的也算（用户第十一轮要求第 3 点）。
                if (state.exports.isNotEmpty() || state.activeExports.isNotEmpty()) {
                    item {
                        ExportSection(
                            exports = state.exports,
                            active = state.activeExports,
                            canOpenFolder = state.canOpenFolder,
                            onOpen = onOpenOutput,
                            onSend = onSendOutput,
                            onDelete = { name -> pendingDeleteExport = name },
                            onOpenFolder = onOpenFolder,
                        )
                    }
                }
                // 离线/刷新失败只提示，不阻断本地阅读（计划 §9 第 7 条）
                state.refreshError?.let { error ->
                    item { ErrorBanner(message = "在线刷新失败：$error", onRetry = onRefresh) }
                }
                item {
                    ChapterSectionHeader(
                        total = state.totalChapters,
                        downloaded = state.downloadedChapters,
                        localSizeBytes = state.localSizeBytes,
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
            text = "会删除这本漫画在本机的全部图片（约 ${formatBytes(state.localSizeBytes)}）" +
                "以及已导出的 zip / PDF；书库记录与收藏会保留。",
            confirmText = "删除文件",
            onConfirm = { showDeleteFilesDialog = false; onDeleteAlbumFiles() },
            onDismiss = { showDeleteFilesDialog = false },
        )
    }
    if (showDeleteRecordDialog) {
        ConfirmDialog(
            title = "从书库移除？",
            text = "会删掉书库记录与章节清单，并连同本机图片、已导出的 zip / PDF 一起清除；" +
                "收藏夹里的关联也会取消。最近浏览记录会保留（封面也还在）。",
            confirmText = "移除",
            onConfirm = { showDeleteRecordDialog = false; onDeleteAlbumRecord() },
            onDismiss = { showDeleteRecordDialog = false },
        )
    }

    // 删导出成品是不可撤销的（重新生成一份 20MB 的 PDF 要花时间），所以先确认一次
    pendingDeleteExport?.let { name ->
        ConfirmDialog(
            title = "删除这个导出文件？",
            text = "会从下载目录里删掉「$name」。漫画本身、已下载的图片与其它成品都不受影响。",
            confirmText = "删除",
            onConfirm = {
                pendingDeleteExport = null
                onDeleteExport(name)
            },
            onDismiss = { pendingDeleteExport = null },
        )
    }

    // 缺图：先列明缺哪些话、缺多少张，提供补下载后再导出（计划 §9 第 7 条）
    state.exportPrecheck?.let { precheck ->
        // "没下过"的话（连图片清单都没有）和"下了一部分"的话要分开说：
        // 前者说不出缺几张，混在一起写"共缺 0 张"会让人以为不用补（用户反馈的 bug）
        val neverDownloaded = precheck.missingChapters.count { !it.hasImageList }
        val partlyDownloaded = precheck.missingChapters.size - neverDownloaded
        AlertDialog(
            onDismissRequest = onDismissExportPrecheck,
            title = { Text("还有图片没下载完") },
            text = {
                Column {
                    Text(
                        text = buildString {
                            if (partlyDownloaded > 0) {
                                append("有 $partlyDownloaded 话没下完（共缺 ${precheck.missingImages} 张）；")
                            }
                            if (neverDownloaded > 0) {
                                append("有 $neverDownloaded 话还没有下载过；")
                            }
                            append("导出前请先补下载，否则成品会缺页。")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(precheck.missingChapters, key = { it.title }) { row ->
                            Text(
                                text = if (row.hasImageList) {
                                    "${row.title}　缺 ${row.missing}/${row.total} 张"
                                } else {
                                    "${row.title}　未下载"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDownloadMissing) { Text("先补下载") }
            },
            dismissButton = {
                TextButton(onClick = onDismissExportPrecheck) { Text("取消") }
            },
        )
    }

    // 导出密码框：默认预填配置里的密码，支持显示/隐藏，保留 [确认] / [不设密码]
    state.exportFormat?.let { format ->
        ExportPasswordDialog(
            format = format,
            defaultPassword = state.exportDefaultPassword,
            targetExists = state.exportTargetExists,
            onConfirm = onConfirmExport,
            onWithoutPassword = onExportWithoutPassword,
            onDismiss = onCancelExport,
        )
    }
}

/**
 * 「导出文件」区块：这本漫画已经导出到系统下载目录的 zip / pdf（用户第九轮要求第 2 点）。
 *
 * 设计取舍：
 * - **默认折叠**（用户第十轮要求第 1 点）：这一块是"补充信息"，不是每次进详情页都要看的，
 *   展开着会白占掉半屏、把章节列表挤到下面；折叠后只留一行标题，
 *   标题右侧直接写明"ZIP · PDF"，不展开也知道有哪些成品；
 * - 点头部整行展开/收起（"能点的是一整行"，不用瞄准小箭头）；
 * - **导出中的格式也算在标题里**（用户第十一轮要求第 3 点）：刚点完导出，折叠状态下
 *   就能看到"正在导出 PDF"，不用展开也不用去任务页确认；
 * - 展开后**一个卡片装下所有成品**，每行一个格式：最多两行，
 *   行内三段式（格式徽标 → 文件名+大小+生成时间 → 三个操作），
 *   左到右正好是"是什么 → 什么状态 → 能对它做什么"，与任务页的成品行一致；
 * - 一个成品都没有、也没有在跑的任务时整块不显示（不留空壳）。
 */
@Composable
private fun ExportSection(
    exports: List<ExportedFormat>,
    active: List<ActiveExport>,
    canOpenFolder: Boolean,
    onOpen: (String) -> Unit,
    onSend: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenFolder: () -> Unit,
) {
    // 展开状态是纯界面状态：默认收起，退出页面即忘（下次进来还是收起）。
    // 但**正在导出时会自动展开一次**，让用户马上看到进度（用户第十一轮要求第 3 点）。
    var expanded by remember { mutableStateOf(false) }
    var autoExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(active.isNotEmpty()) {
        if (active.isNotEmpty() && !autoExpanded) {
            expanded = true
            autoExpanded = true
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 10.dp),
            ) {
                Text("导出文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                // 折叠状态下也能一眼看到有哪几种成品 / 哪几种正在导出
                Text(
                    text = buildString {
                        append(exports.joinToString(" · ") { it.format.label })
                        active.forEach { row ->
                            if (isNotEmpty()) append(" · ")
                            append(row.format.label)
                            append(" 导出中")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起导出文件" else "展开导出文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                exports.forEachIndexed { index, exported ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ExportedFileRow(
                        exported = exported,
                        onOpen = { onOpen(exported.file.displayName) },
                        onSend = { onSend(exported.file.displayName) },
                        onDelete = { onDelete(exported.file.displayName) },
                    )
                }
                active.forEach { row ->
                    if (exports.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ActiveExportRow(row)
                }
                // 「打开文件夹」（用户反馈第 2 点）：让系统文件管理器直接定位到成品目录。
                // 老系统上成品在应用私有目录里、别的应用看不到，这时整条不显示
                // （由 canOpenFolder 控制，见 ExportPublisher.folderUri 的注释）。
                if (canOpenFolder) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = onOpenFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开文件夹") }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 正在导出的那一行：格式徽标 + 状态文案 + 进度条。
 *
 * 没有操作按钮——这一份还没生成出来，能做的只有等（取消在任务页）。
 */
@Composable
private fun ActiveExportRow(row: ActiveExport) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = row.format.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.progress > 0f) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 成品的一行：格式徽标 + 文件名 + 大小/生成时间 + 打开至/发送至/删除。 */
@Composable
private fun ExportedFileRow(
    exported: ExportedFormat,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 格式徽标：一眼分清哪个是 zip 哪个是 pdf，不用去读扩展名
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = exported.format.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exported.file.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(formatBytes(exported.file.sizeBytes))
                    append(" · 生成于 ")
                    append(
                        if (exported.file.createdAt > 0) {
                            formatTime(exported.file.createdAt)
                        } else {
                            "时间未知"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onOpen) { Text("打开至") }
        TextButton(onClick = onSend) { Text("发送至") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

/**
 * 导出密码框（计划 §9 第 7 条）。
 *
 * - 默认**预填**该格式的密码（来自凭据存储，没设过就用参考实现的默认值）；
 * - 有显示/隐藏切换；
 * - 底部是 **[确认] / [不设密码]**，留空等同不加密；
 * - 同名成品已存在时，用橙色提醒"会被覆盖"（用户要求第 5 点）；
 * - 取消/返回不创建任务。
 */
@Composable
private fun ExportPasswordDialog(
    format: JmExportFormat,
    defaultPassword: String,
    targetExists: Boolean,
    onConfirm: (String) -> Unit,
    onWithoutPassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(format) { mutableStateOf(defaultPassword) }
    var visible by remember(format) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 ${format.label}") },
        text = {
            Column {
                Text(
                    text = "给导出的 ${format.label} 设置打开密码；" +
                        "选「不设密码」或留空则不加密，任何人都能打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targetExists) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "⚠ 下载目录里已经有同名文件，再次生成会直接覆盖它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSystemInDarkTheme()) WarningOrangeDark else WarningOrangeLight,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("打开密码") },
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("确认") } },
        dismissButton = {
            Row {
                TextButton(onClick = onWithoutPassword) { Text("不设密码") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 封面区的点赞 / 观看数：爱心图标 + 点赞数、眼睛图标 + 观看数。
 *
 * 两个字段都来自在线详情接口（`likes` / `total_views`），可能是 null 或空串
 * （还没联网刷新过、或接口没给这一项）。这时**整行不占位**——宁可不显示，
 * 也不要在封面旁边挂一对"♥ 0 👁 0"的假数字。
 */
@Composable
private fun AlbumStatRow(likes: String?, views: String?) {
    val likeText = formatStatCount(likes)
    val viewText = formatStatCount(views)
    if (likeText == null && viewText == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        likeText?.let { StatItem(icon = Icons.Default.Favorite, text = it, description = "点赞数") }
        viewText?.let { StatItem(icon = EyeIcon, text = it, description = "观看数") }
    }
}

/** 一项统计：小图标 + 数字（图标只起"这是哪种数字"的作用，读屏靠 [description]）。 */
@Composable
private fun StatItem(icon: ImageVector, text: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 点赞 / 观看数的展示文案。
 *
 * 空白（null、空串、只有空格）返回 null = 这一项不显示。数字本身原样展示：
 * 接口给的就是 `"12345"` 这样的字符串，自己折算成"1.2 万"反而会跟网页端对不上。
 */
fun formatStatCount(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

@Composable
private fun AlbumHeader(
    state: AlbumDetailUiState,
    onAddToLibrary: () -> Unit,
    onOpenFavoriteSheet: () -> Unit,
    onToggleDescription: () -> Unit,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    /** 点作者：跳发现页并把作者名填进搜索框（不自动搜，由用户自己点搜索按钮）。 */
    onSearchAuthor: (String) -> Unit = {},
) {
    val album = state.album ?: return
    val authorText = parseStringArray(album.author).joinToString(" / ")
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
                if (authorText.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    // 点作者 = 拿去在线搜索：切到发现页并把作者名填进搜索框（不自动搜）。
                    // 用主色把它标出来，让用户知道这行可以点。
                    Text(
                        text = authorText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onSearchAuthor(authorText) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 这里原来是"共 x 话 · 已下载 y 话 · 占用 z"，与下方章节区标题说的是同一件事、
                // 在同一屏里重复了一遍；现在占用体积并到章节区标题，
                // 这一行换成点赞 / 观看数（用户要求）。
                AlbumStatRow(likes = album.likes, views = album.totalViews)
                Spacer(Modifier.height(8.dp))
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

        // 三颗按钮**另起一行、占满整宽**（用户第十一轮要求第 2 点）。
        // 挤在封面右边的窄栏里时，"继续阅读 / 下载 / 已收藏"在 360dp 屏上放不下会掉行：
        // 主按钮吃掉剩余宽度（weight），两颗次要按钮自己定宽，标签再长也不会换行。
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            // 「继续阅读」按上次读到的位置续读；没读过才是「阅读」= 从第一话第一张开始。
            Button(
                onClick = onRead,
                enabled = state.chapters.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (state.progress?.isUsable == true) "继续阅读" else "阅读",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

        // 有进度时补一句"读到哪儿了"：按钮上的"继续阅读"只说了一半，
        // 用户想知道的是"继续到哪一话"（用户第十一轮要求第 1 点）。
        state.progressText?.let { text ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    localSizeBytes: Long,
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
            text = chapterSectionTitle(total, downloaded, localSizeBytes),
            style = MaterialTheme.typography.titleSmall,
        )
        // 下载入口只留头部那一个（用户要求第 3 点），这里只负责正序/倒序切换
        TextButton(onClick = onToggleReversed) {
            Text(if (reversed) "倒序" else "正序")
        }
    }
}

/**
 * 章节区标题，例如 `章节 · 0/1 已下载（7.4 MB）`。
 *
 * 本地占用体积原来写在封面区的元信息里（"共 x 话 · 已下载 y 话 · 占用 z"），
 * 那与这里的下载进度本来就是同一件事，在同一屏里说了两遍；现在统一收到这里
 * （用户要求）。本地没有文件时不加括号，不写"（0 B）"这种没有信息量的括号；
 * 章节清单还没拿到（total == 0）时不写"0/0 已下载"，只留"章节"，本地占用照说。
 */
fun chapterSectionTitle(total: Int, downloaded: Int, localSizeBytes: Long): String = buildString {
    append("章节")
    if (total > 0) append(" · $downloaded/$total 已下载")
    if (localSizeBytes > 0) append("（${formatBytes(localSizeBytes)}）")
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
                // 图标与菜单放在同一个 Box 里：DropdownMenu 是相对**它所在的父布局**定位的，
                // 直接放在 Row 里当兄弟节点时锚点会落到整行上，菜单看起来"飘"到了别处
                // （用户反馈第 6 点）。包一层 Box 之后锚点才是那颗按钮。
                Box {
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
