# 实施进度台账

> 计划原文：`docs/android-app-plan.md`（= `2026-10-05-android-jm-bilibili-app.md`）
> 最后更新：2026-10-09

## 目录结构决策

计划 §3.1 建议的是**逻辑模块划分**（`core/network`、`feature-jm`、`ui-jm`…），
§D.3 又明确「初期不必把每个逻辑目录都建成独立 Gradle 模块」。

本工程采用后者：**单个 `:app` Gradle 模块 + 按逻辑模块划分的包结构**。

```
app/src/main/java/com/haruhi/haruhibox/
  HaruhiBoxApp.kt            进程入口（日志、崩溃留存、DI 容器）
  MainActivity.kt            唯一 Activity
  core/
    jm/                      JM 协议：签名、解密、块数、常量与 URL 规则
    codec/                   图片编解码与解扰、PDF（后续加 ArchiveWriter）
    network/                 OkHttp 工厂、错误分类、超时重试
    database/                Room 实体 / Dao / 数据库 / 迁移
    settings/                三作用域设置 + Keystore 凭据存储
    storage/                 目录规划、文件名清洗与排序
    di/                      手写依赖注入容器
  ui/theme/                  Compose 主题
```

拆分 Gradle 模块的触发条件（先不做，但写下来避免争论）：
单一模块的编译时间明显变长（>2 分钟）时；或需要 feature-on-demand 分包时。

## 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| **P0 环境与基线** | ✅ 完成 | 见 `docs/p0/README.md`；3 个 spike 全部有结论 |
| **P1 基础设施** | ✅ 完成 | 见下表；验收项全部达成 |
| **P2 JM 数据与浏览** | 🚧 代码完成，**界面待人工确认** | 见下方 P2 明细 |
| **P3 JM 下载与阅读闭环** | 🚧 代码完成，**界面待人工确认** | 见下方 P3 明细 |
| P4 JM 导出 zip / pdf | ⬜ 未开始 | PDF 能力已在 P0 spike 验证 |
| P5 B站 | ⬜ 未开始 | |
| P6 壳与体验 | ⬜ 未开始 | |
| P7 验收与真机 | ⬜ 未开始 | |

### P2 明细（JM 数据与浏览）

| 项 | 状态 | 产出 |
|---|---|---|
| JM 接口客户端（搜索/详情/章节/模板，**不含登录**） | ✅ | `feature/jm/api/JmApiClient.kt` |
| 接口响应模型（字段逐条对齐参考实现） | ✅ | `feature/jm/api/JmModels.kt` |
| 本子入库（覆盖在线字段 + **保留本地收藏**） | ✅ | `JmRepository.saveAlbum`，测试锁定 |
| 章节清单入库（整章替换 + `imageSort` 从 1 开始） | ✅ | `JmRepository.saveChapterImages` |
| 章节来源优先级（`album.series` → 本地图片表） | ✅ | `JmRepository.chaptersOf`，两个分支都有测试 |
| 在线搜索 + 是否已入库标记 + 分页 | ✅ | `JmRepository.searchOnline` |
| 搜索历史（保留条数、相同条件刷新、快照回显） | ✅ | `recordSearchHistory` / `searchHistorySnapshot` |
| 收藏夹 CRUD（默认夹不可删改、删除只删关联） | ✅ | `JmRepository` 收藏段，测试锁定 |
| 书库筛选（关键字/作者/**多标签 OR**/收藏夹） | ✅ | Dao 用递归 CTE 表达 OR 语义 |
| 书库页（筛选、状态优先展示下载章节数） | ✅ 代码 | `JmLibraryScreen` |
| 发现页（在线搜索 + 历史 + 分页） | ✅ 代码 | `JmOnlineSearchScreen` |
| 收藏页（增删改、默认夹标识） | ✅ 代码 | `JmFavoritesScreen` |
| 漫画详情页（本地优先 + 在线刷新 + 章节状态） | ✅ 代码 | `JmAlbumDetailScreen` |
| 漫画模式底部导航（书库/最近/发现/收藏） | ✅ 代码 | `JmModeRoot` |
| **界面人工确认（截图/交互）** | ⏳ **待你确认** | 见下方"P2 待验证" |

### P3 明细（JM 下载与阅读闭环）

| 项 | 状态 | 产出 |
|---|---|---|
| 下载任务编排（提交去重 / 开始 / 暂停 / 恢复 / 取消 / 重试） | ✅ | `feature/jm/data/JmDownloadManager.kt` |
| 章节图片清单按需补齐（没有就现场拉 `/chapter` 并入库） | ✅ | `JmDownloadManager.ensureImageList` |
| 解扰落盘（`blockNum==0` 直接落原图，不重新编码） | ✅ | `ImageDownloadTransform`，测试逐字节断言 |
| 图片"存在即跳过" + 0 字节残留算缺失 | ✅ | 测试 `images already on disk are not requested again` 等 |
| 进度持久化到"第 N 话 x/y 张" | ✅ | 每张图落库一次；任务级数字由章节明细推导 |
| 任务级防死循环（缺图数不下降就停） | ✅ | 章节内 3 轮上限 + 任务级"没有进展就停" |
| 网络策略闸门（仅 Wi-Fi 时进"等待网络"而不是失败） | ✅ | `awaitNetworkPermission` + `core/network/NetworkMonitor` |
| 杀进程后按磁盘现状重算并继续 | ✅ | `recoverInterrupted` → `requeueForResume` → 自动接着跑 |
| 前台服务常驻通知 | ✅ | 有任务开始即 `DownloadForegroundService.ensureRunning` |
| 任务页（进行中 / 已完成、暂停恢复取消重试、清空历史、角标） | ✅ 代码 | `feature/jm/ui/JmTaskScreen.kt` + `JmTaskViewModel.kt` |
| 阅读器（纵向连续 / 横向分页 / 右到左） | ✅ 代码 | `feature/jm/ui/JmReaderScreen.kt` |
| 单击显隐控件、双指缩放、双击放大还原、放大后拖动 | ✅ 代码 | `PageZoomState` + 自写手势（不用 `transformable`，见下） |
| 章节选择、上一话/下一话、缺图页与重试 | ✅ 代码 | 阅读器控件栏 |
| 阅读位置持久化（本子+章节+图片+页内比例，去抖 400ms） | ✅ | `JmReadingRepository`，测试 7 个用例 |
| 书库「继续阅读」卡片 + 详情页主按钮三态 | ✅ 代码 | `ContinueReadingCard`、`PrimaryAction` |
| 阅读设置（方式/方向/背景/亮度/常亮）与 JM 设置**同一份偏好** | ✅ 代码 | `JmReaderPreferences.kt`，键位复用 JM 作用域 |
| 长图占位稳定（按图片宽高比占位，加载前后不跳） | ✅ 代码 | `ImageCodec.readMeta` + `aspectRatio` |
| **界面人工确认（下载/阅读/任务页观感）** | ⏳ **待你确认** | 见下方"P3 待验证" |

