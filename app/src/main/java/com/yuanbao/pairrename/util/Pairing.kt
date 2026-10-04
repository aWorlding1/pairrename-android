package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.ExtensionPolicy
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.model.SyncDirection

/** 配对依据。推算出来的东西必须能解释，否则用户不敢信。 */
enum class PairReason {
    CONTENT,
    EXIF,
    NAME,
    /** 名字骨架相同：只差 `(1)` / `- 副本` / `_编辑` 这类尾巴。 */
    SIMILAR,
    SEQ,
    ORDER,
}

/**
 * 这个依据是不是"碰巧对上"的弱证据。
 *
 * 只有 `SEQ` 和 `ORDER` 算弱：
 * - `ORDER` 纯按排列位置对应，位次一动就全错；
 * - `SEQ` 靠名字里的数字相同，而两组毫不相干的图完全可能都从 001 开始编。
 *
 * 反例是 `SIMILAR`：名字骨架被人工改得只剩 `副本 / 编辑` 的差别，
 * 说明这两个名字本来就是同一个人起的 —— 那是强证据，不是猜的。
 *
 * 这个判定是「复核」工作流的**唯一**口径：过滤（MatchFilter.SUSPECT）、
 * 对比面板的醒目提示、统计里的"其中弱依据 N 对"、智能建议的"去复核"
 * 全部走这一个函数，不允许各处自己写一遍 `== ORDER || == SEQ`。
 */
fun isWeakReason(r: PairReason): Boolean = r == PairReason.ORDER || r == PairReason.SEQ

/**
 * 拍摄时间的默认容差（毫秒）。
 *
 * 抽成常量，因为它现在有**两个**消费者：引擎的第 0.5 步（用它决定配不配得上），
 * 以及「时间冲突」复核（用它决定报警不报警）。两处各写一个字面量，
 * 迟早会调了一处忘另一处 —— 那时的表现是
 * "引擎认为在容差内配上了，复核却把它报成冲突"，自相矛盾且极难查。
 */
const val EXIF_TOLERANCE_MS: Long = 2000L

/**
 * 找出「已经配上了，但更强的证据说不对」的那些对 —— **时间冲突**。
 *
 * ## 为什么这个检查是必要的
 *
 * 引擎按 `CONTENT → EXIF → NAME → SIMILAR → SEQ → ORDER` 依次出手，
 * 一个文件被某条规则拿走之后，**后面的规则不会再回头看它**。
 * 所以靠 NAME / SIMILAR / SEQ / ORDER 配上的对，
 * 从来没有人用拍摄时间去校验过 —— 哪怕拍摄时间在优先级里比它们都高。
 *
 * 具体能撞出什么：左边 `IMG_0001.jpg` 拍于 10:00、右边 `旅行.jpg` 拍于 12:00。
 * 两边名字不同、序号对不上、两边都有可靠的拍摄时间且相差两小时 ——
 * 这几乎肯定是两台相机各自从 001 开始编号造成的**错配**，
 * 而这个工具下一步就要把 `IMG_0001.jpg` 改成 `旅行.jpg`。
 *
 * ## 为什么现有的复核机制抓不到
 *
 * `MatchFilter.SUSPECT` 看的是"依据**弱不弱**"。而上面这个例子里
 * 依据可能是 `NAME`（名字相同）—— 在弱依据的定义里它算强证据，
 * 于是完全不会进复核清单。**这是两件正交的事**：
 * 一个依据本身很强，却与另一条更强的证据互相矛盾。
 *
 * ## 判定条件（四条，缺一不可）
 *
 * 1. 两边**都有**拍摄时间（有一边是 0 说明没读到，构不成矛盾）；
 * 2. 时间差**超过**容差（在容差内反而是支持的证据，绝不能报）；
 * 3. 依据不是 [PairReason.EXIF] —— EXIF 步本身就要求差 ≤ 容差，不可能冲突；
 * 4. 依据不是 [PairReason.CONTENT] —— 内容相同意味着同一份字节，时间必然相同；
 *    真报了说明是读取环节出错，属于另一个 bug，不该在这里混着报。
 *
 * **手工指定的配对天然被排除**：`applyManualOverrides` 会把它的依据抹掉，
 * 于是 `reason[key]` 为 null。用户已经明确说过"这两个就是一对"，
 * 再反过来质疑他没有任何意义。
 *
 * @return key → 时间差（毫秒），**左右两侧都记**，这样过滤能像 weakKeys 一样直接用。
 */
fun findTimeConflicts(
    partner: Map<String, String>,
    reason: Map<String, PairReason>,
    byKey: Map<String, ImageItem>,
    toleranceMs: Long = EXIF_TOLERANCE_MS,
): Map<String, Long> {
    if (partner.isEmpty()) return emptyMap()
    val out = HashMap<String, Long>()
    for ((aKey, bKey, abs) in collectConflicts(partner, reason, byKey, toleranceMs)) {
        out[aKey] = abs
        out[bKey] = abs
    }
    return out
}

/**
 * 把「时间冲突对」整理成 `(左 key, 右 key, 时间差)` —— 全项目**唯一**的冲突判定。
 *
 * 三处要用同一把尺子：[findTimeConflicts]（报出来给人看）、
 * [findTimeSwaps]（成对换回来）、[findSideMoves]（单侧搬移）。
 * 各写一份的话，迟早调一处忘一处，表现就是"面板报了冲突、另一边却说没法修"——
 * 用户无从判断谁对（这段话原来写在 [findTimeSwaps] 里，现在落到了实现上）。
 *
 * 判定四条缺一不可（与 [findTimeConflicts] 的说明一一对应）：
 * 1. 两边都有拍摄时间（`takenAt <= 0` 是"没读到"的哨兵）；
 * 2. 时间差**超过**容差（容差内是支持的证据，绝不能报）；
 * 3. 依据不是 [PairReason.EXIF] / [PairReason.CONTENT]（这两种依据不可能冲突）；
 * 4. 手工指定的配对天然被排除（依据被抹掉 → `reason[key]` 为 null）。
 *
 * 返回顺序是**全序**的（差值降序 → 左 key → 右 key）：调用方直接拿去做界面建议，
 * 只按差值排的话并列项在不同刷新里顺序不定，用户会以为界面在乱跳。
 */
internal fun collectConflicts(
    partner: Map<String, String>,
    reason: Map<String, PairReason>,
    byKey: Map<String, ImageItem>,
    toleranceMs: Long = EXIF_TOLERANCE_MS,
): List<Triple<String, String, Long>> {
    if (partner.isEmpty()) return emptyList()
    val out = ArrayList<Triple<String, String, Long>>()
    // 配对是双向存储的，每对只处理一次
    val counted = HashSet<String>()
    partner.forEach { (aKey, bKey) ->
        if (!counted.add(aKey)) return@forEach
        counted.add(bKey)
        val r = reason[aKey] ?: return@forEach
        if (r == PairReason.EXIF || r == PairReason.CONTENT) return@forEach
        val a = byKey[aKey] ?: return@forEach
        val b = byKey[bKey] ?: return@forEach
        if (a.takenAt <= 0 || b.takenAt <= 0) return@forEach
        val diff = a.takenAt - b.takenAt
        val abs = if (diff < 0) -diff else diff
        if (abs <= toleranceMs) return@forEach
        // 统一成「左在前」：侧别只用于排序与展示，不参与时间计算
        if (a.side == Side.LEFT) out += Triple(aKey, bKey, abs)
        else out += Triple(bKey, aKey, abs)
    }
    // 差得最远的先修。排序键必须是**全序**（见 KDoc 末段）。
    out.sortWith(
        compareByDescending<Triple<String, String, Long>> { it.third }
            .thenBy { it.first }
            .thenBy { it.second },
    )
    return out
}

