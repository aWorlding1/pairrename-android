package com.yuanbao.pairrename.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PaneState
import com.yuanbao.pairrename.model.Side

/**
 * 基于 Storage Access Framework 的目录读写封装。
 * 只用 DocumentsContract，避免 DocumentFile.listFiles() 的多次往返，性能好很多。
 */
class DocsRepository(private val context: Context) {

    companion object {
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "heic", "heif", "avif", "tif", "tiff", "ico", "dng",
        )
        private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

        /** 超过这个数量就不再逐个读宽高，保证大目录的扫描速度。 */
        const val BOUNDS_LIMIT = 600

        /** 递归扫描的最大层级，防止从存储根目录一路扫进整个盘。 */
        const val MAX_DEPTH = 4

        /** 单栏最多加载多少张，超出就停（内存与 UI 的硬上限）。 */
        const val MAX_ITEMS = 5000

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }

    private val resolver: ContentResolver get() = context.contentResolver

    // ---------- 权限 ----------

    /** 返回是否真的拿到了可持久化的授权；少数 provider 不支持，需要提示用户。 */
    fun takePersistable(uri: Uri): Boolean {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
    }

    fun hasPermission(uri: Uri): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)

    /** 目录还存在且能访问（用于区分「空目录」和「权限失效/已删除」）。 */
    fun isReachable(treeUri: Uri): Boolean {
        val docUri = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }.getOrNull() ?: return false
        return runCatching {
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    // ---------- 读取 ----------

    /**
     * 列出目录下的图片文件（非递归）。
     * @param readBounds 读取宽高。文件很多时会自动跳过，避免逐个打开文件拖慢扫描。
     */
    fun listImages(
        treeUri: Uri,
        side: Side,
        readBounds: Boolean = true,
        recursive: Boolean = false,
        boundsLimit: Int = BOUNDS_LIMIT,
    ): List<ImageItem> {
        val result = mutableListOf<ImageItem>()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return result
        collect(treeUri, rootDocId, side, result, readBounds, recursive, 0, boundsLimit)
        return result
    }

    private fun collect(
        treeUri: Uri,
        docId: String,
        side: Side,
        out: MutableList<ImageItem>,
        readBounds: Boolean,
        recursive: Boolean,
        depth: Int,
        boundsLimit: Int,
    ) {
        if (out.size >= MAX_ITEMS) return
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        }.getOrNull() ?: return
        runCatching {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val iTime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val iFlags = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
                var count = 0
                while (c.moveToNext()) {
                    if (out.size >= MAX_ITEMS) return
                    val name = c.getString(iName) ?: continue
                    val mime = c.getString(iMime) ?: ""
                    val id = c.getString(iId) ?: continue

                    if (mime == MIME_DIR) {
                        // 递归深度设上限，防止从根目录一路扫进整个存储
                        if (recursive && depth < MAX_DEPTH) {
                            collect(treeUri, id, side, out, readBounds, recursive, depth + 1, boundsLimit)
                        }
                        continue
                    }
                    if (!isImage(mime, name)) continue
                    val flags = c.getInt(iFlags)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val dims = if (readBounds && count < boundsLimit) readBounds(docUri) else (0 to 0)
                    count++
                    out += ImageItem(
                        side = side,
                        treeUri = treeUri,
                        docUri = docUri,
                        docId = id,
                        displayName = name,
                        mimeType = mime,
                        size = c.getLong(iSize),
                        lastModified = c.getLong(iTime),
                        width = dims.first,
                        height = dims.second,
                        canRename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
                        canWrite = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
                        canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                    )
                }
            }
        }
    }

    /** 目录下现有的全部显示名（用于冲突检测）。 */
    fun listNames(treeUri: Uri): Set<String> {
        val names = mutableSetOf<String>()
        val childrenUri = childrenUri(treeUri) ?: return names
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) names += c.getString(iName) ?: ""
            }
        }
        return names
    }

    /** 只读图片头部，拿宽高（不解码整图）。 */
    fun readBounds(uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            opts.outWidth to opts.outHeight
        }.getOrDefault(0 to 0)
    }

    // ---------- 写入 ----------

    /** 改名；只有 provider 返回新 URI 才算成功，并尽可能绑定回来源 tree 的授权。 */
    fun rename(docUri: Uri, newName: String, treeUri: Uri? = null): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, docUri, newName) }
            .getOrNull()
            ?.let { renamed -> treeUri?.let { documentUriInTree(it, renamed) } ?: renamed }

    fun delete(docUri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }.getOrDefault(false)

    /**
     * 复制到目标目录（treeUri）。返回新文档 Uri。
     *
     * `copyDocument` 需要 Android 7+，而 minSdk 已经是 26 —— 那个版本判断恒真，
     * 所以原先写在 `else` 里的手写兜底**永远走不到**（lint: ObsoleteSdkInt）。
     * 真正的失败原因只剩一个：**provider 没实现 copyDocument**（部分第三方网盘）。
     * 那时 `runCatching` 把它变成 null，调用方报「复制失败」——
     * 而手写流复制本来就能把这件事干成。
     *
     * 兜底条件从「版本不够」改成「上一条没成」：同一个 copyByStream，
     * 接到真的会发生的失败上，而不是接到一个永远不成立的判断上。
     */
    fun copyTo(sourceUri: Uri, targetTree: Uri): Uri? {
        val parent = treeDocUri(targetTree) ?: return null
        return runCatching {
            DocumentsContract.copyDocument(resolver, sourceUri, parent)
        }.getOrNull() ?: copyByStream(sourceUri, targetTree)
    }

    /**
     * 把文件移动到另一个目录（跨目录移动）。
     *
     * 优先用系统的 `moveDocument`：同分区是瞬时的，不会真的拷贝数据。
     * 前提是**两边的 provider 是同一个**（比如都是外部存储）——
     * 跨 provider（比如网盘 → 本地）会失败。
     *
     * （曾经这里还写着「Android 7+」，外层也确实包了一个
     * `if (SDK_INT >= N)`。minSdk 提到 26 之后那个判断恒真、成了死分支，
     * 而 `moveDocument` 的调用本身不需要它 —— 已拆掉，注释同步。）
     *
     * 失败时退回「复制 + 删原文件」：
     * 只有复制**确认成功**后才删原件，否则宁可两边都有，
     * 也不能让文件凭空消失 —— 丢文件是不可逆的。
     */
    fun moveTo(
        sourceUri: Uri,
        sourceTree: Uri,
        targetTree: Uri,
        knownTargetNames: Set<String>? = null,
    ): Uri? {
        val parent = treeDocUri(targetTree) ?: return null
        val scopedSource = documentUriInTree(sourceTree, sourceUri)
        val sourceName = queryName(sourceUri) ?: return null
        // SAF provider 可能允许同名文档；移动前发现任何同名项就保守失败。
        // 这不是原子 no-clobber 保证，但能阻止已存在的目标被主动覆盖。
        val occupiedNames = knownTargetNames ?: listNames(targetTree)
        if (occupiedNames.any { it.equals(sourceName, ignoreCase = true) }) return null
        // moveDocument 是 4 参：来源文档、**来源的父文档**、目标父文档。
        // 本工程的 docUri 都是 buildDocumentUriUsingTree(treeUri, "根id/名字") 造的，
        // 所以剥掉最后一段就是来源父目录。若 URI 不是 tree 形态会抛异常，
        // 被 runCatching 吃掉后自然退回复制+删除 —— 安全的那条路。
        val sourceParent = runCatching {
            val sourceDocId = DocumentsContract.getDocumentId(scopedSource)
            val rootDocId = DocumentsContract.getTreeDocumentId(sourceTree)
            DocumentsContract.buildDocumentUriUsingTree(
                sourceTree,
                sourceDocId.substringBeforeLast('/', rootDocId),
            )
        }.getOrNull()
        if (sourceParent != null) {
            runCatching {
                DocumentsContract.moveDocument(resolver, scopedSource, sourceParent, parent)
            }.getOrNull()?.let { return documentUriInTree(targetTree, it) }
        }
        // 退回：先复制，确认复制完整后才删原件；源删失败时尽力清理副本并如实报失败。
        val copied = copyTo(sourceUri, targetTree) ?: return null
        if (delete(sourceUri)) return documentUriInTree(targetTree, copied)
        // 原件仍在。副本清理失败只会留下重复文件，不会丢唯一数据。
        delete(copied)
        return null
    }

    /** 把 provider 返回的裸 document URI 绑定回本次操作持有的 tree 授权。 */
    private fun documentUriInTree(treeUri: Uri, documentUri: Uri): Uri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(documentUri),
        )
    }.getOrDefault(documentUri)

    /**
     * `copyDocument` 不可用时的兜底：手写流复制。
     *
     * 目标同名时**直接放弃**（返回 null）—— 与移动、回收站恢复同一条硬规矩：
     * 宁可这次没干成，也不产生第二份同名文件。多出来的副本比一次失败难收拾得多。
     */
    private fun copyByStream(sourceUri: Uri, targetTree: Uri): Uri? {
        val parent = treeDocUri(targetTree) ?: return null
        val name = runCatching { queryName(sourceUri) }.getOrNull() ?: return null
        // 拒覆盖：目标目录已有同名文档就什么都不做，让上层如实报失败。
        if (findByName(targetTree, name) != null) return null
        val target = DocumentsContract.createDocument(resolver, parent, "image/*", name) ?: return null
        val expectedSize = querySize(sourceUri)
        val copied = runCatching {
            val byteCount = resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output) }
                    ?: throw java.io.IOException("Provider returned no output stream")
            } ?: throw java.io.IOException("Provider returned no input stream")
            if (expectedSize != null && byteCount != expectedSize) {
                throw java.io.IOException("Copied byte count does not match source size")
            }
            true
        }.getOrDefault(false)
        if (!copied) {
            // 目标是本次 createDocument 创建的；失败时不留下阻塞重试的半成品。
            delete(target)
            return null
        }
        return target
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getLong(0).takeIf { it >= 0L }
        }
    }.getOrNull()

    private fun queryName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    /** 在目录中按名字找回文档 Uri。 */
    /**
     * 一次性取出目录下「文件名 → 文档 Uri」的完整映射。
     * 用它替代反复调用 [findByName]：后者每次都要重新 query 整个目录，
     * 批量场景下是 O(n²)，5000 张时会卡到不能用。
     */
    fun nameIndex(treeUri: Uri): Map<String, Uri> {
        val childrenUri = childrenUri(treeUri) ?: return emptyMap()
        val out = HashMap<String, Uri>()
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val id = c.getString(iId) ?: continue
                    out[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        return out
    }

    fun findByName(treeUri: Uri, name: String): Uri? {
        val childrenUri = childrenUri(treeUri) ?: return null
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(iName) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(iId))
                    }
                }
                null
            }
        }.getOrNull()
    }

    // ---------- 展示用 ----------

    /** 把 treeUri 转成人类可读路径，如 "内部存储 / DCIM / Camera"。 */
    fun describeTree(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return treeUri.lastPathSegment.orEmpty()
        val body = docId.replace(':', '/')
        return if (body.startsWith("primary")) {
            body.replaceFirst("primary", "内部存储")
        } else {
            body
        }
    }

    /** 目录里是否存在子文件夹（用于「这个文件夹是空的，要不要连子文件夹一起扫」的提示）。 */
    fun hasSubFolders(treeUri: Uri): Boolean {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return false
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        }.getOrNull() ?: return false
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (c.getString(iMime) == MIME_DIR) return true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    fun buildPane(
        treeUri: Uri,
        side: Side,
        readBounds: Boolean = true,
        recursive: Boolean = false,
    ): PaneState =
        PaneState(
            treeUri = treeUri,
            pathLabel = describeTree(treeUri),
            items = listImages(treeUri, side, readBounds, recursive),
        )

    private fun treeDocUri(treeUri: Uri): Uri? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }.getOrNull()

    private fun childrenUri(treeUri: Uri): Uri? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
    }.getOrNull()

    private fun isImage(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT
    }

    /**
     * 目录的显示名（用于"最近用过"列表）。
     * 取不到就退回 Uri 的最后一段，总比显示空白强。
     */
    fun folderLabel(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            val last = docId.substringAfterLast(':', docId)
            if (last.isNotBlank()) return last
        }
        val raw = uri.lastPathSegment
        if (!raw.isNullOrBlank()) return raw.substringAfterLast(':', raw)
        return uri.toString()
    }
}