#### P3 的两个实现取舍

1. **不用 `Modifier.transformable` 做缩放**：它会把单指拖动也当作平移并消费掉事件，
   于是"没放大时列表就再也滚不动了"。阅读器自己写手势循环：
   **只在双指缩放、或已放大后的拖动时才消费事件**，其余交给 `LazyColumn` / `HorizontalPager`。
2. **重复提交不新建任务，而是把章节追加进已有任务**。计划 §9 只要求"重复提交显示已有任务"，
   但"我勾了第 5 话却没开始下载"是更糟的体验；去重规则（同一 `(type, bizKey)` 一个活跃任务）
   仍然成立，只是在追加时会唤醒暂停中的任务。

#### 模拟器实测发现并修掉的三个 bug（2026-10-09）

这三个都是**只有真的把 App 跑起来点一遍才会暴露**的问题，单测覆盖不到：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| E1 | 从搜索结果点进一本**没入库**的漫画，详情页只有一个 JM 号与"共 0 话"，**永远不联网** | 进详情页时会先 `touchRecent` 建一条**只有 id 的骨架行**；`localAlbumDetail` 把它当成"本地已有数据"，于是详情页走了"本地优先"分支、再也不请求 `/album` | `localAlbumDetail` 只认**已经有过在线数据**的最近浏览记录（`name` 非空）；骨架行返回 null，详情页照常联网。用例 `the touch placeholder does not count as local data` |
| E2 | 单话本子显示"共 0 话"，**无法下载**（JM 上这类本子很常见） | `/album` 对单话本子不返回 `series`，本地章节表也是空的，于是章节列表为空 | 按参考实现 `downloadAlbumWithoutLock` 第 866～872 行的兜底：**章节 id = 本子 id**，标题"第1话"。用例 `a single chapter album falls back to using the album id as the chapter id` |
| E3 | 图片已经下到磁盘，界面却一直显示 **0/1 已下载**（点阅读器也是"图片缺失"） | 章节目录名来自 `chapterTitle`：下载时用的是合成的"第1话"，入库时却写成了接口给的 `chapter.name`（**就是本子标题**）。两边对不上，计数器去一个不存在的目录里找图 | 单话本子的章节标题**固定「第1话」**（入库时合成、读取时归一化），阅读器查目录也走同一条规则。用例 `a single chapter album stores the synthetic title so it matches the folder name`、`a single chapter album normalizes the stored title to the folder name` |

顺带调整：底部「任务」角标把 `WAITING_NETWORK` 也算进去（它不是失败，但用户点完下载
回到浏览页后，没有角标就完全没有线索——实测就是这样）。

#### 模拟器实测走过的链路（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 冷启动 | ✅ 5 个底栏 tab（书库/最近/发现/收藏/任务）正常渲染 |
| 单话本子详情页 | ✅ 共 1 话、章节行「第1话·未下载」 |
| 点「下载」→ 面板选章节 → 确认 | ✅ 自动入库 + 建任务；角标出现 |
| 任务页 | ✅ **等待网络 · 0/1 话** + "当前网络不允许下载，已暂停传输"（当时默认策略还是仅 Wi-Fi） |
| 切到 Wi-Fi | ✅ 任务自动继续：`下载图片 · 0/1 话 · 0/11 张`，磁盘上出现 `第1话/0000N.webp.part` |
| 杀进程再启动 | ✅ 日志 `恢复 1 个被中断的下载任务`，恢复后继续跑 |
| 下载中的失败与重试 | ✅ 读超时 → 章节标记未完成、任务判失败并给出"1 话没有下完（0/11 张），可点重试补下"；点「重试」后只补缺口，已发布的图片不再请求 |
| 部分下载后的详情页 | ✅ `部分下载 4/11`，占用从 1.1 MB 涨到 5.1 MB |
| 阅读器 | ✅ 顶栏「第1话 · 11 页 · 缺 7 页」，缺图页显示「第 N 页 · 图片缺失 + 重试」，底栏「补下缺图（7 页）」；已下载的页**正常显示解扰后的画面**，纵向滚动可用 |
| 整本下完 | ✅ 书库卡片变「已下载全 1 话」，磁盘上 11 张图（默认放开移动网络后在 5G 下直接跑完） |

模拟器到 JM 图片 CDN 的链路很慢（单张封面 40 秒、图片经常读超时），所以没等它跑完整本；
"落盘 → 解扰 → 原子发布 → 进度"由 `JmDownloadManagerTest`（真实 HTTP 服务 + 逐字节校验）覆盖。

### P3 待验证（需要你在设备上确认）

自动验证过的部分：**208 个单元测试 + 6 个真机测试**全绿，lint 0 error，debug/release 均可构建。
（真机 6 个用例是上一轮跑的：迁移 2 + PDF 加密 4；本轮没有改数据库结构，所以没有重跑。）