/**
 * 一次「照拍摄时间换过来」的建议。
 *
 * ## 它解决的是哪一类错配
 *
 * 两边各自从 `0001` 开始编号时，引擎按序号会把 (左1, 右1)、(左2, 右2)…
 * 一对一对配上。但只要其中一栏多（或少）一张，真实对应就整体挪位：
 * (左1, 右2)、(左2, 右3)… 表现是**一批对**的拍摄时间差都很大。
 *
 * 这种错位有个很好的性质：它是**成对可换**的。把 (左1,右1) 与 (左2,右2)
 * 互换对象 —— 变成 (左1,右2)、(左2,右1) —— 两对的时间差会**同时**变小。
 * 一次操作修好两对，而不是"解除一个、再手动配一个"重复 N 遍。
 *
 * ## 为什么只做「互惠 2-交换」
 *
 * 只考虑两个对、四个文件之间的互换，并且**参与交换的两对必须都是冲突对**。
 * 不做贪心链（A→B→C→…）：链上的中间状态没人检查过，
 * 而且一处判断失误会顺着一串文件传播出去。
 *
 * 单侧搬移（把 A 从 B 挪到**未配对**的 C）R18 时被排除在这里，理由是"那只会让 B 变成孤儿，
 * 把一个错配换成一个漏配，并没有变好"。这个判断**只对当时那把尺子成立** ——
 * 那时 C 只有"名字/顺序"级别的线索，凭什么说它比 B 更对？
 * R21 补上两把更硬的尺子（C 与 A 的**宽×高×体积完全一致**、
 * 且 C 与 A 的**拍摄时间落在 2 秒容差内**）之后，搬移不再是"把矛盾挪位置"，
 * 而是"把一个**确定错的**配对接成一个**确定对的**配对"。见 [findSideMoves]。
 *
 * ## 收得紧的三道闸（缺一不可，宁可少建议也不能建议错）
 *
 * 1. 交换后**两对都要严格变好**（`after < before`）—— 不允许为了修一对而弄坏另一对；
 * 2. 至少要有一对真的落进容差 —— 否则只是把矛盾从左边挪到右边，白白动了两对文件；
 * 3. 一个文件只能出现在一条建议里 —— 否则两条建议会互相踩，执行一条另一条就失效。
 *
 * 手工指定的配对天然被排除（`reason` 为 null，与 [findTimeConflicts] 同一个理由）：
 * 用户已经说过"这两个就是一对"，不再反向质疑他。
 *
 * @param toleranceMs 与引擎、与 [findTimeConflicts] 共用同一个容差。
 * @return 建议列表，按"差得最远的先修"排序；结果与 Map 遍历顺序无关（确定性）。
 */
data class TimeSwap(
    /** 左栏里那个配错了的文件。 */
    val leftKey: String,
    /** 它当前配着的（被判定为错的）对象。 */
    val oldRightKey: String,
    /** 它**应该**配的对象 —— 来自另一对。 */
    val newRightKey: String,
    /** 原来配着 [newRightKey] 的左栏文件；它会接手 [oldRightKey]。 */
    val otherLeftKey: String,
    /** 交换前 [leftKey] 那对的时间差。 */
    val beforeMs: Long,
    /** 交换后 [leftKey] 那对的时间差。 */
    val afterMs: Long,
    /** 交换前另一半那对的时间差。 */
    val otherBeforeMs: Long,
    /** 交换后另一半那对的时间差。 */
    val otherAfterMs: Long,
) {
    /** 这条建议涉及的全部文档 key（用于界面高亮 / 去重）。 */
    val keys: List<String> get() = listOf(leftKey, oldRightKey, newRightKey, otherLeftKey)
}

/** 找「照拍摄时间换过来」的建议。见 [TimeSwap] 的说明。 */
fun findTimeSwaps(
    partner: Map<String, String>,
    reason: Map<String, PairReason>,
    byKey: Map<String, ImageItem>,
    toleranceMs: Long = EXIF_TOLERANCE_MS,
): List<TimeSwap> {
    // 少于两对就谈不上"互换"。
    if (partner.size < 4) return emptyList()

    // 1) 冲突判定与排序都在 collectConflicts 里 —— 与 [findTimeConflicts] 共用同一份实现。
    //    （原来这里抄了一份，注释里写着"两处各写一份迟早会调一处忘一处"，
    //      R21 加第三个消费者 [findSideMoves] 时索性把它抽成了真函数。）
    val conflicts = collectConflicts(partner, reason, byKey, toleranceMs)
    if (conflicts.size < 2) return emptyList()

    val out = ArrayList<TimeSwap>()
    // 一个文件只能出现在一条建议里：两条建议共用文件会互相踩，
    // 执行完第一条，第二条的前提就不成立了。
    val used = HashSet<String>()
    // 用 for + continue 而不是 `forEach` + `return@标签`：标签版读起来像
    // "跳回外层"，实际语义是"跳过这一项"，嵌套时最容易看错；
    // 而且静态自检认不出 `forEach foo@{` 这种写法，会一直报
    // "未见该标签"的噪音 —— 噪音一多，人就该开始无视检查输出了。
    for ((lKey, rKey, before1) in conflicts) {
        if (lKey in used || rKey in used) continue
        val l = byKey[lKey] ?: continue
        val r = byKey[rKey] ?: continue
        var best: TimeSwap? = null
        var bestAfter1 = Long.MAX_VALUE
        var bestAfter2 = Long.MAX_VALUE
        for ((l2Key, r2Key, otherBefore) in conflicts) {
            if (l2Key == lKey || r2Key == rKey) continue
            if (l2Key in used || r2Key in used) continue
            val l2 = byKey[l2Key] ?: continue
            val r2 = byKey[r2Key] ?: continue
            // 交换后的两个差值：l 改配 r2，l2 接手 r
            val after1 = kotlin.math.abs(l.takenAt - r2.takenAt)
            val after2 = kotlin.math.abs(l2.takenAt - r.takenAt)
            // 闸 1：两对都必须严格变好
            if (after1 >= before1) continue
            if (after2 >= otherBefore) continue
            // 闸 2：至少一对真的进容差，否则只是把矛盾挪了个位置
            if (after1 > toleranceMs && after2 > toleranceMs) continue
            // 择优：剩余差值之和最小；并列时取"较大者更小"（更均衡）。
            // 严格更优才替换 —— 配合上面确定的全序，结果才是确定的。
            // 初值写成 MAX 而不是直接相加：两个 Long.MAX_VALUE 相加会**溢出成负数**，
            // 那样"更优"的判断会莫名其妙地恒为假。短路的写法靠不住，写上就不必担心。
            val score = after1 + after2
            val curScore = if (best == null) Long.MAX_VALUE else bestAfter1 + bestAfter2
            val better = score < curScore ||
                (score == curScore && maxOf(after1, after2) < maxOf(bestAfter1, bestAfter2))
            if (better) {
                best = TimeSwap(
                    leftKey = lKey,
                    oldRightKey = rKey,
                    newRightKey = r2Key,
                    otherLeftKey = l2Key,
                    beforeMs = before1,
                    afterMs = after1,
                    otherBeforeMs = otherBefore,
                    otherAfterMs = after2,
                )
                bestAfter1 = after1
                bestAfter2 = after2
            }
        }
        val picked = best
        if (picked != null) {
            out += picked
            // 闸 3：四个文件全部占用
            used += picked.leftKey
            used += picked.oldRightKey
            used += picked.newRightKey
            used += picked.otherLeftKey
        }
    }
    return out
}

