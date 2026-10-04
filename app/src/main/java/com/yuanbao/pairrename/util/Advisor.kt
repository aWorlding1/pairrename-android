package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.ImageItem

/**
 * 下一步该做什么的建议。
 *
 * @param priority 越大越该先做。
 * @param action 可选的执行标记，界面据此决定点一下做什么。
 */
data class Advice(
    val title: String,
    val detail: String,
    val priority: Int,
    val action: AdviceAction = AdviceAction.NONE,
)

/** 建议对应的可执行动作。 */
enum class AdviceAction {
    NONE,
    READ_EXIF,      // 读取拍摄时间
    VERIFY_CONTENT, // 按内容校验
    ALIGN,          // 顺序对齐
    SYNC,           // 按配对统一
    PIPELINE,       // 一键流水线
    FIX_NAMES,      // 一键清理文件名
    PICK_FOLDER,    // 去选文件夹
    REVIEW_SUSPECT, // 复核弱依据配对（序号 / 顺序猜出来的那些）
    REVIEW_CONFLICT,// 复核时间冲突配对（两边拍摄时间差得离谱却配成了一对）
    SWAP_CONFLICT,  // 照拍摄时间换过来（成对互换，两对的时间差同时变小）
    UNDO_SWAP,      // 撤回刚做的「按时间重配」
    LINK_SIZE,      // 一键配上「尺寸 + 体积完全一致」的漏配
    SIDE_MOVE,      // 把冲突对里的一张搬到「尺寸体积全同 + 时间进容差」的那张上
    UNDO_SIDE_MOVE, // 撤回刚做的「单侧搬移」
    REALIGN_TIME,   // 把整批冲突按拍摄时间重新配一遍（修「整体平移」）
    UNDO_REALIGN,   // 撤回刚做的「整体平移」
}

/**
 * 智能建议引擎。
 *
 * 存在的理由：功能越堆越多，用户反而不知道该点哪个。
 * 与其让人自己判断「我现在该用什么」，不如**分析当前数据直接告诉他**。
 *
 * 设计原则：
 * 1. **只说有把握的** —— 拿不准宁可不提，错误的建议比没建议更糟
 * 2. **按优先级排序** —— 第一步做错了，后面全白费
 * 3. **每条都可执行** —— 光说不做等于没说
 */
object Advisor {

