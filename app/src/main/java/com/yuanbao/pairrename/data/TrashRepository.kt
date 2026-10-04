package com.yuanbao.pairrename.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 回收站：只有在一个独立目录已创建、来源元数据已持久化且文件确实被移动/复制后才报成功。
 * 任何不能证明安全的情况都保留源文件；调用方不得把 null 当成“可以硬删”的许可。
 */
class TrashRepository(private val context: Context) {

    data class TrashedItem(
        val id: String,
        /** 回收站内的目录绝对路径。 */
        val dirPath: String,
        val originalPath: String,
        val displayName: String,
        val size: Long,
        val time: Long,
    ) {
        val file: File get() = File(File(dirPath), displayName)
    }

    enum class RestoreResult { OK, MISSING, OCCUPIED, FAILED }

    companion object {
        private const val DIR = "trash"
        private const val ORIGIN_FILE = "origin.info"
        private const val MAX_UNIQUE_DIR_ATTEMPTS = 8
    }

    private fun root(): File? {
        val external = context.getExternalFilesDir(null) ?: return null
        val dir = File(external, DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir.takeIf { it.isDirectory }
    }

    /** 在原子 mkdir 成功后才占用该 ID；绝不复用已有回收站目录。 */
    private fun createUniqueDir(parent: File): Pair<String, File>? {
        repeat(MAX_UNIQUE_DIR_ATTEMPTS) {
            val id = UUID.randomUUID().toString()
            val dir = File(parent, id)
            if (dir.mkdir()) return id to dir
        }
        return null
    }

    /**
     * 把文件移入回收站。
     * @return 只有源文件已安全移走时才返回记录；失败时源文件保持原位。
     */
    fun moveToTrash(item: com.yuanbao.pairrename.model.ImageItem): TrashedItem? =
        movePathToTrash(item.path)

    fun movePathToTrash(path: String?): TrashedItem? {
        val src = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!src.isFile) return null
        val originalSize = src.length()
        val root = root() ?: return null
        val (id, dir) = createUniqueDir(root) ?: return null
        val dst = File(dir, src.name)
        val info = File(dir, ORIGIN_FILE)

        // 先把恢复所需元数据同步到磁盘。若这一步失败，源文件尚未移动。
        val metadataSaved = runCatching {
            if (!info.createNewFile()) error("Could not reserve origin metadata")
            FileOutputStream(info).use { stream ->
                stream.write(src.absolutePath.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        }.isSuccess
        if (!metadataSaved) {
            dir.deleteRecursively()
            return null
        }

        // 新建的 UUID 目录应为空；仍然显式拒绝目标已存在，避免任何覆盖语义。
        if (dst.exists()) {
            dir.deleteRecursively()
            return null
        }

        val movedAtomically = runCatching { Files.move(src.toPath(), dst.toPath()) }.isSuccess
        if (!movedAtomically) {
            // 跨文件系统时原子 move 会失败。目标以 createNewFile 独占创建；
            // 只有完整复制、长度一致且源文件确实删除后才视为已移入回收站。
            val copied = copyExclusiveAndVerify(src, dst, originalSize)
            if (!copied) {
                dir.deleteRecursively()
                return null
            }
            if (!runCatching { src.delete() }.getOrDefault(false)) {
                // 源仍保留；回收站副本和 origin.info 也保留，形成可恢复冗余。
                // 返回失败使调用方不得把这次操作当作“已删除”。
                return null
            }
        }

        return TrashedItem(
            id = id,
            dirPath = dir.absolutePath,
            originalPath = src.absolutePath,
            displayName = src.name,
            size = originalSize,
            time = System.currentTimeMillis(),
        )
    }

    private fun copyExclusiveAndVerify(src: File, dst: File, expectedSize: Long): Boolean {
        if (!runCatching { dst.createNewFile() }.getOrDefault(false)) return false
        val copied = runCatching {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (dst.length() != expectedSize) error("Copied file length mismatch")
        }.isSuccess
        if (!copied) runCatching { dst.delete() }
        return copied
    }

    /** 列出回收站里的文件，最新在前。 */
    fun list(): List<TrashedItem> {
        val dirs = root()?.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val out = ArrayList<TrashedItem>()
        dirs.forEach { dir ->
            val info = File(dir, ORIGIN_FILE)
            val original = runCatching { info.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            val data = dir.listFiles()?.firstOrNull { it.name != ORIGIN_FILE } ?: return@forEach
            if (original.isBlank()) return@forEach
            out += TrashedItem(
                id = dir.name,
                dirPath = dir.absolutePath,
                originalPath = original,
                displayName = data.name,
                size = data.length(),
                time = dir.lastModified(),
            )
        }
        return out.sortedByDescending { it.time }
    }

    /**
     * 恢复到原路径。使用不替换目标的移动；若跨文件系统则独占创建并校验复制。
     * 原路径被占用时绝不覆盖。
     */
    fun restore(trashed: TrashedItem): RestoreResult {
        val src = trashed.file
        if (!src.isFile) return RestoreResult.MISSING
        val dst = File(trashed.originalPath)
        if (dst.absolutePath == src.absolutePath) return RestoreResult.OK
        if (dst.exists()) return RestoreResult.OCCUPIED
        val parent = dst.parentFile
        if (parent != null && !parent.exists() && !runCatching { parent.mkdirs() }.getOrDefault(false)) {
            return RestoreResult.FAILED
        }
        if (parent != null && !parent.isDirectory) return RestoreResult.FAILED

        if (runCatching { Files.move(src.toPath(), dst.toPath()) }.isSuccess) {
            // 目标已恢复；若清理元数据失败，保留的额外副本比丢文件更安全。
            runCatching { File(trashed.dirPath).deleteRecursively() }
            return RestoreResult.OK
        }
        if (dst.exists()) return RestoreResult.OCCUPIED
        if (!copyExclusiveAndVerify(src, dst, trashed.size)) {
            return if (dst.exists()) RestoreResult.OCCUPIED else RestoreResult.FAILED
        }
        if (!runCatching { src.delete() }.getOrDefault(false)) {
            // 不删除已恢复目标；两份都留着，返回失败让用户知晓存在重复副本。
            return RestoreResult.FAILED
        }
        runCatching { File(trashed.dirPath).deleteRecursively() }
        return RestoreResult.OK
    }

    /** 永久删除（清空回收站）。 */
    fun empty(): Int {
        var n = 0
        root()?.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                if (runCatching { dir.delete() }.getOrDefault(false)) n++
            }
        }
        return n
    }

    fun totalSize(): Long = root()?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun hasAnything(): Boolean = list().isNotEmpty()

    fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