/**
 * 一次「靠尺寸 + 体积认出来的」漏配。
 *
 * ## 它抓的是哪一类漏配
 *
 * 引擎靠 `CONTENT → EXIF → NAME → SIMILAR → SEQ → ORDER` 依次出手，
 * 但后三步全靠名字与顺序 —— 两边名字不同、又**还没跑流水线**
 * （没读拍摄时间、没算内容指纹）时，剩下的就只有「按顺序猜」。
 * 而列表元数据里其实一直躺着一条很硬的证据：**宽 × 高 × 体积**。
 *
 * JPEG 对像素内容极敏感：两张内容不同的照片，即使同机同尺寸连拍，
 * 压缩后体积也几乎不可能完全一样。所以「尺寸相同 + 体积完全相同 + 两边各只有这一张」
 * 的证据强度非常接近内容指纹（第 0 步），却**完全不用读文件内容**。
 *
 * ## 为什么要求「两边各只有这一张」
 *
 * 同尺寸同体积出现**多张**时，那几乎必然是重复图 / 连拍 ——
 * 它们彼此之间没办法再分出谁配谁。猜一个就是制造新的错配，
 * 而这种"我们拿不准"的情况应该留给「查重」，不是在这里赌。
 * 宁可漏，不可配错。
 *
 * @param contentKeys 已算出的内容指纹（跑了流水线才非空）。
 *   两边都**有**指纹且指纹不同 → 强证据说"不是同一张"，尺寸体积再像也要排除；
 *   只有一边有指纹 → 信息不足，仍按尺寸 + 体积判断。
 * @param unlinked 用户手动解除过的（pairKey 集合）。解除过的对绝不再被建议回去 ——
 *   否则用户刚点完"解除"，系统又把同一对塞回来，他会把这个功能整个关掉。
 */
