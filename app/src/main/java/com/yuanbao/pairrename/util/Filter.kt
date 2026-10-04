package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.Side

/** 附加过滤条件：扩展名 / 体积。都是"不选 = 不过滤"。 */
data class ExtraFilter(
    /** 选中的扩展名（小写，不含点）。空集表示不过滤。 */
    val exts: Set<String> = emptySet(),
    /** 体积下限（字节），null 表示不限。 */
    val minBytes: Long? = null,
    /** 体积上限（字节），null 表示不限。 */
    val maxBytes: Long? = null,
) {
    val isEmpty: Boolean get() = exts.isEmpty() && minBytes == null && maxBytes == null
}

/**
 * 「当前可见条目」的唯一算法。
 *
 * 之前这段逻辑只存在于界面（PaneColumn 的 remember 里），
 * 而 ViewModel 完全不知道有过滤这回事 —— 于是：
 * 搜索后点全选会把**被隐藏的文件**也选上，批量改名就改到了
 * 用户看不见的文件。这类错误没有任何提示，极难发现。
 *
 * 现在界面和 ViewModel 都调这一个函数，不会各算各的。
 */
fun applyFilter(
    items: List<ImageItem>,
    query: String,
    matchFilter: MatchFilter,
    synced: Set<String>,
    /** 已配对的 key 集合，用于差异过滤。 */
    matched: Set<String> = emptySet(),
    /** 扩展名 / 体积过滤。 */
    extra: ExtraFilter = ExtraFilter(),
    /**
     * 弱依据配对涉及的 key 集合（见 [PairResult.weakKeys]），供「仅弱依据」过滤。
     *
     * 传**集合**而不是 PairResult：这个函数是纯函数，界面每次重组都要调它，
     * 让它自己从 reason 映射里现算一遍，等于每帧重扫全表。
     * 调用方（ViewModel）在配对重算时算一次、放进 UiState 就够。
     */
    weakKeys: Set<String> = emptySet(),
    /**
     * 「时间冲突」配对涉及的 key 集合（见 [findTimeConflicts]），供「仅时间冲突」过滤。
     *
     * 与 [weakKeys] 同样是**集合**而不是现算：两者是正交的两件事
     * （依据够不够硬 vs 和更强的证据打不打架），但都在配对重算时算一次就够。
     */
    conflictKeys: Set<String> = emptySet(),
): List<ImageItem> {
    val q = query.trim()
    return items.filter { item ->
        val okQuery = q.isEmpty() || item.displayName.contains(q, ignoreCase = true)
        val okFilter = when (matchFilter) {
            MatchFilter.ALL -> true
            MatchFilter.DONE -> item.key in synced
            // 没配上对的也算「待处理」：它同样还没和另一边统一
            MatchFilter.TODO -> item.key !in synced
            // 单边差异：只显示没配上对的（对面没有的）
            MatchFilter.UNPAIRED -> item.key !in matched
            // 弱依据：配上对了，但依据是"序号碰巧一样 / 排位正好对上"，
            // 属于最可能改错的那批，单独拎出来逐条复核
            MatchFilter.SUSPECT -> item.key in weakKeys
            // 时间冲突：配上了，但两边拍摄时间相差超过容差 ——
            // 这不是"依据弱"，而是"和更强的证据矛盾"，比弱依据更该先处理
            MatchFilter.CONFLICT -> item.key in conflictKeys
            MatchFilter.ONLY_LEFT -> item.side == Side.LEFT && item.key !in matched
            MatchFilter.ONLY_RIGHT -> item.side == Side.RIGHT && item.key !in matched
        }
        // size 可能是 0（未知），此时体积过滤一律放行 ——
        // 否则"没读到体积"的文件会被 0 下限误杀掉
        val okSize = run {
            val min = extra.minBytes
            val max = extra.maxBytes
            if (min == null && max == null) return@run true
            val size = item.size
            if (size <= 0) return@run true
            (min == null || size >= min) && (max == null || size <= max)
        }
        val okExt = extra.exts.isEmpty() ||
            Naming.extensionOf(item.displayName).lowercase() in extra.exts
        okQuery && okFilter && okSize && okExt
    }
}

/** 列出目录里出现过的扩展名（按数量降序），供过滤面板勾选。 */
fun availableExts(items: List<ImageItem>): List<Pair<String, Int>> {
    val by = HashMap<String, Int>()
    items.forEach {
        val e = Naming.extensionOf(it.displayName).lowercase()
        if (e.isNotBlank()) by[e] = (by[e] ?: 0) + 1
    }
    return by.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}
