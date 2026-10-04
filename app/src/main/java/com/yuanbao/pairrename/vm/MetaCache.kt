package com.yuanbao.pairrename.vm

import android.net.Uri
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.RenameStep
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片元信息缓存（拍摄时间 / 真实路径 / 内容指纹）。
 *
 * 从 MainViewModel 里抽出来的原因：它是**唯一有并发写入**的状态
 * （多个 IO 协程会同时读 EXIF、算哈希、改名迁移），
 * 把并发控制封闭在一个类里，比散落在 2600 行的 ViewModel 里安全得多。
 *
 * 为什么需要缓存：`listImages` 每次都返回全新的 ImageItem，
 * 不缓存的话刷新或改名后，辛苦读出来的拍摄时间和内容指纹会全部清零，
 * 配对**悄悄退回推算** —— 用户只会觉得"怎么突然不准了"，无从排查。
 */
class MetaCache {

    private val exif = ConcurrentHashMap<String, Long>()
    private val camera = ConcurrentHashMap<String, String>()
    private val path = ConcurrentHashMap<String, String>()
    private val hash = ConcurrentHashMap<String, String>()

    /** 保护「取旧值 → 写新键 → 删旧键」序列，改名迁移必须是原子的。 */
    private val lock = Any()

    /**
     * 缓存键 = 目录 Uri + 显示名。
     *
     * **不能只用显示名**：`IMG_0001.jpg` 这种名字在不同文件夹里太常见了。
     * 只按名字索引，切到另一个目录后同名文件会拿到上一个目录的拍摄时间
     * 和内容指纹 —— 配对会悄悄错掉，而且界面上完全看不出来。
     */
    fun key(item: ImageItem): String = "${item.treeUri}#${item.displayName}"

    fun key(treeUri: Uri?, displayName: String): String = "$treeUri#$displayName"

    // ---------------- 写入 ----------------

    fun putExif(item: ImageItem, takenAt: Long) {
        if (takenAt > 0) exif[key(item)] = takenAt
        trimIfNeeded()
    }

    /** @param cam 相机型号，空串表示取不到。 */
    fun putCamera(item: ImageItem, cam: String) {
        if (cam.isNotBlank()) camera[key(item)] = cam
        trimIfNeeded()
    }

    fun putPath(item: ImageItem, p: String) {
        if (p.isNotBlank()) path[key(item)] = p
        trimIfNeeded()
    }

    fun putHash(item: ImageItem, h: String) {
        if (h.isNotEmpty()) hash[key(item)] = h
        trimIfNeeded()
    }

    // ---------------- 读取 ----------------

    /** 用缓存回填一次扫描结果。没有任何缓存时直接返回原列表，避免多余拷贝。 */
    fun enrich(items: List<ImageItem>): List<ImageItem> {
        if (exif.isEmpty() && path.isEmpty() && camera.isEmpty()) return items
        var changed = false
        val out = items.map { i ->
            val t = exif[key(i)]
            val p = path[key(i)]
            val c = camera[key(i)]
            if ((t != null && i.takenAt <= 0) ||
                (p != null && i.path == null) ||
                (c != null && i.camera.isBlank())
            ) {
                changed = true
                i.copy(
                    takenAt = i.takenAt.takeIf { it > 0 } ?: (t ?: 0L),
                    path = i.path ?: p,
                    camera = i.camera.ifBlank { c ?: "" },
                )
            } else {
                i
            }
        }
        return if (changed) out else items
    }