data class SizeMatch(
    val leftKey: String,
    val rightKey: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

/** 找「没配上、但尺寸和体积都一模一样、且两边各只有这一张」的对。见 [SizeMatch]。 */
fun findSizeMatches(
    partner: Map<String, String>,
    left: List<ImageItem>,
    right: List<ImageItem>,
    unlinked: Set<String> = emptySet(),
    contentKeys: Map<String, String> = emptyMap(),
): List<SizeMatch> {
    // 必须先有"可用的元数据 + 未配对 + 没被解除过"。width/height/size 任何一个
    // 是 0，说明列表时就没读到 —— 拿 0 去比会把所有"读不到"的凑成一堆假候选。
    val unmatchedLeft = left.filter {
        it.key !in partner && it.pairKey !in unlinked &&
            it.width > 0 && it.height > 0 && it.size > 0
    }
    val unmatchedRight = right.filter {
        it.key !in partner && it.pairKey !in unlinked &&
            it.width > 0 && it.height > 0 && it.size > 0
    }
    if (unmatchedLeft.isEmpty() || unmatchedRight.isEmpty()) return emptyList()

    // 按 (宽, 高, 体积) 分组。三个都相同才可能是同一张 —— 只比尺寸
    // 会把连拍凑到一起；只比体积更是毫无说服力。
    val leftGroups = HashMap<Triple<Int, Int, Long>, MutableList<ImageItem>>()
    unmatchedLeft.forEach { leftGroups.getOrPut(Triple(it.width, it.height, it.size)) { ArrayList() }.add(it) }
    val rightGroups = HashMap<Triple<Int, Int, Long>, MutableList<ImageItem>>()
    unmatchedRight.forEach { rightGroups.getOrPut(Triple(it.width, it.height, it.size)) { ArrayList() }.add(it) }

    val out = ArrayList<SizeMatch>()
    leftGroups.forEach { (sig, ls) ->
        val rs = rightGroups[sig] ?: return@forEach
        // 必须两边都**恰好一张**（理由见 SizeMatch 头注释）
        if (ls.size != 1 || rs.size != 1) return@forEach
        val l = ls[0]
        val r = rs[0]
        // 跑了流水线后，内容指纹说了算：指纹不同 → 不是同一张，
        // 哪怕尺寸和体积都一模一样（连拍后删掉中间帧再重存，就可能撞成这样）
        val lk = contentKeys[l.key]
        val rk = contentKeys[r.key]
        if (lk != null && rk != null && lk != rk) return@forEach
        out += SizeMatch(l.key, r.key, sig.first, sig.second, sig.third)
    }
    // 排序，保证确定性：Map 遍历顺序不定，而建议列表直接进界面，
    // 顺序乱跳用户会以为界面在闪。
    out.sortWith(compareBy<SizeMatch> { it.leftKey }.thenBy { it.rightKey })
    return out
}

/**
 * 一次「单侧搬移」：把冲突对里的一张搬到**未配对**的那张上去，原对象回到未配对。
 *
 * ## 它解决的是哪一类错配
 *
 * [TimeSwap] 要求参与交换的**两对都是冲突对**，所以它只能修"两张都配错了"的情形。
 * 另有一类修不动：**只有一对配错，而那张图真正的对象还没被配上**。
 *
 * 例子（左栏 2 张、右栏 3 张，多出来的那张是真的多余的）：
 *
 * | 左 | 引擎按顺序配给的右 | 拍摄时间差 | 真实对应 |
 * | --- | --- | --- | --- |
 * | a（100s） | p（300s） | 200s ✗ | a ↔ z（100s） |
 * | b（200s） | q（200s） | 0 ✓ | b ↔ q |
 * | —— | z（100s）未配对 | | p 才是多余的 |
 *
 * 互换救不了它：把 (a,p) 与谁互换都不会让两对同时变好（b 已经是对的了）。
 * 唯一的解法就是**把 a 从 p 挪到 z**、让 p 回到未配对 —— p 本来就该是孤儿。
 *
 * ## 为什么这次敢搬（R18 拒绝过，理由已经变了）
 *
 * R18 拒绝单侧搬移时的理由是"把一个错配换成一个漏配，并没有变好"。
 * 那时候选只有"名字/顺序"级别的线索，说不出它凭什么比现在的对象更对。
 * 本轮补上两把硬尺子，两把**必须同时成立，而且是独立证据**：
 *
 * 1. **尺寸证据**：候选与搬动对象的宽 × 高 × 体积**完全一致**（同 [findSizeMatches]）；
 * 2. **时间证据**：候选与搬动对象的拍摄时间差**落在容差内**（同引擎的 EXIF 步）。
 *
 * 于是搬完的状态是"一个确定错的配对（差几小时）→ 一个确定对的配对（差几秒）"，
 * 代价只是一张卡片回到未配对 —— 而它确实是多余的，不是被牺牲掉的。
 *
 * ## 六道闸（缺一不可）
 *
 * 1. 搬动对象必须在一个**时间冲突**对里（[collectConflicts]，与报给用户看的同一份判定）；
 * 2. 候选必须**未配对**、且没被用户解除过（解除过的不再建议回去，同 [findSizeMatches]）；
 * 3. 两侧签名都必须**唯一**：搬动对象那一侧**没有任何**别的同签名文件
 *    （否则不知道搬哪一张），候选那一侧同签名且未配对的**恰好一张**
 *    （否则不知道搬去哪一张）；
 * 4. 候选必须有拍摄时间（`takenAt > 0`）且**搬完进容差** ——
 *    不是"好一点"，而是"真的对上了"；
 * 5. 两边都有内容指纹且不同 → 排除（同 [findSizeMatches]：连拍删帧重存会撞同尺寸同体积）；
 * 6. 一个文件只能出现在一条建议里（否则执行第一条，第二条的前提就不成立）。
 *
 * 手工指定的配对天然不在冲突集合里，所以第 1 条就把它挡住了 ——
 * 用户说过"这两个是一对"的，不会被搬走。
 *
 * @param toleranceMs 与引擎、[findTimeConflicts]、[findTimeSwaps] 共用同一个容差。
 * @return 建议列表，按"差得最远的先搬"排序；结果与 Map 遍历顺序无关（确定性）。
 */
data class SideMove(
    /** 要搬走的那张（在冲突对里，时间差大的一方之一）。 */
    val moveKey: String,
    /** 现在配着的那张（错的）。搬走之后它回到未配对。 */
    val fromKey: String,
    /** 要搬去的那张（当前未配对，与 [moveKey] 尺寸体积全同、时间进容差）。 */
    val toKey: String,
    /** 搬之前的拍摄时间差（> 容差）。 */
    val fromDeltaMs: Long,
    /** 搬之后的拍摄时间差（≤ 容差）。 */
    val toDeltaMs: Long,
) {
    /** 这条建议涉及的全部文档 key（用于界面高亮 / 去重）。 */
    val keys: List<String> get() = listOf(moveKey, fromKey, toKey)
}

/** 找「把冲突对里的一张搬到未配对的那张」的建议。见 [SideMove]。 */
fun findSideMoves(
    partner: Map<String, String>,
    reason: Map<String, PairReason>,
    byKey: Map<String, ImageItem>,
    unlinked: Set<String> = emptySet(),
    contentKeys: Map<String, String> = emptyMap(),
    toleranceMs: Long = EXIF_TOLERANCE_MS,
): List<SideMove> {
    val conflicts = collectConflicts(partner, reason, byKey, toleranceMs)
    if (conflicts.isEmpty()) return emptyList()

    // 「可用」= 未配对 + 没被用户解除过 + 元信息齐（0 是"没读到"的哨兵，见 findSizeMatches）
    fun usable(it: ImageItem) = it.key !in partner && it.pairKey !in unlinked &&
        it.width > 0 && it.height > 0 && it.size > 0

    // 未配对候选按侧 + 签名建索引：逐个线性扫未配对列表是 O(冲突数 × 未配对数的)，
    // 图多时会明显（同 findSizeMatches 的分组理由）。
    val sig = { it: ImageItem -> Triple(it.width, it.height, it.size) }
    val freeBySide = HashMap<Side, HashMap<Triple<Int, Int, Long>, MutableList<ImageItem>>>()
    for (item in byKey.values) {
        if (!usable(item)) continue
        freeBySide.getOrPut(item.side) { HashMap() }.getOrPut(sig(item)) { ArrayList() }.add(item)
    }
    // 同一侧每个签名的**总张数**（不论配没配上）—— 闸 3 的"搬动方唯一"靠它判
    val sideSigCount = HashMap<Side, HashMap<Triple<Int, Int, Long>, Int>>()
    for (item in byKey.values) {
        if (item.width <= 0 || item.height <= 0 || item.size <= 0) continue
        val m = sideSigCount.getOrPut(item.side) { HashMap() }
        m[sig(item)] = (m[sig(item)] ?: 0) + 1
    }

    val out = ArrayList<SideMove>()
    // 闸 6：一个文件只能出现在一条建议里
    val used = HashSet<String>()
    // for + continue（不用 forEach + 标签）：标签版读起来像"跳回外层"，
    // 而且静态自检认不出 `forEach foo@{`，会一直报"未见该标签"的噪音。
    for ((lKey, rKey, before) in conflicts) {
        for (dir in 0..1) {
            val moveKey = if (dir == 0) lKey else rKey
            val fromKey = if (dir == 0) rKey else lKey
            if (moveKey in used || fromKey in used) continue
            val move = byKey[moveKey] ?: continue
            val targetSide = if (dir == 0) Side.RIGHT else Side.LEFT
            val candidates = freeBySide[targetSide]?.get(sig(move)) ?: continue
            // 闸 3（候选侧）：同签名且未配对的必须恰好一张
            if (candidates.size != 1) continue
            // 闸 3（搬动侧）：自己这一侧同签名的必须**只有自己** ——
            // 有两张一模一样的候补时，搬哪张都是猜
            if (sideSigCount[move.side]?.get(sig(move)) != 1) continue
            val to = candidates[0]
            if (to.key == fromKey || to.key in used) continue
            // 闸 4：搬完必须真的对上（不是"好一点"）
            if (to.takenAt <= 0) continue
            val after = kotlin.math.abs(move.takenAt - to.takenAt)
            if (after > toleranceMs) continue
            // 闸 5：内容指纹说了算（两边都有且不同 → 不是同一张）
            val mk = contentKeys[move.key]
            val tk = contentKeys[to.key]
            if (mk != null && tk != null && mk != tk) continue
            out += SideMove(
                moveKey = moveKey,
                fromKey = fromKey,
                toKey = to.key,
                fromDeltaMs = before,
                toDeltaMs = after,
            )
            used += moveKey
            used += fromKey
            used += to.key
        }
    }
    return out
}

/**
 * 一次「整体平移」修复：把冲突的那一批按拍摄时间**重新配一遍**。
 *
 * ## 它解决的是哪一类错配（R18 / R21 都修不了的那类）
 *
 * 两边各自从 `0001` 编号造成的**均匀平移**：右栏的时间序列整体挪了一位，
 * 于是引擎按序号配出来的每一对都差一个大体相同的量。
 *
 * | 左 | 右（按序号配上的） | 时间差 |
 * | --- | --- | --- |
 * | 100s | 0s | 100s ✗ |
 * | 200s | 100s | 100s ✗ |
 * | 300s | 200s | 100s ✗ |
 *
 * 真实对应是「左 100 ↔ 右 100」「左 200 ↔ 右 200」，左 300 与右 0 各自没有对象。
 *
 * 为什么前两轮修不了：
 * - [findTimeSwaps]（互惠 2-交换）要求**两对同时变好**。平移里把相邻两对互换，
 *   只会让一对变好、另一对更差（差值从"差一位"变成"差两位"），闸 1 直接挡掉 ——
 *   互换能修的是"局部颠倒"，不是"整体平移"；
 * - [findSideMoves]（单侧搬移）要求目标**当前未配对**。平移里每一张的真对象
 *   此刻都**配着别人**，所以一道候选都找不到。
 *
 * 平移的本质是"要同时改一整串"，逐对判断永远看不出解法 —— 所以本轮的做法不是
 * 再找一条"单对判据"，而是**把整批交给同一套匹配算法**：
 * 取冲突池的左项与右项，跑 [matchByTime]（引擎第 0.5 步的同一个实现），
 * 得到的新配对**每一对都在容差内**，匹配不上的那些就明确回到未配对。
 *
 * ## 为什么这不是 R18 拒绝过的「贪心链」
 *
 * 贪心链指的是一步一步挪、**中间状态没人验证**。这里是**一次性、整体地**
 * 给出最终分配，并且逐条验证最终状态（见下面五道闸），
 * 中间不存在"改到一半"的状态 —— 要么整批成立，要么一条建议都不给。
 *
 * ## 五道闸（缺一不可）
 *
 * 1. 至少 2 对冲突（1 对交给 [findSideMoves]，它的证据更硬：尺寸体积全同）；
 * 2. 匹配结果至少 2 对**确实换了对象**（否则这条建议没有意义）；
 * 3. 每一对改动都**严格变好**（新差值 < 旧差值）—— 不允许为了修一串而弄坏某一对；
 * 4. 被腾出来的项在池子里**确实找不到容差内的对象**（否则是匹配不完整，宁可不动）；
 * 5. 池子外的文件一概不碰（池子 = 冲突对的两端，构建时就固定了）。
 *
 * @param toleranceMs 与引擎、与另外两个复核函数共用同一个容差。
 * @return null 表示"这批冲突不该用整体平移来修"。
 */
data class TimeRealign(
    /** 要建立的新配对（左 key → 右 key），每一对的时间差都 ≤ 容差。 */
    val pairs: List<Pair<String, String>>,
    /** 匹配不上、要回到未配对的左栏 key。 */
    val freedLeft: List<String>,
    /** 匹配不上、要回到未配对的右栏 key。 */
    val freedRight: List<String>,
    /** 修之前有多少对在冲突里（展示用）。 */
    val beforeConflicts: Int,
) {
    /** 这条建议涉及的全部文档 key。 */
    val keys: List<String>
        get() = pairs.flatMap { listOf(it.first, it.second) } + freedLeft + freedRight
}

/** 找「整体平移」的修复方案。见 [TimeRealign]。 */
fun findTimeRealign(
    partner: Map<String, String>,
    reason: Map<String, PairReason>,
    byKey: Map<String, ImageItem>,
    toleranceMs: Long = EXIF_TOLERANCE_MS,
): TimeRealign? {
    // 闸 1：一对冲突交给 findSideMoves（判据更硬），这里只处理成批的
    val conflicts = collectConflicts(partner, reason, byKey, toleranceMs)
    if (conflicts.size < 2) return null

    // 池子 = 冲突对的两端。**只在这里面动**，池子外一个文件都不碰（闸 5）
    val poolLeft = ArrayList<ImageItem>()
    val poolRight = ArrayList<ImageItem>()
    val seen = HashSet<String>()
    for ((lKey, rKey, _) in conflicts) {
        val l = byKey[lKey] ?: continue
        val r = byKey[rKey] ?: continue
        if (seen.add("L$lKey")) poolLeft += l
        if (seen.add("R$rKey")) poolRight += r
    }
    if (poolLeft.size < 2 || poolRight.size < 2) return null

    // 池子里重新配 —— 用引擎第 0.5 步的同一套匹配（不在池外找对象）
    val matched = matchByTime(poolLeft, poolRight, toleranceMs)
    if (matched.size < 2) return null

    val pairs = ArrayList<Pair<String, String>>()
    val movedLeft = HashSet<String>()
    val movedRight = HashSet<String>()
    var changed = 0
    for ((lKey, rKey) in matched) {
        val l = byKey[lKey] ?: return null
        val r = byKey[rKey] ?: return null
        val before = partner[lKey] ?: return null
        val beforeItem = byKey[before] ?: return null
        val afterDiff = kotlin.math.abs(l.takenAt - r.takenAt)
        // 闸 3：严格变好。相等就说明这条改动没有收益，不该进方案 ——
        // 一条"没收益"的条目会拖着一个被腾出来的对象一起下水。
        if (afterDiff >= kotlin.math.abs(l.takenAt - beforeItem.takenAt)) continue
        // 闸 2 的口径：只有真的换了对象才算数
        if (before != rKey) changed++
        pairs += lKey to rKey
        movedLeft += lKey
        movedRight += rKey
    }
    if (changed < 2 || pairs.size < 2) return null

    val freedLeft = poolLeft.filter { it.key !in movedLeft }.map { it.key }
    val freedRight = poolRight.filter { it.key !in movedRight }.map { it.key }
    // 闸 4：被腾出来的必须真的没有对象 —— 池子里还有容差内的候选却说"没配上"，
    // 说明匹配没跑完（比如并发/数据不一致），这种半成品不能拿去改用户的文件
    for (k in freedLeft) {
        val it = byKey[k] ?: return null
        if (poolRight.any { r -> !movedRight.contains(r.key) && kotlin.math.abs(r.takenAt - it.takenAt) <= toleranceMs }) return null
    }
    for (k in freedRight) {
        val it = byKey[k] ?: return null
        if (poolLeft.any { l -> !movedLeft.contains(l.key) && kotlin.math.abs(l.takenAt - it.takenAt) <= toleranceMs }) return null
    }

    return TimeRealign(
        pairs = pairs.sortedWith(compareBy({ it.first }, { it.second })),
        freedLeft = freedLeft.sorted(),
        freedRight = freedRight.sorted(),
        beforeConflicts = conflicts.size,
    )
}

/**
 * 配对结果。
 *
 * @param keys      所有已配对的文档 key（左右两侧都包含）
 * @param partner   key → 对方 key 的双向映射
 * @param offset    由序号锚点推算出的目标栏整体偏移量（右栏相对左栏后移多少位）
 * @param bySeq     其中靠「序号相同」确定的配对数
 * @param byOrder   其中靠「顺序推算」确定的配对数
 * @param bySimilar 其中靠「名字骨架相同」确定的配对数
 */
data class PairResult(
    val keys: Set<String>,
    val partner: Map<String, String>,
    val offset: Int,
    val bySeq: Int,
    val byOrder: Int,
    val bySimilar: Int = 0,
    /** key → 配对依据。 */
    val reason: Map<String, PairReason> = emptyMap(),
    /** 序号匹配时，命中的序号（用于展示）。 */
    val seqHit: Map<String, Int> = emptyMap(),
) {
    /**
     * 弱依据配对涉及的**全部文档 key**（左右两边都在内），供过滤使用。
     *
     * 刻意做成**派生属性**而不是构造参数：`applyManualOverrides` 是用
     * `auto.copy(reason = ...)` 造新对象的，存成字段就要在那边再同步一次 ——
     * 漏一次就会得出"用户已经手动确认过的配对仍被标成可疑"这种反向结论。
     * 派生之后 reason 一变它自动跟着变，且手动配对会把 reason 抹掉
     * （见 applyManualOverrides），于是手动确认过的对自动退出可疑集合。
     *
     * 额外要求 key **必须当前仍在 [partner] 里**：
     * 手动抢配对时被抢走的那一方会变成未配对，但它的 reason 条目可能还在
     * （见 applyManualOverrides 的收尾清理）。少了这道闸，过滤到「仅弱依据」
     * 会冒出根本还没配对的文件 —— 用户会当成 bug。
     */
    val weakKeys: Set<String>
        get() = reason.asSequence()
            .filter { it.key in partner && isWeakReason(it.value) }
            .map { it.key }
            .toSet()

    /** 弱依据配对的**对数**（reason 左右各记一条，所以除以 2）。 */
    val weakPairs: Int
        get() = weakKeys.size / 2
}

/**
 * 按拍摄时间做「全局最近未占用」匹配 —— 引擎第 0.5 步的算法本体。
 *
 * 抽成独立函数是因为它现在有**两个消费者**：
 * 1. [Pairing.compute] 的第 0.5 步（`useExif` 打开时）；
 * 2. [findTimeRealign] —— 对"冲突池"跑同一套匹配来修整体平移。
 *
 * 两处必须是同一个实现：如果建议按 A 算法改、引擎按 B 算法配，
 * 用户按建议改完之后仍会被报冲突 —— 那就成了自相矛盾。
 *
 * ## 算法（与实现一样重要，因为它决定了"哪个才算最近"）
 *
 * 右栏按时间排序后，每个左栏项只需在**插入点附近**向两侧交替走：
 * 拿到的第一个未被占用的项就是全局最近的未占用项（左侧更近取左侧，
 * 否则取右侧），一旦"手上的候选已经更近"或"还没候选但已超出容差"就停。
 * 比"每个左项都完整扫一遍右栏"的 O(n·m) 快得多（5000 张时从 2500 万次比较降到几千次）。
 *
 * @param taken 已被**更硬依据**占用的文档 key（左 + 右都算）。
 *   调用方传当前的 partner 键集即可；函数内部会在其副本上继续累加。
 * @return 有序的「左 key to 右 key」配对，每一对的时间差都 ≤ [toleranceMs]。
 *   顺序由左栏的时间排序决定 —— 确定性，不受列表顺序影响。
 */
internal fun matchByTime(
    left: List<ImageItem>,
    right: List<ImageItem>,
    toleranceMs: Long = EXIF_TOLERANCE_MS,
    taken: Set<String> = emptySet(),
): List<Pair<String, String>> {
    // 排序键取**全序**：takenAt 相同的两张（连拍 / 同一秒）如果只按时间排，
    // 谁在前就取决于输入列表的顺序 —— 那样"同一批文件、换个扫描顺序"
    // 会算出不同的配对。补上 key 作次级键，结果才真正与列表顺序无关。
    // （[collectConflicts] 用全序是同一个理由。）
    val timed = right.filter { it.takenAt > 0 }
        .sortedWith(compareBy({ it.takenAt }, { it.key }))
    // 左栏也按时间走：相邻的两张原图去争同一个右栏文件时，
    // 结果稳定可复现，不再取决于谁先出现在列表里
    val leftByTime = left.filter { it.takenAt > 0 }
        .sortedWith(compareBy({ it.takenAt }, { it.key }))
    val used = HashSet<String>(taken)
    val out = ArrayList<Pair<String, String>>()
    leftByTime.forEach { l ->
        if (l.key in used) return@forEach
        // 二分：第一个 takenAt >= l.takenAt 的下标
        var lo = 0
        var hi = timed.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (timed[mid].takenAt < l.takenAt) lo = mid + 1 else hi = mid
        }
        // 从插入点向两侧交替取更近的那个 —— 这样拿到的第一个
        // 「未被占用」的项，就是全局最近的未占用项
        var down = lo - 1
        var up = lo
        var best: ImageItem? = null
        var bestDiff = Long.MAX_VALUE
        while (down >= 0 || up < timed.size) {
            val dDiff = if (down >= 0) l.takenAt - timed[down].takenAt else 0L
            val uDiff = if (up < timed.size) timed[up].takenAt - l.takenAt else 0L
            val takeDown = when {
                down < 0 -> false
                up >= timed.size -> true
                else -> dDiff <= uDiff
            }
            val diff = if (takeDown) dDiff else uDiff
            // 本轮取的是「更近的那一侧」，所以：
            // 手上已有候选且更近 → 对面只会更远，停；
            // 还没候选但已超出误差 → 对面同样超误差，停。
            if (best != null && diff >= bestDiff) break
            if (best == null && diff > toleranceMs) break
            val r = if (takeDown) timed[down--] else timed[up++]
            // used 包含本轮已配上的，所以不需要再额外维护一张 taken 表
            if (r.key !in used && diff < bestDiff) {
                bestDiff = diff
                best = r
            }
        }
        val r = best?.takeIf { bestDiff <= toleranceMs } ?: return@forEach
        used += l.key
        used += r.key
        out += l.key to r.key
    }
    return out
}