| # | 请确认 | 期望 |
|---|---|---|
| 1 | 底栏五个 tab 之间来回点 | 往右边的 tab 切从右侧滑入、往左边切从左侧滑入，方向与底栏位置一致 |
| 2 | 详情页 | 只有「阅读」「下载」「收藏」三个按钮；「阅读」从第一章开始；点「收藏」直接弹收藏夹面板 |
| 3 | 详情页点「下载」 | 面板里默认勾选未下完的章节；已在队列的章节灰掉、显示"已在下载队列"且点不动 |
| 4 | 挑一本**没下载过**的漫画点「阅读」 | 直接就能看（边看边联网取图），顶栏"已缓存 N 页"会涨；退出再进不重新请求 |
| 5 | 阅读器点一下画面 | 底部出现进度条 + "第几页/共几页" + 上一话 / 下载本话 / 收藏 / 下一话 |
| 6 | 把某本的本地文件删掉后再点「阅读」 | 自动重新取图，不再提示"图片缺失" |
| 7 | 任务页 | 每行有封面，点封面进这本漫画的详情页 |
| 8 | 一边看一边下载 | 两边不冲突：下载计数会把已经看过的页算进去，也不会重复请求同一张图 |
| 9 | 发现页点「加入书库」 | 按钮**立刻**变「已入库」，不用切页面 |
| 10 | 下载到一半杀进程再打开 | 从断点继续，已下载的图片不重下 |

> 第 4～8 条已在模拟器上走过一遍（见下表），但**真机上的观感**
> （通知、息屏继续、弱网重试、缩放/翻页手感、tab 切换动画方向）还是需要你确认一次。

### 第四轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 未下载的本子点「阅读」 | ✅ 直接出图；顶栏「第1话 · 51 页 · 已缓存 29 页」，日志里是逐页的 `/media/photos/...` 请求 |
| 阅读器底栏 | ✅ 进度条 + 「第1话 · 1/51 页」+「加载中 22」+ 上一话 / 下载本话 / ★已收藏 / 下一话 |
| 任务页 | ✅ 三行任务都带封面；点封面进了对应详情页 |
| 详情页头部 | ✅ 阅读 / 下载 / 已收藏 三颗按钮，第二行是「已在书库 · 已加入 1 个收藏夹」 |
| 点「已收藏」 | ✅ 直接弹出收藏夹面板（默认收藏夹已勾选 + 新建收藏夹） |
| 边看边下 | ✅ 同一次操作里，下载任务的计数从 3/51 涨到 33/51——阅读器取回来的图就是下载任务认的那份文件 |

### 第五轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 详情页标题 | ✅ 紧贴状态栏，留白消失 |
| 打开一本未下载的漫画进阅读器 | ✅ 显示"正在加载第 1 页…"，底栏进度条 + 「第1话 · 1/40 页」+「加载中 40」 |
| 该过程中的主线程 | ✅ logcat **没有任何 `Skipped N frames` / `Davey!`**（改之前实测同一动作会 `Skipped 72 frames!`） |
| 阅读器点「收藏」→ 返回详情页 | ✅ 按钮立刻变「已收藏」，状态行变「已在书库 · 已加入 1 个收藏夹」 |
| 详情页收藏夹面板里移出 | ✅ 按钮立刻变回「收藏」，状态行变「未收藏」，面板勾选同步取消 |
| 章节行的 ⋮ | ✅ 菜单紧贴按钮右下弹出 |
| 书库列表 | ✅ 最近加入的排在最前 |
| 收藏夹删除 | ✅ 弹出「删除「X」？……漫画仍然留在书库，已下载的图片也不会被删掉」 |
| 改名撞车 | ✅ 提示「已经有同名的收藏夹了，换个名字」（之前是"默认收藏夹不支持重命名"） |

### 第六轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 收藏页 | ✅ 默认收藏夹左侧显示夹内最近加入那本的封面；三个空夹在同一位置显示 ☆ 占位，行高一致 |
| 点「排序：创建时间 ⌄」 | ✅ 弹出三选一菜单，当前项打勾并高亮 |
| 切到「最近加入」 | ✅ 标签变成「排序：最近加入」，顺序随之变化，**默认收藏夹仍在最上** |
| 详情页「三点」 | ✅ 只剩 导出 ZIP / 导出 PDF（都标着 P4 接入）/ 删除本地文件 / 从书库移除，「下载管理」已移除 |

### 用户提出的 8 点改动（2026-10-08 完成）

| # | 内容 | 落地方式 | 验证 |
|---|---|---|---|
| 1 | 收藏后收藏页数量不更新 | 收藏页改用 `observeFavoritesWithCount()` 的 **Flow**（之前是 N 次一次性查询），一条 LEFT JOIN + Flow 重发 | `favorite count flow emits after collecting` 等 3 个用例 |
| 2 | 点详情不该等于加入书库 | 详情页只 `touchRecent`；入书库只发生在点「加入书库」 | `touching recent does not add to the library` 等 3 个用例 |
| 3 | 新增最近浏览页（按浏览降序） | 新表 `t_jm_recent_album`（字段同书库 + `viewed_time`），底栏新增「最近」tab | `recent list is ordered by viewed time descending` |
| 4 | 详情页先读本地再联网；刷新更新记录但不改浏览时间 | `localAlbumDetail()` 先查书库→最近浏览；`fetchAlbumDetail(saveToLibrary)`；`saveToRecent` 保留原 `viewedTime` | `refresh updates recent fields but keeps the viewed time` |
| 5 | 搜索一次渲染太多封面 | 首屏只渲染 10 条，**最后一条进入可视区就自动追加**；本页全部展示完才出现「加载下一页」 | 逻辑在 `OnlineSearchUiState.remainingCount / allVisible`；观感待设备确认 |
| 6 | 缺少修改收藏夹功能 | 详情页「收藏夹」底部面板：加入 / 移出 / 移动到 / 新建并加入 | 实现于 `moveToFavorite` + 收藏夹关联用例 |
| 7 | 夹内显示收藏夹名称、去掉「收藏」筛选 | `JmLibraryScreen(title = …, showCollectedChip = false)` | 代码 |
| 8 | 封面本地缓存 | `AppContainer.imageLoader` 把 Coil 磁盘缓存指向 `files/covers`，网络层复用 JM 的 OkHttp（UA/代理） | 代码；观感待设备确认 |

