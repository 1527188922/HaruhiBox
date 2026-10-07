package com.haruhi.haruhibox.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PageMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.haruhi.haruhibox.core.storage.FileNaming
import timber.log.Timber
import java.io.File

/**
 * PDF 生成（JM 本子 → 单文件 PDF）。
 *
 * 行为对齐参考实现 `JmcomicService.albumToPdf` / `addImageToPdf`（第 674～744 行）：
 * 1. 每张图片一页，**页面尺寸 = 图片像素尺寸**；
 * 2. 章节目录按 `第N话` 序号排序，每章一个书签，指向该章第一页；
 * 3. 根书签标题 `目录`，`PageMode = USE_OUTLINES`；
 * 4. 加密用 `StandardProtectionPolicy`，权限 `canModify=false` /
 *    `canExtractContent=false` / `canExtractForAccessibility=false`；
 * 5. 图片用 `LosslessFactory` 内嵌（无损 Flate 压缩，文件偏大但与参考实现一致）。
 *
 * [password] 为 `null` 或空串时**不加任何保护**（对应用户点「不设密码」）。
 *
 * ## 与参考实现的三处**已确认**差异（2026-10-08 用户决策）
 *
 * 1. **加密算法固定为 AES-128**（参考实现实际是 40 位 RC4，见下）。
 *    参考实现只调了 `policy.setEncryptionKeyLength(128)` 而没设 `preferAES`。
 *    实测（桌面版 PDFBox 3.0.2 与 pdfbox-android 2.0.27.0 行为一致）：
 *    单独设 `encryptionKeyLength` **被忽略**，产出 `V=1 / R=3 / Length=40`，
 *    即 **40 位 RC4**；只有同时设 `preferAES = true` 才会得到
 *    `V=4 / R=4 / Length=128 / StdCF=AESV2`。
 *    用户已确认改用 AES-128 —— 这是**有意的安全强化**，不是与参考实现对齐。
 *    `PdfWriterSpikeTest` 把 `V/R/Length/StdCF` 四项锁成回归断言，
 *    因为漏设任一项都会静默降级、不报错。
 * 2. **访问控制只看"能否打开"**。参考实现把 owner 与 user 密码设成同一个值，
 *    导致打开文档时判定为 owner 身份，权限位被忽略（实测 `isOwnerPermission == true`、
 *    `canModify()` 返回 true）。用户已确认接受这个行为：
 *    「只需要管控能否打开/阅读 pdf 就行，能打开就不管其他权限」。
 *    因此这里仍然传同一个密码，权限位只是按参考实现写入、不作为安全承诺。
 *    `PdfWriterSpikeTest` 把这一事实固定成断言，避免以后误以为它生效。
 * 3. 参考实现用 `ImageIO.read`，这里用 `BitmapFactory`（Android 上没有 ImageIO/AWT）。
 */