    fun advise(
        left: List<ImageItem>,
        right: List<ImageItem>,
        matched: Set<String>,
        synced: Set<String>,
        contentKeys: Map<String, String>,
        hasAllFilesAccess: Boolean,
        leftUri: Boolean,
        rightUri: Boolean,
        /**
         * 弱依据配对的**对数**（靠「序号相同 / 排列顺序」猜出来的）。
         *
         * 由调用方传入而不是在这里现算：判断需要 `reason` 表，
         * 而 reason 只有配对引擎知道；Advisor 不该反过来依赖引擎的内部结构。
         * 带默认值是为了不动既有调用点与验证脚本。
         */
        weakPairs: Int = 0,
        /**
         * 「时间冲突」配对的**对数**（两边都有拍摄时间、却相差超过容差）。
         *
         * 与 [weakPairs] 正交，所以不能合并成一个数：
         * 依据弱是"这条线索本身不够硬"，时间冲突是"这条线索和更硬的证据打架"。
         * 一个配对可以依据很强（主文件名相同）同时时间冲突 ——
         * 那时 SUSPECT 完全看不到它。
         */
        conflictPairs: Int = 0,
        /**
         * 其中有多少处能「照拍摄时间一键换回来」（见 [findTimeSwaps]）。
         *
         * 与 [conflictPairs] 是"问题"与"现成解法"的关系：冲突数是"有 N 对不对劲"，
         * 这个是"其中 M 处已经有确定的换法"。所以它排在冲突建议**之前** ——
         * 能一键修的先修掉，"去核对"留给那些换不动的（比如只有一对、或者换了没改善）。
         */
        swappablePairs: Int = 0,
        /**
         * 刚才「按时间重配」了几对（可以整体撤回）。
         *
         * 交换改的是配对关系，而配对一旦变成"用户指定"就不再被质疑 ——
         * 所以必须留一条明说的退路，否则用户点错了只能自己一对一对改回来。
         */
        undoableSwaps: Int = 0,
        /**
         * 没配上、但「尺寸 + 体积完全一致、且两边各只有这一张」的对数。
         *
         * 这是最接近"内容指纹"的一条元数据证据，却**完全不用读文件内容**。
         * 它的主要价值恰恰在**没跑流水线**的时候：EXIF 没读、指纹没算、名字又不同，
         * 引擎只剩"按顺序猜" —— 这时能立刻免费配上的对，应该先配上，
         * 再决定要不要去跑又慢又耗电的流水线。
         */
        sizeMatches: Int = 0,
        /**
         * 冲突里有多少处能「直接搬到对的那张」（见 [findSideMoves]）。
         *
         * 与 [swappablePairs] 是同一批冲突的两种修法：互换要**两对都配错**，
         * 搬移针对"只有一对配错、它真正的对象还空着"。判据比互换更硬 ——
         * 互换只要求"两对都变小"，搬移要求"搬完**进容差**"，
         * 所以它排在互换之前（强证据先执行）。
         */
        sideMoves: Int = 0,
        /**
         * 刚才单侧搬移了几张（可以整体撤回）。
         *
         * 与 [undoableSwaps] 同一个理由：搬移写进去的是"用户指定的配对"，
         * 之后不会再被质疑，必须留一条明说的退路。
         */
        undoableSideMoves: Int = 0,
        /**
         * 「整体平移」能一次修几对（见 [findTimeRealign]）。
         *
         * 与 [swappablePairs] / [sideMoves] 是三种修法，判据的适用面不同：
         * 互换只能修"局部颠倒"、搬移只能修"真对象恰好空着"，
         * 而**均匀平移**（两边各自从 0001 编号）里每一张的真对象都配着别人 ——
         * 前两种一条都出不来，只有它能修。
         */
        realignPairs: Int = 0,
        /** 刚才整体平移了几对（可以整体撤回）。 */
        undoableRealign: Int = 0,
    ): List<Advice> {
        val out = mutableListOf<Advice>()

        // ---- 1. 前提：目录选了没有 ----
        if (!leftUri || !rightUri) {
            out += Advice(
                title = "先选两个文件夹",
                detail = "左边一个、右边一个。没选目录什么都做不了。",
                priority = 1000,
                action = AdviceAction.PICK_FOLDER,
            )
            return out
        }
        if (left.isEmpty() && right.isEmpty()) {
            out += Advice(
                title = "这两个目录里没有图片",
                detail = "换个目录，或在设置里打开「包含子文件夹」再试。",
                priority = 1000,
            )
            return out
        }

        val all = left + right
        val paired = matched.size / 2
        val total = minOf(left.size, right.size)

        // ---- 2. 数量差异：这是最容易发现也最该先看的 ----
        if (left.size != right.size) {
            val diff = kotlin.math.abs(left.size - right.size)
            val more = if (left.size > right.size) "左" else "右"
            out += Advice(
                title = "两边数量不等（$more 栏多 $diff 张）",
                detail = "多出来的多半是原图或导出残留。用过滤切到「仅独有」可以快速定位。",
                priority = 900,
            )
        }

        // ---- 2.5 能立刻配上的漏配：排在「跑流水线」**之前** ----
        //
        // 优先级 850 > 跑流水线的 800：先配上这些能立刻、免费配上的（不读盘、不耗电），
        // 配完之后配对率上去了，"要不要跑流水线"的判断才更有意义 ——
        // 顺序反了，用户会先跑一遍又慢又耗电的流水线，回头才发现本来点一下就行。
        if (sizeMatches > 0) {
            out += Advice(
                title = "有 $sizeMatches 对没配上，但尺寸和体积完全一致",
                detail = "两边名字对不上、又还没跑流水线时，引擎只能按顺序猜。" +
                    "而这 $sizeMatches 对的宽、高、体积一模一样、且两边各只有这一张 —— " +
                    "这几乎肯定是同一张。点一下全部配上，不用等流水线。",
                priority = 850,
                action = AdviceAction.LINK_SIZE,
            )
        }

        // ---- 3. 配对质量不足：先补数据，别急着改 ----
        val hasExif = all.count { it.takenAt > 0 }
        val pairRate = if (total > 0) paired.toFloat() / total else 0f

        if (pairRate < 0.5f && hasExif == 0 && contentKeys.isEmpty()) {
            out += Advice(
                title = "配对率偏低，建议先补齐数据",
                detail = "当前只配上 $paired 对。跑一遍「一键流水线」（读拍摄时间）能显著提升准确率 —— 在配对不准的情况下改名，等于照着错的对应关系改。",
                priority = 800,
                action = AdviceAction.PIPELINE,
            )
        } else if (pairRate < 0.8f && hasExif == 0) {
            out += Advice(
                title = "建议读取拍摄时间",
                detail = "文件名对不上时，拍摄时间是最可靠的配对依据。有 $total 张可选，当前配上 $paired 对。",
                priority = 700,
                action = AdviceAction.READ_EXIF,
            )
        }

        // ---- 4. 内容校验：有把握时才建议 ----
        if (contentKeys.isEmpty() && paired > 0 && pairRate < 0.95f) {
            out += Advice(
                title = "可按需做内容校验",
                detail = "按文件内容比对能 100% 确定是不是同一张，但要读文件内容，图多时会慢。建议先只校验你拿不准的那几张。",
                priority = 500,
                action = AdviceAction.VERIFY_CONTENT,
            )
        }

        // ---- 5. 名字该清理：明确的客观问题 ----
        val dirty = all.count { Naming.isDirtyName(it.displayName) }
        if (dirty > 0) {
            out += Advice(
                title = "有 $dirty 个文件名需要清理",
                detail = "含首尾空格、连续空格、大写扩展名或 Windows 不允许的字符。手机上没事，传到电脑就报错。建议先清理再统一，避免改完还要返工。",
                priority = 600,
                action = AdviceAction.FIX_NAMES,
            )
        }

        // ---- 6. 顺序错位：只在两边数量接近时提，否则是误报 ----
        if (paired < total && total >= 3 &&
            kotlin.math.abs(left.size - right.size) <= 2
        ) {
            val seqLeft = left.count { Naming.seqOf(it.displayName) != null }
            val seqRight = right.count { Naming.seqOf(it.displayName) != null }
            if (seqLeft > total * 0.6 && seqRight > total * 0.6) {
                out += Advice(
                    title = "两边序号可能对不上",
                    detail = "两边都有序号但配不上对，通常是整体错位了。用「顺序对齐」滑一下偏移量试试。",
                    priority = 550,
                    action = AdviceAction.ALIGN,
                )
            }
        }

        // ---- 6.25 整体平移：一次修一整串，排在所有逐对修法之前 ----
        //
        // 优先级 495 > 搬移 490 > 互换 480：三种修法都"确定"，
        // 但平移一次解决的面最大（逐对判据在均匀错位下**一条都出不来**），
        // 先做面大的，剩下的零头再逐对处理。
        if (realignPairs > 0) {
            out += Advice(
                title = "有 $realignPairs 对能按拍摄时间整批重配",
                detail = "两边各自从 0001 编号时右栏整体挪了一位，于是按序号配出来的每一对" +
                    "都差一个差不多的量。这种错位逐对修不了（怎么换都会弄坏另一对），" +
                    "只能整批重配：按拍摄时间把这 $realignPairs 对重新配一遍，" +
                    "配不上的会明确回到未配对。改错了可以在下面整体撤回。",
                priority = 495,
                action = AdviceAction.REALIGN_TIME,
            )
        }

        // ---- 6.3 能直接搬到对的那张：判据比互换更硬，排在它之前 ----
        //
        // 优先级 490 > 互换的 480 > 冲突复核的 470：
        // 互换的判据是"两对的时间差都变小"，搬移的判据是"搬完**进 2 秒容差**"，
        // 而且只在"尺寸 + 体积完全一致"时才成立 —— 两条独立硬证据。
        // 代价是被腾出来的那张回到未配对，但那本来就不是它的对象。
        if (sideMoves > 0) {
            out += Advice(
                title = "有 $sideMoves 处能直接搬到对的那张",
                detail = "这些对里的图，真正的对象还没被配上：有一张未配对的图，" +
                    "和它宽、高、体积一模一样，拍摄时间也落在 2 秒容差内。" +
                    "搬过去之后原来配着的那张会回到未配对 —— 它本来就没对象。" +
                    "搬错了可以在下面整体撤回。",
                priority = 490,
                action = AdviceAction.SIDE_MOVE,
            )
        }

        // ---- 6.35 能一键修好的错配：排在"去核对"之前 ----
        //
        // 优先级 480 > 冲突复核的 470：同样是时间冲突，这一批**已经有确定的换法**，
        // 点一下就修好两对；而"去核对"要用户自己一对一对点开看。能自动的别让人手工做。
        if (swappablePairs > 0) {
            out += Advice(
                title = "有 $swappablePairs 处可以照拍摄时间换回来",
                detail = "两边各自从 0001 编号时整体会错位，表现是一批对的拍摄时间都对不上。" +
                    "这种错位能成对互换 —— 把 A 从 B 换到 C，同时让 D 接手 B，" +
                    "两对的时间差会同时变小。换错了可以在下面撤回。",
                priority = 480,
                action = AdviceAction.SWAP_CONFLICT,
            )
        }

        // ---- 6.4 时间冲突：比"弱依据"更该先处理，因为它是矛盾而不是猜测 ----
        //
        // 优先级 470 > 弱依据的 450：弱依据只是"这条线索本身不够硬，可能没事"，
        // 而时间冲突是"你手上这条线索和更硬的证据直接打架"。
        // 两个都排在「去统一」（400）之前 —— 先把错配清掉再统一，顺序反了就是白改。
        if (conflictPairs > 0) {
            out += Advice(
                title = "有 $conflictPairs 对配得不对",
                detail = "这些对两边都有拍摄时间，却相差超过 2 秒 —— 而拍摄时间比序号、" +
                    "顺序、甚至名字都更可靠。多半是两边各自从 0001 开始编号造成的错配，" +
                    "照这样改名就会改错文件。建议逐条看过再统一。",
                priority = 470,
                action = AdviceAction.REVIEW_CONFLICT,
            )
        }

        // ---- 6.45 撤回上一次「按时间重配」 ----
        //
        // 排在冲突 / 换回之后：它是**退路**，不是待办，不该霸占第一位。
        // 但只要它还在（本次会话里做过交换、且没撤过），就一直摆在建议里 ——
        // 用户过一会儿才想起"刚才那下不太对"的时候，还得找得到这个入口。
        if (undoableSwaps > 0) {
            out += Advice(
                title = "刚按拍摄时间重配了 $undoableSwaps 对",
                detail = "如果换得不对，可以整体撤回，回到原来的配对关系。" +
                    "撤回只影响刚才这一次重配，之后自己改过的配对不会被抹掉。",
                priority = 460,
                action = AdviceAction.UNDO_SWAP,
            )
        }

        // ---- 6.41 撤回上一次「整体平移」 ----
        //
        // 三种撤回并排（平移 453 / 搬移 455 / 互换 460），都是退路，不抢第一位。
        if (undoableRealign > 0) {
            out += Advice(
                title = "刚把 $undoableRealign 对按拍摄时间重配了",
                detail = "如果整批配得不对，可以整体撤回，回到原来的配对关系。" +
                    "撤回只影响刚才这一次重配。",
                priority = 453,
                action = AdviceAction.UNDO_REALIGN,
            )
        }

        // ---- 6.42 撤回上一次「单侧搬移」 ----
        //
        // 与上一条同样是**退路**，所以并排放在一起（455 < 460），不该霸占第一位。
        if (undoableSideMoves > 0) {
            out += Advice(
                title = "刚搬了 $undoableSideMoves 张到别的对象上",
                detail = "如果搬得不对，可以整体撤回：搬过去的配对会取消，" +
                    "被腾出来的那些也不再是「已解除」状态。撤回只影响刚才这一次搬移。",
                priority = 455,
                action = AdviceAction.UNDO_SIDE_MOVE,
            )
        }

        // ---- 6.5 弱依据配对：统计页报了"靠顺序 N 对"，这里给出能直接去处理的入口 ----
        // 排在「去统一」之前：先把可能改错的配对复核掉，再统一 —— 顺序反了就是白改。
        // 只在配对率够的时候提（<0.5 时第 3 条已经在叫先补数据，再说这条就是噪音）
        if (weakPairs > 0 && pairRate >= 0.5f) {
            out += Advice(
                title = "有 $weakPairs 对是按序号 / 顺序猜的",
                detail = "这类配对没有内容或拍摄时间做凭证，只是编号或排位碰巧对上，最可能改错。" +
                    "复核里会只留下这些对，逐条点开看，确认无误的留着，不对的直接解除。",
                priority = 450,
                action = AdviceAction.REVIEW_SUSPECT,
            )
        }

        // ---- 7. 可以收尾了 ----
        val pending = paired - synced.size / 2
        if (pending > 0 && pairRate >= 0.5f) {
            out += Advice(
                title = "还有 $pending 对没有统一",
                detail = "配对已经够用了，现在可以把一边的名字应用到另一边。",
                priority = 400,
                action = AdviceAction.SYNC,
            )
        }

        // ---- 8. 都做完了 ----
        if (out.isEmpty() || (pending <= 0 && dirty == 0 && paired > 0)) {
            out += Advice(
                title = "看起来都处理完了",
                detail = "配对 $paired 对，没有待统一的项。可以刷新确认，或换个目录继续。",
                priority = 10,
            )
        }

        return out.sortedByDescending { it.priority }
    }
}