> **第 8 点说明**：Coil 的磁盘缓存默认就是"先查本地、没有再联网"，
> 不需要自己写文件缓存。但它同时带来一个待办：`LibraryDirs.clearCache()`
> 目前只清 `tmp` / `export`，**没算上 `covers`**。封面缓存有 256MB 上限、
> 由 Coil 按 LRU 自行淘汰，所以不紧急；做"清理缓存"设置项时要把它纳入（P6）。

### 第二轮反馈修复（2026-10-08）

| # | 反馈 | 根因 | 修复 |
|---|---|---|---|
| B1 | 搜索分屏追加要"先滑到顶再滑到底"才生效 | ① `derivedStateOf` 读 `listState.layoutInfo` 拿到**过期快照**，只在别的重组顺带读它时才重算；② `LaunchedEffect` 的 key 含当前批次条数，每次追加都取消并重启 effect，打断正在进行的滚动检测 | 改 `snapshotFlow` 主动监听；key 只放列表身份，判定用 `rememberUpdatedState` 读实时值 |
| B2 | 收藏夹内一直显示"清除 1 项" | 进夹时 `favoriteId` 必然已设置，`filter.isActive` 恒真；但那是页面上下文、不是用户可清的筛选 | 夹内不显示清除按钮 |
| B3 | 详情页标题下方一大块留白 | edge-to-edge 下 `TopAppBar` 的 windowInsets 与 Scaffold 内容内边距叠加 | 显式声明 `windowInsets = TopAppBarDefaults.windowInsets`（待你在设备上确认是否真的解决） |
| B4 | 简介本来能全显示时仍出现"展开简介" | 按钮无条件渲染 | 用 `onTextLayout` 的 `hasVisualOverflow` 判断是否真被截断 |
| B5 | 页面切换是淡入淡出 | 未配转场 | NavHost 加 `slideInHorizontally` / `slideOutHorizontally`（260ms），返回反向 |
| B6 | 收藏夹弹框挡住提示语 | 详情页自己的 Scaffold 弹 Snackbar，被 `ModalBottomSheet` 盖住 | 提示上抛到根部 Scaffold 弹（新增 `onMessage` 回调） |
| Q3 | 首次点收藏卡一下，取消再收藏就很快 | **首次收藏多打了一次 `/album` 网络请求**（`addToLibrary(albumId)`），本地已有记录后就不再请求 | 新增 `addToLibrary(dto)` / `addToLibrary(entity)`，用内存数据直接落库 |

> B1 的判定抽成了 `OnlineSearchUiState.shouldRevealMore()` 纯函数，
> 由 `SearchRevealStateTest` 覆盖（10→20→30→35 的推进与边界条件）。
> 滚动触发本身没法在这台设备上自动验证，所以单独把"判定条件"锁住。

**仍未自动验证**：B1 的滚动触发（模拟器单次 `/search` 要 44 秒、超过应用 30 秒超时；
真机 MIUI 又拒绝 `adb input`）、以及 B3/B5/B6 的观感。需要你在设备上确认。

### 第三轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| C1 | 底栏切换的滑入方向要跟 tab 位置一致 | `tabShift(from, to)` 比较两个 tab 在底栏里的下标：往右切从右侧滑入、往左切从左侧滑入；旧页面往反方向退，像一条传送带。进详情页/阅读器仍是从右侧进入 | `JmReaderAndTaskLogicTest` 的 3 个用例（右移/左移/非 tab 路由） |
| C2 | 「最近」「任务」的图标换掉 | 最近 → 日历（`DateRange`，表达"按时间翻过的记录"）；任务 → 清单（`List`，表达"一批待办的事"）。两者都在 `material-icons-core` 里，**不引入 extended** | 模拟器截图 |
| C3 | 详情页四个下载入口收敛成一个 | 头部只留「下载」：点开面板选章节、确认后整批入队。章节行的下载菜单、章节区标题的「下载未完成」全部删掉；章节行尾菜单只剩"删除本话文件"（且只在已下过图时出现） | 模拟器截图 |
| C4 | 下载面板要标出"已在队列"的章节 | `DownloadTaskRepository.observeQueuedChapters(albumId)` 盯住活跃任务的章节明细 → `AlbumDetailUiState.queuedChapterIds`；面板里这些章节复选框禁用、标题降权、"已在下载队列"用 tertiary 色，且**不默认勾选**。`downloadChapters` 再兜一层过滤 | `chapters already in the queue are not preselected`；模拟器截图 |
| C5 | 详情页要有「阅读」（从第一章开始） | 头部「阅读」按钮取第一章的 id 进阅读器，`chapterId > 0` 时阅读器**从该话第一页开始**（不恢复上次位置）；续读入口仍在书库的「继续阅读」卡片 | 模拟器截图 |
| C6 | 发现页点「加入书库」按钮不变成「已入库」 | `visibleItems` 的 `remember` key 是 `(visibleCount, items.size)`：`existsLocal` 变了但这两个数没变，于是**一直复用加库之前的那份列表**，切走再回来才会重建 | 模拟器实测：点了立刻变「已入库」 |
| C7 | 下载先不分 Wi-Fi / 流量，等设置模块再做 | 全局下载网络默认值从"仅 Wi-Fi"改为**允许移动网络**（`SettingDefaults.DOWNLOAD_NETWORK_ANY`）。判定逻辑与"等待网络"状态机不动，P6 做出开关后用户可自行收紧 | 模拟器在 5G 下直接开下 |

