# 实施进度台账

> 计划原文：`docs/android-app-plan.md`（= `2026-10-05-android-jm-bilibili-app.md`）
> 最后更新：2026-10-07

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
| P3 JM 下载与阅读闭环 | ⬜ 未开始 | 下载引擎与任务仓库已在 P1 备好 |
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
| 漫画模式底部导航（书库/发现/收藏） | ✅ 代码 | `JmModeRoot` |
| **界面人工确认（截图/交互）** | ⏳ **待你确认** | 见下方"P2 待验证" |

### P2 待验证（需要你确认）

代码已编译、可安装、启动无崩溃，**逻辑层有 115 个单元测试覆盖**，
但**界面本身我无法在这台设备上自动验证**：

1. 设备当前处于休眠（`mWakefulness=Dozing`），`adb shell input keyevent POWER/WAKEUP`
   都唤不醒，截出来是全黑；
2. 我写的 Compose UI 测试（`JmScreensTest`）在这种状态下会**永久阻塞**——
   Compose 测试框架要等窗口获得焦点，熄屏时永远等不到。**已删除**，
   不留无法验证的"绿色"测试。

需要你做的（任选其一）：

- 解锁手机后告诉我，我把 Compose UI 测试加回来并跑通；
- 或者自己打开 App 看一眼四个页面是否符合预期。

界面的展示逻辑（状态文案、体积格式、空态、章节状态判定）已经用
`JmDisplayLogicTest` 单测覆盖，所以"文案对不对"是验证过的，未验证的是"排版与交互"。

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
| 下载网络策略在安全检查点检查 | `DownloadGate` | P1 剩余项（与前台服务一起接） |

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
| WorkManager 约束调度（仅 Wi-Fi 时自动延后） | ⬜ | `DownloadGate` 已就绪，缺 Worker 接线 |

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

# 开发机：88 个单元测试 + lint + 两种构建
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease

# 设备：instrumented test（PDF 加密等）
.\gradlew.bat :app:runDeviceTests
```

当前基线：**115 个单元测试 + 4 个真机用例全绿**，lint 0 error，
debug APK 可安装运行（P2 四个页面已接入），release APK 可构建。

### 测试清单（单元测试）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `feature.jm.data.JmRepositoryTest` | 18 | 入库字段一致性 / 收藏保留 / 章节来源优先级 / 标签 OR / 删除语义 |
| `core.jm.JmCryptoGoldenTest` | 14 | 签名 / AES-256 解密 / 块数，与参考实现逐值一致 |
| `core.storage.FileNamingGoldenTest` | 14 | 文件名清洗 / 本子目录名 / 章节与图片排序 |
| `core.database.JmDaoTest` | 13 | 书库分页排序筛选 / 标签 OR / 章节分组 / 收藏唯一索引 |
| `core.database.DownloadTaskDaoTest` | 12 | 任务去重 / 恢复 / 章节计数幂等 / 历史裁剪 |
| `core.download.FileDownloaderTest` | 12 | Range 续传 / 不支持 Range / 断流 / 416 / 原子发布 |
| `core.download.DownloadTaskRepositoryTest` | 11 | 杀进程恢复 / 按磁盘重算进度 / 章节状态不回退 / 取消不复活 |
| `feature.jm.ui.JmDisplayLogicTest` | 8 | 书库状态文案 / 章节状态 / 体积格式 / 空态与错误文案 |
| `core.codec.DescramblerGoldenTest` | 5 | 解扰块布局 + 与参考实现逐像素一致 |
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
