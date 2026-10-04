package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.ImageItem

/** 单种格式的统计。 */
data class FormatStat(
    /** 扩展名（不含点，小写）。 */
    val ext: String,
    val count: Int,
    val bytes: Long,
)

/**
 * 目录体检结果。
 *
 * 存在的理由：目录里藏着的问题，光看缩略图发现不了 ——
 * 0 字节的图在相册里就是个黑块，超大文件会拖慢备份，
 * 不支持改名的图会在批量操作时静默跳过。
 * 与其让用户一个个翻，不如直接统计出来告诉他。
 */
data class DirStats(
    val total: Int = 0,
    val totalBytes: Long = 0,
    /** 按体积降序排的格式分布。 */
    val formats: List<FormatStat> = emptyList(),
    /**
     * 极小（1 字节 ~ 1KB）：几乎肯定是坏文件。
     *
     * **刻意不含 0 字节**：`ImageItem.size == 0` 表示"体积没读到"
     * （大目录扫描时为了首屏速度会主动跳过读取），
     * 拿它当"坏文件"会把一大批正常文件误报进去。
     */
    val broken: List<ImageItem> = emptyList(),
    /** 超过 10MB：备份/传输会很慢。 */
    val huge: List<ImageItem> = emptyList(),
    /** 此存储不支持改名的文件。 */
    val notRenamable: List<ImageItem> = emptyList(),
    /** 文件名可能有问题的（空格、大写扩展名、非法字符等）。 */
    val dirtyNames: List<ImageItem> = emptyList(),
) {
    /** 有没有发现任何问题。 */
    val hasIssues: Boolean
        get() = broken.isNotEmpty() || huge.isNotEmpty() ||
            notRenamable.isNotEmpty() || dirtyNames.isNotEmpty()

    /**
     * 有问题的**文件数**（去重）。
     *
     * 不能简单把四个列表长度相加：一张 10 字节又不支持改名的图
     * 会同时出现在两个列表里，加起来就重复计了一个 ——
     * 界面上会说"发现 2 处问题"，其实只有 1 个文件。
     */
    val issueCount: Int
        get() = (broken.asSequence() + huge + notRenamable + dirtyNames)
            .map { it.key }
            .distinct()
            .count()
}

object Stats {

    private const val BROKEN_BYTES = 1024L          // 小于 1KB 基本是坏文件
    private const val HUGE_BYTES = 10L * 1024 * 1024 // 10MB

    fun analyze(items: List<ImageItem>): DirStats {
        if (items.isEmpty()) return DirStats()

        val byExt = HashMap<String, Pair<Int, Long>>()
        val broken = ArrayList<ImageItem>()
        val huge = ArrayList<ImageItem>()
        val notRenamable = ArrayList<ImageItem>()
        val dirty = ArrayList<ImageItem>()

        var totalBytes = 0L
        items.forEach { item ->
            val size = item.size.coerceAtLeast(0)
            totalBytes += size

            val ext = Naming.extensionOf(item.displayName).lowercase().ifBlank { "(无)" }
            val cur = byExt[ext] ?: (0 to 0L)
            byExt[ext] = (cur.first + 1) to (cur.second + size)

            // 只按体积判断"坏"：不解码，否则大目录会非常慢
            if (size in 1L..BROKEN_BYTES) broken += item
            if (size > HUGE_BYTES) huge += item
            if (!item.canRename) notRenamable += item
            if (Naming.isDirtyName(item.displayName)) dirty += item
        }

        val formats = byExt.map { (ext, v) -> FormatStat(ext, v.first, v.second) }
            .sortedWith(compareByDescending<FormatStat> { it.bytes }.thenBy { it.ext })

        return DirStats(
            total = items.size,
            totalBytes = totalBytes,
            formats = formats,
            // 坏文件按体积升序：最小的排最前，最可疑
            broken = broken.sortedBy { it.size },
            huge = huge.sortedByDescending { it.size },
            notRenamable = notRenamable,
            dirtyNames = dirty,
        )
    }
}