> C7 是对计划 §9.5 的一处**有意偏离**：原计划把 Android 侧默认收紧成"仅 Wi-Fi"。
> 但设置模块还没做，用户在界面上改不了，默认收紧会让移动网络下的下载**看起来没反应**
> （任务静默停在"等待网络"）。等 P6 把三个下载网络开关做出来，默认值是否收回可以再定。

### 第四轮反馈修复（2026-10-09）

这一轮把"看"和"下"统一到**同一份磁盘文件**上：`JmImageStore`（`feature/jm/data/`）
负责"算路径 / 命中判断 / 下载后解扰"，阅读器与下载任务都走它。

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| D1 | 任务列表要显示封面，点封面进详情 | 任务行加 `JmCover`（应用级 Coil 加载器 + `files/covers` 磁盘缓存，所以看过的封面优先读本地），`TaskRow.albumId` 从 `bizKey` 解析；点封面 → `JmRoutes.detail(aid)` | 模拟器截图：任务页有封面、点击进详情 |
| D2 | **不必先下载才能看**；取回来的图要缓存；下载时先看缓存；边看边下不冲突 | 新增 `JmImageStore`：本地有就直接用，没有就下载 + 解扰 + 落盘。阅读器**按页懒加载**（只取滚动到附近的页）。下载任务每张图仍走"文件在不在"的判断，两边看的是同一个文件 | `JmImageStoreTest`（7 个用例：不发多余请求 / 第二次不再请求 / 删掉后重新取）；模拟器实测：未下载的本子点「阅读」直接出图，顶栏显示"51 页 · 已缓存 29 页" |
| D3 | 阅读器遮罩要有进度条、下载按钮、收藏按钮 | 底栏改成三层：进度条 + 「第1话 · 12/34 页」（含加载中/失败页数）+ 上一话 / **下载本话** / **收藏** / 下一话 | 模拟器截图 |
| D4 | 删掉本地文件后再读不该报"图片缺失" | 同 D2：文件不在就是缓存未命中，自动重新取；只有真的取不到（离线/图床故障）才显示"第 N 页没取回来 + 重试" | `deleting the local file makes the next read fetch it again` |
| D5 | 取消「收藏夹」按钮，点「收藏」直接弹收藏夹面板 | 头部三颗按钮变成 阅读 / 下载 / 收藏，收藏按钮直接打开 `FavoritePickerSheet`；原来第二行的「收藏夹」位置改成一句状态（`已加入 N 个收藏夹`） | 模拟器截图 |

#### 顺带修掉的两处并发隐患（D2 带来的）

1. **同一张图被两个调用方同时要**（一边看一边下载）会写同一个 `<图>.part`。
   现在 `FileDownloader` 对每个目标文件加锁，等锁期间会重新判断"是不是已经下好了"。
2. **解扰不可逆**：`JmImageStore` 对每张图加锁，保证同一张只解扰一次。

### 第五轮反馈修复（2026-10-09）

**这一轮的主线是主线程阻塞**：用户报的三处"卡一下"其实是同一个根因——
`viewModelScope` 用的是主线程调度器，而我在里面直接做了磁盘与解码的重活。

| # | 反馈 | 根因 | 修复 |
|---|---|---|---|
| E1 | 收藏夹里全部移出后按钮还是"已收藏" | `removeFromFavorite` 只改了数据库，详情页的 `album` 是**进页面时读的一次性快照**，没人通知它 | 详情页改成**观察** `observeAlbum(aid)` 与 `observeFavoriteIdsOf(aid)`（新增 Dao Flow），谁改了都立刻反映 |
| E2 | 书库要按入库时间降序 | 排序是 `collected DESC, COALESCE(modify_time, create_time) DESC`——收藏优先，而且刷新详情会改 `modify_time` 把顺序打乱 | 改成 `create_time DESC, id DESC`。`create_time` 才是"第一次进书库的时间" |
| E3 | 阅读器里收藏后返回详情页仍显示"收藏" | 同 E1 | 同 E1（两个页面现在看同一份 Flow） |
| E4 | 阅读器去掉"护眼暖色" | — | 删掉 `ReadingBackground.SEPIA` 与对应配色；历史数据里的编码 3 回落到"跟随主题"（有用例锁定） |
| E5 | 详情页标题上方一大块留白 | edge-to-edge 下**两层 Scaffold 各加了一次状态栏内边距**：外层 `JmModeRoot` 的 Scaffold 已经把状态栏高度作为 `padding` 给了 NavHost，内层 Scaffold 与 TopAppBar 又各加了一遍 | 内层 `contentWindowInsets = WindowInsets(0,0,0,0)`，TopAppBar 同样置 0 |
| E6 | 章节的"删除本话文件"菜单飘到别处 | `DropdownMenu` 直接当 `Row` 的子节点，锚点算在了整行上 | 把图标与菜单包进同一个 `Box`，锚点才是那颗按钮 |
| E7 | 改名撞车时提示"默认收藏夹不支持重命名"；删除缺二次确认 | `renameFavorite` / `deleteFavorite` 只用 `Boolean` 表达结果，界面只能猜一个文案 | 改成 `FavoriteWriteResult`（Ok / DefaultFolder / NameTaken / NameBlank / NotFound），界面逐项给文案；删除加确认框并写清影响 |
| E8 | **阅读器在线取图时 UI 卡住** | `JmImageStore.file()` 跑在 `viewModelScope`（主线程）上，而里面的**解扰是纯 CPU 重活**：JM 长图解码成 ARGB_8888 要几十 MB，还要逐行 `getPixels/setPixels` 再重新编码 | `file()` 与 `aspectRatioOf()` 整段 `withContext(Dispatchers.IO)` |
| E9 | 进详情页、点进收藏夹各卡一下 | 同类问题：`downloadedImageCount` 逐张查文件、`localSize` 递归遍历目录、`readMeta` 读图片头，全在主线程 | `loadLocalStats` 与 `buildStat` 的整段计算切到 IO；阅读器的 `refreshFromDisk` 也切到 IO |

