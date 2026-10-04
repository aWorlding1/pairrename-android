package com.yuanbao.pairrename.data

import android.content.Context
import android.provider.MediaStore
import com.yuanbao.pairrename.model.ImageItem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 需要「管理所有文件」权限才能做的**写操作**与全盘分析。
 *
 * 全部走 File API，因为 SAF 无法在目录间移动文件、也无法建目录后再写回
 * MediaStore。这些能力只有拿到真实路径才有。
 */
object Organizer {

    /** 一次移动。 */
    data class MoveRow(
        val name: String,
        val fromPath: String,
        val toPath: String,
        val takenAt: Long,
    )

    data class ArchiveResult(
        val moved: Int,
        val failed: Int,
        /**
         * **真正移动成功**的行。
         *
         * 撤销步骤只能由它构造。以前撤销步骤用的是「计划里的全部行」，
         * 于是源文件已丢失、或目标已被人占住而根本没动的那些行也进了撤销栈 ——
         * 点撤销时会把占着那个位置的文件移走，用户凭空丢一张图。
         */
        val movedRows: List<MoveRow> = emptyList(),
        /** 目标已存在同名文件、为不覆盖而跳过（原文件原样保留）的行数。 */
        val skipped: Int = 0,
    )

    /** 日期目录的默认格式。 */
    const val DEFAULT_PATTERN = "yyyy-MM"

    /**
     * 生成归档计划（纯函数，不碰磁盘）。
     *
     * 先预览后执行是这个项目一贯的原则 —— 移动文件比改名更难撤销，
     * 所以必须让用户先看清「哪些会动、动到哪」。
     *
     * @param pattern 日期目录格式，如 `yyyy-MM`、`yyyy/MM`。
     * @param intoDateDir 目标是否建在「文件当前所在目录」下的日期子目录。
     */
    fun planArchive(
        items: List<ImageItem>,
        pattern: String = DEFAULT_PATTERN,
    ): List<MoveRow> {
        val fmt = runCatching { SimpleDateFormat(pattern, Locale.US) }
            .getOrElse { SimpleDateFormat(DEFAULT_PATTERN, Locale.US) }
        val rows = ArrayList<MoveRow>()
        items.forEach { item ->
            val src = item.path?.takeIf { it.isNotBlank() } ?: return@forEach
            if (item.takenAt <= 0) return@forEach
            val srcFile = File(src)
            val parent = srcFile.parentFile ?: return@forEach
            val dirName = fmt.format(java.util.Date(item.takenAt))
            // 幂等：已经在目标日期目录里就不再动。
            // 否则重复点归档会变成 2024-03/2024-03/… 无限嵌套。
            //
            // 判断用的是「父目录路径以 dirName 结尾」，而不是 `parent.name == dirName`。
            // 后者只认单层格式：pattern 一旦写成 `yyyy/MM`（dirName = "2024/03"），
            // 目录名永远不可能等于 "2024/03"，嵌套照样发生。
            // 参数既然开放给调用方，就不能只在默认值下正确。
            if (parent.path.replace('\\', '/').endsWith("/" + dirName.replace('\\', '/'))) {
                return@forEach
            }
            val dst = File(File(parent, dirName), srcFile.name)
            if (dst.absolutePath == srcFile.absolutePath) return@forEach
            rows += MoveRow(srcFile.name, srcFile.absolutePath, dst.absolutePath, item.takenAt)
        }
        return rows
    }

    /**
     * 执行归档。同分区 rename 是瞬时的；跨存储退化为拷贝+删除，
     * **拷贝失败会保留原文件**，不会丢。
     */
    fun executeArchive(rows: List<MoveRow>): ArchiveResult {
        var moved = 0
        var failed = 0
        var skipped = 0
        val done = ArrayList<MoveRow>()
        rows.forEach { row ->
            val src = File(row.fromPath)
            val dst = File(row.toPath)
            if (!src.exists()) {
                failed++
                return@forEach
            }
            // 同一路径：已经在目标位置，无事可做。既不移动也不报失败。
            if (dst.absolutePath == src.absolutePath) return@forEach
            // 目标已被别的文件占着 —— **不覆盖**。
            // 原来这里没有任何判断：renameTo 在 Linux 上会静默替换目标、
            // 跨存储的 copyTo 更是直接截断写入，归档一次就吃掉一张图，
            // 而且失败计数是 0，界面还会报"已归档成功"。
            if (dst.exists()) {
                skipped++
                return@forEach
            }
            when (moveNoReplace(src, dst)) {
                MoveOutcome.OK -> {
                    moved++
                    done += row
                }
                MoveOutcome.OCCUPIED -> skipped++
                MoveOutcome.MISSING, MoveOutcome.FAILED -> failed++
            }
        }
        return ArchiveResult(moved, failed, done, skipped)
    }