/**
 * 配对引擎。
 *
 * 为什么不能直接用「主文件名相同」当配对依据：
 * 用户打开这个应用的原因恰恰是两边名字不一样、需要统一，
 * 所以在他动手之前，按名字判定的配对数恒为 0，配对角标和过滤功能等于摆设。
 * 真正可靠的信号是**文件名里的序号**与**排列顺序**。
 */
object Pairing {

    /**
     * 提取文件名中的序号（`IMG_0001` → 1，`photo_02` → 2，无数字 → null）。
     *
     * **这里只是委托**：真正的实现、以及"括号里的副本计数不是序号"这条规则，
     * 都在 `Naming.seqOf` 里，全项目只有那一份。
     * 以前这里和 `Naming.seqOf` 是两套独立实现，同一个文件名会得到相反结论
     * （`IMG_2024.jpg` 在统计里"没有序号"、在配对里却有），
     * 修一处忘另一处就是必然。
     *
     * 唯一传出去的区别是 [Naming.seqOf] 的 `treatYearAsSeq = true`：
     * 配对引擎**必须**把 4 位年份也算序号。相机连拍编号真的会走到 2000+
     * （`DSC_2099.JPG`），把 2000~2099 排除掉会让这批文件整体退化成
     * 「按排列顺序」配对 —— 那是依据最弱的一档，比错配一个年份危险得多。
     */
    fun extractSeq(name: String): Int? = Naming.seqOf(name, treatYearAsSeq = true)

