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

# PDF 加密等必须在设备上跑的用例（先确认 adb devices 有设备）
.\gradlew.bat :app:connectedDebugAndroidTest
```

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

## 安装失败排查

| 报错 | 原因 | 处理 |
|---|---|---|
| `Requested internal only, but not enough space` | 设备 `/data` 空间不足（本机实测可用 226 MB 时失败） | 清理设备存储，或用 4.4 MB 的 release 包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` | 已装的是另一种签名的包 | `adb uninstall com.haruhi.haruhibox` 后重装 |
| 安装成功但启动即退 | R8 规则问题，看 `adb logcat -d \| Select-String FATAL` | 见上面 `packageScope` 一节 |

查看设备剩余空间：

```powershell
adb shell df -h /data
```
