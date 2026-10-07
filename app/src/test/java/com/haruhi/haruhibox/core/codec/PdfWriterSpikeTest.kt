package com.haruhi.haruhibox.core.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0 spike 2：用 `com.tom-roush:pdfbox-android` 生成 PDF 的可行性验证。
 *
 * 需要验证的三件事（计划 §7 的 B1 方案）：
 * 1. **加密**：`StandardProtectionPolicy` 在 Android 端口上可用，且错误密码打不开；
 * 2. **书签**：章节目录（outline）能写入并被重新解析出来；
 * 3. **页面**：每张图一页、页面尺寸 = 图片像素尺寸。
 *
 * 这里用 Robolectric 跑，因为 PDFBox 依赖 `android.graphics.Bitmap`；
 * 真机验证是 P4 的验收项，P0 只确认"能力存在且行为正确"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfWriterSpikeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = PdfWriter()

    @Test
    fun `album is converted to one page per image with pixel sized pages`() {
        val albumDir = buildAlbum(
            "第1话" to listOf("00001" to (40 to 100), "00002" to (40 to 120)),
            "第2话" to listOf("00001" to (30 to 80)),
        )
        val out = File(temp.root, "album.pdf")

        val result = writer.albumToPdf(albumDir, out, password = null)

        assertThat(out.exists()).isTrue()
        assertThat(result.pageCount).isEqualTo(3)
        assertThat(result.chapterCount).isEqualTo(2)
        assertThat(result.encrypted).isFalse()

        PDDocument.load(out).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(3)
            // 页面尺寸 = 图片像素尺寸（参考实现用 PDRectangle(image.getWidth(), image.getHeight())）
            assertThat(doc.getPage(0).mediaBox.width).isEqualTo(40f)
            assertThat(doc.getPage(0).mediaBox.height).isEqualTo(100f)
            assertThat(doc.getPage(2).mediaBox.width).isEqualTo(30f)
            assertThat(doc.getPage(2).mediaBox.height).isEqualTo(80f)
        }
    }

    @Test
    fun `chapter bookmarks are written in chapter order with a root outline`() {
        val albumDir = buildAlbum(
            // 故意乱序放，验证按 "第N话" 序号排序
            "第10话" to listOf("00001" to (20 to 20)),
            "第2话" to listOf("00001" to (20 to 20)),
            "第1话" to listOf("00001" to (20 to 20)),
        )
        val out = File(temp.root, "bookmarks.pdf")

        writer.albumToPdf(albumDir, out, password = null)

        PDDocument.load(out).use { doc ->
            val outline = doc.documentCatalog.documentOutline
            assertThat(outline).isNotNull()

            val root = outline.firstChild
            assertThat(root.title).isEqualTo("目录")

            val chapterTitles = mutableListOf<String>()
            var child = root.firstChild
            while (child != null) {
                chapterTitles += child.title
                child = child.nextSibling
            }
            assertThat(chapterTitles).containsExactly("第1话", "第2话", "第10话").inOrder()
        }
    }

    @Test
    fun `password protected album cannot be opened with a wrong password`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "protected.pdf")

        val result = writer.albumToPdf(albumDir, out, password = "1234")
        assertThat(result.encrypted).isTrue()

        // 正确密码 + 空 owner 密码都能打开
        PDDocument.load(out, "1234").use { doc ->
            assertThat(doc.isEncrypted).isTrue()
            assertThat(doc.numberOfPages).isEqualTo(1)
        }

        // 错误密码必须失败
        var wrongPasswordRejected = false
        try {
            PDDocument.load(out, "9999").close()
        } catch (_: InvalidPasswordException) {
            wrongPasswordRejected = true
        }
        assertThat(wrongPasswordRejected).isTrue()
    }

    @Test
    fun `protection sets the read only permission bits`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "permissions.pdf")

        writer.albumToPdf(albumDir, out, password = "1234")

        PDDocument.load(out, "1234").use { doc ->
            val encryption = doc.encryption ?: error("文档没有加密字典")

            // ------------------------------------------------------------------
            // P0 spike 2 的两个重要发现（详见 docs/p0/spike2-pdf.md）：
            //
            // 1. pdfbox-android 2.0.27.0 的 `StandardProtectionPolicy` **没有**
            //    `setEncryptionKeyLength`。参考实现调的 `policy.setEncryptionKeyLength(128)`
            //    在 Android 端口上根本不存在，实际生效的是 StandardSecurityHandler 默认值。
            //    → 这里读实际加密字典作为 P4 的验收基线。
            //
            // 2. **权限位只对 user 密码生效**。参考实现把 owner/user 密码都设成同一个值
            //    （`new StandardProtectionPolicy(password, password, permission)`），
            //    于是打开文档时用的是 owner 身份，PDFBox 的 `canModify()` 一律返回 true，
            //    "不可修改/不可复制"在阅读器里等于没生效（P 位本身是对的）。
            //    → 真要限制阅读者，必须区分 owner/user 两个密码；这属于**行为差异**，
            //      需要用户确认后再改，P0 先照抄参考实现并把事实记录下来。
            // ------------------------------------------------------------------
            assertThat(readEncryptionKeyLength(encryption)).isEqualTo(128)

            val pValue = (encryption.getCOSObject().getItem("P") as COSNumber).intValue()
            val permission = AccessPermission(pValue)

            // P 位本身与参考实现一致：只关了"修改/复制/无障碍提取"三项，
            // 其余权限沿用 PDFBox 的默认值（加注、填表、打印仍然允许）。
            assertThat(permission.canModify()).isFalse()
            assertThat(permission.canExtractContent()).isFalse()
            assertThat(permission.canExtractForAccessibility()).isFalse()
            assertThat(permission.canPrint()).isTrue()

            // 用 owner 身份打开时 PDFBox 一律放行——这正是上面第 2 点的表现
            assertThat(doc.currentAccessPermission.isOwnerPermission).isTrue()
        }
    }

    private fun readEncryptionKeyLength(encryption: com.tom_roush.pdfbox.pdmodel.encryption.PDEncryption): Int =
        try {
            encryption.javaClass.getMethod("getLength").invoke(encryption) as Int
        } catch (_: NoSuchMethodException) {
            error("PDEncryption 上没有可用的长度 getter；请更新本测试以匹配 pdfbox-android 版本")
        }

    @Test
    fun `empty password means no protection at all`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "plain.pdf")

        val result = writer.albumToPdf(albumDir, out, password = "")

        assertThat(result.encrypted).isFalse()
        PDDocument.load(out).use { doc ->
            assertThat(doc.isEncrypted).isFalse()
        }
    }

    @Test
    fun `page mode is use outlines`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "pagemode.pdf")

        writer.albumToPdf(albumDir, out, password = null)

        PDDocument.load(out).use { doc ->
            assertThat(doc.documentCatalog.pageMode.name).isEqualTo("USE_OUTLINES")
        }
    }

    // ------------------------------------------------------------------

    /** 构造一个本子目录：`第N话/00001.webp` …，图片是纯色 PNG（内容不重要，尺寸才重要）。 */
    private fun buildAlbum(vararg chapters: Pair<String, List<Pair<String, Pair<Int, Int>>>>): File {
        val albumDir = temp.newFolder("album")
        var hue = 0
        for ((chapterName, images) in chapters) {
            val chapterDir = File(albumDir, chapterName).apply { mkdirs() }
            for ((imageName, size) in images) {
                writePng(File(chapterDir, "$imageName.webp"), size.first, size.second, hue++)
            }
        }
        return albumDir
    }

    /**
     * 写一个**PNG 字节流但用 .webp 后缀**的文件。
     * 理由：P0 只验证 PDF 能力，`BitmapFactory` 在 Robolectric 下对 WebP 支持有限；
     * 而 `PdfWriter` 只按扩展名判断"是不是图片"，解码交给 `BitmapFactory`，
     * 真机上的真实 WebP 解码是 P4 的验收项。
     */
    private fun writePng(file: File, width: Int, height: Int, hue: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40 + hue * 20, 80, 120))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