    /**
     * 计算左右两栏的配对关系。
     *
     * 按「证据强度」从强到弱依次尝试，一个文件只会被第一条命中的规则拿走：
     * 0. 内容指纹相同 → 唯一的 100% 确定依据；
     * 0.5. 拍摄时间相同 → 文件名全废时最后的硬锚点；
     * 1. 主文件名相同 → 已经统一过的情况；
     * 1.5. 名字骨架相同 → 只差 `(1)` / `- 副本` / `_编辑` 这类尾巴；
     * 2. 序号相同 → 配对，并据此统计出两边的主流偏移量；
     * 3. 剩下的按主流偏移量做顺序配对（覆盖「序号体系完全不同」的情况）。
     *
     * 为什么「相似名」要排在「序号相同」前面：
     * 名字能对上说明本来就是同一张的两种叫法；而序号相同只是数字碰巧一样，
     * 两张毫不相干的图完全可能都叫 001。证据更强的先出手，
     * 才不会出现"明明有 IMG_1234 (1)，却把 IMG_1234 配给了别的 1234"。
     */
    fun compute(
        left: List<ImageItem>,
        right: List<ImageItem>,
        /** key → 内容指纹。非空且跨栏相同时，优先级高于一切推算。 */
        contentKeys: Map<String, String> = emptyMap(),
        /**
         * 是否用 EXIF 拍摄时间配对。
         * 相册导出/聊天工具传输后文件名全变，序号和顺序都废了，
         * 但拍摄时间还在 —— 这时它是唯一可靠的锚点。
         */
        useExif: Boolean = false,
        /** 允许的拍摄时间误差（毫秒）。见 [EXIF_TOLERANCE_MS]。 */
        exifToleranceMs: Long = EXIF_TOLERANCE_MS,
    ): PairResult {
        if (left.isEmpty() || right.isEmpty()) {
            return PairResult(emptySet(), emptyMap(), 0, 0, 0)
        }

        val partner = HashMap<String, String>((left.size + right.size) * 2)
        val reason = HashMap<String, PairReason>()
        val seqHit = HashMap<String, Int>()
        var bySeq = 0
        var byOrder = 0

        // ---- 第 0 步：内容指纹相同。这是唯一 100% 确定的依据 ----
        if (contentKeys.isNotEmpty()) {
            val rightByHash = HashMap<String, MutableList<ImageItem>>()
            right.forEach { r ->
                val h = contentKeys[r.key]
                if (!h.isNullOrEmpty()) rightByHash.getOrPut(h) { ArrayList() }.add(r)
            }
            left.forEach { l ->
                if (partner.containsKey(l.key)) return@forEach
                val h = contentKeys[l.key]
                if (h.isNullOrEmpty()) return@forEach
                val r = rightByHash[h]?.firstOrNull { it.key !in partner } ?: return@forEach
                partner[l.key] = r.key
                partner[r.key] = l.key
                reason[l.key] = PairReason.CONTENT
                reason[r.key] = PairReason.CONTENT
            }
        }

        // ---- 第 0.5 步：EXIF 拍摄时间相同（在允许误差内）----
        if (useExif) {
            // 算法本体抽到 [matchByTime] 里了 —— R22 的「整体平移」修复
            // 要对**冲突池**跑同一套匹配，两处必须是同一个实现：
            // "引擎怎么按时配的"和"建议怎么改"不一致的话，
            // 用户按建议改完仍会被报冲突，那就成了自相矛盾。
            matchByTime(left, right, exifToleranceMs, partner.keys).forEach { (lk, rk) ->
                partner[lk] = rk
                partner[rk] = lk
                reason[lk] = PairReason.EXIF
                reason[rk] = PairReason.EXIF
            }
        }

        // ---- 第 1 步：主文件名相同 ----
        // 用「列表」而不是「单个」：右栏完全可能同时有 a.jpg 和 a.png，
        // 只留最后一个会让左栏的同名文件白等一场，然后掉到"按顺序猜"里去。
        val rightByName = HashMap<String, MutableList<ImageItem>>()
        right.forEach { r -> rightByName.getOrPut(r.pairKey) { ArrayList() }.add(r) }

        left.forEach { l ->
            if (partner.containsKey(l.key)) return@forEach
            val r = rightByName[l.pairKey]?.firstOrNull { it.key !in partner } ?: return@forEach
            partner[l.key] = r.key
            partner[r.key] = l.key
            reason[l.key] = PairReason.NAME
            reason[r.key] = PairReason.NAME
        }

        // 右栏 key → 下标。原来在循环里用 `right.indexOfFirst { … }` 现找，
        // 是 O(n) 的线性扫描套在 n 次循环里，整体 O(n²)：
        // 5000 张就是 2500 万次比较，正好卡在用户按下配对的那一刻。
        // 建一次索引降到 O(n)。相似名 / 序号两步都要用它记偏移，所以提前建。
        val rightIndex = HashMap<String, Int>(right.size)
        right.forEachIndexed { idx, r -> rightIndex[r.key] = idx }
        // 偏移量 → 出现次数。样本来自所有「有把握的」配对（相似名 + 序号），
        // 样本越多，第 3 步猜出来的整体错位越准。
        val offsets = HashMap<Int, Int>()

        // ---- 第 1.5 步：名字骨架相同（只差副本 / 编辑尾巴）----
        // IMG_1234.jpg ↔ IMG_1234 (1).jpg / IMG_1234 - 副本.jpg / IMG_1234_编辑.jpg
        // 主文件名并不相同，所以第 1 步抓不到；末尾那个 (1) 又会让第 2 步
        // 抓错序号 —— 这类文件以前只能听天由命地掉到第 3 步"按顺序猜"。
        val rightByStem = HashMap<String, MutableList<ImageItem>>()
        right.forEach { r ->
            val stem = normStem(r.displayName)
            if (usableStem(stem)) rightByStem.getOrPut(stem) { ArrayList() }.add(r)
        }
        var bySimilar = 0
        left.forEachIndexed { li, l ->
            if (partner.containsKey(l.key)) return@forEachIndexed
            val stem = normStem(l.displayName)
            if (!usableStem(stem)) return@forEachIndexed
            val r = rightByStem[stem]?.firstOrNull { it.key !in partner } ?: return@forEachIndexed
            partner[l.key] = r.key
            partner[r.key] = l.key
            reason[l.key] = PairReason.SIMILAR
            reason[r.key] = PairReason.SIMILAR
            rightIndex[r.key]?.let { offsets[it - li] = (offsets[it - li] ?: 0) + 1 }
            bySimilar++
        }

        // ---- 第 2 步：序号相同 ----
        val rightBySeq = HashMap<Int, MutableList<ImageItem>>()
        right.forEach { r ->
            extractSeq(r.displayName)?.let { rightBySeq.getOrPut(it) { ArrayList() }.add(r) }
        }

        left.forEachIndexed { li, l ->
            if (partner.containsKey(l.key)) return@forEachIndexed
            val seq = extractSeq(l.displayName) ?: return@forEachIndexed
            val candidates = rightBySeq[seq] ?: return@forEachIndexed
            val r = candidates.firstOrNull { it.key !in partner } ?: return@forEachIndexed
            partner[l.key] = r.key
            partner[r.key] = l.key
            reason[l.key] = PairReason.SEQ
            reason[r.key] = PairReason.SEQ
            seqHit[l.key] = seq
            seqHit[r.key] = seq
            val ri = rightIndex[r.key] ?: -1
            if (ri >= 0) offsets[ri - li] = (offsets[ri - li] ?: 0) + 1
            bySeq++
        }

        // ---- 第 3 步：按主流偏移量做顺序配对 ----
        val offset = offsets.maxByOrNull { it.value }?.key ?: 0
        val safeOffset = offset.coerceIn(-(left.size - 1), right.size - 1)

        left.forEachIndexed { li, l ->
            if (partner.containsKey(l.key)) return@forEachIndexed
            val ri = li + safeOffset
            if (ri !in right.indices) return@forEachIndexed
            val r = right[ri]
            if (r.key in partner) return@forEachIndexed
            partner[l.key] = r.key
            partner[r.key] = l.key
            reason[l.key] = PairReason.ORDER
            reason[r.key] = PairReason.ORDER
            byOrder++
        }

        return PairResult(
            keys = partner.keys.toSet(),
            partner = partner,
            offset = safeOffset,
            bySeq = bySeq,
            byOrder = byOrder,
            bySimilar = bySimilar,
            reason = reason,
            seqHit = seqHit,
        )
    }

