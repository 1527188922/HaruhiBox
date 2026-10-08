# 构建与安装

## 快速命令

```powershell
cd D:\Project\HaruhiBox

# 日常验证（提交前必跑）
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# 装到真机（debug 包）
.\gradlew.bat :app:installDebug

# 生成可侧载安装的 release 包
.\gradlew.bat :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 直接安装 release 包
adb install -r app\build\outputs\apk\release\app-release.apk

# 设备上的 instrumented test（PDF 加密等）
.\gradlew.bat :app:runDeviceTests
# 只跑某个测试类
.\gradlew.bat :app:runDeviceTests "-PclassFilter=com.haruhi.haruhibox.core.codec.PdfEncryptionInstrumentedTest"
```

> **不要用 `:app:connectedDebugAndroidTest`**（在小米机型上）：
> AGP 安装测试 APK 时硬编码带 `-g`，MIUI / HyperOS 会拒绝，详见
> 「小米机型上跑设备测试」一节。`:app:runDeviceTests` 是本工程自带的替代品。

## 构建环境（P0 固定，见 `docs/p0/README.md`）

| 项 | 值 |
|---|---|
| 构建 JDK | 本机 JDK 21，由 `gradle.properties` 的 `org.gradle.java.home` 指定 |
| Gradle / AGP / Kotlin | 9.6.0 / 9.4.0 / 2.2.10（AGP 内置） |
| compileSdk / minSdk / targetSdk | 37 / 26 / 37 |

> `gradle.properties` 里的 `org.gradle.java.home` 是本机绝对路径。
> CI 上由 workflow 里的 `sed` 删掉这一行，改用 `JAVA_HOME`。

## 两种包可以同时安装

| | debug | release |
|---|---|---|
| applicationId | `com.haruhi.haruhibox.debug` | `com.haruhi.haruhibox` |
| 体积 | ~33 MB | ~4.4 MB |
| 混淆/裁剪 | 关闭 | R8 minify + resource shrink |
| 私有目录 | 各自的 `filesDir`（applicationId 不同，天然隔离） | 同左 |
| 签名 | Android 调试密钥 | `haruhibox-release.jks`（本地） |

因为 applicationId 不同，**两个包可以同时装在一台设备上、互不覆盖**，
不需要每次切换都卸载。代价是桌面出现两个图标、数据不互通。

> 历史：`applicationIdSuffix = ".debug"` 是 2026-10-08 加的。
> 在那之前两者同名，切换构建类型必须 `adb uninstall`，会清掉 App 数据。

## release 签名

`app/build.gradle.kts` 会读工程根目录的 `keystore.properties`：

```properties
storeFile=haruhibox-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

- 文件与 `*.jks` 都在 `.gitignore` 里，**不会进版本库**；
- 没有这个文件时 release 包**不签名**（CI 与只跑单测不受影响）；
- **务必备份 keystore**：丢了就无法对已安装的应用做覆盖升级。

重新生成一个本地 keystore：

```powershell
& "D:\Environment\jdk-21.0.4\bin\keytool.exe" -genkeypair -v `
  -keystore haruhibox-release.jks -alias haruhibox `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <口令> -keypass <口令> `
  -dname "CN=HaruhiBox, OU=Personal, O=HaruhiBox, L=Local, ST=Local, C=CN"
```

## 体积优化记录（33 MB → 4.4 MB）

安装时遇到的 `INSTALL_FAILED_INSUFFICIENT_STORAGE`（"Requested internal only,
but not enough space"）直接原因是**设备存储满**，但 APK 也确实虚胖。清理项：

| 措施 | 效果 |
|---|---|
| release 开启 R8 minify + resource shrink | 主要收益来源 |
| 排除 `org/bouncycastle/pqc/**`（后量子密码查找表，纯数据文件，R8 不删） | 约 −8 MB（未压缩） |
| 不再引入 `material-icons-extended` | 该模块含几千个矢量图标 |
| 移除 AGP 9 的 `optimization.packageScope`（见下） | 7.5 MB → 4.4 MB，且修掉启动崩溃 |

### ⚠ 不要配 `optimization { packageScope = ... }`

脚手架里原本有：

```kotlin
optimization {
    enable = true
    packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
}
```