> E8/E9 是同一类错误的三个出口。判断方法很简单：**`viewModelScope` 里没有 `withContext`
> 的代码都在主线程上跑**。现在的约定是——凡是碰文件、解码、遍历目录的，一律先切 IO。

### 第六轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| F1 | 收藏夹要有封面（夹内最近收藏的那本），空夹给占位 | 汇总查询加两个相关子查询：`lastAddedTime`（`MAX(create_time)`）与 `latestAlbumId`（按 `create_time DESC, id DESC` 取第一条）。列表左侧复用 `JmCover`（应用级 Coil + `files/covers` 磁盘缓存，看过的封面优先读本地）；空夹在同一位置画等大的占位方块，避免列表高度跳动 | `FavoriteSortTest`；模拟器截图：默认收藏夹显示封面、三个空夹显示 ☆ 占位 |
| F2 | 收藏页要有排序机制（数量 / 创建时间 / 最近加入，都降序，默认创建时间） | 右上角「排序：xxx ⌄」按钮打开三选一菜单（当前项打勾并高亮）。排序是**纯界面行为**，由 `sortFavorites()` 在展示层完成，不写库 | `FavoriteSortTest` 的 7 个用例；模拟器实测切换后顺序变化 |
| F3 | 详情页「三点」菜单去掉「下载管理」 | 直接删掉该项；任务页仍可从底栏「任务」进入 | 模拟器截图 |

> F2 的两条隐藏规则也写进了 `sortFavorites()` 并有用例锁定：
> **默认收藏夹永远置顶**（换排序也不动它），以及同值时按 id 兜底（顺序不抖）。

### 真机验证发现的一个严重 bug（已修）

**改动数据库表结构却忘记升 `@Database(version)`**，导致**应用一启动就崩**：

```
java.lang.IllegalStateException: Room cannot verify the data integrity.
Looks like you've changed schema but forgot to update the version number.
Expected identity hash: 7ecd5cfc..., found: 31e452937...
```

触发条件是**覆盖安装**——全新安装不会有这个问题，所以"删掉重装"这种调试习惯
永远发现不了它。P2 加 `t_jm_recent_album` 时就这样漏了。

修复：

- `@Database(version = 1 → 2)`；
- `Migrations.MIGRATION_1_2` 建新表（**不使用 destructive migration**，否则用户数据全丢）；
- 新增 `MigrationsTest`（真机）：从 v1 建库 → 跑迁移 → 校验 schema → 断言旧数据还在，
  并断言新表可写。这类错误从今往后会被测试拦住；
- `app/schemas/` 挂进 `androidTest` 的 assets（`MigrationTestHelper` 要从那里读 schema），
  并且**历史版本的 schema JSON 必须一起进版本库**——我一度删掉了 `1.json`，
  迁移测试立刻失败。

**教训**：`@Database(entities)` 一改就必须同时升 version + 补迁移 + 保留历史 schema。

### P2 待验证（需要你在设备上确认）

我能自动验证的部分都过了：**130 个单元测试 + 6 个真机测试**
（迁移 2 + PDF 加密 4）全绿，lint 0 error，应用启动无崩溃。

**但交互与观感我无法自己验证**，因为这台设备同时封掉了两条路：

| 手段 | 结果 |
|---|---|
| Compose UI 测试 | ❌ 永久阻塞：测试 Activity 拿不到焦点（`topResumedActivity` 始终是 launcher） |
| `adb shell input tap/swipe` | ❌ `SecurityException: ... INJECT_EVENTS permission` |
| 截图 | ✅ 可用（只读） |

所以请你在设备上确认这几项（都是这次改动的重点）：

1. **发现页搜索**：首屏是否只出 10 条、下滑是否自动追加、全部展示完才出现「加载下一页」；
2. **封面缓存**：第二次进同一页是否明显更快（缓存落在 `files/covers`）；
3. **点详情不入库**：从搜索点进详情后返回，书库是否没多出这本；
4. **最近浏览**：进过详情的本子是否出现在「最近」tab，且按浏览时间倒序；
5. **收藏数量**：收藏一本后进收藏页，数量是否立即更新；
6. **收藏夹编辑**：详情页「收藏夹」面板的加入 / 移出 / 移动到 / 新建；
7. **夹内页面**：标题是否显示收藏夹名、是否只剩「已下载 / 未下载」。

### P1 明细

| 项 | 状态 | 产出 |
|---|---|---|
| Room：9 张表（业务 5 + 任务 2 + 位置 1 + 搜索历史 1） | ✅ | `core/database/entity/*.kt` |
| Dao 与查询语义（分页/排序/分组/唯一索引） | ✅ | `core/database/Daos.kt`，测试 `JmDaoTest` |
| 持久化下载任务的去重与恢复语义 | ✅ | `DownloadTaskDaoTest`（12 个用例） |
| 三作用域设置 + 跟随全局继承 | ✅ | `core/settings/Settings*`、`SettingsRepository` |
| Keystore 凭据存储 | ✅ | `core/settings/SecretStore.kt` |
| 目录规划 | ✅ | `core/storage/LibraryDirs.kt` |
| 网络层（工厂 / 错误分类 / 超时重试） | ✅ | `core/network/*` |
| DI 容器 + Application + 权限声明 | ✅ | `core/di/AppContainer.kt`、`HaruhiBoxApp`、Manifest |
| **下载引擎（Range 续传 + 并发 + 取消）** | ✅ | `core/download/FileDownloader.kt`，测试 `FileDownloaderTest`（12 个用例） |
| 图片解扰落盘（`blockNum==0` 直接落原图） | ✅ | `core/codec/ImageCodec.kt`、`ImageDownloadTransform` |
| **任务仓库 + 进程重启恢复编排** | ✅ | `core/download/DownloadTaskRepository.kt`，测试 `DownloadTaskRepositoryTest` |
| **前台服务 + 常驻通知** | ✅ | `core/download/DownloadForegroundService.kt`、`core/notification/*` |
| WorkManager 约束调度（仅 Wi-Fi 等） | ⬜ | 服务与恢复已就绪，缺 Worker 与 Constraints |
| 设置层单元测试（作用域隔离 / 继承 / 恢复默认） | ⬜ | |
| 设置校验工具（写入前校验，拒绝非法代理等） | ⬜ | `ResolvedNetworkConfig.validate()` 已就绪，缺 UI 对接 |