    // ------------------------------------------------------------------
    // 名字骨架（相似名配对用）
    // ------------------------------------------------------------------

    /** 末尾的副本计数：`(1)`、`（12）`、`[3]`、`【4】`。 */
    private val END_BRACKET_COUNTER = Regex("""[\(\（\[\【]\s*\d{1,3}\s*[\)\）\]\】]\s*$""")

    /**
     * 末尾的「同一张图、另一份」标记。
     *
     * 前面用零宽断言 `(?<![a-z])` 而不是 `[^a-z]`：后者会把紧邻的那个字符
     * 一起吃进匹配范围，`img1234编辑` 会被削成 `img123` —— 序号最后一位
     * 直接没了，那才是真的配不上。零宽断言只判断"前面不是英文字母"，
     * 不消耗字符，所以 `img1234编辑` 与 `img_编辑` 都能正确剥成
     * `img1234` / `img`。
     */
    private val END_NOISE = Regex(
        """(?<![a-z])(?:副本|复件|拷贝|复制|已编辑|编辑|修改|修图|调色|美化|""" +
            """原图|原片|导出|完成|最终|终稿|无水印|copy|edit|edited|final|""" +
            """export|hdr|retouch)\d{0,2}\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 归一后必须还剩点东西，而且得含字母。
     * 纯数字（`1234` vs `1234`）交给「序号相同」那一步更合适：
     * 那一步会检查两边是否真的都有序号，给出的标签也更准确。
     */
    private fun usableStem(stem: String): Boolean = stem.length >= 3 && stem.any { it.isLetter() }

    /**
     * 名字骨架：把「同一张图的另一份」这类尾巴全部剥掉，只留身份部分。
     *
     * `IMG_1234.jpg`、`IMG_1234 (1).jpg`、`IMG_1234 - 副本.jpg`、
     * `IMG_1234_编辑.jpg` 的骨架都是 `img1234`，显然该配成一对 ——
     * 但主文件名并不相同，第 1 步抓不到，而末尾那个 `(1)` 又会让
     * 序号提取抓错数字。这类文件以前只能掉到「按顺序猜」，
     * 一旦右栏整体错位就全错。
     *
     * 刻意只做**确定性**变换（剥标记 + 归一分隔符），没有编辑距离、
     * 没有相似度阈值：同样的输入永远得到同样的结果。用户点开「配对依据」
     * 看到的就是真实规则，不会出现"它凭什么这么配"的玄学。
     */
    fun normStem(name: String): String {
        var s = name.substringBeforeLast('.', name).lowercase()
        var progressed = true
        while (progressed) {
            progressed = false
            val trimmed = s.trimEnd(' ', '\t', '-', '_', '.', '·', '#', '+')
            if (trimmed != s) {
                s = trimmed
                progressed = true
            }
            // `(1)` 这类副本计数剥掉后，可能又露出发音尾巴，所以放在循环里
            val counter = END_BRACKET_COUNTER.find(s)
            if (counter != null && counter.range.first > 0) {
                s = s.substring(0, counter.range.first)
                progressed = true
                continue
            }
            val noise = END_NOISE.find(s)
            if (noise != null && noise.range.first > 0) {
                s = s.substring(0, noise.range.first)
                progressed = true
            }
        }
        // 分隔符不携带身份信息：img_1234 / IMG 1234 / img-1234 是同一个骨架
        return s.filter { it.isLetterOrDigit() }
    }

    /**
     * 按配对关系生成「统一名字」计划：每一对中，把来源的名字应用到目标身上。
     *
     * 与 [com.yuanbao.pairrename.util.Batch.buildAlignPlan] 的区别：
     * 后者按索引偏移一一对应，一旦两边数量不等（缺几张 / 多几张截图）
     * 或者中间有缺失，尾部就会整体错位，只能靠手动试偏移救场。
     * 这里直接走已经算好的配对映射，**天然容忍数量不等和中间缺失** ——
     * 而这正是用户需要这个应用时最常见的情形。
     *
     * @param partner   compute() 得到的双向映射
     * @return 只包含「确实需要改名」的行；已统一的对会被跳过去。
     */
    fun buildPairPlan(
        left: List<ImageItem>,
        right: List<ImageItem>,
        partner: Map<String, String>,
        direction: SyncDirection,
        existingLeft: Set<String>,
        existingRight: Set<String>,
        settings: AppSettings,
    ): List<PlanRow> {
        if (partner.isEmpty()) return emptyList()

        val byKey = HashMap<String, ImageItem>(left.size + right.size)
        (left + right).forEach { byKey[it.key] = it }

        // 目标栏的已占用名字。改名过程中同步维护，避免同批互相撞车。
        val usedTarget = (if (direction == SyncDirection.LEFT_TO_RIGHT) existingRight else existingLeft)
            .toMutableSet()
        // 来源栏不动，但它的名字会「搬」到目标栏，所以要预先占座
        val sourceNames = (if (direction == SyncDirection.LEFT_TO_RIGHT) left else right)
            .map { it.displayName }
            .toSet()

        val rows = ArrayList<PlanRow>(partner.size / 2)
        val handled = HashSet<String>()

        left.forEach { l ->
            val rKey = partner[l.key] ?: return@forEach
            val r = byKey[rKey] ?: return@forEach
            if (l.key in handled || r.key in handled) return@forEach
            handled += l.key
            handled += r.key

            val source = if (direction == SyncDirection.LEFT_TO_RIGHT) l else r
            val target = if (direction == SyncDirection.LEFT_TO_RIGHT) r else l

            // 已经一致就不用动了
            if (Naming.baseOf(source.displayName) == Naming.baseOf(target.displayName)) return@forEach
            if (!target.canRename) return@forEach

            val ext = if (settings.extensionPolicy == ExtensionPolicy.USE_SOURCE) {
                Naming.extensionOf(source.displayName)
            } else {
                Naming.extensionOf(target.displayName)
            }
            val desiredBase = Naming.sanitize(
                Naming.withTemplate(Naming.baseOf(source.displayName), settings.prefix, settings.suffix),
                reserve = ext.length + 1,
            )
            val candidate = Naming.join(desiredBase, ext)

            usedTarget.remove(target.displayName)
            // 来源栏同名文件「搬过来」后也会占位，提前排除
            val blocked = usedTarget.any { it.equals(candidate, ignoreCase = true) } ||
                sourceNames.any { it.equals(candidate, ignoreCase = true) && it != source.displayName }
            val finalName = if (blocked) {
                Naming.resolveConflict(desiredBase, ext, usedTarget + sourceNames, settings.numbering)
            } else {
                candidate
            }
            usedTarget.add(finalName)

            rows += PlanRow(
                target = target,
                oldName = target.displayName,
                newName = finalName,
                sourceName = source.displayName,
                conflict = blocked,
            )
        }

        // 保持与列表一致的稳定顺序
        return rows.sortedBy { Naming.naturalKey(it.oldName) }
    }

    /**
     * 导出配对报告（CSV，带 BOM）。
     *
     * 用途：几百对图片的配对结果不可能一个个点开看，
     * 导出成表格扫一眼就能发现"这一对配错了"，比在界面上翻高效得多。
     */
    fun reportCsv(
        left: List<ImageItem>,
        right: List<ImageItem>,
        partner: Map<String, String>,
        reason: Map<String, PairReason>,
    ): String {
        val sb = StringBuilder()
        sb.append('\ufeff')
        sb.append("左栏,右栏,配对依据,状态\n")
        val nameOf = HashMap<String, String>()
        (left + right).forEach { nameOf[it.key] = it.displayName }

        left.forEach { l ->
            val other = partner[l.key]
            val r = reason[l.key]
            sb.append(quote(l.displayName)).append(',')
                .append(quote(other?.let { nameOf[it] }.orEmpty())).append(',')
                .append(quote(reasonLabel(r))).append(',')
                .append(if (other == null) "未配对" else "已配对")
                .append('\n')
        }
        // 右栏没被配上的也要列出来，否则"多出来的那几张"在报告里看不见
        val pairedRight = partner.values.toSet()
        right.forEach { r ->
            if (r.key in pairedRight) return@forEach
            sb.append(quote("")).append(',')
                .append(quote(r.displayName)).append(',')
                .append(quote("")).append(',')
                .append("右栏独有")
                .append('\n')
        }
        return sb.toString()
    }

    private fun reasonLabel(r: PairReason?): String = when (r) {
        PairReason.CONTENT -> "文件内容相同"
        PairReason.EXIF -> "拍摄时间相同"
        PairReason.NAME -> "文件名相同"
        PairReason.SIMILAR -> "名字骨架相同"
        PairReason.SEQ -> "序号相同"
        PairReason.ORDER -> "按排列顺序"
        null -> ""
    }

    private fun quote(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
}
