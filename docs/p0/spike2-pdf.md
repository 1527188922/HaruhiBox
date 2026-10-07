# P0 spike 2：JM PDF 生成方案选型

> 计划 §7 把这个 spike 定为「本任务最大难点」：必须先验证
> `com.tom-roush:pdfbox-android` 的**加密 + 书签 + 页面**三件套是否可用，
> 通过就走 B1，不通过就退到 B2（手写 PDF 写入器）。

## 结论

**采用 B1：`com.tom-roush:pdfbox-android:2.0.27.0`。**
三件套全部可用，不需要走 B2（手写 PDF 写入器，约 400～700 行）。

验收测试：`app/src/test/java/com/haruhi/haruhibox/core/codec/PdfWriterSpikeTest.kt`
实现：`app/src/main/java/com/haruhi/haruhibox/core/codec/PdfWriter.kt`

## 验证结果

| 能力 | 结果 | 验收方式 |
|---|---|---|
| 每张图一页，页面尺寸 = 图片像素尺寸 | ✅ | `doc.getPage(i).mediaBox` 逐页断言 |
| 章节书签（outline），根书签 `目录`，按 `第N话` 序号排序 | ✅ | 重新加载 PDF 遍历 outline，断言标题顺序 |
| `PageMode = USE_OUTLINES` | ✅ | `documentCatalog.pageMode.name == "USE_OUTLINES"` |
| 密码保护：错误密码打不开 | ✅ | `PDDocument.load(out, "9999")` 抛 `InvalidPasswordException` |
| 「不设密码」= 完全不加密 | ✅ | `doc.isEncrypted == false` |
| 权限位（不可修改 / 不可复制 / 不可无障碍提取） | ⚠️ 有效但有前提，见下 | 直接解析加密字典的 `P` 值并构造 `AccessPermission` |
| 密钥长度 | ⚠️ 与参考实现不同，见下 | 读加密字典 `Length` = 128 |

## 两个必须记录的发现（与参考实现行为不一致）

### 发现 1：`setEncryptionKeyLength` 在 Android 端口上不存在

参考实现（`JmcomicService` 第 692 行）调用：

```java
policy.setEncryptionKeyLength(128);
```

`pdfbox-android 2.0.27.0` 的 `StandardProtectionPolicy` **没有这个方法**
（已用反编译确认它的方法只有 `getPermissions/setPermissions/getOwnerPassword/
setOwnerPassword/getUserPassword/setUserPassword`）。也就是说这行在 Android 上
无法照抄，实际生效的是 `StandardSecurityHandler` 的默认值。

**实测结果**：`V=2`、`R=3`、`Length=128`，即 **RC4-128**，不是 AES-128。

> 计划 §D.1 已经警告过「源码只设置密钥长度，未显式 preferAES，不能据此认定 AES-128」。
> 本次实测证实了这一点，并且更进一步：Android 端口连"设置密钥长度"这个 API 都没有。

**P4 待决策项（需要用户确认，属于行为差异）**：

- 选项 A：保持现状（RC4-128），与参考实现最接近，实现成本最低；
- 选项 B：显式改用 AES-128（需要拿 `StandardSecurityHandler` 配置 crypt filter，
  或改用手写安全字典），安全强度更高但与参考实现产物不同。

**这不阻塞 P4 开工**：两种选项都只需要改 `PdfWriter.applyProtection` 一处。

### 发现 2：owner/user 用同一个密码会让"不可修改/不可复制"在阅读器里失效

参考实现把两个密码设成同一个值：

```java
new StandardProtectionPolicy(password, password, permission)
```

后果：打开文档时用的是 **owner 身份**，符合规范的阅读器会忽略权限位。
实测 `doc.currentAccessPermission.isOwnerPermission == true`，
`canModify()` / `canExtractContent()` 全部返回 `true`。

但加密字典里的 `P` 值（实测 `-540`）**是正确的**：其中
"修改"、"复制/提取"、"无障碍提取" 三位都是关闭的。也就是说
**权限位写对了，只是对持有 owner 密码的人不生效**。

**P4 待决策项（需要用户确认）**：

- 选项 A：照抄参考实现（owner = user = 导出密码）。用户在 App 里输同一个密码
  就能打开，但读到 PDF 的任何人都能修改/复制。
- 选项 B：owner 用一个内部随机密码、user 用用户输入的密码并保存 owner 密码，
  真正做到"只能看不能改"。代价是要多存一份 owner 密码，且与参考实现产物不同。

P0 按计划"先照抄对齐"，实现保持选项 A，并在测试里把事实固定下来。

## 其它实现约束（P4 用）

1. **WebP 不能直接内嵌 PDF**，必须解码后重新编码：`PdfWriter` 走
   `LosslessFactory.createFromImage(document, bitmap)`，即无损 Flate 内嵌，
   与参考实现一致（文件会明显偏大）。
2. **PDF 单页尺寸上限 14400×14400**：JM 长图接近上限时要处理，
   参考实现没有处理，P4 需要加校验并给出明确错误。
3. **必须流式写**：`PdfWriter` 直接 `document.save(out)`，不在内存里拼 ByteArray；
   P4 要把生成放进前台服务，并支持取消（取消时删除半成品）。
4. **`BitmapFactory` 依赖真实解码器**：P0 的测试用 PNG 字节流配 `.webp` 后缀，
   只验证 PDF 能力；真机上的真实 WebP 解码是 P4 的验收项。

## 复现方式

```powershell
cd D:\Project\HaruhiBox
.\gradlew.bat :app:testDebugUnitTest --tests "com.haruhi.haruhibox.core.codec.PdfWriterSpikeTest"
```

测试用 Robolectric 运行（PDFBox 依赖 `android.graphics.Bitmap`）。
