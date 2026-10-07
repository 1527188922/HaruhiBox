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
| **P1 基础设施** | 🚧 进行中 | 见下表 |
| P2 JM 数据与浏览 | ⬜ 未开始 | |
| P3 JM 下载与阅读闭环 | ⬜ 未开始 | |
| P4 JM 导出 zip / pdf | ⬜ 未开始 | PDF 能力已在 P0 spike 验证 |
| P5 B站 | ⬜ 未开始 | |
| P6 壳与体验 | ⬜ 未开始 | |
| P7 验收与真机 | ⬜ 未开始 | 本机无已连接设备，需用户提供 |

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
| **前台服务 + 常驻通知** | ⬜ | 下一步 |
| **WorkManager 接入与进程重启恢复编排** | ⬜ | 依赖前台服务 |
| 设置层单元测试（作用域隔离 / 继承 / 恢复默认） | ⬜ | |
| 设置校验工具（写入前校验，拒绝非法代理等） | ⬜ | |

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

当前状态：
- 「下载任意大文件到私有目录 + 断点续传 + 不完整文件不发布」✅ 已实现并有测试；
- 「杀进程后任务表恢复」的数据层语义 ✅（`requeueInterrupted` + 章节计数幂等）；
- **还差执行层**：前台服务/通知、WorkManager 编排、以及"启动时按磁盘重算进度"的仓库层胶水。
  所以 P1 验收仍不能整体打勾。

## 已知问题 / 待用户确认

1. **PDF 加密强度**：参考实现想设 128 位密钥并暗示 AES，但 `pdfbox-android`
   没有 `setEncryptionKeyLength`，实测产物是 `R=3 / RC4-128`。保持对齐还是改 AES-128？
   详见 `docs/p0/spike2-pdf.md`。
2. **PDF 权限对 owner 无效**：参考实现 owner 与 user 同密码，导致"不可修改/不可复制"
   对打开者不生效。是否拆分 owner/user 密码？
3. **真机验收**：本机 `adb devices` 为空，P7 需要用户提供设备或模拟器。
4. **JM 图片线路默认值**：参考实现硬编码 `IMAGE_DOMAIN`，App 侧作为可编辑线路列出，
   默认值沿用 `cdn-msp2.jmapiproxy2.cc`（见 `JmDefaults`）。

## 验证命令

```powershell
cd D:\Project\HaruhiBox
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

当前基线：**79 个单元测试全绿**，lint 0 error，debug APK 可构建。

### 测试清单

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `core.jm.JmCryptoGoldenTest` | 14 | 签名 / AES-256 解密 / 块数，与参考实现逐值一致 |
| `core.storage.FileNamingGoldenTest` | 14 | 文件名清洗 / 本子目录名 / 章节与图片排序 |
| `core.database.JmDaoTest` | 12 | 书库分页排序筛选 / 章节分组 / 收藏唯一索引与删除语义 |
| `core.database.DownloadTaskDaoTest` | 12 | 任务去重 / 恢复 / 章节计数幂等 / 历史裁剪 |
| `core.download.FileDownloaderTest` | 12 | Range 续传 / 不支持 Range / 断流 / 416 / 原子发布 |
| `core.codec.DescramblerGoldenTest` | 5 | 解扰块布局 + 与参考实现逐像素一致 |
| `core.codec.PdfWriterSpikeTest` | 6 | PDF 页面 / 书签 / 加密 / 权限位 / 不加密 |
| `core.jm.CryptoProbeTest` | 2 | 环境回归：JCE 可用性 |
| `core.codec.PixelProbeTest` | 2 | 环境回归：位图像素读写行为 |
