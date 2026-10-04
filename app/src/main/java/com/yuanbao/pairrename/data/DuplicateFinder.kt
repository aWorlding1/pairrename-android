package com.yuanbao.pairrename.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.yuanbao.pairrename.util.ContentHash

/**
 * 全盘重复图片查找。
 *
 * **这是真正只有「管理所有文件」权限才能做的功能**：
 * 没有它，MediaStore 只能查到本应用自己贡献的媒体，拿不到全盘图片。
 *
 * 策略同样是两级过滤，避免把整盘照片都读一遍：
 * 1. 先按文件体积分组（MediaStore 已索引，一次 query 就有）；
 *    体积不同的必然不是同一张，这一层免费筛掉绝大多数。
 * 2. 只读取「体积相同」的候选，算头尾采样哈希确认。
 */
object DuplicateFinder {

    /** 一组内容完全相同的图片。 */
    data class DuplicateGroup(
        val hash: String,
        val size: Long,
        val members: List<Member>,
    )

    data class Member(
        val name: String,
        val path: String,
        val id: Long,
    ) {
        /** 用于展示的 Uri，需要时可交给系统看图应用打开。 */
        val uri: android.net.Uri get() =
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    /** 一次最多读取多少个候选文件，防止整盘扫描卡死。 */
    private const val MAX_CANDIDATES = 300
    /** 体积过小的不算（多半是损坏的缩略图或占位文件）。 */
    private const val MIN_SIZE = 1024L

    val hasPermission: Boolean get() = MediaStoreMeta.hasAllFilesAccess()

    /** 未授权时返回 false，UI 据此引导去开启。 */
    fun canRun(): Boolean = hasPermission

    /**
     * 查找全盘重复图片。
     * @return 内容相同的分组（每组至少 2 个成员），按「可释放空间」从大到小。
     */
    fun find(context: Context): List<DuplicateGroup> {
        if (!hasPermission) return emptyList()

        // ---- 第 1 步：按体积分组（一次 query，走 MediaStore 索引）----
        val bySize = LinkedHashMap<Long, MutableList<Member>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.SIZE} > ?",
            arrayOf(MIN_SIZE.toString()),
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            // Android 10+ 上 DATA 可能取不到，取不到就退化为显示名
            val iData = c.getColumnIndex(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                val size = c.getLong(iSize)
                val path = if (iData >= 0) c.getString(iData).orEmpty() else ""
                bySize.getOrPut(size) { ArrayList() }.add(Member(name, path, id))
            }
        } ?: return emptyList()

        // ---- 第 2 步：只对「体积相同」的候选读内容算哈希 ----
        val byHash = HashMap<String, MutableList<Member>>()
        val sizeOf = HashMap<String, Long>()
        var budget = MAX_CANDIDATES

        for ((size, group) in bySize) {
            if (group.size < 2) continue
            if (budget <= 0) break
            for (m in group) {
                if (budget <= 0) break
                budget--
                val stream = runCatching {
                    context.contentResolver.openInputStream(m.uri)
                }.getOrNull() ?: continue
                val hash = ContentHash.digest(stream, size) ?: continue
                byHash.getOrPut(hash) { ArrayList() }.add(m)
                sizeOf[hash] = size
            }
        }

        return byHash.entries
            .filter { (_, members) -> members.size >= 2 }
            .map { (hash, members) -> DuplicateGroup(hash, sizeOf[hash] ?: 0L, members) }
            // 按可释放空间排序：重复份数越多、单份越大，越值得先看
            .sortedByDescending { it.size * (it.members.size - 1) }
    }

    /** 可释放空间：每组保留 1 份，其余都是冗余。 */
    fun reclaimable(groups: List<DuplicateGroup>): Long =
        groups.sumOf { it.size * (it.members.size - 1) }
}