    /**
     * 把执行结果转成可撤销的移动步骤。
     *
     * 参数**故意**收 ArchiveResult 而不是 `rows`：只要签名还允许把
     * 「计划里的全部行」传进来，就一定会有人这么传，撤销步骤里就会混进
     * 没真正移动的行。换成执行结果之后，这个错误在调用点直接写不出来。
     */
    fun toMoveSteps(result: ArchiveResult): List<com.yuanbao.pairrename.model.MoveStep> =
        result.movedRows.map { com.yuanbao.pairrename.model.MoveStep(it.fromPath, it.toPath) }

    /**
     * 单次移动的结果。**失败原因必须能区分**：
     * 「目标被占」用户有动作可做（把那个同名文件改名或移走），
     * 「源没了」没有（文件已经不在那了）。两种混在同一个"失败"里，
     * 用户拿着一句"撤销失败"不知道该去处理哪一个。
     */
    enum class MoveOutcome { OK, OCCUPIED, MISSING, FAILED }

    /**
     * 移动单个文件（重做用）。
     */
    fun moveFile(fromPath: String, toPath: String): MoveOutcome =
        moveOne(File(fromPath), File(toPath))

    /** 撤销：把文件从 toPath 移回 fromPath。 */
    fun moveBack(toPath: String, fromPath: String): MoveOutcome = moveFile(toPath, fromPath)

    private fun moveOne(srcFile: File, dst: File): MoveOutcome {
        if (!srcFile.exists()) return MoveOutcome.MISSING
        // 同一路径 = 已经在位，直接算成功（幂等，撤销连点也不会出错）
        if (dst.absolutePath == srcFile.absolutePath) return MoveOutcome.OK
        return moveNoReplace(srcFile, dst)
    }

    /** 不覆盖目标的移动；跨卷时使用独占目标创建 + 完整复制 + 确认删源。 */
    private fun moveNoReplace(src: File, dst: File): MoveOutcome {
        if (!src.isFile) return MoveOutcome.MISSING
        if (dst.exists()) return MoveOutcome.OCCUPIED
        val parent = dst.parentFile
        if (parent != null && !parent.exists() && !runCatching { parent.mkdirs() }.getOrDefault(false)) {
            return MoveOutcome.FAILED
        }
        if (parent != null && !parent.isDirectory) return MoveOutcome.FAILED
        if (runCatching { Files.move(src.toPath(), dst.toPath()) }.isSuccess) return MoveOutcome.OK
        if (dst.exists()) return MoveOutcome.OCCUPIED

        val expectedSize = src.length()
        if (!runCatching { dst.createNewFile() }.getOrDefault(false)) {
            return if (dst.exists()) MoveOutcome.OCCUPIED else MoveOutcome.FAILED
        }
        val copied = runCatching {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (dst.length() != expectedSize) error("Copied file length mismatch")
        }.isSuccess
        if (!copied) {
            runCatching { dst.delete() }
            return MoveOutcome.FAILED
        }
        if (!runCatching { src.delete() }.getOrDefault(false)) {
            // 源没删掉时优先恢复原状态；若目标清理也失败则留下两份而非丢唯一副本。
            runCatching { dst.delete() }
            return MoveOutcome.FAILED
        }
        return MoveOutcome.OK
    }

    // ---------------- 大文件分析 ----------------

    data class BigFile(
        val name: String,
        val path: String,
        val size: Long,
        val id: Long,
    ) {
        val uri: android.net.Uri get() =
            android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
            )
    }

    /**
     * 全盘找出体积最大的图片。
     * 走 MediaStore 索引按体积倒序，不需要读任何文件内容。
     */
    fun findBigFiles(context: Context, limit: Int = 30): List<BigFile> {
        if (!MediaStoreMeta.hasAllFilesAccess()) return emptyList()
        val out = ArrayList<BigFile>(limit)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                "${MediaStore.Images.Media.SIZE} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iData = c.getColumnIndex(MediaStore.Images.Media.DATA)
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val path = if (iData >= 0) c.getString(iData).orEmpty() else ""
                    out += BigFile(
                        name = c.getString(iName) ?: continue,
                        path = path,
                        size = c.getLong(iSize),
                        id = c.getLong(iId),
                    )
                    n++
                }
            }
        }
        return out
    }

    /** 全盘图片总占用与总数。 */
    data class StorageSummary(val count: Int, val bytes: Long)

    fun summary(context: Context): StorageSummary {
        if (!MediaStoreMeta.hasAllFilesAccess()) return StorageSummary(0, 0)
        var count = 0
        var bytes = 0L
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.SIZE),
                null, null, null,
            )?.use { c ->
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    count++
                    bytes += c.getLong(iSize)
                }
            }
        }
        return StorageSummary(count, bytes)
    }
}