### 下载引擎已覆盖的语义（计划点名项）

| 计划要求 | 实现位置 | 测试 |
|---|---|---|
| 图片"文件存在即跳过" | `FileDownloader.download(skipIfComplete)` | `skips download when the target already exists` |
| HTTP Range 续传 | `attemptOnce` 的 206 分支 | `resumes from the existing part file` |
| **CDN 不支持 Range 时整文件重下** | 200 分支丢弃 `.part` | `does not support range - server ignores Range and we restart from zero` |
| 临时文件校验后再原子发布 | `publishAfterValidation` + `publish` | `truncated download is rejected when the caller knows the expected size` |
| 416 / 区间不匹配的处理 | `RangeNotSatisfiable` / `RangeMismatch` | `range 416 drops the local offset and restarts from scratch` |
| 直链过期后重新解析 | `urlProvider` 每次尝试重新调用 | `server error is retried and url is re-resolved each attempt` |
| 批量图片并发 + 进度到图片 | `ParallelImageDownloader` | 逻辑由 `JmDaoTest` 的章节计数用例覆盖 |
| 下载网络策略在安全检查点检查 | `DownloadGate` + `JmDownloadManager.awaitNetworkPermission` | `DownloadGateTest`；模拟器实测"等待网络 → 切网络自动继续" |

### P1 验收（计划原文）

> 能下载一个任意大文件到私有目录，断网/杀进程后能续传，通知显示进度；
> **杀掉进程再启动，任务列表和进度能从数据库恢复（RUNNING 任务按磁盘现状重算进度后继续）**。

| 验收点 | 状态 | 证据 |
|---|---|---|
| 下载任意大文件到私有目录 | ✅ | `FileDownloaderTest`；落盘在 `filesDir/library`，导出走 SAF |
| 断点续传（Range） | ✅ | `resumes from the existing part file`；不支持 Range 时整文件重下 |
| 不完整文件不发布 | ✅ | `publishAfterValidation` + 断流用例 |
| 通知显示进度 | ✅ | `DownloadForegroundService` 订阅活跃任务渲染通知（真机观感待 P7） |
| 杀进程后任务与进度从数据库恢复 | ✅ | `recoverInterrupted` + `DownloadTaskRepositoryTest` 的 4 个恢复用例 |
| 恢复后按磁盘现状重算（不回退、不重复累加） | ✅ | `recomputeChapterProgress`；0 字节残file 不计入 |
| **恢复后自动接着跑**（P3 接上） | ✅ | `HaruhiBoxApp.recoverInterruptedDownloads` → `jmDownloads.resumeQueued` |
| WorkManager 约束调度（仅 Wi-Fi 时自动延后） | ⬜ | 前台服务已就绪；"等待网络"由执行器的闸门实现，Worker 留到 P6 |

## 已确认的决策（2026-10-08）

| 项 | 决策 | 落地位置 |
|---|---|---|
| PDF 加密算法 | **改为 AES-128** | `PdfWriter.applyProtection` 同时设 `encryptionKeyLength=128` + `isPreferAES=true` |
| PDF 权限 | **只看「能否打开」**：owner 与 user 同密码，权限位不作为安全承诺 | `PdfWriter.applyProtection`；事实由 `PdfEncryptionInstrumentedTest` 断言固定 |
| debug 包共存 | **debug 加 `.debug` 后缀**，可与 release 同时安装 | `app/build.gradle.kts` 的 `applicationIdSuffix` |

> **一处更正**：先前 `docs/p0/spike2-pdf.md` 写「参考实现产出 AES-128、Android 只能 RC4-128」，
> 这是错的（当时 `javap` 输出不完整，漏看了 `setPreferenceAES`/`setEncryptionKeyLength`）。
> 实测：**只设 `encryptionKeyLength` 会被忽略，参考实现实际产出 40 位 RC4**；
> 只有同时设 `preferAES=true` 才得到 AES-128。所以本 App 的 AES-128 是安全强化，不是对齐。
> 详见 `docs/p0/spike2-pdf.md` 第 2 节。

## 剩余待确认 / 待处理

1. **release 签名 keystore 需备份**：`haruhibox-release.jks` 只在本地，丢了无法覆盖升级。
   （运维事项，不是代码问题。）
2. **JM 图片线路默认值**：参考实现硬编码 `IMAGE_DOMAIN`，App 侧作为可编辑线路列出，
   默认值沿用 `cdn-msp2.jmapiproxy2.cc`（见 `JmDefaults`）。这条只是记录，不是待确认项。
3. **PDF 单页尺寸上限**：参考实现不处理 14400×14400 上限，JM 长图可能超。
   P4 需要加校验并给出明确错误——届时应按"行为差异"再次确认处理方式（拒绝导出 / 按高度切片）。

## 真机验证记录

设备：小米 23116PN5BC（`shennong`，1440×3200），通过无线调试连接（会掉线，需重连）。

| 项 | 结果 |
|---|---|
| release APK 安装 | ✅ 4.44 MB，`adb install` 成功 |
| 冷启动 | ✅ 无崩溃，`topResumedActivity` 为 `MainActivity` |
| 界面渲染 | ✅ 显示占位页（截图已确认） |
| R8 混淆后的运行期正确性 | ✅ 修掉 `packageScope` 引发的 `IllegalAccessError` 后正常 |
| **PDF AES-128 真机验证** | ✅ **4 个用例全通过**（`:app:runDeviceTests`） |

