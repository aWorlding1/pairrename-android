package com.yuanbao.pairrename.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yuanbao.pairrename.model.ImageItem

/**
 * 用 MediaStore 一次性批量取图片元信息（宽高、体积）。
 *
 * 存在的意义：SAF 拿宽高要**逐个文件** openInputStream 走一次
 * DocumentsProvider 的 IPC，60 张就是 60 次往返 —— 这正是扫描转圈的原因。
 * MediaStore 一次 query 就能把整个盘的图片「路径 → 宽高大小」全取回来，
 * 宽高是现成的列，不需要解码任何图片。
 *
 * 需要「管理所有文件」权限（Android 11+）才能查到全部内容，
 * 否则只能查到本应用贡献的媒体，此时调用方应回退到逐文件读取。
 */
object MediaStoreMeta {

    data class Meta(val width: Int, val height: Int, val size: Long, val path: String = "")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /**
     * 取「显示名 → 元信息」的映射。
     *
     * 用文件名做键：SAF 侧能拿到的是显示名，两边靠它对齐。
     * 这也是**权限的直接收益**：宽高、体积、真实路径都是 MediaStore 的现成列，
     * 一次 query 全部拿到，完全不需要逐个文件解码。
     */
    /**
     * 缓存上一次结果。
     *
     * 这个函数查的是**整个 MediaStore**（设备上全部图片），
     * 每次扫描/刷新都要解析上万行再建一遍 HashMap 是很大的浪费 ——
     * 图库越大越卡。缓存 30 秒足够覆盖一次连续操作，
     * 又不会让新拍的照片长时间对不上。
     */
    @Volatile
    private var cached: Map<String, Meta>? = null

    @Volatile
    private var cachedAt = 0L

    private const val CACHE_TTL_MS = 30_000L

    /** 改名后索引变了，主动失效。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    fun loadMeta(context: Context): Map<String, Meta>? {
        if (!hasAllFilesAccess()) return null
        val now = System.currentTimeMillis()
        cached?.let {
            if (now - cachedAt < CACHE_TTL_MS) return it
        }
        val built = queryAll(context) ?: return null
        cached = built
        cachedAt = now
        return built
    }

    private fun queryAll(context: Context): Map<String, Meta>? {
        val out = HashMap<String, Meta>(512)
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
        )
        val selection = "${MediaStore.Images.Media.WIDTH} > 0"
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val iS = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iData = c.getColumnIndex(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val path = if (iData >= 0) c.getString(iData).orEmpty() else ""
                    out[name] = Meta(c.getInt(iW), c.getInt(iH), c.getLong(iS), path)
                }
            }
            out
        }.getOrNull()
    }

    /**
     * 通知系统某个文件改名了。
     *
     * SAF renameDocument 只改了存储里的文件，**MediaStore 的索引不会自动更新**，
     * 于是相册里会残留一个打不开的旧条目 —— 这是 SAF 改名的常见副作用。
     * 拿到「管理所有文件」权限后可以顺手把这条旧记录删掉。
     *
     * 失败不影响改名本身，所以全程静默。
     */
    fun notifyRenamed(context: Context, oldPath: String) {
        if (oldPath.isBlank() || !hasAllFilesAccess()) return
        runCatching {
            val where = "${MediaStore.Images.Media.DATA} = ?"
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                where,
                arrayOf(oldPath),
            )
            // 让系统重新扫一遍新文件，把索引补回来
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(oldPath.substringBeforeLast('/')),
                null,
                null,
            )
        }
    }

    /**
     * 用 MediaStore 的元信息补齐 SAF 扫出来的条目：宽高、体积、真实路径。
     * 只填空，不覆盖已有值。
     */
    fun enrich(items: List<ImageItem>, meta: Map<String, Meta>): List<ImageItem> {
        if (meta.isEmpty()) return items
        var changed = false
        val out = items.map { item ->
            val m = meta[item.displayName] ?: return@map item
            val next = item.copy(
                width = if (item.width > 0) item.width else m.width,
                height = if (item.height > 0) item.height else m.height,
                size = if (item.size > 0) item.size else m.size,
                path = item.path ?: m.path.ifBlank { null },
            )
            if (next != item) changed = true
            next
        }
        return if (changed) out else items
    }
}
