package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.BatchMode
import com.yuanbao.pairrename.model.BatchParams
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PlanRow

/**
 * 批量改名 / 顺序对齐的「计划生成」：纯计算，不触碰文件系统，
 * 因此可以先预览、确认后再统一执行。
 */
object Batch {

    /** 单栏批量改名：编号 / 查找替换 / 前后缀 / 大小写。 */
    fun buildBatchPlan(
        items: List<ImageItem>,
        mode: BatchMode,
        params: BatchParams,
        existing: Set<String>,
        settings: AppSettings,
        /** NUMBER 模式下的命名预设，null 表示用 baseName + 序号。 */
        preset: Naming.Preset? = null,
    ): List<PlanRow> {
        // 同样先算理想名字、再统一裁决：批量里也可能出现置换
        // （比如 TRIM 去序号后 A、B 都变成同名，或大小写转换后互换）
        var seq = params.startIndex
        val desiredList = ArrayList<Pair<String, String>>(items.size)
        items.forEach { item ->
            val old = item.displayName
            val oldBase = Naming.baseOf(old)
            val ext = if (params.keepExtension) Naming.extensionOf(old) else ""
            val newBase = when (mode) {
                // 预设：缺数据时返回 null，此时保持原名（不改名）
                // —— 宁可漏改几个，也不能编出 19700101 这种假日期
                BatchMode.NUMBER -> (preset?.let {
                    Naming.presetName(it, oldBase, item.takenAt, item.camera, seq, params.digits)
                } ?: (params.baseName + Naming.padded(seq, params.digits)))
                BatchMode.REPLACE -> Naming.replaceIn(oldBase, params.find, params.replace)
                BatchMode.AFFIX -> Naming.withTemplate(oldBase, params.prefix, params.suffix)
                BatchMode.CASE -> Naming.applyCase(oldBase, params.caseOp)
                // 去掉末尾序号：`photo (1)` -> `photo`、`IMG_0001` -> `IMG`
                BatchMode.TRIM -> Naming.trimNumbering(oldBase)
                // 一键清理：整个文件名一起处理（扩展名也要管），
                // fixName 返回 null 表示"无需改动" -> 保持原名
                BatchMode.FIX -> Naming.fixName(old, params.clean) ?: oldBase
                // 正则：不合法时返回 null，此时保持原名，
                // 绝不能因为一个坏正则就把整批文件改名成空
                BatchMode.REGEX ->
                    Naming.regexReplace(oldBase, params.pattern, params.replacement, params.ignoreCase)
                        ?: oldBase
                // 截取：区间非法返回 null，同样保持原名
                BatchMode.SUBSTR -> Naming.substring(oldBase, params.from, params.to) ?: oldBase
                BatchMode.INSERT -> Naming.insertAt(oldBase, params.from, params.insertText)
                BatchMode.DELETE -> Naming.deleteRange(oldBase, params.from, params.to ?: oldBase.length)
            }
            // FIX 模式由 fixName 全权处理（含扩展名），
            // 不能再走 base+ext 重组，否则刚清理好的名字又被拼回去
            if (mode == BatchMode.FIX) {
                desiredList += newBase to ""
            } else {
                desiredList += Naming.sanitize(
                    Naming.withTemplate(newBase, settings.prefix, settings.suffix),
                    reserve = ext.length + 1,
                ) to ext
            }
            if (mode == BatchMode.NUMBER) seq++
        }

        val assigned = assignNames(
            desiredList = desiredList,
            oldNames = items.map { it.displayName },
            existing = existing,
            settings = settings,
        )

        val rows = mutableListOf<PlanRow>()
        items.forEachIndexed { i, item ->
            rows += PlanRow(
                target = item,
                oldName = item.displayName,
                newName = assigned[i].second,
                conflict = assigned[i].first,
            )
        }
        return rows
    }

