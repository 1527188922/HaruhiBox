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
| **下载引擎（Range 续传 + 并发 + 取消）** | ⬜ | 下一步 |
| **前台服务 + 常驻通知** | ⬜ | 依赖下载引擎 |
| **WorkManager 接入与进程重启恢复编排** | ⬜ | 依赖下载引擎 |
| 设置层单元测试（作用域隔离 / 继承 / 恢复默认） | ⬜ | |

### P1 验收（计划原文）

> 能下载一个任意大文件到私有目录，断网/杀进程后能续传，通知显示进度；
> **杀掉进程再启动，任务列表和进度能从数据库恢复（RUNNING 任务按磁盘现状重算进度后继续）**。

当前状态：任务的**数据层**恢复语义已实现并通过测试（`requeueInterrupted`、
章节计数器幂等）；**执行层**（下载引擎 / 前台服务 / WorkManager）尚未实现，
所以 P1 验收还不能打勾。

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

当前基线：**68 个单元测试全绿**，lint 0 error，debug APK 可安装。
