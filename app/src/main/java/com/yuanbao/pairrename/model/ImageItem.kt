package com.yuanbao.pairrename.model

import android.net.Uri

data class ImageItem(
    val side: Side,
    val treeUri: Uri,
    val docUri: Uri,
    val docId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val canRename: Boolean = false,
    val canWrite: Boolean = false,
    val canDelete: Boolean = false,
    /**
     * 真实文件路径。只有拿到「管理所有文件」权限、且能把 SAF 目录
     * 映射回内部存储时才非空。用于显示文件在哪，以及走 File 快通道。
     */
    val path: String? = null,
    /** EXIF 拍摄时间（毫秒），未读取或取不到时为 0。 */
    val takenAt: Long = 0,
    /** EXIF 相机型号，未读取或取不到时为空。用于「相机+序号」命名规则。 */
    val camera: String = "",
) {
    /** 列表内的稳定 key。 */
    val key: String get() = docUri.toString()

    /** 配对用的键：主文件名小写，忽略扩展名差异。 */
    val pairKey: String get() = displayName.substringBeforeLast('.', displayName).lowercase()

    val dimension: String get() = if (width > 0 && height > 0) "${width}×${height}" else ""

    /** 有可用的拍摄时间。 */
    val hasTakenAt: Boolean get() = takenAt > 0
}

/** 一次改名中的最小可逆步骤。 */
data class RenameStep(
    val docUri: Uri,
    val previousName: String,
    val newName: String,
    val side: Side,
    /**
     * 这一步**发生在哪个目录**。
     *
     * 为什么要钉在记录里，而不是回头去问"当前打开的目录"：
     * 撤销 / 重做原本用 `paneUri(step.side)` 去定位文件与缓存键 ——
     * 那是一个**隐含的全局状态**。用户换过目录之后，同一个 step 就指向了别的目录，
     * 于是"改名"会改到另一个目录的文件、"缓存迁移"会因为查不到旧键而**静默什么都不做**。
     *
     * 唯一正确的记录时刻是操作**刚完成**的那一刻（见 `pushUndo`）：
     * 那时"当前目录"必然就是刚才动手的目录，之后再也不会变。
    *
    * 可空是为了兼容 v5.3.0 之前落的盘 / 导出的 CSV（那时没有这个字段），
    * 读回来是 null 时调用方回退到旧行为（按 side 取当前目录）。
    */
    val treeUri: Uri? = null,
    /** 该改名步骤执行前的文档 Uri；用于不重扫目录的精确 UI 增量更新。 */
    val previousUri: Uri? = null,
)

/** 一次移动（归档用）。撤销时把 toPath 移回 fromPath。 */
data class MoveStep(
    val fromPath: String,
    val toPath: String,
)

/** SAF 文档移动步骤。移动后 URI 可能变化，因此每次 undo/redo 都必须保存 provider 返回的新 URI。 */
data class DocumentMoveStep(
    val fromTree: Uri,
    val toTree: Uri,
    val currentUri: Uri,
    val name: String,
    /** true 表示文档当前位于 toTree，false 表示已撤回到 fromTree。 */
    val atTarget: Boolean = true,
)

/** 一次复制产生的新文件。撤销 = 删掉它；重做 = 再复制一次。 */
data class CreatedFile(
    val docUri: Uri,
    val sourceUri: Uri,
    val targetTree: Uri,
    val name: String,
)

/** 一次用户操作的撤销记录。四类变动可以同时存在。 */
data class UndoEntry(
    val steps: List<RenameStep> = emptyList(),
    val label: String,
    /** 归档产生的移动步骤，撤销时反向移回。 */
    val moves: List<MoveStep> = emptyList(),
    /** SAF 跨目录移动步骤；URI 由每次 move 返回值推进。 */
    val documentMoves: List<DocumentMoveStep> = emptyList(),
    /** 复制产生的新文件，撤销时删除。 */
    val created: List<CreatedFile> = emptyList(),
    /** 被移入回收站的文件，撤销时恢复。 */
    val trashed: List<com.yuanbao.pairrename.data.TrashRepository.TrashedItem> = emptyList(),
) {
    /** 一共影响了多少个文件。 */
    val total: Int get() = steps.size + moves.size + documentMoves.size + created.size + trashed.size
}

/** 一栏的状态。 */
data class PaneState(
    val treeUri: Uri? = null,
    val pathLabel: String = "",
    val items: List<ImageItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 非错误性的提示（比如「这个文件夹是空的，但有子文件夹」）。 */
    val hint: String? = null,
)

/** 批量/对齐操作的预览行。 */
data class PlanRow(
    val target: ImageItem,
    val oldName: String,
    val newName: String,
    val sourceName: String = "",
    val conflict: Boolean = false,
) {
    val changed: Boolean get() = oldName != newName
}