### PDF 加密真机用例（2026-10-08 跑通）

```
com.haruhi.haruhibox.core.codec.PdfEncryptionInstrumentedTest:....
Time: 0.2
OK (4 tests)
```

| 用例 | 验证内容 |
|---|---|
| `albumPdfWithPasswordUsesAes128` | `V=4 / R=4 / Length=128 / StdCF=AESV2`，3 页 |
| `wrongPasswordCannotOpenThePdf` | 错误密码抛 `InvalidPasswordException` |
| `emptyPasswordMeansNoEncryption` | 「不设密码」确实不加密 |
| `permissionBitsAreWrittenButOwnerIdentityBypassesThem` | P 位写对，但 owner 身份使其不生效（有意固定该事实） |

### 设备测试的运行方式

**不要用 `connectedAndroidTest`**：AGP 给测试 APK 硬编码加 `-g`，MIUI/HyperOS
拒绝该 flag（`SecurityException: ...INSTALL_GRANT_RUNTIME_PERMISSIONS...`），
安装阶段就失败。这在该 ROM 上无解，`installOptions` 改不到 AGP 内部默认值。

改用本工程自带的任务（自己 `adb install` 不带 `-g`，再 `am instrument`）：

```powershell
.\gradlew.bat :app:runDeviceTests
.\gradlew.bat :app:runDeviceTests "-PclassFilter=<完全限定类名>"
```

排查过程与其它安装问题见 `docs/BUILD.md`。

详细排查与体积优化记录见 `docs/BUILD.md`。

## 验证命令

```powershell
cd D:\Project\HaruhiBox

# 开发机：208 个单元测试 + lint + 两种构建
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease

# 设备：instrumented test（迁移 + PDF 加密）
.\gradlew.bat :app:runDeviceTests
```

当前基线：**208 个单元测试 + 6 个真机用例全绿**，lint 0 error（27 条 warning 均为既有项），
debug / release APK 均可构建（4.4 MB → 5.6 MB，新增阅读器与任务页）。

### 测试清单（单元测试）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `feature.jm.data.JmRepositoryTest` | 22 | 入库字段一致性 / 收藏保留 / 章节来源优先级（含单话本子兜底与标题归一化）/ 标签 OR / 删除语义 |
| `feature.jm.ui.JmReaderAndTaskLogicTest` | 22 | 任务状态与进度文案 / 下载面板默认勾选与"已在队列" / **底栏切换方向** / 阅读器进度条与"待取 vs 失败" / 阅读偏好编解码 |
| `feature.jm.data.JmRecentAndFavoriteTest` | 19 | 最近浏览倒序 / 浏览时间语义 / 点详情不入库 / **骨架行不算本地数据** / 收藏夹数量与封面来源 Flow |
| `core.jm.JmCryptoGoldenTest` | 14 | 签名 / AES-256 解密 / 块数，与参考实现逐值一致 |
| `core.storage.FileNamingGoldenTest` | 14 | 文件名清洗 / 本子目录名 / 章节与图片排序 |
| `feature.jm.data.JmDownloadManagerTest` | 13 | **下载编排**：逐字节一致（块数 0 不重编码）/ 跳过已存在 / 断点只补缺口 / 等待网络 / 取消 / 追加章节 / 重试 |
| `core.database.JmDaoTest` | 13 | 书库分页排序（入库时间倒序）/ 标签 OR / 章节分组 / 收藏唯一索引 |
| `core.database.DownloadTaskDaoTest` | 12 | 任务去重 / 恢复 / 章节计数幂等 / 历史裁剪 |
| `core.download.FileDownloaderTest` | 12 | Range 续传 / 不支持 Range / 断流 / 416 / 原子发布 |
| `core.download.DownloadTaskRepositoryTest` | 11 | 杀进程恢复 / 按磁盘重算进度 / 章节状态不回退 / 取消不复活 |
| `feature.jm.ui.JmDisplayLogicTest` | 8 | 书库状态文案 / 章节状态 / 体积格式 / 空态与错误文案 |
| `feature.jm.data.JmImageStoreTest` | 7 | **本地优先取图**：命中不发请求 / 取回来就缓存 / 删掉后重新取 / 块数 0 原样保留 |
| `feature.jm.ui.FavoriteSortTest` | 7 | **收藏页排序**：默认夹置顶 / 三种方式都是降序 / 空夹排最后 / 同值按 id 稳定 |
| `feature.jm.data.JmReadingRepositoryTest` | 6 | 阅读位置读写与钳位 / "继续阅读"取最近一本 / 章节目录名规则 |
| `feature.jm.ui.SearchRevealStateTest` | 6 | 搜索增量展示的推进与边界 |
| `core.codec.DescramblerGoldenTest` | 5 | 解扰块布局 + 与参考实现逐像素一致 |
| `core.download.DownloadGateTest` | 5 | 下载网络策略矩阵（仅 Wi-Fi / 允许移动网络） |
| `core.codec.PdfWriterSpikeTest` | 4 | PDF 页面 / 书签 / `PageMode` / 不加密 |
| `core.jm.CryptoProbeTest` | 2 | 环境回归：JCE 可用性 |
| `core.codec.PixelProbeTest` | 2 | 环境回归：位图像素读写行为 |

### 设备测试（instrumented）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `core.codec.PdfEncryptionInstrumentedTest` | 4 | AES-128 / 错误密码 / 权限位事实 |

```powershell
.\gradlew.bat :app:runDeviceTests
```

> **界面交互测试的前置条件**：Compose 的测试框架会一直等窗口获得焦点，
> 因此设备必须**亮屏且已解锁**。熄屏状态下 `connectedAndroidTest` /
> `runDeviceTests` 会永久阻塞（不是失败，是挂住）。