    /**
     * 顺序对齐：把 from[i] 的名字应用到 to[i + offset]。
     * offset 表示目标栏整体后移多少位才与来源栏对齐。
     */
    fun buildAlignPlan(
        from: List<ImageItem>,
        to: List<ImageItem>,
        offset: Int,
        existing: Set<String>,
        settings: AppSettings,
    ): List<PlanRow> {
        val rows = mutableListOf<PlanRow>()
        if (from.isEmpty() || to.isEmpty()) return rows
        val safeOffset = offset.coerceIn(0, (to.size - 1).coerceAtLeast(0))
        val count = minOf(from.size, to.size - safeOffset)
        if (count <= 0) return rows

        val targets = (0 until count).map { to[it + safeOffset] }
        val sources = (0 until count).map { from[it] }

        // 先算出每个目标「理想的名字」，再统一裁决冲突。
        //
        // 不能边算边判冲突：如果 A 想改成 B，而 B 也是本次要改名的文件之一，
        // 那一刻 B 还占着名字，A 就会被误判冲突而改成 `B (1)` ——
        // 可 B 紧接着也要改走，名字本该空出来。
        // 典型触发场景：两边顺序相反（一边正序、一边倒序），整批都是置换，
        // 结果全被改成 `xxx (1)`。
        val desiredList = ArrayList<Pair<String, String>>(count) // desired -> ext
        for (i in 0 until count) {
            val src = sources[i]
            val dst = targets[i]
            val old = dst.displayName
            val ext = if (settings.extensionPolicy == com.yuanbao.pairrename.model.ExtensionPolicy.USE_SOURCE) {
                Naming.extensionOf(src.displayName)
            } else {
                Naming.extensionOf(old)
            }
            val desired = Naming.sanitize(
                Naming.withTemplate(Naming.baseOf(src.displayName), settings.prefix, settings.suffix),
                reserve = ext.length + 1,
            )
            desiredList += desired to ext
        }

        val assigned = assignNames(
            desiredList = desiredList,
            oldNames = targets.map { it.displayName },
            existing = existing,
            settings = settings,
        )

        for (i in 0 until count) {
            rows += PlanRow(
                target = targets[i],
                oldName = targets[i].displayName,
                newName = assigned[i].second,
                sourceName = sources[i].displayName,
                conflict = assigned[i].first,
            )
        }
        return rows
    }

    /**
     * 统一裁决一批「理想名字」，返回 (是否发生了冲突避让, 最终名字)。
     *
     * 关键点：**本次要改名的旧名不算占用**。它们马上会被改走，
     * 名字会腾出来，所以 A→B、B→A 这种置换应当直接完成，
     * 而不是把其中一个改成 `B (1)`。
     *
     * 但仍然会避让两种情况：
     * 1. 名字被**不参与本次改名**的文件占用（真冲突，必须让）
     * 2. 多个目标争同一个名字（自动编号）
     */
    private fun assignNames(
        desiredList: List<Pair<String, String>>,
        oldNames: List<String>,
        existing: Set<String>,
        settings: AppSettings,
    ): List<Pair<Boolean, String>> {
        val renaming = HashSet<String>()
        oldNames.forEach { renaming.add(it.lowercase()) }

        // 不参与本次改名的现有文件 —— 只有它们才构成真冲突
        val hard = HashSet<String>()
        existing.forEach { n ->
            if (n.lowercase() !in renaming) hard.add(n.lowercase())
        }

        val out = ArrayList<Pair<Boolean, String>>(desiredList.size)
        val taken = HashSet<String>(hard)
        desiredList.forEach { (desired, ext) ->
            val candidate = Naming.join(desired, ext)
            val conflict = candidate.lowercase() in taken
            val finalName = if (conflict) {
                Naming.resolveConflict(desired, ext, taken, settings.numbering)
            } else {
                candidate
            }
            taken.add(finalName.lowercase())
            out += conflict to finalName
        }
        return out
    }


    /**
     * 把改名计划导出成 CSV（带 BOM，Excel 直接打开不乱码）。
     *
     * 用途：改之前先存档，或发给别人核对。
     * 几百个文件的改名一旦出错，有这份清单就能对回去。
     */
    fun planToCsv(rows: List<PlanRow>): String {
        val sb = StringBuilder()
        sb.append('\ufeff') // BOM
        sb.append("序号,原名,新名,是否冲突避让\n")
        rows.forEachIndexed { i, r ->
            sb.append("${i + 1},")
                .append(quote(r.oldName)).append(',')
                .append(quote(r.newName)).append(',')
                .append(if (r.conflict) "是" else "")
                .append('\n')
        }
        return sb.toString()
    }

    /** CSV 转义：含逗号/引号/换行的要包起来，内部引号翻倍。 */
    private fun quote(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
}