class PdfWriter(
    private val bitmapFactory: (File) -> Bitmap? = { path ->
        BitmapFactory.decodeFile(path.absolutePath)
    },
) {

    /** 生成结果，用于给 UI 展示"已加密 / 未加密"以及页数。 */
    data class Result(
        val file: File,
        val pageCount: Int,
        val chapterCount: Int,
        val encrypted: Boolean,
    )

    /**
     * 把 [albumDir] 下的章节目录打成 PDF。
     *
     * @param albumDir 本子目录，直接子目录视为章节
     * @param out      输出文件（会被覆盖）
     * @param password 打开密码；`null`/空串表示不加密
     * @param onProgress 进度回调 `(已完成页数, 总页数, 当前章节名)`；总页数未知时为 -1
     */
    fun albumToPdf(
        albumDir: File,
        out: File,
        password: String?,
        onProgress: ((done: Int, total: Int, chapter: String) -> Unit)? = null,
    ): Result {
        val chapters = chapterDirs(albumDir)
        require(chapters.isNotEmpty()) { "本子目录下没有章节文件夹：${albumDir.absolutePath}" }

        val totalPages = chapters.sumOf { chapterImages(it).size }
        require(totalPages > 0) { "本子目录下没有图片：${albumDir.absolutePath}" }

        out.parentFile?.mkdirs()

        PDDocument().use { document ->
            applyProtection(document, password)

            val documentOutline = PDDocumentOutline()
            document.documentCatalog.documentOutline = documentOutline
            val rootOutline = PDOutlineItem().apply { title = "目录" }
            documentOutline.addLast(rootOutline)

            var donePages = 0
            for (chapterDir in chapters) {
                val images = chapterImages(chapterDir)
                if (images.isEmpty()) continue

                val chapterOutline = PDOutlineItem().apply { title = chapterDir.name }
                rootOutline.addLast(chapterOutline)

                val chapterStartPage = document.numberOfPages
                for (image in images) {
                    addImageToPdf(document, image)
                    donePages++
                    onProgress?.invoke(donePages, totalPages, chapterDir.name)
                }

                // 书签指向该章第一页（参考实现用 PDPageFitWidthDestination）
                val destination = PDPageFitWidthDestination()
                destination.page = document.getPage(chapterStartPage)
                chapterOutline.destination = destination
            }

            document.documentCatalog.pageMode = PageMode.USE_OUTLINES
            document.save(out)

            return Result(
                file = out,
                pageCount = document.numberOfPages,
                chapterCount = chapters.size,
                encrypted = !password.isNullOrEmpty(),
            )
        }
    }

    private fun applyProtection(document: PDDocument, password: String?) {
        if (password.isNullOrEmpty()) return

        val permission = AccessPermission().apply {
            // PDFBox 的 setter 是 setCanModify/setCanExtractContent/…，
            // Kotlin 的属性语法会解析成 getter，必须显式调用 setter。
            //
            // 注意：owner 与 user 用同一个密码时这些权限位**不会生效**
            // （打开者被判定为 owner）。用户已确认接受这一点，这里保留写入只是
            // 为了与参考实现产物一致，不构成安全承诺。见类注释第 2 条。
            setCanModify(false)
            setCanExtractContent(false)
            setCanExtractForAccessibility(false)
        }

        val policy = StandardProtectionPolicy(password, password, permission).apply {
            // ------------------------------------------------------------------
            // **两行都必须设**，缺一个都会静默降级（实测，见 docs/p0/spike2-pdf.md）：
            //   什么都不设            -> V=1 R=3 Length=40  StdCF=(none)  ← 40 位 RC4
            //   只设 encryptionKeyLength -> V=1 R=3 Length=40  StdCF=(none)  ← 长度被忽略
            //   只设 preferAES         -> V=1 R=3 Length=40  StdCF=(none)
            //   两个都设              -> V=4 R=4 Length=128 StdCF=AESV2   ← AES-128 ✅
            //
            // 参考实现（桌面版 PDFBox 3.0.2）只设了 encryptionKeyLength，
            // 也就是它**实际产出的是 40 位 RC4**。用户已确认本 App 改用 AES-128：
            // 这不与参考实现对产物的对齐，而是有意的安全强化。
            // ------------------------------------------------------------------
            encryptionKeyLength = AES_KEY_BITS
            isPreferAES = true
        }
        document.protect(policy)
    }

    private fun addImageToPdf(document: PDDocument, imageFile: File) {
        val bitmap = bitmapFactory(imageFile)
            ?: error("无法解码图片：${imageFile.absolutePath}")
        try {
            val image = LosslessFactory.createFromImage(document, bitmap)
            val page = PDPage(PDRectangle(image.width.toFloat(), image.height.toFloat()))
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, image.width.toFloat(), image.height.toFloat())
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 章节目录：`albumDir` 的直接子目录，按 `第N话` 序号升序。 */
    fun chapterDirs(albumDir: File): List<File> {
        val dirs = albumDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.sortedBy { FileNaming.extractChapterNumber(it.name) }
    }

    /** 章节图片：按去扩展名后的数字升序，只保留参考实现认得的图片扩展名。 */
    fun chapterImages(chapterDir: File): List<File> {
        val files = chapterDir.listFiles { file -> file.isFile && isImage(file.name) } ?: return emptyList()
        return files.sortedBy { FileNaming.extractImageNumber(FileNaming.baseName(it.name)) }
    }

    private fun isImage(fileName: String): Boolean =
        FileNaming.extension(fileName) in IMAGE_EXTENSIONS

    companion object {
        private val IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "gif", "bmp")

        /** 加密密钥长度。与参考实现一致（128 位）。 */
        const val AES_KEY_BITS = 128

        init {
            // pdfbox-android 要求显式初始化一次资源加载器
            runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(null) }
                .onFailure { Timber.w(it, "PDFBoxResourceLoader.init failed") }
        }
    }
}