    /**
     * 内容指纹按当前文件名派生，不单独按 Uri 存 ——
     * 改名后 Uri 就失效了，存了也白存。
     */
    fun deriveContentKeys(items: List<ImageItem>): Map<String, String> {
        if (hash.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        items.forEach { i -> hash[key(i)]?.let { out[i.key] = it } }
        return out
    }

    fun exifOf(item: ImageItem): Long = exif[key(item)] ?: 0L

    // ---------------- 改名迁移 ----------------

    /**
     * 改名后把缓存迁到新名字上。
     *
     * **必须严格按 `steps` 的顺序逐条"读旧键 → 写新键 → 删旧键"**，
     * 而且调用方给的顺序必须与真实磁盘操作的顺序一致。
     *
     * 原因是这个顺序语义**承重**，不能"优化"成两阶段（先把所有旧值读出来、
     * 再统一写新键）：
     *
     * - 链式改名 `A→B, B→C` 顺序执行时，`B→C` 读到的是刚被 `A→B` 写进来的值，
     *   于是最终 `C` 拿到 A 的元信息 —— 而磁盘上正是"原 A 的文件最终叫 C"，正确。
     * - 置换 `A→temp, B→A, temp→B` 里那个**临时名**是关键：
     *   顺序执行才能把值沿着 A→temp→B 这条链正确搬运。
     *   改成两阶段就会丢掉 `B` 的原值（`temp` 本来没有值可写回去）。
     *
     * 一句话：缓存是文件系统的镜像，所以"按文件操作的真实顺序搬运"就是唯一正确的算法。
     * 想改这里之前，先跑 `verify_metacache.py` —— 里面专门放了
     * "两阶段实现会让置换结果错掉"的反例。
     *
     * @param treeUriOf 由 side 取目录 Uri（ViewModel 才知道当前左右各是哪个目录）
     */
    fun migrate(steps: List<RenameStep>, treeUriOf: (com.yuanbao.pairrename.model.Side) -> Uri?) {
        if (steps.isEmpty()) return
        synchronized(lock) {
            steps.forEach { st ->
                // 优先用记录自带的目录（见 RenameStep.treeUri）。
                //
                // 缓存键是 **(目录, 名字)**，所以用"当前打开的目录"去算 oldKey，
                // 在用户换过目录之后会落在错误的命名空间里 —— `exif[oldKey]` 查不到，
                // 四个 `?.let` 全部空转，结果是**静默地什么都不迁移**：
                // 撤销之后拍摄时间和内容指纹没了，配对悄悄退回推算，
                // 而界面一切正常，用户根本看不出来。
                //
                // 老记录（v5.3.0 之前落的盘，treeUri 为 null）才回退到按 side 取当前目录。
                val tree = st.treeUri ?: treeUriOf(st.side) ?: return@forEach
                val oldKey = key(tree, st.previousName)
                val newKey = key(tree, st.newName)
                if (oldKey == newKey) return@forEach
                exif[oldKey]?.let { exif[newKey] = it; exif.remove(oldKey) }
                camera[oldKey]?.let { camera[newKey] = it; camera.remove(oldKey) }
                path[oldKey]?.let { path[newKey] = it; path.remove(oldKey) }
                hash[oldKey]?.let { hash[newKey] = it; hash.remove(oldKey) }
            }
        }
    }

    /** 清空内容指纹（重新校验前调用）。 */
    fun clearHashes() {
        synchronized(lock) { hash.clear() }
    }

    fun clearAll() {
        synchronized(lock) {
            exif.clear()
            camera.clear()
            path.clear()
            hash.clear()
        }
    }

    /**
     * 条目上限。
     * 反复切换目录时缓存会一直累积（没有合适的清理时机），
     * 而它只是**加速**用的 —— 超了直接清空重来，不影响任何正确性。
     *
     * 判断必须看**四个表里最大的那个**，不能只看 `exif`：
     * 用户如果没开「管理所有文件」权限、或图片都没有拍摄时间，
     * `putExif` 一次都不会写入，`exif` 永远是空的 ——
     * 只看它的话上限永远不会触发，而 `hash` 会随内容校验跟着切换目录
     * 一路涨下去（每个键约 100 字节 + 32 字节值，几万条就是几 MB 且只增不减）。
     */
    private fun trimIfNeeded() {
        if (biggest() <= LIMIT) return
        synchronized(lock) {
            if (biggest() <= LIMIT) return
            exif.clear()
            camera.clear()
            path.clear()
            hash.clear()
        }
    }

    /** 四个缓存里最大的条目数（ConcurrentHashMap.size() 是 O(1)，可以每写一次都算）。 */
    private fun biggest(): Int = maxOf(exif.size, camera.size, path.size, hash.size)

    /**
     * 各缓存的条目数（诊断面板展示用）。
     *
     * 四个都要报：以前这里是 `Triple(exif, path, hash)`，KDoc 却写着"三个缓存"——
     * 而 `camera` 被漏掉了。用户排查"为什么拍摄时间没出现"时，
     * "相机有值但时间没有"和"两个都没有"指向完全不同的原因，漏一个就没法判断。
     */
    data class Sizes(
        val exif: Int,
        val camera: Int,
        val path: Int,
        val hash: Int,
    ) {
        /** 最大的一张表 —— 上限是按它判断的（见 [trimIfNeeded]）。 */
        val biggest: Int get() = maxOf(exif, camera, path, hash)

        /** 诊断面板的一行。 */
        val text: String get() = "时间 $exif / 相机 $camera / 路径 $path / 指纹 $hash"
    }

    fun sizes(): Sizes = Sizes(exif.size, camera.size, path.size, hash.size)

    private companion object {
        const val LIMIT = 20_000
    }
}