开启后 release 包**启动即闪退**：

```
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider:
  androidx.startup.StartupException: java.lang.IllegalAccessError:
  Class kotlin.text.k extended by class v02 is inaccessible
```

原因：`packageScope` 让 R8 把这些包**重打包**到别的包名。Kotlin 的 `internal`
在字节码里是 `public`，R8 按"只在包内使用"的判断把它们收窄成包私有；
重打包改变了"包内"的边界，于是任何继承者（包括 R8 自己合成的类 `v02`）
在运行期就撞上 `IllegalAccessError`。

**结论**：本工程不使用 `packageScope`。它既导致崩溃，又让包更大。
`app/proguard-rules.pro` 里也不要加 `-repackageclasses`。

### 排除 BouncyCastle 后量子密码表是安全的吗？

是的。`pdfbox-android` → `bcprov-jdk15to18`，PDFBox 只用它做标准 CMS/证书解析。
`org/bouncycastle/pqc/**` 是后量子算法（Picnic/BIKE/SIKE）的参数表，
与 PDF 加密（RC4/AES + 标准安全字典）无关。
排掉的是**数据文件**，不是把某个安全功能关掉。

## 小米机型上跑设备测试

`connectedAndroidTest` 在 MIUI / HyperOS 上**永远失败在安装阶段**。用 `--info` 能看到
AGP 实际执行的命令：

```
Installing with command: adb install --bypass-low-target-sdk-block -t              --user 0 app-debug.apk
Installing with command: adb install --bypass-low-target-sdk-block -t -g --user 0  app-debug-androidTest.apk
```

**测试 APK 那一条多了 `-g`**（一次性授予全部运行时权限）。MIUI 拒绝它：

```
java.lang.SecurityException: You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
  permission to use the PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag
  at com.android.server.pm.PackageInstallerService.createSessionInternal
```

原因：MIUI 要求调用方持有 `INSTALL_GRANT_RUNTIME_PERMISSIONS` 才允许带该 flag，
而普通 shell 只有 `INSTALL_PACKAGES`。AOSP / Pixel 没有这个限制，
所以同一份配置在别的机器上 `connectedAndroidTest` 是正常的。

`-g` 是 AGP 测试引擎内部的默认值，不是 DSL 的 `installOptions`，
所以 `testOptions.installation { installOptions.removeAll { it == "-g" } }` **改不到它**
（已实测确认），`app/build.gradle.kts` 里那段配置留作记录。

### 替代方案：`:app:runDeviceTests`

本工程自带一个任务，自己 `adb install`（不带 `-g`）再 `am instrument`：

```powershell
.\gradlew.bat :app:runDeviceTests
.\gradlew.bat :app:runDeviceTests "-PclassFilter=<完全限定类名>"
```

实现：`app/build.gradle.kts` 的 `runDeviceTests` + `tools/device-tests/run-instrumented-tests.ps1`。

两个实现细节：

- **`am instrument` 有用例失败时退出码仍是 0**，所以脚本从输出里找
  `FAILURES!!!` / `INSTRUMENTATION_FAILED` / `OK (N tests)` 来判断成败；
- 脚本文件**必须带 UTF-8 BOM**：Windows PowerShell 5.1 会把无 BOM 的 UTF-8
  按 ANSI 读，中文注释会乱码并直接导致语法解析失败。

## 安装失败排查

| 报错 | 原因 | 处理 |
|---|---|---|
| `Requested internal only, but not enough space` | 设备 `/data` 空间不足（实测可用 226 MB 时失败） | 清理设备存储，或用 4.4 MB 的 release 包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` | 已装的是另一种签名的包 | `adb uninstall <包名>` 后重装 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（切换到 `.debug` 之前装的旧包） | 旧包 applicationId 没有 `.debug` 后缀 | `adb uninstall com.haruhi.haruhibox` |
| `SecurityException: ...INSTALL_GRANT_RUNTIME_PERMISSIONS...` | 小米机型 + `adb install -g` | 见上一节 |
| 安装成功但启动即退 | R8 规则问题，看 `adb logcat -d \| Select-String FATAL` | 见 `packageScope` 一节 |

查看设备剩余空间：

```powershell
adb shell df -h /data
```
