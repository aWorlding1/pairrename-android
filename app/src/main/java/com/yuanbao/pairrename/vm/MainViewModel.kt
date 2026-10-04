package com.yuanbao.pairrename.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.DocsRepository
import com.yuanbao.pairrename.data.HistoryEntry
import com.yuanbao.pairrename.data.HistoryRepository
import com.yuanbao.pairrename.data.TemplateRepository
import com.yuanbao.pairrename.data.MediaStoreMeta
import com.yuanbao.pairrename.data.DuplicateFinder
import com.yuanbao.pairrename.data.ExifMeta
import com.yuanbao.pairrename.data.TrashRepository
import com.yuanbao.pairrename.data.Organizer
import com.yuanbao.pairrename.data.PlanSnapshot
import com.yuanbao.pairrename.data.SessionStore
import com.yuanbao.pairrename.data.StoredCredential
import com.yuanbao.pairrename.data.CredentialKind
import com.yuanbao.pairrename.data.ThumbPreloader
import com.yuanbao.pairrename.data.sanitizePairs
import com.yuanbao.pairrename.data.sanitizeUnlinked
import com.yuanbao.pairrename.data.sessionFingerprint
import coil.Coil
import com.yuanbao.pairrename.util.ContentHash
import com.yuanbao.pairrename.data.SettingsRepository
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.BatchIssue
import com.yuanbao.pairrename.model.BatchLedger
import com.yuanbao.pairrename.model.BatchReport
import com.yuanbao.pairrename.model.ConflictPolicy
import com.yuanbao.pairrename.model.ExtensionPolicy
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.IssueKind
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.PaneState
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.model.RenameStep
import com.yuanbao.pairrename.model.BatchMode
import com.yuanbao.pairrename.model.BatchTemplate
import com.yuanbao.pairrename.model.Screen
import com.yuanbao.pairrename.model.SelectCondition
import com.yuanbao.pairrename.model.SameFolderMode
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.model.SortOrder
import com.yuanbao.pairrename.model.UndoEntry
import com.yuanbao.pairrename.util.Naming
import com.yuanbao.pairrename.util.Pairing
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yuanbao.pairrename.data.RecentFolders
import com.yuanbao.pairrename.model.BatchParams
import com.yuanbao.pairrename.model.CreatedFile
import com.yuanbao.pairrename.model.DocumentMoveStep
import com.yuanbao.pairrename.model.SyncDirection
import com.yuanbao.pairrename.util.Advice
import com.yuanbao.pairrename.util.AdviceAction
import com.yuanbao.pairrename.util.Advisor
import com.yuanbao.pairrename.util.DirStats
import com.yuanbao.pairrename.util.ExtraFilter
import com.yuanbao.pairrename.util.Stats
import com.yuanbao.pairrename.util.PairResult
import com.yuanbao.pairrename.util.applyFilter
import com.yuanbao.pairrename.util.findTimeConflicts
import com.yuanbao.pairrename.util.findTimeSwaps
import com.yuanbao.pairrename.util.findSizeMatches
import com.yuanbao.pairrename.util.SizeMatch
import com.yuanbao.pairrename.util.findSideMoves
import com.yuanbao.pairrename.util.SideMove
import com.yuanbao.pairrename.util.findTimeRealign
import com.yuanbao.pairrename.util.TimeRealign
import com.yuanbao.pairrename.util.TimeSwap
import com.yuanbao.pairrename.util.isWeakReason

/**
 * 「大文件」筛选的阈值。
 *
 * 手机上单张照片普遍 2~4 MB，5 MB 以上基本就是原图 / RAW / 截图长图了，
 * 按这个线找"占空间的元凶"最实用。
 */
private const val LARGE_FILE_THRESHOLD = 5L * 1024 * 1024

/**
 * 一次可撤回的写入凭据。
 *
 * 三种操作形状完全一样 —— 按时间重配（R18）、单侧搬移（R21）、整体平移（R22）：
 * 都写了若干条手动配对、都可能把某些文件加进解除集合、都在某个"现场"里签发，
 * 换目录之后就都不能用了（`pairKey` 取自主文件名，`img_0001` 跨目录撞名是常态）。
 *
 * 所以它们共用一个类型 + 一个判定实现（[MainViewModel.credentialUsable]）。
 * 这不再是"三个字段各自抄一遍"：那样每加一种修法就多一份 drift 的机会，
 * 而 drift 的表现是"建议条说能撤、点下去却什么也没发生"。
 */
data class Credential(
    /** 这次操作写进去的配对（pairKey → pairKey）。撤回时**按值匹配**删除。 */
    val pairs: Map<String, String> = emptyMap(),
    /** 这次操作加进解除集合的 pairKey（撤回时要把它们拿回来）。 */
    val unlinked: Set<String> = emptySet(),
    /** 签发时的现场指纹。与当前现场不符 → 整批作废。 */
    val session: String = "",
) {
    /** 有没有凭据（决定"还能不能撤回"）。 */
    val present: Boolean get() = pairs.isNotEmpty()

    /** 涉及几张（每对两条，所以除以 2）。 */
    val pairCount: Int get() = pairs.size / 2
}

data class UiState(
    val left: PaneState = PaneState(),
    val right: PaneState = PaneState(),
    val screen: Screen = Screen.COMPARE,
    /** 上一次目录扫描耗时（毫秒）。卡顿时先看这个数。 */
    val lastScanMs: Long = 0,
    /** 已保存的批量参数模板。 */
    val templates: List<BatchTemplate> = emptyList(),
    /** 最近用过的文件夹（显示名 → Uri 字符串）。 */
    val recents: List<Pair<String, String>> = emptyList(),
    /**
     * 已保存的工作现场（左右目录 + 手工配对方案）。
     *
     * 与 [recents] 一样是持久化数据的**只读投影**：诊断面板要同步显示
     * "存了几个现场、当前这个存了多少对"，不能在组合函数里挂起读盘。
     */
    val sessionScenes: List<PlanSnapshot> = emptyList(),
    /**
     * 当前工作现场的指纹（左右目录 Uri 拼成），缺一边时为 null。
     *
     * 抽成 UiState 字段而不是让界面自己拼：界面要拿它去 [sessionScenes] 里
     * 找出"当前这个现场"的存档 —— 两处各拼一次，迟早拼得不一样。
     */
    val sessionKey: String? = null,
    /** 「下一步该做什么」的建议，按优先级排序。 */
    val advices: List<Advice> = emptyList(),
    /** 目录体检结果（按需计算，不常驻）。 */
    val dirStats: DirStats? = null,
    /**
     * 撤销栈的可读快照（最新的在最前）。
     * 让用户看得见"我刚才做了什么"，并能直接回到某一步。
     */
    val undoLabels: List<Pair<Int, String>> = emptyList(),
    /** 单栏页当前看的是哪一栏（底栏从「左栏/右栏」两个 Tab 合成一个）。 */
    val singleSide: Side = Side.LEFT,
    val query: String = "",
    val selectedSource: ImageItem? = null,
    val dragSource: ImageItem? = null,
    /** 多选（批量操作用），存 key。 */
    val checked: Set<String> = emptySet(),
    /** 两栏能配对上的 key。 */
    val matched: Set<String> = emptySet(),
    /**
     * key → 配对对方的**文件名**。
     *
     * 预计算而不是让卡片自己查：卡片有 N 张，逐个线性查找就是 O(N²)，
     * 60 张 = 3600 次比对，每次重组都来一遍，滑动必卡。
     */
    val partnerNames: Map<String, String> = emptyMap(),
    /** key → 配对对方 key 的双向映射。 */
    val partner: Map<String, String> = emptyMap(),
    val pairOffset: Int = 0,
    val bySeq: Int = 0,
    val byOrder: Int = 0,
    /** 靠「名字骨架相同」配上的对数（两边只差 `(1)` / `- 副本` / `_编辑` 这类尾巴）。 */
    val bySimilar: Int = 0,
    /** key → 内容指纹（校验过才非空）。 */
    val contentKeys: Map<String, String> = emptyMap(),
    /** key → 配对依据（可解释性）。 */
    val reason: Map<String, com.yuanbao.pairrename.util.PairReason> = emptyMap(),
    /**
     * 弱依据配对涉及的 key（靠「序号相同 / 排列顺序」猜出来的）。
     *
     * 存成 UiState 字段而不是每次现算：过滤、对比导航都要读它，
     * 而界面每帧都会调过滤 —— 现算就是每帧重扫 reason 全表。
     * 由 [recomputeMatch] 在配对重算时算一次。
     */
    val weakKeys: Set<String> = emptySet(),
    /** 弱依据配对的**对数**（界面直接显示，不用再除 2）。 */
    val weakPairs: Int = 0,
    /**
     * 「时间冲突」配对涉及的 key（见 [com.yuanbao.pairrename.util.findTimeConflicts]）。
     *
     * 与 [weakKeys] 是**正交**的两件事：那边问"依据够不够硬"，
     * 这边问"这个配对和更强的证据打不打架"。
     * 一样在配对重算时算一次放进 UiState —— 过滤和对比导航都要读它，
     * 界面每帧现算就是每帧重建一次 key→item 表。
     */
    val conflictKeys: Set<String> = emptySet(),
    /** 时间冲突的**对数**。 */
    val conflictPairs: Int = 0,
    /** key → 冲突的时间差（毫秒），对比面板用来显示"差了几个小时"。 */
    val conflictDelta: Map<String, Long> = emptyMap(),
    /**
     * 「照拍摄时间换过来」的建议（见 [com.yuanbao.pairrename.util.findTimeSwaps]）。
     *
     * 时间冲突只告诉用户"这一对不对劲"，但两边各自从 0001 编号造成的整体错位
     * 是**成对可换**的：把 (左1,右1) 与 (左2,右2) 互换对象，两对的差值同时变小，
     * 一次操作修好两对。这里存当前可执行的互换建议，随配对重算一起更新。
     */
    val timeSwaps: List<TimeSwap> = emptyList(),
    /**
     * 「按时间重配」的撤回凭据（见 [Credential]）。
     *
     * 单独留一份是为了能**整体撤回**这一步：交换改的是配对关系，而配对一旦
     * 变成"用户指定"就不再被质疑，用户点错了也没有回头路。
     * 撤回时只删**值仍然匹配**的条目，避免误删用户之后自己又改过的配对。
     * 刻意**不持久化**：它是"刚才那一下"的撤销凭据，重启之后
     * 交换的结果就是用户确认过的配对，没有回撤可言。
     *
     * [Credential.session] 是签发时的现场指纹：换目录之后那批 pairKey 会被拿去比对
     * **新目录**的文件 —— 而两边各自从 0001 编号时，`img_0001` 这种 pairKey 撞名是常态，
     * 于是"撤回上一次重配"会悄悄删掉新目录里一对毫不相干的配对。
     * 这正是这个项目反复出现的那类错误：记录活着，但它描述的那个上下文已经没了。
     * 判断统一走 [swapUndoAvailable]，任何地方都不要自己再判一遍。
     */
    val swapCredential: Credential = Credential(),
    /**
     * 「靠尺寸 + 体积认出来的」漏配建议（见 [com.yuanbao.pairrename.util.findSizeMatches]）。
     *
     * 时间冲突 / 互换建议修的是"配**错**了"；这个找的是"根本没配上、但证据很硬
     * 的" —— 尺寸相同 + 体积完全相同 + 两边各只有这一张。主要价值在**没跑流水线**
     * 的时候：EXIF 没读、指纹没算、名字又对不上，引擎只剩"按顺序猜"，
     * 而列表元数据里的宽 × 高 × 体积一直在。随配对重算一起更新。
     */
    val sizeMatches: List<SizeMatch> = emptyList(),
    /**
     * 漏配候选的 key → 对方文件名（卡片角标用，与 [sizeMatches] 同源、同一次重算产出）。
     *
     * 与 [partnerNames] 同一个理由：卡片有 N 张，逐个线性查建议列表就是 O(N²)，
     * 滑动必卡 —— 在重算时预建成双向 Map，卡片 O(1) 查自己那张有没有候选、对面叫什么。
     */
    val sizeMatchPartner: Map<String, String> = emptyMap(),
    /**
     * 「单侧搬移」建议（见 [com.yuanbao.pairrename.util.findSideMoves]）。
     *
     * 与 [timeSwaps] 的分工：互换要求**两对都配错**且能互相接手；
     * 搬移解决"只有一对配错、而它真正的对象还空着"—— 把起冲突的那张搬到
     * 尺寸体积全同、拍摄时间进容差的候选上，原对象回到未配对。
     */
    val sideMoves: List<SideMove> = emptyList(),
    /** key → 涉及它的那条搬移建议（对比面板据此显示「搬到对的那张」按钮）。 */
    val sideMoveByKey: Map<String, SideMove> = emptyMap(),
    /**
     * 「单侧搬移」的撤回凭据（见 [Credential]）。
     *
     * 与 [swapCredential] 同一个理由：搬移的结果是"用户指定的配对"，
     * 之后不会再被质疑，所以必须留一条明说的退路。
     */
    val sideMoveCredential: Credential = Credential(),
    /**
     * 「整体平移」修复方案（见 [com.yuanbao.pairrename.util.findTimeRealign]）。
     *
     * 与 [timeSwaps] / [sideMoves] 的关系：那两条是"逐对判据"（互换两对都变好 /
     * 单侧搬到未配对的候选）。均匀平移里**每一张的真对象都配着别人**，
     * 逐对判据全都失效 —— 它要的是一次性把整批按拍摄时间重配。
     */
    val timeRealign: TimeRealign? = null,
    /** 「整体平移」的撤回凭据（见 [Credential]）。 */
    val realignCredential: Credential = Credential(),
    /**
     * 整批重配之后这一张会改配到谁：key → 新对方的文件名（只含**确实换了对象**的 key）。
     *
     * 与 [sizeMatchPartner] / partnerNames 同一个理由：结果是一批文件一起动，
     * 而用户在对比面板里只看到"这一对差 2 小时"—— 看不到"重配之后它会变成什么"。
     * 只报一个"能重配 N 对"就让他整批提交，等于让他在不知道后果的情况下改一批配对。
     * 重算时预建成表，面板 O(1) 查自己这一对。
     */
    val realignTargetName: Map<String, String> = emptyMap(),
    /**
     * 整批重配之后会回到未配对的 key（池里找不到拍摄时间对得上的对象）。
     *
     * 与 [realignTargetName] 同源、同一次重算产出。单独存一张表而不是从
     * [timeRealign] 现算：面板每帧都要问"这一对里有没有被腾出来的那张"，
     * 逐个现算就是 O(对数²)，与"卡片逐个线性查建议列表"是同一个坑。
     */
    val realignFreedKeys: Set<String> = emptySet(),
    val seqHit: Map<String, Int> = emptyMap(),
    /** 配对双方主文件名已经一致的文件（即「这一步做完了」）。 */
    val synced: Set<String> = emptySet(),
    /**
     * 用户手动指定的配对（pairKey → pairKey），优先级高于自动推算。
     * 用 pairKey 而不是文档 Uri：改名后 Uri 会变，而主文件名才是用户看得见的锚点。
     */
    val manualPairs: Map<String, String> = emptyMap(),
    /** 用户手动解除的配对（存 pairKey，与 manualPairs 一致）。 */
    val unlinked: Set<String> = emptySet(),
    /** 是否用 EXIF 拍摄时间参与配对（读过后可开）。 */
    val useExif: Boolean = false,
    val matchFilter: MatchFilter = MatchFilter.ALL,
    /** 扩展名 / 体积过滤。 */
    val extraFilter: ExtraFilter = ExtraFilter(),
    /**
     * 请求滚动到的文件 key（配对跳转 / 下一个待处理用）。
     * 刻意**不**在滚动后清空：focusNextTodo 要靠它记住当前走到哪，
     * 否则每次都从列表开头找，「下一个」永远不会前进。
     * 重复点击同一目标靠 [focusNonce] 触发重新滚动。
     */
    val focusKey: String? = null,
    val focusNonce: Int = 0,
    val busy: Boolean = false,
    /**
     * 当前是否有**可取消**的长任务在跑。
     *
     * 与 [busy] 是两回事：`busy` 只表示"界面在忙"，而全项目二十多个设 busy
     * 的操作里，绝大多数（复制 / 删除 / 归档 / 还原 / 查重清理）走的是
     * `once()` 或裸 `io {}`，**根本不受取消影响**。
     * 用 busy 控制"取消"按钮的后果是：用户在这些操作期间会看到一个
     * 点了毫无作用的按钮，还会收到「已取消」的假提示 ——
     * 比干脆不显示这个按钮更糟。
     */
    val canCancel: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    /**
     * 上一次批量执行的结果（有问题时才非空，界面据此弹报告）。
     *
     * 批量改 200 张、失败 5 张时，光有一句"失败"是没用的 ——
     * 用户需要的是**是哪 5 张、为什么、能不能重来**。
     */
    val batchReport: BatchReport? = null,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    /**
     * 因超出撤销栈容量而被丢弃的条目数 / 总步数（见 [UndoStack.droppedEntries]）。
     *
     * 上限是必需的（否则 64 次 5000 步的批量能吃掉几百 MB），
     * 但**丢包必须是可见的**：那些操作已经改到磁盘上了，只是从这里撤不回来。
     * 界面据此提示"更早的 N 步已超出上限，可在改名历史里查"。
     */
    val undoDroppedEntries: Int = 0,
    val undoDroppedSteps: Int = 0,
    val showCheckboxes: Boolean = false,
) {
    fun pane(side: Side): PaneState = if (side == Side.LEFT) left else right

    /**
     * 配对对方的文件名，没有配对则返回 null。
     *
     * 左右来回看很累 —— 直接在卡片上显示"对面叫什么"，
     * 一眼就能看出这一对要不要改、会改成什么。
     */
    /** 配对对方的文件名（O(1) 查表，不会拖慢滚动）。 */
    fun partnerName(item: ImageItem): String? = partnerNames[item.key]

    fun checkedItems(): List<ImageItem> =
        (left.items + right.items).filter { it.key in checked }
}

sealed interface UiEvent {
    data class Message(val text: String, val withUndo: Boolean = false) : UiEvent
    data class ConfirmApply(val from: ImageItem, val to: ImageItem, val preview: String) : UiEvent
    data class AskConflict(
        val from: ImageItem,
        val to: ImageItem,
        val suggested: String,
        val conflictName: String,
    ) : UiEvent
    data class OpenBatch(
        val side: Side,
        val existing: Set<String>,
        val initialMode: BatchMode = BatchMode.NUMBER,
    ) : UiEvent

    /**
     * 展示一份改名计划让用户确认（按时间命名等"自动生成"路径用）。
     *
     * 这类操作的名字是程序算出来的，用户没亲眼输入过，
     * 所以**必须先过一遍预览**再落盘 —— 直接改等于盲改。
     */
    data class ConfirmPlan(val rows: List<PlanRow>, val side: Side) : UiEvent
    data class OpenAlign(
        val leftNames: Set<String>,
        val rightNames: Set<String>,
        val suggestedOffset: Int = 0,
        /** 以哪一栏为基准对齐（工具页「左→右 / 右→左」两个入口会传不同值）。 */
        val source: Side = Side.LEFT,
    ) : UiEvent

    /** 打开「按配对统一」面板。 */
    data class OpenSync(
        val leftNames: Set<String>,
        val rightNames: Set<String>,
        /** 初始统一方向，决定对话框里默认选中哪个方向。 */
        val direction: SyncDirection = SyncDirection.LEFT_TO_RIGHT,
    ) : UiEvent

    /** 空状态里点了「去设置」（通常是想去打开递归扫描）。 */
    data object OpenSettings : UiEvent

    /** 打开改名历史。 */
    data class OpenHistory(val entries: List<HistoryEntry>) : UiEvent

    /** 把历史 CSV 交给系统保存。 */
    data class SaveHistory(val csv: String) : UiEvent

    /** 把改名计划导出成 CSV 存档。 */
    data class SavePlan(val csv: String) : UiEvent

    /** 展示全盘重复图片结果。 */
    data class ShowDuplicates(val groups: List<DuplicateFinder.DuplicateGroup>) : UiEvent

    /** 展示回收站。 */
    data class ShowTrash(val items: List<TrashRepository.TrashedItem>) : UiEvent

    /** 请界面配置并启动一键流水线。 */
    data object OpenPipeline : UiEvent

    /** 请界面确认「删除勾选的文件」。 */
    data object AskDelete : UiEvent

    /** 归档预览。 */
    data class ShowArchivePlan(val rows: List<Organizer.MoveRow>) : UiEvent

    /** 大文件排行。 */
    data class ShowBigFiles(
        val files: List<Organizer.BigFile>,
        val summary: Organizer.StorageSummary,
    ) : UiEvent

    /**
     * 请界面打开某个文件的对比面板。
     *
     * 「复核可疑配对」要一步到位：点了建议按钮就该直接看到第一对，
     * 而不是只切个过滤让用户自己再双击一次（那等于没做）。
     * 事件里带整个 [ImageItem] 而不是 key：事件在 `LaunchedEffect(Unit)` 里消费，
     * 那里读 ui 状态拿不到最新值，而事件对象本身是发出瞬间的快照。
     */
    data class OpenCompare(val item: ImageItem) : UiEvent

    /**
     * 请界面把「整批重配」逐条摊开给用户确认。
     *
     * 事件里带 [TimeRealign] 快照而不是让界面现读 ui 状态：`LaunchedEffect` 消费事件时
     * 读到的是**发出瞬间**的快照，现读会拿到更晚的状态（理由同 [OpenCompare]）。
     * 确认时仍由 [applyTimeRealign] 读最新方案 —— 快照只用于展示。
     */
    data class PreviewRealign(val plan: TimeRealign) : UiEvent
}

private sealed interface Outcome {
    data class Success(val entry: UndoEntry, val message: String) : Outcome
    data class Skipped(val message: String) : Outcome
    data class Failed(val message: String) : Outcome
    data class Ask(val suggested: String, val conflictName: String) : Outcome
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val docs = DocsRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val history = HistoryRepository(app)
    private val templateRepo = TemplateRepository(app)
    private val recentFolders = RecentFolders(app)
    private val trash = TrashRepository(app)
    private val sessionStore = SessionStore(app)

    /** 是否该自动读 EXIF：用户没显式设置时，有权限才自动读（有权限时是 File 直读，很快）。 */
    private fun autoExifEnabled(): Boolean =
        settings.value.autoExif ?: MediaStoreMeta.hasAllFilesAccess()

    /**
     * 元信息缓存，按**显示名**索引。
     *
     * 为什么需要它：`listImages` 每次都返回全新的 ImageItem，
     * 于是刷新或改名后，辛辛苦苦读出来的 EXIF 拍摄时间、内容指纹会全部清零，
     * 配对又悄悄退回推算 —— 用户完全不知道为什么突然不准了。
     * 用显示名做键，改名时跟着迁移，就能跨刷新存活。
     */
    /** 元信息缓存（拍摄时间 / 路径 / 内容指纹），并发安全。 */
    private val metaCache = MetaCache()

    /**
     * 缓存键 = 目录 Uri + 显示名。
     *
     * **不能只用显示名**：`IMG_0001.jpg` 这种名字在不同文件夹里太常见了。
     * 只按名字索引，切到另一个目录后同名文件会拿到上一个目录的拍摄时间
     * 和内容指纹 —— 配对会悄悄错掉，而用户完全看不出来。
     */
    /**
     * 缓存条目上限。
     * 用户反复切换目录时缓存会一直累积（没有清理时机），
     * 而它只是加速用的 —— 超了直接清空重来，不影响任何正确性。
     */


    /** 扫描时先显示这么多张，剩下的后台继续。避免大目录长时间转圈。 */
    private companion object {
        const val FIRST_BATCH = 200
    }

    /** 改名后把缓存迁到新名字上，否则元信息会随刷新丢失。 */
    private fun migrateMeta(steps: List<RenameStep>) {
        metaCache.migrate(steps) { side -> _ui.value.pane(side).treeUri }
    }


    /** 用缓存回填一次扫描结果。 */
    private fun enrich(items: List<ImageItem>): List<ImageItem> = metaCache.enrich(items)

    /**
     * 元信息缓存的条目数，给诊断面板显示。
     *
     * 这张表是排查"刷新后拍摄时间/指纹怎么没了"最直接的证据：
     * 四个数全是 0 说明根本没缓存住（读失败或没权限），
     * 有值却仍显示"EXIF 时间 0 张"说明是回填 / 键不匹配的问题。
     */
    fun metaCacheNote(): String = metaCache.sizes().text

    // ---------------- 会话：手工配对方案的持久化 ----------------
    //
    // 手工配对的成果必须比进程活得久。
    // 之前 `manualPairs` / `unlinked` 只活在 UiState 里，应用被系统回收
    // 或者用户重启手机之后全部归零 —— 而且用户**看不出来丢了什么**，
    // 只是发现"配对怎么又乱了"。二十分钟的手工劳动不该这样蒸发。
    //
    // 两件事分开做：
    //   · 变更时**防抖保存**（拖动配对会连点，不能每次都写盘）；
    //   · 扫描完成后**尝试恢复一次**（按现场指纹，且要过安全闸）。

    /**
     * 已经尝试过恢复的现场指纹。
     *
     * 用指纹而不是一个 boolean：用户可能在两三个目录对之间来回切，
     * 每个现场都该有自己的"恢复过了"标记。
     */
    private var sessionTried = ""

    /** 防抖保存的句柄。 */
    private var sessionSaveJob: Job? = null

    /** 当前左右目录组成的现场指纹，缺一边就是 null。 */
    private fun currentFingerprint(): String? {
        val s = _ui.value
        return sessionFingerprint(s.left.treeUri?.toString(), s.right.treeUri?.toString())
    }

    /**
     * 记下当前的手工配对方案。
     *
     * 防抖 600ms：用户一连拖好几对，或者连点几次解除，
     * 不该每一对都写一次盘。**不需要** `finally` 里清 `sessionSaveJob`——
     * 那样反而会让"新任务刚注册、旧任务的 finally 把它清空"，
     * 于是取消失效（这正是本项目踩过的坑）。这里只做 cancel → 覆盖赋值。
     */
    private fun scheduleSessionSave() {
        sessionSaveJob?.cancel()
        sessionSaveJob = viewModelScope.launch(Dispatchers.IO + crashGuard) {
            delay(600)
            runCatching { saveSessionNow() }
        }
    }

    private suspend fun saveSessionNow() {
        val fp = currentFingerprint() ?: return
        val s = _ui.value
        val label = "${s.left.pathLabel.ifBlank { "左栏" }} ↔ ${s.right.pathLabel.ifBlank { "右栏" }}"
        val snap = PlanSnapshot(
            fingerprint = fp,
            label = label,
            pairs = s.manualPairs,
            unlinked = s.unlinked,
            useExif = s.useExif,
            savedAt = System.currentTimeMillis(),
            credentials = storedCredentials(s, fp),
        )
        // 一条手动干预都没有时 SessionStore 会自动删掉该现场，
        // 所以"用户把干预全撤了"这件事也会被正确记下来。
        sessionStore.save(snap)
    }

    /**
     * 把三个撤回凭据整理成可落盘的形式（0~3 条）。
     *
     * 两道过滤，缺一不可：
     *
     * 1. **必须属于本现场**（`session == fp`）。凭据里的 `pairKey` 取自主文件名，
     *    跨目录撞名是常态 —— 一条"属于别处"的凭据存进这个现场的存档，
     *    下次在本现场恢复出来就会被当成能撤的东西，点下去删掉的是一对
     *    毫不相干的配对。过滤之后"凭据属于本现场"成了**存储层的不变量**，
     *    读取侧只需要复核一次而不是重新推导。
     * 2. **必须仍然原封不动**（[credentialIntact]）。用户在操作之后又手动改过其中
     *    一条，这份凭据就已经只能做"半批撤回"，存下去下次还要再丢一次。
     */
    private fun storedCredentials(s: UiState, fp: String): List<StoredCredential> {
        val out = ArrayList<StoredCredential>(3)
        fun add(kind: String, c: Credential) {
            if (!credentialIntact(c.pairs, c.unlinked, c.session, s.manualPairs, s.unlinked, fp)) {
                return
            }
            out += StoredCredential(kind, c.session, c.pairs, c.unlinked)
        }
        add(CredentialKind.SWAP, s.swapCredential)
        add(CredentialKind.SIDE_MOVE, s.sideMoveCredential)
        add(CredentialKind.REALIGN, s.realignCredential)
        return out
    }

    /**
     * 一份凭据"还是原来那一批、一条都没被动过"吗 —— **落盘与恢复共用**的闸。
     *
     * 一条凭据描述的是一次写入：`pairs` 是它写进去的配对，`unlinked` 是它加进
     * 解除集合的名字。只要其中**任何一条**不再原样成立，这份凭据就不能用了 ——
     * 不是因为"少撤几条也无所谓"，而是因为撤回的语义是**整批**：
     * 删掉还匹配的那几条、留下其余，会留下一半改一半的状态，
     * 与"半批改完的分配比不改更难收拾"是同一个判断。
     *
     * 落盘侧传当前状态、恢复侧传"安全闸过滤之后的结果"，于是：
     * · 用户操作后手动改过某条 → 两侧都判否；
     * · 文件被删 / 改名 / 跨栏换位 → 恢复侧的 `live` 里已经没有那条 → 判否。
     * 这正是"重启之后分不清是用户改过还是目录内容变了"这个问题的答案：
     * 分不清就不要猜，只要不能**整批**成立就整条作废。
     */
    private fun credentialIntact(
        pairs: Map<String, String>,
        unlinked: Set<String>,
        session: String,
        live: Map<String, String>,
        liveUnlinked: Set<String>,
        fp: String,
    ): Boolean {
        // 没有现场，就谈不上"这份凭据属于哪个现场" —— 与 credentialUsable 同一个口径。
        // 两个空串相比会"相等"，所以这一句不能省（两侧都空时下面的对照会全部通过）。
        if (fp.isEmpty()) return false
        if (session != fp) return false
        if (pairs.isEmpty()) return false
        if (pairs.any { (k, v) -> live[k] != v }) return false
        if (unlinked.any { it !in liveUnlinked }) return false
        return true
    }

    /**
     * 扫描完成后尝试恢复上次的手工配对方案。
     *
     * 只在"两边目录都在、都扫出了文件、而且用户这次还没动过手"时进行 ——
     * 否则会把用户在本次会话里的操作覆盖掉。
     */
    private fun maybeRestoreSession() {
        val fp = currentFingerprint() ?: return
        if (fp == sessionTried) return
        val s = _ui.value
        // 两边都得有内容：只扫完一边时恢复，会把另一边整个判成"文件已删除"
        if (s.left.items.isEmpty() || s.right.items.isEmpty()) return
        sessionTried = fp
        if (s.manualPairs.isNotEmpty() || s.unlinked.isNotEmpty()) return
        io {
            val snap = sessionStore.load(fp) ?: return@io
            val now = _ui.value
            val leftKeys = now.left.items.mapTo(HashSet()) { it.pairKey }
            val rightKeys = now.right.items.mapTo(HashSet()) { it.pairKey }
            // 安全闸：只留下"现在真的生效得了"的条目
            val pairs = sanitizePairs(snap.pairs, leftKeys, rightKeys)
            val unlinked = sanitizeUnlinked(snap.unlinked, leftKeys, rightKeys)
            if (pairs.isEmpty() && unlinked.isEmpty()) return@io
            val dropped = snap.pairCount - pairs.size / 2
            // 撤回凭据跟着**同一批**配对一起回来。
            //
            // 之前这里是"三种凭据一起作废"，理由是恢复出来的 pairKey 描述的是
            // 上一批文件。那个理由到今天仍然成立一半：**文件确实可能变了**。
            // 但一刀切作废的代价是界面自相矛盾 —— 恢复回来的配对里有一部分
            // 正是上一次操作写进去的，凭据没了，就再没有任何入口能把它们一次撤回。
            //
            // 所以改成"带闸恢复"：凭据里描述的每一条都必须**原封不动**地
            // 出现在这次恢复的结果里（[credentialIntact]），否则整条作废。
            // 文件被删 / 改名 / 跨栏换位都会让那条对不上 → 凭据自动失效，
            // 与原来"换现场整批作废"是同一个安全口径，只是颗粒度从"现场"
            // 细化到"这一批配对是否还完整"。
            val creds = restoreCredentials(snap.credentials, pairs, unlinked, fp)
            _ui.update {
                it.copy(
                    manualPairs = pairs,
                    unlinked = unlinked,
                    swapCredential = creds[CredentialKind.SWAP] ?: Credential(),
                    sideMoveCredential = creds[CredentialKind.SIDE_MOVE] ?: Credential(),
                    realignCredential = creds[CredentialKind.REALIGN] ?: Credential(),
                )
            }
            recomputeMatch()
            val base = if (dropped > 0) {
                getString(R.string.msg_session_restored_stale, pairs.size / 2, dropped)
            } else {
                getString(R.string.msg_session_restored, pairs.size / 2)
            }
            // 恢复了什么必须说出来。凭据回来了却不提，用户不会想到"现在还能撤回"——
            // 而这一轮做的正是让这件事跨启动成立。
            val undoable = creds.values.count { it.present }
            emitMsg(
                if (undoable > 0) base + getString(R.string.msg_session_restored_undo, undoable)
                else base,
            )
        }
    }

    /**
     * 把落盘的凭据还原成内存里的三个槽位。
     *
     * 只认三种已知种类：存档可能来自别的版本（新加的修法 / 更早的格式），
     * 认不出的种类**丢掉而不是猜** —— 猜错的代价是界面上挂一个
     * "点下去不知道会发生什么"的按钮。
     *
     * 额外再核一次 [StoredCredential.session]（保存侧已经保证过等于快照指纹）：
     * 读侧独立成立，将来谁绕过保存侧往存档里写数据，也不会让一条外来凭据生效。
     */
    private fun restoreCredentials(
        stored: List<StoredCredential>,
        pairs: Map<String, String>,
        unlinked: Set<String>,
        fp: String,
    ): Map<String, Credential> {
        if (stored.isEmpty()) return emptyMap()
        val out = HashMap<String, Credential>(3)
        for (c in stored) {
            if (c.kind != CredentialKind.SWAP
                && c.kind != CredentialKind.SIDE_MOVE
                && c.kind != CredentialKind.REALIGN
            ) {
                continue
            }
            if (!credentialIntact(c.pairs, c.unlinked, c.session, pairs, unlinked, fp)) continue
            out[c.kind] = Credential(pairs = c.pairs, unlinked = c.unlinked, session = c.session)
        }
        return out
    }

    /** 诊断面板用：保存了几个现场、当前现场存了多少对、跟着存了几步可撤回的操作。 */
    fun sessionNote(): String {
        val scenes = _ui.value.sessionScenes
        if (scenes.isEmpty()) return "无（还没产生手工配对）"
        val here = _ui.value.sessionKey?.let { k -> scenes.firstOrNull { it.fingerprint == k } }
        // 凭据也落盘了，所以诊断里要能看到"存了几步"—— 否则用户
        // 在别的目录对里看到撤回按钮消失，没法判断是"确实没存"还是"没恢复回来"
        val undo = here?.credentials?.size ?: 0
        return "已保存 ${scenes.size} 个现场 · 当前 ${here?.pairCount ?: 0} 对 · 可撤回 $undo 步"
    }

    /**
     * 丢弃**当前工作现场**保存的方案。
     *
     * 刻意不做"没有当前现场就清空全部"的兜底：那样按钮写着"丢弃本目录对"、
     * 实际却清掉了别的现场，是最难查的一类数据丢失。要清全部就走下面那个。
     */
    fun clearSavedSession() {
        val fp = currentFingerprint()
        io {
            if (fp == null) return@io
            sessionStore.remove(fp)
            // 清掉"试过了"标记，这样用户在同一现场重新扫描时不会被挡住
            sessionTried = ""
            emitMsg(getString(R.string.msg_session_cleared))
        }
    }

    /** 清空**所有**保存的现场。 */
    fun clearAllSavedSessions() {
        io {
            sessionStore.clear()
            sessionTried = ""
            emitMsg(getString(R.string.msg_session_cleared_all))
        }
    }

    /** 内容指纹改为从缓存派生，不再单独按 Uri 存（改名后 Uri 就失效了）。 */
    /** 预计算「对方文件名」，避免每张卡片线性查找造成 O(N²)。 */
    private fun buildPartnerNames(
        s: UiState,
        partner: Map<String, String>,
    ): Map<String, String> {
        if (partner.isEmpty()) return emptyMap()
        val nameByKey = HashMap<String, String>((s.left.items.size + s.right.items.size) * 2)
        (s.left.items + s.right.items).forEach { nameByKey[it.key] = it.displayName }
        val out = HashMap<String, String>(partner.size * 2)
        partner.forEach { (k, other) ->
            nameByKey[other]?.let { out[k] = it }
        }
        return out
    }

    private fun deriveContentKeys(items: List<ImageItem>): Map<String, String> =
        metaCache.deriveContentKeys(items)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /**
     * 撤销 / 重做栈。
     *
     * 抽成独立类是为了把「双重上限」「push 清 redo」「失败塞回」这些
     * 不变量集中管理 —— 撤销是最容易出错、后果最严重的逻辑，
     * 散在 2600 行里迟早写错。
     */
    private val undoHistory = UndoStack()
    /** 所有涉及用户文件的事务共用一把锁，避免不同入口/undo/redo 交错写盘。 */
    private val fileMutationMutex = Mutex()
    /** 每栏只允许最新一次目录扫描提交结果，防止快速切换时旧目录覆盖新列表。 */
    private val folderLoadGeneration = java.util.concurrent.ConcurrentHashMap<Side, java.util.concurrent.atomic.AtomicLong>()

    // ---------------- 协程兜底 ----------------

    /**
     * 这个协程块的异常处理器。
     *
     * 为什么必须加：**20 个协程块是「裸奔」的**（没有 try/catch/runCatching）。
     * `viewModelScope.launch` 默认没有异常处理器，任何未捕获异常都会直接
     * 抛到线程的 uncaughtExceptionHandler —— 也就是**闪退**。
     * 文件 IO 的失败方式千奇百怪（存储被拔、权限回收、provider 抛异常），
     * 不能指望每一处都记得写 try。
     *
     * 兜底做两件事：把 busy/progress 复位（否则界面永远卡在转圈），
     * 再提示用户。CancellationException 不会被这里捕获（结构化取消照常工作）。
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        _ui.update { it.copy(busy = false, progress = null) }
        val msg = e.message
        emitMsg(
            if (msg.isNullOrBlank()) {
                getString(R.string.msg_unexpected)
            } else {
                getString(R.string.msg_unexpected_detail, msg)
            },
        )
    }

    /** 替代裸的 viewModelScope.launch(Dispatchers.IO)：带异常兜底。 */
    private fun io(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(Dispatchers.IO + crashGuard, block = block)

    /** 文件变更任务在同一串行队列执行；锁覆盖整个操作及其撤销记录提交。 */
    private fun mutationIo(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(Dispatchers.IO + crashGuard) {
            fileMutationMutex.withLock { block.invoke(this) }
        }

    /** 正在跑的重操作 key。用并发集合：拦截判定可能发生在任意线程。 */
    private val runningOps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 重量级操作的并发去重。
     *
     * 复制几百个文件时再点一次，两个协程会同时复制，
     * 目标目录凭空多出一整套 `xxx (1).jpg`；删除、归档、批量还原同理。
     *
     * 三个取舍：
     *   1. 按 key 去重，不是全局锁 —— 不能因为「正在复制」就顺带禁掉改名
     *   2. 被拦下必须提示，不能静默（静默 = 用户以为点了没反应）
     *   3. `finally` 释放 —— 异常也必须释放，否则这个操作永久失效
     */
    private fun once(key: String, block: suspend CoroutineScope.() -> Unit) {
        if (!runningOps.add(key)) {
            emitMsg(getString(R.string.msg_still_running))
            return
        }
        mutationIo {
            try {
                block.invoke(this)
            } finally {
                runningOps.remove(key)
            }
        }
    }

    init {
        mutationIo { restoreFolders() }
        // 模板要在打开批量对话框前就绪，所以启动时就加载
        io {
            runCatching {
                templateRepo.templates.collect { list ->
                    _ui.update { it.copy(templates = list) }
                }
            }
        }
        io {
            runCatching {
                recentFolders.folders.collect { list ->
                    _ui.update { it.copy(recents = list) }
                }
            }
        }
        io {
            runCatching {
                sessionStore.snapshots.collect { list ->
                    _ui.update { it.copy(sessionScenes = list) }
                }
            }
        }
    }


    // ---------------- 目录 ----------------

    fun onFolderPicked(side: Side, uri: Uri) {
        mutationIo {
            val persisted = docs.takePersistable(uri)
            settingsRepo.saveTree(side, uri.toString())
            // 记住这次选择，下次一键切回（目录层级深时最省事）
            rememberFolder(uri, docs.folderLabel(uri))
            val other = _ui.value.pane(if (side == Side.LEFT) Side.RIGHT else Side.LEFT).treeUri
            if (other != null && other == uri) {
                emitMsg(getString(R.string.msg_same_folder))
            } else if (!persisted) {
                emitMsg(getString(R.string.msg_no_persist))
            }
            load(side, uri, allowFolderChange = true)
        }
    }

    fun clearFolder(side: Side) {
        mutationIo {
            invalidateFolderLoad(side)
            // 这一栏的目录都被清掉了，指向它的撤销记录同样失效
            //（理由见 resetUndoForFolderChange）
            resetUndoForFolderChange()
            settingsRepo.saveTree(side, null)
            updatePane(side) { PaneState() }
            recomputeMatch()
        }
    }

    fun refresh(side: Side) {
        val uri = _ui.value.pane(side).treeUri ?: return
        io { load(side, uri) }
    }

    fun refreshAll() {
        Side.entries.forEach { side ->
            _ui.value.pane(side).treeUri?.let { refresh(side) }
        }
    }

    private suspend fun restoreFolders() {
        Side.entries.forEach { side ->
            val raw = settingsRepo.readTree(side) ?: return@forEach
            if (_ui.value.pane(side).treeUri != null) return@forEach
            runCatching { Uri.parse(raw) }.getOrNull()?.let { load(side, it, allowFolderChange = true) }
        }
    }

    private fun load(side: Side, uri: Uri, allowFolderChange: Boolean = false) {
        val previous = _ui.value.pane(side).treeUri
        // refresh/copy 的过期请求不能把 pane 切回它捕获的旧 URI；只有用户选择/启动恢复可切目录。
        if (!allowFolderChange && previous != uri) return
        val generation = nextFolderLoadGeneration(side)
        if (previous != null && previous != uri) resetUndoForFolderChange()
        // 计时：扫描到底慢不慢，用数字说话，别靠感觉
        val t0 = System.currentTimeMillis()
        _ui.update { state ->
            if (folderLoadGeneration[side]?.get() != generation ||
                (!allowFolderChange && state.pane(side).treeUri != uri)
            ) {
                state
            } else if (side == Side.LEFT) {
                state.copy(left = state.left.copy(treeUri = uri, loading = true, error = null))
            } else {
                state.copy(right = state.right.copy(treeUri = uri, loading = true, error = null))
            }
        }
        if (!isCurrentFolderLoad(side, uri, generation)) return
        try {
            // 扫描时**不读宽高**：宽高要逐个文件 openInputStream 走一次 IPC，
            // 60 张就是 60 次往返，全都卡在 loading 上 —— 图片迟迟不出来。
            // 宽高有权限时走 MediaStore 一次拿全（maybeEnrichMeta），
            // 没权限就干脆不显示，比让整个界面等它划算得多。
            val items = docs.listImages(
                uri,
                side,
                readBounds = false,
                recursive = settings.value.recursive,
            ).sortedWith(settings.value)
                .let { list -> enrich(list) }
                // 注意：这里**不要**同步查全盘 MediaStore。
                // 手机里照片多时（几万张）那次 query + 建表要好几秒，
                // 会一直卡在 loading —— 表现就是"一直转圈，图片出不来"。
                // 宽高体积改成目录扫完后再异步补（见 maybeEnrichMeta）。
            // 先塞一批让用户马上看到内容。
            // 但小目录（比如 30 张）分批只会造成两次列表替换、白白重建一次，
            // 所以只在真的很大时才启用。
            if (items.size > FIRST_BATCH) {
                updatePaneForLoad(side, uri, generation) {
                    it.copy(
                        items = items.take(FIRST_BATCH),
                        loading = true,
                        pathLabel = docs.describeTree(uri),
                    )
                }
            }
            val reachable = items.isNotEmpty() || docs.isReachable(uri)
            if (!reachable) {
                // 目录被删除或权限被系统回收，与「目录是空的」区分开提示
                updatePaneForLoad(side, uri, generation) {
                    it.copy(
                        items = emptyList(),
                        loading = false,
                        error = getString(R.string.msg_no_permission),
                    )
                }
            } else {
                updatePaneForLoad(side, uri, generation) {
                    it.copy(
                        items = items,
                        loading = false,
                        pathLabel = docs.describeTree(uri),
                        error = null,
                        // 顶层没图片但下面还有子文件夹时，给一句明确的出路
                        hint = if (items.isEmpty() && !settings.value.recursive && docs.hasSubFolders(uri)) {
                            getString(R.string.hint_subfolders)
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (se: SecurityException) {
            updatePaneForLoad(side, uri, generation) {
                it.copy(loading = false, error = getString(R.string.msg_no_permission))
            }
        } catch (t: Throwable) {
            // 任何 provider 的异常都不应该让应用崩溃
            updatePaneForLoad(side, uri, generation) {
                it.copy(loading = false, error = getString(R.string.msg_scan_failed, t.message.orEmpty()))
            }
        } finally {
            if (isCurrentFolderLoad(side, uri, generation)) {
                // loading 兜底复位；旧任务不能结束新目录的 loading 状态。
                updatePaneForLoad(side, uri, generation) { if (!it.loading) it else it.copy(loading = false) }
                _ui.update { it.copy(lastScanMs = System.currentTimeMillis() - t0) }
                pruneChecked()
                recomputeMatch()
                // 两边都扫完之后，看看这个现场上次有没有留下手工配对方案。
                maybeRestoreSession()
                maybeEnrichMeta(side)
                maybeEnrichBounds(side)
                maybePreloadThumbs(side)
                if (autoExifEnabled()) maybeAutoExif(side)
            }
        }
    }

    /**
     * 没有「所有文件」权限时，逐个用 SAF 读宽高。
     *
     * 放在扫描之后异步做：这种读法要走 IPC，60 张就是 60 次往返，
     * 同步做的话全都卡在 loading 上（这正是「一直转圈」的来源之一）。
     * 放到后台后，图片先出来，尺寸稍后补上。
     */
    private fun maybeEnrichBounds(side: Side) {
        if (MediaStoreMeta.hasAllFilesAccess()) return
        if (!settings.value.showMeta) return
        val items = _ui.value.pane(side).items
        val missing = items.filter { it.width <= 0 || it.height <= 0 }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO + crashGuard) {
            runCatching {
                // 先全部算完再一次性更新。
                // 逐个 updatePane 会让整栏重组 N 次 —— 60 张就是 60 次全量重组，
                // 这正是最典型的滑动卡顿来源，不能边读边刷。
                val dims = HashMap<String, Pair<Int, Int>>()
                missing.forEach { item ->
                    val (w, h) = docs.readBounds(item.docUri)
                    if (w > 0 && h > 0) dims[item.key] = w to h
                }
                if (dims.isNotEmpty()) {
                    updatePane(side) { pane ->
                        pane.copy(
                            items = pane.items.map {
                                dims[it.key]?.let { (w, h) -> it.copy(width = w, height = h) } ?: it
                            },
                        )
                    }
                }
            }
        }
    }

    /** 扫描后预加载缩略图（失败静默，只是加速）。 */
    private fun maybePreloadThumbs(side: Side) {
        val items = _ui.value.pane(side).items
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO + crashGuard) {
            runCatching {
                ThumbPreloader.preload(
                    getApplication(),
                    Coil.imageLoader(getApplication()),
                    items,
                )
            }
        }
    }

    /**
     * 异步补齐宽高 / 体积 / 真实路径。
     *
     * 只在有「管理所有文件」权限时才做（没权限时 loadMeta 直接返回 null）。
     * 放在扫描之后异步执行：**绝不能挡住出图**。
     */
    private fun maybeEnrichMeta(side: Side) {
        if (!MediaStoreMeta.hasAllFilesAccess()) return
        viewModelScope.launch(Dispatchers.IO + crashGuard) {
            runCatching {
                val meta = MediaStoreMeta.loadMeta(getApplication()) ?: return@launch
                updatePane(side) { pane ->
                    val next = MediaStoreMeta.enrich(pane.items, meta)
                    if (next === pane.items) pane else pane.copy(items = next)
                }
            }
        }
    }

    /**
     * 只补读「还没有时间」的条目，避免重复劳动。
     * 失败完全静默 —— 这只是增强，读不到就退回推算，不该打扰用户。
     */
    private fun maybeAutoExif(side: Side) {
        io {
            runCatching {
                val pane = _ui.value.pane(side)
                val missing = pane.items.filter { it.takenAt <= 0 }
                if (missing.isEmpty()) return@io
                var got = 0
                missing.forEach { item ->
                    val info = ExifMeta.read(getApplication(), item)
                    if (info != null && info.takenAt > 0) {
                        metaCache.putExif(item, info.takenAt)
                        metaCache.putCamera(item, info.camera)
                        item.path?.let { metaCache.putPath(item, it) }
                        got++
                    }
                }
                if (got > 0) {
                    updatePane(side) { it.copy(items = enrich(it.items)) }
                    _ui.update { st -> st.copy(useExif = true) }
                    recomputeMatch()
                }
            }
        }
    }

    private fun recomputeMatch() {
        val s = _ui.value
        val auto = Pairing.compute(
            s.left.items,
            s.right.items,
            deriveContentKeys(s.left.items + s.right.items),
            s.useExif,
        )
        val result = applyManualOverrides(s, auto)
        // 「已同步」= 配对双方主文件名相同。
        // 注意不能靠记录已处理的 key 来画进度：改名后文档 Uri 会变，
        // key 全部失效；而「名字已经一样了」是随时可重算的稳定事实。
        val synced = HashSet<String>()
        val byKey = HashMap<String, ImageItem>()
        (s.left.items + s.right.items).forEach { byKey[it.key] = it }
        result.partner.forEach { (key, otherKey) ->
            val a = byKey[key] ?: return@forEach
            val b = byKey[otherKey] ?: return@forEach
            if (Naming.baseOf(a.displayName) == Naming.baseOf(b.displayName)) {
                synced += key
            }
        }
        // 内容指纹统一由缓存派生，保证与当前文件名同步（改名后依然有效）
        val derivedContentKeys = deriveContentKeys(s.left.items + s.right.items)
        // 时间冲突：配上了，但两边拍摄时间相差超过容差。
        //
        // 必须在这里（而不是界面）算：要一张 key→item 的表，而界面每帧都调过滤，
        // 现算就是每帧重建全表。与 weakKeys 同一个理由。
        // 上面算 `synced` 时已经建好了 byKey，直接复用，不再重建一次。
        val conflicts = findTimeConflicts(result.partner, result.reason, byKey)
        // 「照拍摄时间换过来」的建议：与冲突**同源**（同一批冲突对、同一个容差），
        // 只是更进一步 —— 冲突只说"这一对不对劲"，这里给出"怎么换才对"。
        // 放在这里算而不是界面里现算：要给界面每帧读，现算就是每帧重建全表。
        val swaps = findTimeSwaps(result.partner, result.reason, byKey)
        // 「靠尺寸 + 体积认出来的」漏配：同样在这里算一次，不给界面每帧现算。
        // 用 result.partner（已含手工覆盖）作为"已配上"的基准 —— 手工配上的
        // 不会再被当成漏配重新建议回去；derivedContentKeys 在上面已经算好，直接复用。
        val sizeMatches = findSizeMatches(
            result.partner,
            s.left.items,
            s.right.items,
            unlinked = s.unlinked,
            contentKeys = derivedContentKeys,
        )
        // 「单侧搬移」：只有一对配错、而真正的对象还空着时，把冲突那张搬过去。
        // 与互换**同源**（同一份 collectConflicts），所以"面板说冲突、这里说搬得动"永远一致。
        // 用 result.partner（已含手工覆盖）：手工指定的对不在冲突集合里，天然被排除。
        val sideMoves = findSideMoves(
            result.partner,
            result.reason,
            byKey,
            unlinked = s.unlinked,
            contentKeys = derivedContentKeys,
        )
        // 「整体平移」：均匀错位时每一张的真对象都配着别人，逐对判据全失效 ——
        // 这里把冲突池交给引擎同一套时间匹配，一次性给出整批的新配对。
        val timeRealign = findTimeRealign(result.partner, result.reason, byKey)
        // 卡片角标用的反查表：key → 漏配候选对方的文件名。双向都建，
        // 左右两栏各自的卡片都能 O(1) 查到（byKey 在上面算 synced 时已建好，直接复用）。
        val sizeMatchPartner = HashMap<String, String>()
        sizeMatches.forEach { m ->
            val l = byKey[m.leftKey]
            val r = byKey[m.rightKey]
            if (l != null && r != null) {
                sizeMatchPartner[m.leftKey] = r.displayName
                sizeMatchPartner[m.rightKey] = l.displayName
            }
        }
        // 搬移建议的反查表：涉及的三张卡（搬动的、被腾出来的、搬去的）都能查到
        // 那条建议 —— 卡片 / 对比面板据此显示"能搬到哪张"。
        val sideMoveByKey = HashMap<String, SideMove>()
        sideMoves.forEach { m ->
            sideMoveByKey[m.moveKey] = m
            sideMoveByKey[m.fromKey] = m
            sideMoveByKey[m.toKey] = m
        }
        // 整批重配的反查表：重配后的去向（key → 新对方文件名）+ 会被腾出的 key。
        // 与 sizeMatchPartner / sideMoveByKey 同一个理由 —— 面板只显示"这一对差多少"
        // 是不够的，整批操作必须让用户先看见"我这一对会变成什么"。
        // 方向**双向都建**：左右两栏的卡片都能查到自己的去向。
        val realignTargetName = HashMap<String, String>()
        val realignFreedKeys = HashSet<String>()
        timeRealign?.let { plan ->
            plan.pairs.forEach { (lKey, rKey) ->
                val l = byKey[lKey]
                val r = byKey[rKey]
                if (l != null && r != null) {
                    realignTargetName[lKey] = r.displayName
                    realignTargetName[rKey] = l.displayName
                }
            }
            realignFreedKeys += plan.freedLeft
            realignFreedKeys += plan.freedRight
        }
        // 现场指纹：界面靠它找"当前这个现场的存档"。
        // 提前到 Advisor 之前算 —— 建议里「还能不能撤回上一次重配」正是靠它判断
        // （凭据必须属于当前现场，否则换目录后那批 pairKey 会指向另一个目录的文件）。
        val sessionKey = sessionFingerprint(
            s.left.treeUri?.toString(),
            s.right.treeUri?.toString(),
        )
        // 建议必须喂**刚刚算出来的** synced，不能喂 s.synced（那是上一次的值）。
        // 否则刚统一完一批，工具页还在说"还有 N 对没有统一" ——
        // 而 recomputeMatch 恰恰是改名后唯一的一次重算，这一轮过去就没人再纠了。
        val advices = Advisor.advise(
            left = s.left.items,
            right = s.right.items,
            matched = result.keys,
            synced = synced,
            contentKeys = derivedContentKeys,
            hasAllFilesAccess = MediaStoreMeta.hasAllFilesAccess(),
            leftUri = s.left.treeUri != null,
            rightUri = s.right.treeUri != null,
            // 用 result.weakPairs 而不是 auto.weakPairs：手动指定过的配对
            // 在 applyManualOverrides 里已经把 reason 抹掉，不该再被建议去复核
            weakPairs = result.weakPairs,
            // 时间冲突的对数：与弱依据正交，且更该先处理 —— 它是"矛盾"而不是"猜的"
            conflictPairs = conflicts.size / 2,
            // 其中有多少处能"照拍摄时间一键换过来"（成对互换，两对同时变好）
            swappablePairs = swaps.size,
            // 刚才按时间重配了几对（可以整体撤回）。
            // 用**即将生效**的现场指纹判断，见上面 sessionKey 处的说明。
            undoableSwaps = if (swapUndoAvailable(s, sessionKey)) s.swapCredential.pairCount else 0,
            // 没配上、但尺寸和体积都一模一样的对数（马上就能配上，不用跑流水线）
            sizeMatches = sizeMatches.size,
            // 冲突里有多少处能"直接搬到对的那张"（进容差 + 尺寸体积全同，比互换更硬）
            sideMoves = sideMoves.size,
            // 刚才搬了几张（可以整体撤回）
            undoableSideMoves = if (sideMoveUndoAvailable(s, sessionKey)) s.sideMoveCredential.pairCount else 0,
            // 整体平移能一次修几对（逐对判据修不了的那类错位）
            realignPairs = timeRealign?.pairs?.size ?: 0,
            // 刚才整体平移了几对（可以整体撤回）
            undoableRealign = if (realignUndoAvailable(s, sessionKey)) s.realignCredential.pairCount else 0,
        )
        val partnerNames = buildPartnerNames(s, result.partner)
        _ui.update {
            it.copy(
                contentKeys = derivedContentKeys,
                sessionKey = sessionKey,
                matched = result.keys,
                partnerNames = partnerNames,
                advices = advices,
                partner = result.partner,
                synced = synced,
                pairOffset = result.offset,
                bySeq = result.bySeq,
                byOrder = result.byOrder,
                bySimilar = result.bySimilar,
                reason = result.reason,
                // weakKeys / weakPairs 是 PairResult 的派生属性（reason 一变就跟着变），
                // 这里显式取出来放进 UiState，界面和过滤就不必各自再扫一遍 reason
                weakKeys = result.weakKeys,
                weakPairs = result.weakPairs,
                // 时间冲突：与 weakKeys 同样在配对重算时算一次
                conflictKeys = conflicts.keys.toSet(),
                conflictPairs = conflicts.size / 2,
                conflictDelta = conflicts,
                // 可执行的互换建议：与冲突同一次重算产出，界面上"有冲突"和"能怎么修"永远一致
                timeSwaps = swaps,
                // 漏配建议：同样与这次重算同源 —— 配上一对、或解除一对，它都会跟着变
                sizeMatches = sizeMatches,
                sizeMatchPartner = sizeMatchPartner,
                // 搬移建议：与冲突同一次重算产出（同一份 collectConflicts），
                // 界面上"有冲突"和"能怎么搬"永远一致
                sideMoves = sideMoves,
                sideMoveByKey = sideMoveByKey,
                // 平移方案：同样与这次重算同源（冲突一变，它就跟着变）
                timeRealign = timeRealign,
                // 平移的"去向"反查表：面板据此说清"这一对会变成什么 / 哪张会回到未配对"，
                // 而不是只丢一个"能重配 N 对"给用户猜
                realignTargetName = realignTargetName,
                realignFreedKeys = realignFreedKeys,
                seqHit = result.seqHit,
            )
        }
    }

    /**
     * 把用户手动指定的配对 / 解除覆盖到自动推算结果上。
     * 配对本质是猜测，所以「改得动」比「猜得准」更重要。
     */
    private fun applyManualOverrides(s: UiState, auto: PairResult): PairResult {
        val hasManual = s.manualPairs.isNotEmpty() || s.unlinked.isNotEmpty()
        if (!hasManual) return auto

        val partner = auto.partner.toMutableMap()

        // 1) 先解除。unlinked 存的是 pairKey，先映射回当前这批文档的 key
        val all = s.left.items + s.right.items
        val unlinkedKeys = all.filter { it.pairKey in s.unlinked }.map { it.key }
        unlinkedKeys.forEach { key ->
            val other = partner.remove(key)
            if (other != null) partner.remove(other)
        }

        // 2) 再按 pairKey 重建手动配对
        if (s.manualPairs.isNotEmpty()) {
            val byPairKey = HashMap<String, ImageItem>()
            (s.left.items + s.right.items).forEach { byPairKey[it.pairKey] = it }
            s.manualPairs.forEach { (aKey, bKey) ->
                val a = byPairKey[aKey] ?: return@forEach
                val b = byPairKey[bKey] ?: return@forEach
                if (a.side == b.side) return@forEach
                // 清掉双方原有的配对，再建立新的
                partner.remove(a.key)?.let { partner.remove(it) }
                partner.remove(b.key)?.let { partner.remove(it) }
                partner[a.key] = b.key
                partner[b.key] = a.key
            }
        }

        // 手动指定的配对，依据标为「用户指定」，别再显示推算理由误导人
        val reason = auto.reason.toMutableMap()
        if (s.manualPairs.isNotEmpty()) {
            val byPairKey = HashMap<String, ImageItem>()
            (s.left.items + s.right.items).forEach { byPairKey[it.pairKey] = it }
            s.manualPairs.forEach { (aKey, bKey) ->
                val a = byPairKey[aKey] ?: return@forEach
                val b = byPairKey[bKey] ?: return@forEach
                if (a.side == b.side) return@forEach
                reason.remove(a.key)
                reason.remove(b.key)
            }
        }
        unlinkedKeys.forEach { reason.remove(it) }

        // 3) 收尾清理：把「已经没有配对对象、却还留着配对依据」的条目删掉。
        //
        // 上面第 2) 步重建手动配对时，会把对方原有的配对抢走（partner.remove），
        // 但那个**被抢走的一方**变成未配对后，它的 reason 条目并没有被清 ——
        // 于是会剩下一批"没配对却有依据"的孤儿条目。后果：
        //   · 对比面板对一张没配对的图显示「配对依据：序号都是 0007」
        //   · 「仅弱依据」过滤里冒出根本还没配对的文件
        // 这里以 partner 为准做一次对齐，保证 reason 只描述真实存在的配对。
        val staleKeys = reason.keys.filter { it !in partner }
        staleKeys.forEach { reason.remove(it) }

        // 4) 三个依据计数跟着 reason 一起重算。
        //
        // 它们原本是引擎的原始输出（auto.bySeq / auto.byOrder / auto.bySimilar），
        // 覆盖之后就跟 reason 对不上了：用户手动拆掉一对序号配对，
        // 统计里仍写着「靠序号 5 对」，而「依据构成」按 reason 数出来是 4 对 ——
        // 同一屏两个数字打架。改成从 reason 派生，四处口径（含新增的弱依据）
        // 统一由 reason 一份数据说了算。
        //
        // 按 partner 逐对遍历、每对只数一次：即使 reason 出现奇数条目
        // （理论上不该有），也只是少算一对，不会把一对算成两对。
        var bySeq = 0
        var byOrder = 0
        var bySimilar = 0
        val counted = HashSet<String>()
        partner.forEach { (key, otherKey) ->
            if (!counted.add(key)) return@forEach
            counted.add(otherKey)
            when (reason[key]) {
                com.yuanbao.pairrename.util.PairReason.SEQ -> bySeq++
                com.yuanbao.pairrename.util.PairReason.ORDER -> byOrder++
                com.yuanbao.pairrename.util.PairReason.SIMILAR -> bySimilar++
                else -> Unit
            }
        }

        return auto.copy(
            partner = partner,
            keys = partner.keys.toSet(),
            reason = reason,
            bySeq = bySeq,
            byOrder = byOrder,
            bySimilar = bySimilar,
        )
    }

    /** 把当前勾选的两个跨栏文件设为配对（覆盖自动推算）。 */
    fun linkSelected() {
        val s = _ui.value
        val picked = s.checkedItems()
        if (picked.size != 2) {
            emitMsg(getString(R.string.msg_link_need_two))
            return
        }
        val (a, b) = picked[0] to picked[1]
        if (a.side == b.side) {
            emitMsg(getString(R.string.msg_link_need_cross))
            return
        }
        // 两边主文件名相同时 pairKey 会撞成一个值（都是 "img_1"）。
        // 存进去就是 `"img_1" → "img_1"`，而 applyManualOverrides 建映射时
        // `byPairKey[pk] = item` 是覆盖写法 —— 同一个 pairKey 只剩一个 item，
        // 于是 `a.side == b.side` 成立、被**静默跳过**。
        // 结果是"点了按钮、弹了成功提示、配对没有任何变化"。
        // 而这种文件本来就已经被算作「已同步」，压根不需要手动指定 ——
        // 直说比让用户对着没反应的界面反复点强。
        if (a.pairKey == b.pairKey) {
            emitMsg(getString(R.string.msg_link_same_base, a.displayName, b.displayName))
            return
        }
        val pairs = s.manualPairs.toMutableMap()
        // 双向都记，这样无论从哪边查都能命中
        pairs[a.pairKey] = b.pairKey
        pairs[b.pairKey] = a.pairKey
        _ui.update {
            it.copy(
                manualPairs = pairs,
                unlinked = it.unlinked - a.key - b.key,
            )
        }
        recomputeMatch()
        // 手工成果立刻安排落盘 —— 用户下一次操作可能就是在别处配完了
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_linked, a.displayName, b.displayName))
    }

    /**
     * 解除某个文件的配对（配对错了就用这个纠正）。
     *
     * 注意存的是 **pairKey（主文件名）而不是文档 key**：改名后文档 Uri 会变，
     * key 随之失效，解除记录就会「丢」。这与 manualPairs 的存法保持一致。
     */
    fun unlinkPartner(key: String) {
        val s = _ui.value
        val byKey = (s.left.items + s.right.items).associateBy { it.key }
        val other = s.partner[key]
        val pairKeys = listOfNotNull(key, other).mapNotNull { byKey[it]?.pairKey }.toSet()
        if (pairKeys.isEmpty()) return
        _ui.update {
            it.copy(
                unlinked = it.unlinked + pairKeys,
                manualPairs = it.manualPairs.filter { (k, v) -> k !in pairKeys && v !in pairKeys },
            )
        }
        recomputeMatch()
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_unlinked))
    }

    /**
     * 照拍摄时间把这一对（连同被换的另一对）换过来。
     *
     * 为什么值得单独做一个操作：两边各自从 0001 编号造成的整体错位，
     * 用户可以「解除一个、再手动配一个」重复 N 遍 —— 但那正是最费眼的一步。
     * 互换是**成对**的：把 (左1,右1) 与 (左2,右2) 换过来，两对的拍摄时间差同时变小。
     *
     * 结果写进 [UiState.manualPairs]（而不是新造一套"按时间配对"的状态），三个好处：
     * - 复用 `applyManualOverrides` 的覆盖链路，一处生效、处处生效（过滤 / 角标 / 统计）；
     * - 自动跟着现场存档落盘，退出重进不会丢；
     * - 语义正确 —— 用户点了这个按钮，就是用户确认了这两对，不该再被反过来质疑。
     *
     * 同时把这次写进去的条目另存一份到 [UiState.swapCredential]，好让这一步能整体撤回。
     */
    fun applyTimeSwap(swap: TimeSwap) {
        val s = _ui.value
        val byKey = (s.left.items + s.right.items).associateBy { it.key }
        val l = byKey[swap.leftKey] ?: return
        val r = byKey[swap.oldRightKey] ?: return
        val r2 = byKey[swap.newRightKey] ?: return
        val l2 = byKey[swap.otherLeftKey] ?: return
        // pairKey 撞车（两边主文件名相同时是同一串）会让 applyManualOverrides
        // 把这一条**静默跳过** —— 与 linkSelected 是同一个坑：点了按钮、弹了成功提示、
        // 配对没变。宁可提前说清楚，也不要让用户对着没反应的界面反复点。
        if (l.pairKey == r2.pairKey || l2.pairKey == r.pairKey) {
            emitMsg(getString(R.string.msg_swap_same_base))
            return
        }
        val pairs = s.manualPairs.toMutableMap()
        pairs[l.pairKey] = r2.pairKey
        pairs[r2.pairKey] = l.pairKey
        pairs[l2.pairKey] = r.pairKey
        pairs[r.pairKey] = l2.pairKey
        _ui.update {
            it.copy(
                manualPairs = pairs,
                // 交换是"改配"，之前若有解除记录会抢在手动配对之前生效，必须一并撤掉
                unlinked = it.unlinked - l.pairKey - r.pairKey - r2.pairKey - l2.pairKey,
                // 只记**这一次**写进去的条目：撤回时按值匹配删除。
                // 打上现场戳（Credential.session）：换目录之后这批凭据不再有效。
                // 指纹为空（目录没选齐）时存空串 —— credentialUsable 对空指纹一律判否。
                swapCredential = Credential(
                    pairs = mapOf(
                        l.pairKey to r2.pairKey,
                        r2.pairKey to l.pairKey,
                        l2.pairKey to r.pairKey,
                        r.pairKey to l2.pairKey,
                    ),
                    unlinked = emptySet(),
                    session = s.sessionKey ?: "",
                ),
            )
        }
        recomputeMatch()
        // 与手工配对同样立刻安排落盘：用户下一步可能就切到别的目录去了
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_time_swapped))
    }

    /**
     * 照拍摄时间修好**当前最该修的一处**（Advisor 的「照时间换回」）。
     *
     * 一次只换一处，不做"把所有能换的都换掉"：交换虽然保证两对同时变好，
     * 但换过来之后两对里的另一半文件是不是真的该跟过去配，最终还得人看一眼。
     * 换完 [UiState.timeSwaps] 会重算，建议条会变成「还有 N 处可以换回」，
     * 用户就能一处一处确认下去 —— 而不是一口气动掉十几对。
     */
    fun applyBestTimeSwap() {
        val first = _ui.value.timeSwaps.firstOrNull()
        if (first == null) {
            emitMsg(getString(R.string.msg_no_swap))
            return
        }
        applyTimeSwap(first)
    }

    /**
     * 把「靠尺寸 + 体积认出来的」漏配**一键全部配上**。
     *
     * 为什么敢一键：判定要求"尺寸相同 + 体积完全相同 + 两边各只有这一张"，
     * 证据强度非常接近内容指纹 —— 引擎自己用内容指纹配上时也不会要求你逐个确认。
     * 多候选（重复图 / 连拍）与内容指纹不同的对，已经在 [findSizeMatches] 里被排除了。
     *
     * 写进 [UiState.manualPairs] 而不是新造状态：复用覆盖链路、自动落盘、语义正确
     * （理由同 [applyTimeSwap]）。**不做**"一键撤回"：引擎用内容指纹配上的对
     * 也没有批量撤回 —— 真有个别不对，在卡片上单个解除即可，
     * 证据这么硬还配错的概率本来就极低。
     */
    fun applySizeMatches() {
        val linked = linkSizeMatches(_ui.value.sizeMatches)
        if (linked > 0) emitMsg(getString(R.string.msg_size_linked, linked))
    }

    /**
     * 配上**单个**漏配（卡片角标「能对上」点一下）：只动这一张对应的建议，其余保持。
     *
     * 与 [applySizeMatches] 共用同一条写入路径（[linkSizeMatches]），
     * 区别只在入口：批量入口喂全部建议，这里按 key 找到对应那一条。
     * key 已经不在建议里（刚被配上 / 刚被解除 / 现场变了）→ 静默返回，
     * 角标会随这次重算一起消失，不需要报错。
     */
    fun applySizeMatch(key: String) {
        val m = _ui.value.sizeMatches.firstOrNull { it.leftKey == key || it.rightKey == key } ?: return
        val linked = linkSizeMatches(listOf(m))
        if (linked > 0) emitMsg(getString(R.string.msg_size_linked, linked))
    }

    /**
     * 把给定的漏配建议写进 [UiState.manualPairs]（pairKey 双向），返回实际配上的对数。
     *
     * 写进 [UiState.manualPairs] 而不是新造状态：复用覆盖链路、自动落盘、语义正确
     * （理由同 [applyTimeSwap]）。**不做**"一键撤回"：引擎用内容指纹配上的对
     * 也没有批量撤回 —— 真有个别不对，在卡片上单个解除即可，
     * 证据这么硬还配错的概率本来就极低。
     */
    private fun linkSizeMatches(matches: List<SizeMatch>): Int {
        if (matches.isEmpty()) return 0
        val s = _ui.value
        val byKey = (s.left.items + s.right.items).associateBy { it.key }
        val pairs = s.manualPairs.toMutableMap()
        var linked = 0
        matches.forEach { m ->
            val l = byKey[m.leftKey] ?: return@forEach
            val r = byKey[m.rightKey] ?: return@forEach
            // pairKey 撞车（两边主文件名相同）会被 applyManualOverrides 静默跳过 ——
            // 但这种文件本来就已经"同名"，跳过不算失败，不必单独提示
            if (l.pairKey == r.pairKey) return@forEach
            pairs[l.pairKey] = r.pairKey
            pairs[r.pairKey] = l.pairKey
            linked++
        }
        if (linked == 0) return 0
        _ui.update { it.copy(manualPairs = pairs) }
        recomputeMatch()
        scheduleSessionSave()
        return linked
    }

    /**
     * 把冲突对里的一张搬到"拍摄时间对得上、尺寸体积全同"的那张上去（单个）。
     *
     * 写入是**有界**的，只动三个文件：搬动的、被腾出来的、搬去的。
     * 关键是最后一件事：被腾出来的那张要进 [UiState.unlinked]（用户级的"解除"），
     * 否则下次重算时引擎会按序号/顺序把它重新配给别人 —— 那就成了"链式重排"，
     * 链上的中间状态没人验证过（[com.yuanbao.pairrename.util.findTimeSwaps] 拒绝贪心链的理由）。
     * 显式解除后，它就以"未配对"的样子停在列表里：诚实，而且用户可以自己处理，
     * 或者被 [findSizeMatches] 之类的下一轮建议接走。
     */
    fun applySideMove(move: SideMove) {
        val moved = linkSideMoves(listOf(move))
        if (moved > 0) emitMsg(getString(R.string.msg_side_moved, moved))
    }

    /**
     * 把所有搬移建议一次做完（Advisor 的「搬过去」）。
     *
     * 与 [applyTimeSwap] 的"一次只换一处"不同，这里敢批量：每条建议的判据是
     * "搬完**进容差**"（互换只要求"变小"），而且每条建议涉及的三个文件互不重叠
     * （[com.yuanbao.pairrename.util.findSideMoves] 闸 6）—— 彼此独立、可各自验证。
     * 搬错了有 [undoSideMoves] 一键退回。
     */
    fun applySideMoves() {
        val moved = linkSideMoves(_ui.value.sideMoves)
        if (moved > 0) emitMsg(getString(R.string.msg_side_moved, moved))
    }

    /**
     * 把给定的搬移建议写进去，返回实际搬成的张数。
     *
     * 单张入口与批量入口共用这一条路径（同 `linkSizeMatches` 的理由：
     * 两条手写的写入路径迟早 drift 成"批量能搬、单张静默失败"）。
     */
    private fun linkSideMoves(moves: List<SideMove>): Int {
        if (moves.isEmpty()) return 0
        val s = _ui.value
        val byKey = (s.left.items + s.right.items).associateBy { it.key }
        val pairs = s.manualPairs.toMutableMap()
        val unlinked = s.unlinked.toMutableSet()
        val written = HashMap<String, String>()
        val freed = HashSet<String>()
        var moved = 0
        moves.forEach { m ->
            val move = byKey[m.moveKey] ?: return@forEach
            val from = byKey[m.fromKey] ?: return@forEach
            val to = byKey[m.toKey] ?: return@forEach
            // pairKey 撞车（两边主文件名相同）会被 applyManualOverrides 静默跳过 ——
            // 与 linkSizeMatches 同一个坑，跳过不算失败，但也不能记进凭据
            if (move.pairKey == to.pairKey) return@forEach
            // 搬动的那张若已有手动条目，先清掉（要搬的是**这一张**，不是它原来的条目）
            pairs.remove(move.pairKey)?.let { pairs.remove(it) }
            pairs[move.pairKey] = to.pairKey
            pairs[to.pairKey] = move.pairKey
            written[move.pairKey] = to.pairKey
            written[to.pairKey] = move.pairKey
            // 被腾出来的那张：清掉它的手动条目 + 显式解除，免得引擎把它重新配走
            pairs.remove(from.pairKey)?.let { pairs.remove(it) }
            unlinked += from.pairKey
            freed += from.pairKey
            moved++
        }
        if (moved == 0) return 0
        _ui.update {
            it.copy(
                manualPairs = pairs,
                unlinked = unlinked,
                // 只记**这一次**写进去的条目：撤回时按值匹配删除；被腾出来的记进 unlinked，
                // 撤回时再拿回来。现场戳同 swap 凭据（见 [Credential]）。
                sideMoveCredential = Credential(
                    pairs = written,
                    unlinked = freed,
                    session = s.sessionKey ?: "",
                ),
            )
        }
        recomputeMatch()
        scheduleSessionSave()
        return moved
    }

    /**
     * 这次「单侧搬移」现在还撤得动吗。
     *
     * 与 [swapUndoAvailable] 同一个形状、同一个理由：有凭据，且凭据属于当前现场。
     * 判定只写在这里，建议条的显示与实际撤回都走这一个口径。
     */
    private fun sideMoveUndoAvailable(s: UiState, sessionKey: String? = s.sessionKey): Boolean =
        credentialUsable(s.sideMoveCredential.present, s.sideMoveCredential.session, sessionKey)

    /**
     * 撤回最近一次「单侧搬移」。
     *
     * 三件事一起做：删掉值仍匹配的手工配对、把腾出来的那些从解除集合里拿掉
     * （它们是被我们解除的，不是用户解除的 —— 留着就成了一条莫名其妙的历史包袱）、
     * 清空凭据。换现场则整批作废且**不动任何配对**（同 [undoTimeSwaps]）。
     */
    fun undoSideMoves() {
        val s = _ui.value
        if (!s.sideMoveCredential.present) return
        if (!sideMoveUndoAvailable(s)) {
            _ui.update { it.copy(sideMoveCredential = Credential()) }
            emitMsg(getString(R.string.msg_side_stale))
            return
        }
        val kept = s.manualPairs.filter { (k, v) -> s.sideMoveCredential.pairs[k] != v }
        _ui.update {
            it.copy(
                manualPairs = kept,
                unlinked = it.unlinked - s.sideMoveCredential.unlinked,
                sideMoveCredential = Credential(),
            )
        }
        recomputeMatch()
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_side_undone))
    }

    /**
     * 把「整批重配」的方案交给界面逐条摊开，等用户确认。
     *
     * 与 [applyTimeRealign] 的关系：这个是**入口**，那个是**执行**。互换 / 搬移
     * 只动一两对，按下去直接生效、留一条撤回凭据就够了；整批重配一次动一串，
     * 用户看不到每一对会变成什么就提交，等于在赌。所以两个入口（对比面板的按钮、
     * 状态条的按钮）都先走这里，真正的写入统一由预览对话框的确认触发。
     *
     * 已经算过方案就不必再算：方案是 [recomputeMatch] 的产物，这里只做转发。
     */
    fun previewRealign() {
        val plan = _ui.value.timeRealign
        if (plan == null) {
            emitMsg(getString(R.string.msg_realign_none))
            return
        }
        _events.tryEmit(UiEvent.PreviewRealign(plan))
    }

    /**
     * 按拍摄时间把那**一批**冲突重新配一遍（整体平移）。
     *
     * 与 [applySideMove] / [applyTimeSwap] 的关键区别：这里动的是**一串**文件，
     * 所以写入方式也不同 —— 先把涉及到的所有手动条目清掉，再统一写新配对，
     * 最后把匹配不上的明确解除。为什么必须"先清"：`applyManualOverrides` 会遍历
     * `manualPairs` 的**全部**条目，池子里任何一个残留的旧条目（比如 a 还留着 a→p）
     * 都会在之后被重新应用，把刚修好的分配又拆掉 —— 而且拆不拆取决于 Map 的遍历顺序。
     */
    fun applyTimeRealign() {
        val s = _ui.value
        val plan = s.timeRealign
        // 曾经是 `?: return` —— 用户按下"整批重配"什么都不会发生，也没人说一句为什么。
        // 这台机器上按钮点下去没反应，是最容易被理解成"软件卡了"的一类现象。
        // 方案是重算出来的，重算的触发源在用户手里，正常不会在预览期间消失；
        // 真消失了（会话换文件夹之类）也得明说，而不是静默吞掉。
        if (plan == null) {
            emitMsg(getString(R.string.msg_realign_none))
            return
        }
        val byKey = (s.left.items + s.right.items).associateBy { it.key }
        val pairs = s.manualPairs.toMutableMap()
        val unlinked = s.unlinked.toMutableSet()
        // pairKey 撞车（两边主文件名相同）会被 applyManualOverrides 静默跳过 ——
        // 整批里只要有一对撞车，就整批不动：半批改完的分配比不改更难收拾
        val items = plan.keys.mapNotNull { byKey[it] }
        for ((lKey, rKey) in plan.pairs) {
            val l = byKey[lKey] ?: return
            val r = byKey[rKey] ?: return
            if (l.pairKey == r.pairKey) {
                emitMsg(getString(R.string.msg_realign_same_base))
                return
            }
        }
        val written = HashMap<String, String>()
        val freed = HashSet<String>()
        // 先清掉池子里所有旧的手动条目（见方法说明：残留条目会被重新应用）
        items.forEach { it ->
            pairs.remove(it.pairKey)?.let { other -> pairs.remove(other) }
        }
        plan.pairs.forEach { (lKey, rKey) ->
            val l = byKey[lKey] ?: return@forEach
            val r = byKey[rKey] ?: return@forEach
            pairs[l.pairKey] = r.pairKey
            pairs[r.pairKey] = l.pairKey
            written[l.pairKey] = r.pairKey
            written[r.pairKey] = l.pairKey
        }
        (plan.freedLeft + plan.freedRight).forEach { key ->
            val it = byKey[key] ?: return@forEach
            unlinked += it.pairKey
            freed += it.pairKey
        }
        _ui.update {
            it.copy(
                manualPairs = pairs,
                unlinked = unlinked,
                realignCredential = Credential(
                    pairs = written,
                    unlinked = freed,
                    session = s.sessionKey ?: "",
                ),
            )
        }
        recomputeMatch()
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_realign_applied, plan.pairs.size, plan.freedLeft.size + plan.freedRight.size))
    }

    /**
     * 这次「整体平移」现在还撤得动吗。判定与另外两个凭据同一个实现（[credentialUsable]）。
     */
    private fun realignUndoAvailable(s: UiState, sessionKey: String? = s.sessionKey): Boolean =
        credentialUsable(s.realignCredential.present, s.realignCredential.session, sessionKey)

    /**
     * 撤回最近一次「整体平移」。
     *
     * 与另外两个撤回同一个形状：只删**值仍匹配**的条目、把被腾出来的从解除集合里
     * 拿回、清空凭据；换现场则整批作废且不动任何配对。
     */
    fun undoTimeRealign() {
        val s = _ui.value
        if (!s.realignCredential.present) return
        if (!realignUndoAvailable(s)) {
            _ui.update {
                it.copy(realignCredential = Credential())
            }
            emitMsg(getString(R.string.msg_realign_stale))
            return
        }
        val kept = s.manualPairs.filter { (k, v) -> s.realignCredential.pairs[k] != v }
        _ui.update {
            it.copy(
                manualPairs = kept,
                unlinked = it.unlinked - s.realignCredential.unlinked,
                realignCredential = Credential(),
            )
        }
        recomputeMatch()
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_realign_undone))
    }

    /**
     * 「这次操作现在还撤得动吗」——**唯一的实现**。
     *
     * 现在有三个用法各异的凭据（按时间重配 / 单侧搬移 / 整体平移），
     * 形状完全一样：有内容，且签发时的现场指纹等于当前现场（指纹为空时一律判否：
     * 没有现场，就谈不上"这份凭据属于哪个现场"）。
     * 逻辑收在这里，三个 `xxxUndoAvailable` 只负责取字段 ——
     * 否则第三个副本写歪一次，就会出现"建议条说能撤、点下去却什么也没发生"。
     */
    private fun credentialUsable(hasCredential: Boolean, issuedIn: String, sessionKey: String?): Boolean {
        val key = sessionKey ?: return false
        return hasCredential && issuedIn == key
    }

    /**
     * 这次「按时间重配」现在还撤得动吗。
     *
     * 两个条件缺一不可：**有凭据**，且**凭据属于当前这个现场**。
     * 判断只写在这里 —— 建议条的显示与实际撤回都走这一个口径，
     * 否则迟早出现"建议条说能撤、点下去却什么也没发生"这种自相矛盾。
     *
     * @param sessionKey 传"即将生效"的那个指纹（配对重算时用刚算出来的值，
     *   否则会拿换目录**之前**的指纹去比，多显示一轮错误的建议条）。
     */
    private fun swapUndoAvailable(s: UiState, sessionKey: String? = s.sessionKey): Boolean =
        credentialUsable(s.swapCredential.present, s.swapCredential.session, sessionKey)

    /**
     * 撤回最近一次「按时间重配」。
     *
     * 只删**值仍然匹配**的条目 —— 用户交换之后完全可能又手动改过其中一对，
     * 那时无脑按 key 删会把后来的改动一起抹掉，比不撤销更糟。
     */
    fun undoTimeSwaps() {
        val s = _ui.value
        if (!s.swapCredential.present) return
        if (!swapUndoAvailable(s)) {
            // 现场已经换了：凭据里的 pairKey 属于另一个目录，一条都不能用，
            // 只能整批作废。留着更糟 —— 会一直挂着一条"可以撤回"的建议。
            _ui.update { it.copy(swapCredential = Credential()) }
            emitMsg(getString(R.string.msg_swap_stale))
            return
        }
        val kept = s.manualPairs.filter { (k, v) -> s.swapCredential.pairs[k] != v }
        _ui.update {
            it.copy(manualPairs = kept, swapCredential = Credential())
        }
        recomputeMatch()
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_swap_undone))
    }

    /** 清除所有手动干预，回到自动推算。 */
    fun resetPairingOverrides() {
        // 「不要这些手动干预了」也包括"别再摆着一条'可以撤回重配'的建议"——
        // 凭据指向的条目已经全被清掉，留着它点下去只会说"已作废"。
        _ui.update {
            it.copy(
                manualPairs = emptyMap(),
                unlinked = emptySet(),
                swapCredential = Credential(),
                sideMoveCredential = Credential(),
                realignCredential = Credential(),
            )
        }
        recomputeMatch()
        // 「我不想要这些手动干预了」自然也包括**别在下次启动时给我恢复回来** ——
        // 保存一条空快照即可：SessionStore 把空快照视为"删除该现场"。
        scheduleSessionSave()
        emitMsg(getString(R.string.msg_pair_reset))
    }

    /** 滚动到下一个「已配对但还没统一」的文件，形成流水线。 */
    fun focusNextTodo() {
        val s = _ui.value
        val list = s.left.items + s.right.items
        val current = s.focusKey?.let { k -> list.indexOfFirst { it.key == k } } ?: -1
        // 先在当前位置之后找，找不到再从头找一圈
        val rotated = list.drop(current + 1) + list.take((current + 1).coerceAtLeast(0))
        val next = rotated.firstOrNull { it.key in s.matched && it.key !in s.synced }
        if (next == null) {
            emitMsg(getString(R.string.msg_all_done))
            return
        }
        _ui.update { it.copy(focusKey = next.key, focusNonce = it.focusNonce + 1) }
    }

    /** 空状态里点了「去设置」（通常是想去打开递归扫描）。 */
    fun openSettingsFromPane() {
        _events.tryEmit(UiEvent.OpenSettings)
    }

    /** 按配对关系批量统一名字。这是本应用的主操作。 */
    fun requestSyncAll(direction: SyncDirection = SyncDirection.LEFT_TO_RIGHT) {
        io {
            val s = _ui.value
            if (s.left.treeUri == null || s.right.treeUri == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@io
            }
            if (s.matched.isEmpty()) {
                emitMsg(getString(R.string.msg_no_pair))
                return@io
            }
            _events.emit(
                UiEvent.OpenSync(
                    leftNames = docs.listNames(s.left.treeUri),
                    rightNames = docs.listNames(s.right.treeUri),
                    direction = direction,
                ),
            )
        }
    }

    /** 这一对为什么被配在一起。推算的结果必须能解释。 */
    fun reasonText(key: String): String {
        val s = _ui.value
        return when (s.reason[key]) {
            com.yuanbao.pairrename.util.PairReason.CONTENT -> "文件内容完全相同（已校验）"
            com.yuanbao.pairrename.util.PairReason.EXIF -> "拍摄时间相同（已读 EXIF）"
            com.yuanbao.pairrename.util.PairReason.NAME -> "两边主文件名相同"
            com.yuanbao.pairrename.util.PairReason.SIMILAR -> "两边名字只差「副本 / 编辑」这类尾巴"
            com.yuanbao.pairrename.util.PairReason.SEQ -> {
                val seq = s.seqHit[key]
                if (seq != null) "文件名里的序号都是 $seq" else "文件名里的序号相同"
            }
            com.yuanbao.pairrename.util.PairReason.ORDER -> "按排列顺序对应（序号对不上）"
            null -> if (s.partner.containsKey(key)) "你手动指定的配对" else "未配对"
        }
    }

    /**
     * 读取文件内容，给配对做「确定性」校验。
     *
     * 只算体积相同的候选：体积不同必然不是同一张，
     * 所以这一步通常只需要读很少的文件。
     */
    fun verifyByContent() {
        io { verifyByContentSuspend() }
    }

    /** verifyByContent 的挂起版本，供流水线在同一协程里复用。 */
    private suspend fun verifyByContentSuspend() {
        val s = _ui.value
        val left = s.left.items
        val right = s.right.items
        if (left.isEmpty() || right.isEmpty()) {
            emitMsg(getString(R.string.msg_need_both))
            return
        }
        // 先按体积找候选，避免把两边所有图都读一遍
        val rightBySize = HashMap<Long, MutableList<ImageItem>>()
        right.forEach { if (it.size > 0) rightBySize.getOrPut(it.size) { ArrayList() }.add(it) }
        val needHash = LinkedHashSet<ImageItem>()
        left.forEach { l ->
            if (l.size > 0 && rightBySize.containsKey(l.size)) {
                needHash += l
                rightBySize[l.size]?.let { needHash += it }
            }
        }
        if (needHash.isEmpty()) {
            emitMsg(getString(R.string.msg_content_no_candidate))
            return
        }
        _ui.update { it.copy(busy = true, progress = 0 to needHash.size) }
        val keys = HashMap<String, String>()
        try {
            var done = 0
            needHash.forEach { item ->
                // 长循环必须能中断：几百张图逐一读盘时，
                // 点了取消却只能干等到跑完，体验上等于没有取消
                if (!currentCoroutineContext().isActive) return@forEach
                val stream = runCatching { getApplication<Application>().contentResolver.openInputStream(item.docUri) }
                    .getOrNull()
                val hash = stream?.let { ContentHash.digest(it, item.size) }
                if (!hash.isNullOrEmpty()) {
                    keys[item.key] = hash
                    metaCache.putHash(item, hash)
                }
                done++
                progressEvery(done, needHash.size) { pg -> _ui.update { it.copy(progress = pg) } }
            }
            // contentKeys 由 recomputeMatch 从缓存统一派生，
            // 这里不再单独写入 —— 两份数据源迟早会不一致

            recomputeMatch()
            val confirmed = keys.values.groupBy { it }.count { it.value.size >= 2 }
            emitMsg(getString(R.string.msg_content_done, confirmed, needHash.size))
        } finally {
            _ui.update { it.copy(busy = false, progress = null) }
        }
    }

    /**
     * 改名后顺手同步系统相册索引。
     * SAF 只改文件本身，MediaStore 里会残留一条打不开的旧记录；
     * 有「管理所有文件」权限时可以把这条脏数据清掉。失败不影响改名。
     */
    private fun syncMediaStore(item: ImageItem) {
        val path = item.path ?: return
        if (path.isBlank()) return
        io {
            MediaStoreMeta.notifyRenamed(getApplication(), path)
            // 索引刚被改过，缓存必须失效，否则下次扫描拿到的是旧名字
            MediaStoreMeta.invalidate()
        }
    }

    /**
     * 文件**数量**发生变化后调用（复制 / 移动 / 删除 / 归档）。
     *
     * 与 [syncMediaStore] 区分开：改名只是改索引里的名字，
     * 而增删会改变 MediaStore 的内容本身。缓存若不失效，
     * 新复制出来的文件会查不到宽高体积（表现为"这一张没显示尺寸"），
     * 不一望而知但确实不对。
     *
     * 刻意**不**放在 refreshBoth 里：改名也会走 refreshBoth，
     * 那样会让 30 秒缓存完全失效，等于把这个优化废掉。
     */
    private fun invalidateMetaOnFileChange() {
        io { MediaStoreMeta.invalidate() }
    }

    /** 清除内容校验结果，回到纯推算配对。 */
    fun clearContentKeys() {
        metaCache.clearHashes()
        _ui.update { it.copy(contentKeys = emptyMap()) }
        recomputeMatch()
    }

    /**
     * 读取左右两栏的 EXIF 拍摄时间。
     *
     * 有「管理所有文件」权限时走文件路径直读（快）；没有时退回 SAF 流，
     * 每张一次 IPC，会慢一些但照样能用。
     */
    fun readExif() {
        io { readExifSuspend() }
    }

    /** readExif 的挂起版本，供流水线在同一协程里复用。 */
    private suspend fun readExifSuspend() {
        val s = _ui.value
        val all = s.left.items + s.right.items
        if (all.isEmpty()) {
            emitMsg(getString(R.string.msg_need_both))
            return
        }
        _ui.update { it.copy(busy = true, progress = 0 to all.size) }
        try {
            var done = 0
            var got = 0
            all.forEach { item ->
                if (!currentCoroutineContext().isActive) return@forEach
                val info = ExifMeta.read(getApplication(), item)
                if (info != null && info.takenAt > 0) {
                    metaCache.putExif(item, info.takenAt)
                    metaCache.putCamera(item, info.camera)
                    item.path?.let { metaCache.putPath(item, it) }
                    got++
                }
                done++
                progressEvery(done, all.size) { pg -> _ui.update { it.copy(progress = pg) } }
            }

            _ui.update {
                it.copy(
                    useExif = got > 0,
                    left = it.left.copy(items = enrich(it.left.items)),
                    right = it.right.copy(items = enrich(it.right.items)),
                )
            }
            recomputeMatch()
            emitMsg(getString(R.string.msg_exif_done, got, all.size))
        } finally {
            _ui.update { it.copy(busy = false, progress = null) }
        }
    }

    fun toggleUseExif(on: Boolean) {
        _ui.update { it.copy(useExif = on) }
        recomputeMatch()
    }

    /** 清除 EXIF 时间与内容指纹，回到纯推算。 */
    fun clearAllVerification() {
        metaCache.clearAll()
        _ui.update { st ->
            st.copy(
                contentKeys = emptyMap(),
                useExif = false,
                left = st.left.copy(items = st.left.items.map { it.copy(takenAt = 0L) }),
                right = st.right.copy(items = st.right.items.map { it.copy(takenAt = 0L) }),
            )
        }
        recomputeMatch()
    }

    /** 用 EXIF 拍摄时间改名（如 20240315_143022.jpg）。 */
    fun renameByExif(side: Side) {
        io {
            val s = _ui.value
            val pane = s.pane(side)
            val tree = pane.treeUri
            if (tree == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@io
            }
            val targets = s.checkedItems().filter { it.side == side }.ifEmpty { pane.items }
            val withTime = targets.filter { it.takenAt > 0 }
            if (withTime.isEmpty()) {
                emitMsg(getString(R.string.msg_exif_none))
                return@io
            }
            val existing = docs.listNames(tree)
            val rows = ArrayList<PlanRow>()
            val used = existing.toMutableSet()
            withTime.sortedBy { it.takenAt }.forEach { item ->
                val ext = Naming.extensionOf(item.displayName)
                val desired = ExifMeta.timestampName(item.takenAt)
                if (desired.isEmpty()) return@forEach
                val candidate = Naming.join(desired, ext)
                // 同一秒连拍会撞名，自动加序号
                val final = if (used.any { it.equals(candidate, ignoreCase = true) }) {
                    Naming.resolveConflict(desired, ext, used, settings.value.numbering)
                } else {
                    candidate
                }
                used.remove(item.displayName)
                used.add(final)
                rows += PlanRow(
                    target = item,
                    oldName = item.displayName,
                    newName = final,
                    sourceName = item.displayName,
                    conflict = final != candidate,
                )
            }
            rows.removeAll { it.oldName == it.newName }
            if (rows.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@io
            }
            _events.emit(UiEvent.ConfirmPlan(rows, side))
        }
    }

    /** 打开回收站。 */
    /** 请求删除勾选的文件。真正的删除要由界面二次确认后才执行。 */
    fun requestDelete() {
        _events.tryEmit(UiEvent.AskDelete)
    }

    fun requestTrash() {
        io {
            _events.emit(UiEvent.ShowTrash(trash.list()))
        }
    }

    fun restoreTrashed(item: com.yuanbao.pairrename.data.TrashRepository.TrashedItem) {
        mutationIo {
            val r = runCatching { trash.restore(item) }
                .getOrDefault(TrashRepository.RestoreResult.FAILED)
            // 三种失败原因给三句话：用户拿着"操作失败"不知道该干什么，
            // 而 OCCUPIED 是有明确动作的（去把同名文件改名或移走）。
            emitMsg(
                when (r) {
                    TrashRepository.RestoreResult.OK ->
                        getString(R.string.msg_restored_one, item.displayName)
                    TrashRepository.RestoreResult.MISSING ->
                        getString(R.string.msg_restore_gone, item.displayName)
                    TrashRepository.RestoreResult.OCCUPIED ->
                        getString(R.string.msg_restore_occupied, item.displayName)
                    TrashRepository.RestoreResult.FAILED ->
                        getString(R.string.msg_failed, item.displayName)
                },
                // 被占住没恢复 = 回收站里还在，得给一条重试的路
                withUndo = false,
            )
            _events.emit(UiEvent.ShowTrash(trash.list()))
            refreshBoth()
        }
    }

    fun emptyTrash() {
        mutationIo {
            val n = runCatching { trash.empty() }.getOrDefault(0)
            emitMsg(getString(R.string.msg_trash_emptied, n))
            _events.emit(UiEvent.ShowTrash(trash.list()))
        }
    }

    /** 全盘查找重复图片（需要「管理所有文件」权限）。 */
    fun findDuplicates() {
        io {
            if (!DuplicateFinder.canRun()) {
                emitMsg(getString(R.string.msg_need_allfiles))
                return@io
            }
            _ui.update { it.copy(busy = true) }
            try {
                val groups = DuplicateFinder.find(getApplication())
                _events.emit(UiEvent.ShowDuplicates(groups))
                if (groups.isEmpty()) emitMsg(getString(R.string.msg_dup_none))
            } catch (se: SecurityException) {
                emitMsg(getString(R.string.msg_need_allfiles))
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** 请求滚动到某个文件的配对对象（跨栏跳转）。 */
    fun focusPartner(key: String) {
        val otherKey = _ui.value.partner[key] ?: return
        // nonce 自增保证连续点同一个目标也能再次滚动
        _ui.update { it.copy(focusKey = otherKey, focusNonce = it.focusNonce + 1) }
    }

    /** 返回某个文件的配对对象（跨栏），没有则 null。 */
    fun partnerOf(key: String): ImageItem? {
        val s = _ui.value
        val otherKey = s.partner[key] ?: return null
        return (s.left.items + s.right.items).firstOrNull { it.key == otherKey }
    }

    /** 目录刷新后，去掉已经不存在或已改名的勾选项。 */
    /**
     * 剔除已失效的勾选。
     *
     * 除了「文件被删/改名」，还要处理**被搜索或过滤隐藏**的情况：
     * 勾了几张后改搜索词，那些文件还在列表里但界面上看不见，
     * 此时执行批量操作就会改到看不见的文件 —— 必须跟着剔除。
     */
    private fun pruneChecked() {
        _ui.update { s ->
            val visible = Side.entries.flatMap { side ->
                applyFilter(
                    s.pane(side).items, s.query, s.matchFilter, s.synced, s.matched,
                    s.extraFilter, s.weakKeys, s.conflictKeys,
                ).map { it.key }
            }.toSet()
            val next = s.checked.filter { it in visible }.toSet()

            // 「名字来源」和「拖拽来源」是文件快照，改名/删除后就失效了。
            // 留着会有两个后果：
            //   1. 界面高亮一个已经不存在的文件
            //   2. 拖放时拿着旧 Uri 去改名 —— 直接失败，或更糟
            // 所以这里按「文件还在不在」清理（用全集，不能用过滤后的可见集：
            // 被搜索隐藏的文件依然是真实存在的，不该因此清掉选中源）。
            val exists = (s.left.items + s.right.items).map { it.key }.toSet()
            val src = s.selectedSource?.takeIf { it.key in exists }
            val drag = s.dragSource?.takeIf { it.key in exists }

            if (next.size == s.checked.size &&
                src == s.selectedSource &&
                drag == s.dragSource
            ) {
                s
            } else {
                s.copy(
                    checked = next,
                    showCheckboxes = next.isNotEmpty(),
                    selectedSource = src,
                    dragSource = drag,
                )
            }
        }
    }

    // ---------------- 交互 ----------------

    fun setScreen(screen: Screen) = _ui.update { it.copy(screen = screen) }

    /** 单栏页切换看左还是看右。 */
    fun setSingleSide(side: Side) = _ui.update { it.copy(singleSide = side) }

    /** 二级页返回：回到它所属的一级页。 */
    fun backFromSub() = _ui.update { st -> st.copy(screen = st.screen.parent) }

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q) }
        // 搜索词一变，可见集合就变了，勾选必须跟着收敛
        pruneChecked()
    }

    fun setDragSource(item: ImageItem?) = _ui.update { it.copy(dragSource = item) }

    fun clearSelection() = _ui.update { it.copy(selectedSource = null, dragSource = null) }

    fun resetFilters() = _ui.update {
        it.copy(query = "", matchFilter = MatchFilter.ALL, extraFilter = ExtraFilter())
    }

    /** 切换一个扩展名的过滤（再点一次取消）。 */
    fun toggleExt(ext: String) {
        _ui.update { st ->
            val cur = st.extraFilter.exts.toMutableSet()
            val e = ext.lowercase()
            if (!cur.remove(e)) cur += e
            st.copy(extraFilter = st.extraFilter.copy(exts = cur))
        }
        pruneChecked()
    }

    /** 设置体积区间（传 null 表示不限）。 */
    fun setSizeRange(minBytes: Long?, maxBytes: Long?) {
        _ui.update {
            it.copy(extraFilter = it.extraFilter.copy(minBytes = minBytes, maxBytes = maxBytes))
        }
        pruneChecked()
    }

    fun clearExtraFilter() {
        _ui.update { it.copy(extraFilter = ExtraFilter()) }
        pruneChecked()
    }

    fun cycleMatchFilter() {
        val next = when (_ui.value.matchFilter) {
            MatchFilter.ALL -> MatchFilter.TODO
            MatchFilter.TODO -> MatchFilter.DONE
            MatchFilter.DONE -> MatchFilter.UNPAIRED
            // 「复核」紧跟在「差异」后面：两者都是"挑出有问题的那批"，
            // 语义相邻，用户按同一个按钮连点两下就能从"对面没有的"走到"可能配错的"
            MatchFilter.UNPAIRED -> MatchFilter.SUSPECT
            // 「时间冲突」紧跟「弱依据」：都是"这一对可能配错了"，但角度相反 ——
            // 弱依据问"凭什么配上的"，时间冲突问"配上了但证据打架吗"。
            // 放隔壁是因为用户复核完一批可疑的，下一批自然就是这些自相矛盾的。
            MatchFilter.SUSPECT -> MatchFilter.CONFLICT
            MatchFilter.CONFLICT -> MatchFilter.ONLY_LEFT
            MatchFilter.ONLY_LEFT -> MatchFilter.ONLY_RIGHT
            MatchFilter.ONLY_RIGHT -> MatchFilter.ALL
        }
        _ui.update { it.copy(matchFilter = next) }
        pruneChecked()
    }

    /** 直接指定过滤（建议 / 统计页的「去复核」用；顶栏按钮走 [cycleMatchFilter]）。 */
    fun setMatchFilter(filter: MatchFilter) {
        if (_ui.value.matchFilter == filter) return
        _ui.update { it.copy(matchFilter = filter) }
        pruneChecked()
    }

    /**
     * 这一对是不是弱依据配的（对比面板据此加醒目提示）。
     *
     * 手动指定过的配对没有 reason，一律返回 false —— 用户亲手确认过的东西，
     * 界面不该再拿"可疑"去打扰他。
     */
    fun isWeakPair(key: String): Boolean =
        _ui.value.reason[key]?.let { isWeakReason(it) } ?: false

    /**
     * 复核弱依据配对：切到「仅弱依据」过滤 + 直接打开第一对的对比面板。
     *
     * 两个动作缺一不可：
     * 1. **切过滤** —— 不切的话点「下一对」会走到强依据的配对上去，复核等于没做；
     *    切了之后 compareNeighbor 只在这批里循环（它走的是可见集合）。
     * 2. **打开第一对** —— 只切过滤等于让用户自己再双击一次，白点了这个按钮。
     */
    fun reviewSuspects() {
        val s = _ui.value
        if (s.weakKeys.isEmpty()) {
            emitMsg(getString(R.string.msg_no_weak_pair))
            return
        }
        setMatchFilter(MatchFilter.SUSPECT)
        // 左栏优先：配对是双向的，随便从哪边进都能看到同一对，
        // 固定从左栏取是为了让「第一对」稳定不跳（右下两栏顺序一致时）
        val first = (s.left.items + s.right.items).firstOrNull { it.key in s.weakKeys }
        if (first != null) _events.tryEmit(UiEvent.OpenCompare(first))
    }

    /**
     * 复核时间冲突配对：切到「仅时间冲突」过滤 + 打开第一对的对比面板。
     *
     * 与 [reviewSuspects] 同一套路（切过滤 + 打开第一对，缺一不可），
     * 但复核的是**另一类**问题：这一对是通过 NAME/SIMILAR/SEQ/ORDER 配上的，
     * 可两边的拍摄时间差了 2 秒以上 —— 说明至少有一边配错了。
     *
     * 手工配对不会进来：用户亲手指定过的对，界面不该拿"可疑"打扰他，
     * 而 [findTimeConflicts] 也天然排除了它们（手工对的 reason 是 null）。
     */
    fun reviewConflicts() {
        val s = _ui.value
        if (s.conflictKeys.isEmpty()) {
            emitMsg(getString(R.string.msg_no_conflict))
            return
        }
        setMatchFilter(MatchFilter.CONFLICT)
        // 左栏优先，理由同 reviewSuspects：让"第一对"稳定不跳
        val first = (s.left.items + s.right.items).firstOrNull { it.key in s.conflictKeys }
        if (first != null) _events.tryEmit(UiEvent.OpenCompare(first))
    }

    fun toggleCheck(item: ImageItem) {
        _ui.update { s ->
            val next = s.checked.toMutableSet()
            if (!next.remove(item.key)) next.add(item.key)
            s.copy(checked = next, showCheckboxes = next.isNotEmpty())
        }
    }

    /**
     * 全选 / 取消全选**当前可见**的条目。
     *
     * 必须传入可见列表而不是用整栏全部条目：搜索或过滤后，
     * 界面上只显示一部分，如果全选把被隐藏的也选上，
     * 批量改名就会改到用户看不见的文件 —— 这类错误极难发现。
     */
    fun checkAllOf(visible: List<ImageItem> = visibleItems()) {
        _ui.update { s ->
            val keys = visible.map { it.key }
            val allChecked = keys.isNotEmpty() && keys.all { it in s.checked }
            val next = s.checked.toMutableSet()
            if (allChecked) next.removeAll(keys.toSet()) else next.addAll(keys)
            s.copy(checked = next, showCheckboxes = next.isNotEmpty())
        }
    }

    fun clearChecked() = _ui.update { it.copy(checked = emptySet(), showCheckboxes = false) }

    /** 轻点卡片：第一次选中为“名字来源”，第二次点击目标文件即应用。 */
    fun onCardTap(item: ImageItem) {
        val current = _ui.value.selectedSource
        when {
            current == null -> {
                _ui.update { it.copy(selectedSource = item) }
                emitMsg(getString(R.string.source_selected, item.displayName))
            }
            current.key == item.key -> _ui.update { it.copy(selectedSource = null) }
            else -> {
                _ui.update { it.copy(selectedSource = null) }
                applyRename(current, item)
            }
        }
    }

    /** 拖拽落下。 */
    /**
     * 拖放落点。
     *
     * **多选拖放**：如果拖起来的那张本身在勾选集合里、且勾了不止一个，
     * 就把整批名字按顺序套到目标及其后续文件上 ——
     * 几十张图一个个拖太痛苦。
     *
     * 数量不够时自动退化为单文件拖放，不会报错。
     */
    fun onDrop(target: ImageItem) {
        val st = _ui.value
        val source = st.dragSource
        _ui.update { it.copy(dragSource = null) }
        if (source == null || source.key == target.key) return

        val picked = st.checkedItems()
        // 拖动的那张必须在勾选集合里，才算"拖的是这一批"
        if (picked.size > 1 && picked.any { it.key == source.key }) {
            val ordered = orderByVisible(picked, source.side)
            val startIndex = ordered.indexOfFirst { it.key == target.key }
            if (startIndex >= 0 && ordered.size - startIndex > 1) {
                // 目标在来源栏内部：按顺序套到目标开始的连续一段
                val targets = ordered.subList(startIndex, ordered.size)
                applyNamesInOrder(listOf(source), targets, settings.value.conflictPolicy)
                return
            }
            val side = target.side
            val visible = visibleItems().filter { it.side == side }
            val idx = visible.indexOfFirst { it.key == target.key }
            if (idx >= 0) {
                val targets = visible.subList(idx, minOf(visible.size, idx + picked.size))
                applyNamesInOrder(picked, targets, settings.value.conflictPolicy)
                return
            }
        }
        applyRename(source, target)
    }

    /** 按当前可见顺序排序（拖放应用顺序要跟界面一致）。 */
    private fun orderByVisible(items: List<ImageItem>, side: Side): List<ImageItem> {
        val vis = visibleItems().filter { it.side == side }
        val rank = vis.mapIndexed { i, it -> it.key to i }.toMap()
        return items.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    fun updateSettings(s: AppSettings) {
        io {
            settingsRepo.save(s)
            refreshAll()
        }
    }

    // ---------------- 单文件改名 ----------------

    fun applyRename(
        from: ImageItem,
        to: ImageItem,
        overridePolicy: ConflictPolicy? = null,
        confirmed: Boolean = false,
    ) {
        mutationIo {
            _ui.update { it.copy(busy = true) }
            // done 必须声明在 try 之外：finally 里要用它决定"增量替换"还是整栏重扫。
            // 声明在 try 内的话，finally 看不到它（既编译不过，逻辑上也拿不到结果）。
            var done: List<RenameStep> = emptyList()
            var shouldRefresh = false
            try {
                if (!confirmed && settings.value.confirmBeforeApply) {
                    _events.emit(UiEvent.ConfirmApply(from, to, previewName(from, to)))
                    return@mutationIo
                }
                when (val outcome = perform(from, to, overridePolicy)) {
                    is Outcome.Success -> {
                        pushUndo(outcome.entry)
                        emitMsg(outcome.message, withUndo = true)
                        done = outcome.entry.steps
                    }
                    is Outcome.Skipped -> emitMsg(outcome.message)
                    is Outcome.Failed -> {
                        shouldRefresh = true
                        emitMsg(outcome.message)
                    }
                    is Outcome.Ask -> _events.emit(UiEvent.AskConflict(from, to, outcome.suggested, outcome.conflictName))
                }
            } finally {
                _ui.update { it.copy(busy = false) }
                // 拖放最多改两个文件（含交换），增量替换即可
                when {
                    done.isNotEmpty() -> patchAfterRename(done)
                    shouldRefresh -> refreshBoth()
                }
            }
        }
    }

    /** 手动改名：只改这一个文件。 */
    fun commitManualRename(item: ImageItem, newBase: String, newExt: String) {
        mutationIo {
            _ui.update { it.copy(busy = true) }
            // finalName 提到 try 外：finally 里要用它做增量替换（别整栏重扫）
            var finalName = item.displayName
            var resultUri = item.docUri
            var didRename = false
            try {
                if (!item.canRename) {
                    emitMsg(getString(R.string.msg_readonly))
                    return@mutationIo
                }
                val st = settings.value
                val ext = Naming.sanitizeExt(newExt)
                val base = Naming.sanitize(newBase, reserve = ext.length + 1)
                val full = Naming.join(base, ext)
                val existing = docs.listNames(item.treeUri) - item.displayName
                finalName = if (existing.any { it.equals(full, ignoreCase = true) }) {
                    Naming.resolveConflict(base, ext, existing, st.numbering)
                } else {
                    full
                }
                val newUri = docs.rename(item.docUri, finalName, item.treeUri)
                if (newUri == null) {
                    emitMsg(getString(R.string.msg_failed, finalName))
                    return@mutationIo
                }
                syncMediaStore(item)
                resultUri = newUri
                didRename = true
                pushUndo(
                    UndoEntry(
                        listOf(RenameStep(newUri, item.displayName, finalName, item.side, item.treeUri, item.docUri)),
                        item.displayName,
                    ),
                )
                emitMsg(getString(R.string.msg_renamed, item.displayName, finalName), withUndo = true)
            } finally {
                _ui.update { it.copy(busy = false) }
                // 单文件改名：优先用 provider 返回的新 URI 就地更新；解析不到时才重扫。
                if (didRename) {
                    patchAfterRename(
                        listOf(RenameStep(resultUri, item.displayName, finalName, item.side, item.treeUri, item.docUri)),
                    )
                } else {
                    refreshBoth()
                }
            }
        }
    }

    /** 估算拖放结果名（用于确认弹窗）。 */
    fun previewName(from: ImageItem, to: ImageItem): String {
        val st = settings.value
        val ext = if (st.extensionPolicy == ExtensionPolicy.USE_SOURCE) {
            Naming.extensionOf(from.displayName)
        } else {
            Naming.extensionOf(to.displayName)
        }
        val desiredBase = Naming.sanitize(
            Naming.withTemplate(Naming.baseOf(from.displayName), st.prefix, st.suffix),
            reserve = ext.length + 1,
        )
        val existing = docs.listNames(to.treeUri) - to.displayName - from.displayName
        return Naming.resolveConflict(desiredBase, ext, existing, st.numbering)
    }

    private fun perform(from: ImageItem, to: ImageItem, overridePolicy: ConflictPolicy?): Outcome {
        val st = settings.value
        if (!to.canRename) return Outcome.Failed(getString(R.string.msg_readonly))

        val fromBase = Naming.baseOf(from.displayName)
        val fromExt = Naming.extensionOf(from.displayName)
        val toBase = Naming.baseOf(to.displayName)
        val toExt = Naming.extensionOf(to.displayName)
        val ext = if (st.extensionPolicy == ExtensionPolicy.USE_SOURCE) fromExt else toExt
        val desiredBase = Naming.sanitize(
            Naming.withTemplate(fromBase, st.prefix, st.suffix),
            reserve = ext.length + 1,
        )
        val sameFolder = from.treeUri == to.treeUri
        val swap = sameFolder && st.sameFolderMode == SameFolderMode.SWAP

        // 目录列表走 SAF provider IPC；本次操作保留一份可变快照，后续改名时就地更新。
        val directoryNames = docs.listNames(to.treeUri).toMutableSet()
        val names = directoryNames.toMutableSet()
        names.remove(to.displayName)
        if (swap) names.remove(from.displayName)

        var desired = Naming.join(desiredBase, ext)
        val conflict = names.firstOrNull { it.equals(desired, ignoreCase = true) }
        val policy = overridePolicy ?: st.conflictPolicy

        // 需要被改名为 desired 的那个文件；覆盖模式下会先让位到临时名
        var working = to

        if (conflict != null) {
            when (policy) {
                ConflictPolicy.AUTO_RENAME ->
                    desired = Naming.resolveConflict(desiredBase, ext, names, st.numbering)

                ConflictPolicy.SKIP ->
                    return Outcome.Skipped(getString(R.string.msg_skipped))

                ConflictPolicy.OVERWRITE -> {
                    // 暂不允许不可撤回的覆盖。SAF 冲突文件必须先有可验证备份，
                    // 再把备份纳入同一 UndoEntry 后才能重新开放该策略。
                    return Outcome.Failed(getString(R.string.msg_overwrite_unsafe))
                }

                ConflictPolicy.ASK ->
                    return Outcome.Ask(
                        Naming.resolveConflict(desiredBase, ext, names, st.numbering),
                        conflict,
                    )
            }
        }

        val steps = mutableListOf<RenameStep>()
        return try {
            if (swap) {
                val temp = Naming.tempName(directoryNames)
                val tempUri = renameOrFind(from, temp) ?: return rollback(steps, "temp")
                steps += RenameStep(tempUri, from.displayName, temp, from.side, from.treeUri, from.docUri)
                directoryNames.remove(from.displayName)
                directoryNames.add(temp)

                val toUri = renameOrFind(working, desired) ?: return rollback(steps, desired)
                steps += RenameStep(toUri, working.displayName, desired, to.side, working.treeUri, working.docUri)
                directoryNames.remove(working.displayName)
                directoryNames.add(desired)

                val afterNames = directoryNames - temp - to.displayName
                val restName = Naming.resolveConflict(toBase, fromExt, afterNames, st.numbering)
                val restUri = renameOrFind(tempUri.toItem(from, temp), restName)
                    ?: return rollback(steps, restName)
                steps += RenameStep(restUri, temp, restName, from.side, from.treeUri, tempUri)

                Outcome.Success(
                    UndoEntry(steps, getString(R.string.label_swap_names, "$fromBase.$fromExt", "$toBase.$toExt")),
                    getString(R.string.msg_swapped, "$fromBase.$fromExt", "$toBase.$toExt"),
                )
            } else if (sameFolder) {
                val spare = directoryNames - from.displayName - working.displayName
                val shifted = Naming.resolveConflict(fromBase, fromExt, spare, st.numbering, forceNumber = true)
                val fromUri = renameOrFind(from, shifted) ?: return rollback(steps, shifted)
                steps += RenameStep(fromUri, from.displayName, shifted, from.side, from.treeUri, from.docUri)
                directoryNames.remove(from.displayName)
                directoryNames.add(shifted)

                val names2 = directoryNames - shifted - working.displayName
                val finalName = Naming.resolveConflict(desiredBase, ext, names2, st.numbering)
                val toUri = renameOrFind(working, finalName) ?: return rollback(steps, finalName)
                steps += RenameStep(toUri, working.displayName, finalName, to.side, working.treeUri, working.docUri)

                Outcome.Success(
                    UndoEntry(steps, getString(R.string.label_drag_rename, to.displayName, finalName)),
                    getString(R.string.msg_renamed, to.displayName, finalName) +
                        "（${from.displayName} → $shifted）",
                )
            } else {
                val toUri = renameOrFind(working, desired) ?: return rollback(steps, desired)
                steps += RenameStep(toUri, working.displayName, desired, to.side, working.treeUri, working.docUri)
                Outcome.Success(
                    UndoEntry(steps, getString(R.string.label_drag_rename, to.displayName, desired)),
                    getString(R.string.msg_renamed, to.displayName, desired),
                )
            }
        } catch (e: Exception) {
            rollback(steps, "")
            Outcome.Failed(getString(R.string.msg_failed, e.message.orEmpty()))
        }
    }

    /** URI 改名未明确成功时不按显示名猜测身份，避免误改目录内同名文件。 */
    private fun renameOrFind(item: ImageItem, newName: String): Uri? =
        docs.rename(item.docUri, newName, item.treeUri)

    private fun rollback(steps: List<RenameStep>, failedAt: String): Outcome {
        steps.asReversed().forEach { step -> docs.rename(step.docUri, step.previousName, step.treeUri) }
        return Outcome.Failed(getString(R.string.msg_failed, failedAt))
    }

    private fun Uri.toItem(base: ImageItem, name: String): ImageItem =
        base.copy(docUri = this, displayName = name)

    // ---------------- 批量 / 对齐 ----------------

    /** 打开批量改名面板：先在 IO 线程取到真实的文件名集合。 */
    fun requestBatch(side: Side) {
        io {
            val state = _ui.value
            val tree = state.pane(side).treeUri
            if (tree == null) {
                emitMsg(getString(R.string.msg_no_persist))
                return@io
            }
            val chosen = state.checkedItems().filter { it.side == side }
            val otherSide = state.checkedItems().count { it.side != side }
            if (chosen.isEmpty()) {
                // 勾选集中在另一栏时，直接按另一栏处理，别让用户困惑
                val fallback = if (side == Side.LEFT) Side.RIGHT else Side.LEFT
                val fbTree = state.pane(fallback).treeUri
                if (fbTree == null) {
                    emitMsg(getString(R.string.msg_nothing))
                    return@io
                }
                _events.emit(UiEvent.OpenBatch(fallback, docs.listNames(fbTree)))
                return@io
            }
            if (otherSide > 0) {
                emitMsg(getString(R.string.msg_cross_pane, chosen.size))
            }
            _events.emit(UiEvent.OpenBatch(side, docs.listNames(tree)))
        }
    }

    /** 打开顺序对齐面板。[source] 是以哪一栏为基准（工具页两个入口各传一个）。 */
    fun requestAlign(source: Side = Side.LEFT) {
        io {
            val l = _ui.value.left.treeUri
            val r = _ui.value.right.treeUri
            if (l == null || r == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@io
            }
            _events.emit(
                UiEvent.OpenAlign(
                    leftNames = docs.listNames(l),
                    rightNames = docs.listNames(r),
                    // 配对引擎已经从序号里推算出偏移量，直接作为初值，省得用户手动拨
                    suggestedOffset = _ui.value.pairOffset.coerceAtLeast(0),
                    source = source,
                ),
            )
        }
    }

    /** 执行一份预先算好的改名计划，中途失败整体回滚。可随时停止。 */
    fun executePlan(rows: List<PlanRow>) {
        cancellable {
            val total = rows.size
            _ui.update { it.copy(busy = true, progress = 0 to total) }
            val steps = mutableListOf<RenameStep>()
            // 记账器：批量只允许"某个文件失败"，不允许"整批失败"。
            val ledger = BatchLedger(total)
            var stopped = false
            try {
                var done = 0
                for (row in rows) {
                    // 每次循环检查一次取消：批量跑到一半想停手就停
                    if (!isActive) {
                        stopped = true
                        break
                    }
                    if (row.newName == row.oldName) {
                        ledger.onUnchanged()
                    } else if (!row.target.canRename) {
                        ledger.onIssue(
                            BatchIssue(row, IssueKind.READONLY),
                        )
                    } else {
                        val uri = docs.rename(row.target.docUri, row.newName, row.target.treeUri)
                        // syncMediaStore 返回 Unit，所以原来这里的 `?: docs.findByName(...)`
                        // 永远不可能被执行（Unit 不为 null），而且表达式的结果本来也被丢弃 ——
                        // 是重构留下的死表达式。删掉它，行为完全不变。
                        // 真要给"返回 Uri 为空"补兜底，得在 docs.rename 那一层做，
                        // 靠这里的 elvis 只会让人误以为已经有兜底了。
                        if (uri != null) syncMediaStore(row.target)
                        if (uri == null) {
                            // **不再回滚整批、也不再中止**。
                            //
                            // 旧行为是 `rollback(steps, ...)` + return：一张改不了的图
                            // 会让另外 199 张全部白改（已改的还被倒着改回去），
                            // 而用户只看到一句"改名失败：IMG_0042.jpg"。
                            // 批量工具的价值就是"一次处理很多个"，所以单个失败
                            // 只该影响它自己。真要反悔，撤销栈还整批记着 ——
                            // 一键就能全退，比"自动回滚但不知道为什么"好得多。
                            val keepGoing = ledger.onIssue(
                                BatchIssue(row, IssueKind.FAILED),
                            )
                            if (!keepGoing) {
                                // 连续失败到阈值：环境看着坏了，停。
                                //
                                // **刻意不设 stopped** —— `aborted`（环境坏了）与
                                // `stopped`（用户手动停）是两件不同的事，
                                // 两个标志同时为真，报告就说不清到底是哪一种停法，
                                // 用户也无从判断"是我按的还是没有"。
                                //
                                // 也不丢弃 steps：已经改成的那些是真实结果，
                                // 丢掉它们等于让界面认知和磁盘状态对不上。
                                break
                            }
                        } else {
                            steps += RenameStep(uri, row.oldName, row.newName, row.target.side)
                            ledger.onChanged()
                        }
                    }
                    done++
                    progressEvery(done, total) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                val report = ledger.report(
                    title = getString(R.string.batch_title),
                    stopped = stopped,
                )
                if (steps.isNotEmpty()) {
                    pushUndo(UndoEntry(steps, getString(R.string.label_batch_rename, steps.size)))
                }
                publishBatchResult(report, steps.size, total)
            } finally {
                _ui.update { it.copy(busy = false, progress = null) }
                // 批量改名：增量替换，别整栏重扫（会让所有缩略图重新解码）
                patchAfterRename(steps)
                clearChecked()
            }
        }
    }

    /**
     * 批量执行收尾：把结果**同时**说成一句话和一份可查的报告。
     *
     * 只有问题时才弹出报告对话框。全成功时弹框是纯打扰 ——
     * 但"部分成功"必须给全量清单，否则用户没法知道漏了哪几个。
     */
    private fun publishBatchResult(report: BatchReport, succeeded: Int, total: Int) {
        val msg = when {
            report.hasIssues -> null // 由报告对话框承担说明，避免两处说法打架
            report.stopped -> getString(R.string.msg_batch_stopped, succeeded, total)
            succeeded == 0 -> getString(R.string.msg_nothing)
            else -> getString(R.string.msg_batch_done, succeeded)
        }
        if (msg != null) {
            emitMsg(msg, withUndo = succeeded > 0)
            return
        }
        emitMsg(
            getString(R.string.msg_batch_partial, succeeded, report.issues.size),
            withUndo = succeeded > 0,
        )
        _ui.update { it.copy(batchReport = report) }
    }

    /** 关掉批量结果报告。 */
    fun dismissBatchReport() {
        _ui.update { it.copy(batchReport = null) }
    }

    /**
     * 重试报告里"真失败"的那些。
     *
     * 直接复用记录下来的 `PlanRow`，不再重新推导目标名字 ——
     * 重新推导会拿到和上次不同的结果（目录已经变了一批名字），
     * 用户点"重试"时期待的是"再来一次刚才没做成的"，不是"重新规划一次"。
     */
    fun retryFailedBatch() {
        val report = _ui.value.batchReport ?: return
        val rows = report.retryRows
        dismissBatchReport()
        if (rows.isEmpty()) {
            emitMsg(getString(R.string.msg_retry_none))
            return
        }
        executePlan(rows)
    }


    /** 把勾选的文件复制到另一栏目录。 */
    fun copyCheckedTo(other: Side) {
        once("copy") {
            val targetTree = _ui.value.pane(other).treeUri
            if (targetTree == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@once
            }
            val items = _ui.value.checkedItems().filter { it.side != other }
            if (items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@once
            }
            _ui.update { it.copy(busy = true, progress = 0 to items.size) }
            var ok = 0
            val created = ArrayList<CreatedFile>()
            try {
                items.forEachIndexed { index, item ->
                    // 复制几百个文件时点取消，不该继续复制剩下的
                    if (!currentCoroutineContext().isActive) return@forEachIndexed
                    val uri = docs.copyTo(item.docUri, targetTree)
                    if (uri != null) {
                        ok++
                        created += CreatedFile(uri, item.docUri, targetTree, item.displayName)
                    }
                    progressEvery(index + 1, items.size) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                // 复制也必须可撤销：否则用户点撤销时会去撤销更早的改名，
                // 那是一次「看起来成功、实际改错了东西」的静默错误
                if (created.isNotEmpty()) {
                    pushUndo(UndoEntry(created = created, label = getString(R.string.label_copy, created.size)))
                }
                emitMsg(
                    getString(R.string.msg_copied, ok, items.size) +
                        if (created.isNotEmpty()) getString(R.string.msg_can_undo) else "",
                    withUndo = created.isNotEmpty(),
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false, progress = null) }
                load(other, targetTree)
            }
        }
    }

    /** 把另一栏的全部图片复制到目标栏（工具页用，不依赖勾选）。 */
    fun copyAllTo(target: Side) {
        once("copy") {
            val targetTree = _ui.value.pane(target).treeUri
            if (targetTree == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@once
            }
            val source = if (target == Side.LEFT) Side.RIGHT else Side.LEFT
            val items = _ui.value.pane(source).items
            if (items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@once
            }
            _ui.update { it.copy(busy = true, progress = 0 to items.size) }
            var ok = 0
            val created = ArrayList<CreatedFile>()
            try {
                items.forEachIndexed { index, item ->
                    // 复制几百个文件时点取消，不该继续复制剩下的
                    if (!currentCoroutineContext().isActive) return@forEachIndexed
                    val uri = docs.copyTo(item.docUri, targetTree)
                    if (uri != null) {
                        ok++
                        created += CreatedFile(uri, item.docUri, targetTree, item.displayName)
                    }
                    progressEvery(index + 1, items.size) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                if (created.isNotEmpty()) {
                    pushUndo(UndoEntry(created = created, label = getString(R.string.label_copy, created.size)))
                }
                emitMsg(
                    getString(R.string.msg_copied, ok, items.size) +
                        if (created.isNotEmpty()) getString(R.string.msg_can_undo) else "",
                    withUndo = created.isNotEmpty(),
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false, progress = null) }
                load(target, targetTree)
            }
        }
    }

    /**
     * 补齐：把一边**独有**的文件复制到另一边，让两边都有。
     *
     * 与「整栏复制」的区别：整栏复制会连已有的也复制一遍，
     * 目标目录立刻多出一堆重名文件（`a.jpg` 变成 `a (1).jpg`），
     * 还得手动清理 —— 那不是补齐，是制造混乱。
     *
     * 这里只复制**没配上对的**，即对面确实没有的那些。
     *
     * @param to 复制到哪一栏
     */
    fun syncUnique(to: Side) {
        cancellable {
            val st = _ui.value
            val targetTree = st.pane(to).treeUri
            if (targetTree == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@cancellable
            }
            val from = if (to == Side.LEFT) Side.RIGHT else Side.LEFT
            val sources = st.pane(from).items.filter { it.key !in st.matched }
            if (sources.isEmpty()) {
                emitMsg(getString(R.string.msg_no_unique))
                return@cancellable
            }
            _ui.update { it.copy(busy = true, progress = 0 to sources.size) }
            val created = ArrayList<CreatedFile>()
            try {
                sources.forEachIndexed { index, item ->
                    if (!currentCoroutineContext().isActive) return@forEachIndexed
                    val uri = docs.copyTo(item.docUri, targetTree)
                    if (uri != null) {
                        created += CreatedFile(uri, item.docUri, targetTree, item.displayName)
                    }
                    progressEvery(index + 1, sources.size) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                if (created.isNotEmpty()) {
                    pushUndo(
                        UndoEntry(
                            created = created,
                            label = getString(R.string.label_sync_unique, created.size),
                        ),
                    )
                }
                emitMsg(
                    getString(R.string.msg_copied, created.size, sources.size) +
                        if (created.isNotEmpty()) getString(R.string.msg_can_undo) else "",
                    withUndo = created.isNotEmpty(),
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false, progress = null) }
                load(to, targetTree)
            }
        }
    }

    /** 两个方向各有多少独有的文件（用于按钮上的数字）。 */
    fun uniqueCounts(): Pair<Int, Int> {
        val s = _ui.value
        val l = s.left.items.count { it.key !in s.matched }
        val r = s.right.items.count { it.key !in s.matched }
        return l to r
    }

    /**
     * 把勾选的文件**移动**到另一栏。
     *
     * 与「复制」的区别不用多说，但有个关键点必须讲清楚：
     * 撤销移动需要记住「从哪来」。这里同时记两套信息 ——
     * - `created`：新文档的 Uri（撤销时删掉它）
     * - `restored`：原文档 Uri + 原名字（撤销时把原件"复活"查回来）
     *
     * 只记一套是不够的：SAF 的 moveDocument 之后原 Uri 就失效了，
     * 而删除新文档并不会让原文档自己回来。
     */
    fun moveCheckedTo(target: Side) {
        cancellable {
            val st = _ui.value
            val targetTree = st.pane(target).treeUri
            if (targetTree == null) {
                emitMsg(getString(R.string.msg_need_both))
                return@cancellable
            }
            val items = st.checkedItems()
            if (items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@cancellable
            }
            _ui.update { it.copy(busy = true, progress = 0 to items.size) }
            val movedSteps = ArrayList<DocumentMoveStep>()
            val targetNames = docs.listNames(targetTree).toMutableSet()
            try {
                items.forEachIndexed { index, item ->
                    if (!currentCoroutineContext().isActive) return@forEachIndexed
                    val uri = docs.moveTo(item.docUri, item.treeUri, targetTree, targetNames)
                    if (uri != null) {
                        targetNames += item.displayName
                        movedSteps += DocumentMoveStep(
                            fromTree = item.treeUri,
                            toTree = targetTree,
                            currentUri = uri,
                            name = item.displayName,
                        )
                    }
                    progressEvery(index + 1, items.size) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                if (movedSteps.isNotEmpty()) {
                    pushUndo(
                        UndoEntry(
                            documentMoves = movedSteps,
                            label = getString(R.string.label_move, movedSteps.size),
                        ),
                    )
                }
                emitMsg(
                    getString(R.string.msg_moved, movedSteps.size, items.size) +
                        if (movedSteps.isNotEmpty()) getString(R.string.msg_can_undo) else "",
                    withUndo = movedSteps.isNotEmpty(),
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false, progress = null) }
                refreshBoth()
            }
        }
    }

    /** 删除勾选的文件（需二次确认，由 UI 负责弹窗）。 */
    fun deleteChecked() {
        once("delete") {
            val items = _ui.value.checkedItems().filter { it.canDelete }
            if (items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@once
            }
            _ui.update { it.copy(busy = true) }
            try {
                var trashed = 0
                var failed = 0
                val trashedItems = ArrayList<com.yuanbao.pairrename.data.TrashRepository.TrashedItem>()
                items.forEach { item ->
                    // 默认删除必须可恢复；路径/回收失败时保留原件，绝不悄悄降级为永久删除。
                    val moved = runCatching { trash.moveToTrash(item) }.getOrNull()
                    if (moved != null) {
                        trashed++
                        trashedItems += moved
                    } else {
                        failed++
                    }
                }
                // 删除必须进撤销栈。否则用户点「撤销」时会去撤销更早的改名操作 ——
                // 界面提示"已撤销 N 项"，实际改的是另一批文件，这是最糟的一类错误。
                if (trashedItems.isNotEmpty()) {
                    pushUndo(
                        UndoEntry(
                            trashed = trashedItems,
                            label = getString(R.string.label_delete, trashedItems.size),
                        ),
                    )
                }
                emitMsg(
                    if (failed == 0) {
                        getString(R.string.msg_trashed, trashed)
                    } else {
                        getString(R.string.msg_delete_partial, trashed, failed)
                    },
                    withUndo = trashed > 0,
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false) }
                clearChecked()
                refreshBoth()
            }
        }
    }

    // ---------------- 目录体检 ----------------

    /**
     * 对指定一栏做体检：格式分布 + 可疑文件。
     *
     * 按需计算而不是常驻：几百张图时每次重组都算一遍会拖慢界面，
     * 而这是个低频操作，点一下再算完全来得及。
     */
    fun analyzeDir(side: Side) {
        io {
            val items = _ui.value.pane(side).items
            if (items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@io
            }
            _ui.update { it.copy(busy = true) }
            try {
                // 纯计算（不解码），但仍放后台，避免大目录卡住主线程
                val result = withContext(Dispatchers.Default) { Stats.analyze(items) }
                _ui.update { it.copy(dirStats = result) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun clearDirStats() = _ui.update { it.copy(dirStats = null) }

    // ---------------- 对比导航（逐个确认流水线） ----------------

    /**
     * 找到相邻的「还没统一的配对」，用于在对比面板里快速跳转。
     *
     * 为什么需要：双击打开对比 → 确认 → 应用 → 关掉 → 再找下一对，
     * 一轮要五六次操作，几十对时根本做不完。
     * 有了「下一对」，点一下就跳到下一个待确认的，形成流水线。
     *
     * **只在当前可见的条目里跳**（走 util/Filter.kt 的同一个 [applyFilter]）：
     * 这里原先用的是同栏**全量**列表，于是「仅待处理」过滤下点「下一对」，
     * 会跳到界面上根本看不见的项 —— 与那个文件开头"界面和 ViewModel 必须算出
     * 同一份可见集合"的约定自相矛盾（全选/批量早就修过同一个坑，导航漏了）。
     * 修好之后还有第二个好处：把过滤切到「仅弱依据」，
     * 「下一对」就自动变成「下一个可疑配对」，复核流水线不用另写一套。
     *
     * @param current 当前正在看的文件
     * @param delta +1 下一个、-1 上一个
     * @return 同栏的下一个待确认文件；没有则返回 null
     */
    fun compareNeighbor(current: ImageItem, delta: Int): ImageItem? {
        val s = _ui.value
        val all = s.pane(current.side).items
        if (all.isEmpty()) return null

        // 可见集合 = 与界面完全一致的那一份（同一个函数、同一批参数）
        val visible = applyFilter(
            all, s.query, s.matchFilter, s.synced, s.matched, s.extraFilter, s.weakKeys,
            s.conflictKeys,
        )
        if (visible.isEmpty()) return null

        // 候选 = 已配对但名字还没统一的（即真正需要人工确认的）
        val pending = visible.filter { it.key in s.matched && it.key !in s.synced }
        // 全处理完了就在已配对的里循环，方便回看。
        // 注意这里必须也用 visible 兜底，不能退回 all —— 否则过滤等于失效。
        val pool = pending.ifEmpty { visible.filter { it.key in s.matched } }
        if (pool.isEmpty()) return null

        val idx = pool.indexOfFirst { it.key == current.key }
        // 当前项不在候选里（比如刚被应用过），按方向就近取
        val next = if (idx < 0) {
            if (delta > 0) 0 else pool.size - 1
        } else {
            val raw = idx + delta
            // 循环：到头了绕回去，避免"点到最后一个就没反应了"
            ((raw % pool.size) + pool.size) % pool.size
        }
        return pool.getOrNull(next)
    }

    /** 当前待确认的配对数（用于对比面板显示进度）。 */
    fun pendingPairCount(): Int {
        val s = _ui.value
        return s.left.items.count { it.key in s.matched && it.key !in s.synced }
    }

    // ---------------- 智能建议 ----------------

    /**
     * 执行一条建议对应的动作。
     *
     * 只做「打开对应界面 / 直接开跑」这两类安全的事，
     * 绝不在这里直接改文件 —— 建议只是指路，决定权仍在用户手上。
     */
    /**
     * 导出配对报告。
     *
     * 几百对图片的配对结果不可能一个个点开核对，
     * 导出成表格扫一眼就能发现配错的，比在界面上翻高效得多。
     */
    fun exportPairReport() {
        io {
            val s = _ui.value
            if (s.left.items.isEmpty() && s.right.items.isEmpty()) {
                emitMsg(getString(R.string.msg_nothing))
                return@io
            }
            val csv = Pairing.reportCsv(
                left = s.left.items,
                right = s.right.items,
                partner = s.partner,
                reason = s.reason,
            )
            _events.emit(UiEvent.SavePlan(csv))
        }
    }

    /** 导出改名计划（改之前先存档，或发给别人核对）。 */
    fun requestExportPlan(csv: String) {
        if (csv.isBlank()) return
        _events.tryEmit(UiEvent.SavePlan(csv))
    }

    fun runAdvice(action: AdviceAction) {
        when (action) {
            AdviceAction.NONE -> Unit
            AdviceAction.READ_EXIF -> readExif()
            AdviceAction.VERIFY_CONTENT -> verifyByContent()
            AdviceAction.PIPELINE -> requestPipeline()
            AdviceAction.SYNC -> requestSyncAll()
            AdviceAction.ALIGN -> requestAlign()
            AdviceAction.FIX_NAMES -> requestFixNames()
            AdviceAction.REVIEW_SUSPECT -> reviewSuspects()
            AdviceAction.REVIEW_CONFLICT -> reviewConflicts()
            AdviceAction.SWAP_CONFLICT -> applyBestTimeSwap()
            AdviceAction.UNDO_SWAP -> undoTimeSwaps()
            AdviceAction.LINK_SIZE -> applySizeMatches()
            AdviceAction.SIDE_MOVE -> applySideMoves()
            AdviceAction.UNDO_SIDE_MOVE -> undoSideMoves()
            // 三个入口（状态条 / 对比面板 / 这条建议）全部走同一个流程：先逐条预览再确认。
            // 只要有一个入口直连写入，用户在不同地方点同一个动作就会得到两种后果。
            AdviceAction.REALIGN_TIME -> previewRealign()
            AdviceAction.UNDO_REALIGN -> undoTimeRealign()
            AdviceAction.PICK_FOLDER -> _ui.update {
                it.copy(screen = Screen.COMPARE)
            }
        }
    }

    /**
     * 直接打开批量改名的「一键清理」模式。
     *
     * 关键点：批量对话框只处理**已勾选**的文件，所以这里必须先把
     * 「需要清理的」勾上，否则打开后列表是空的 —— 用户会以为功能坏了。
     * 这里主动替用户选中，正是"建议"该有的样子。
     */
    private fun requestFixNames() {
        selectByCondition(SelectCondition.DIRTY_NAME)
        val s = _ui.value
        val picked = s.checkedItems()
        if (picked.isEmpty()) {
            emitMsg(getString(R.string.msg_no_match))
            return
        }
        // 勾选可能横跨两栏，取占多数的那一栏
        val side = if (picked.count { it.side == Side.LEFT } >= picked.count { it.side == Side.RIGHT }) {
            Side.LEFT
        } else {
            Side.RIGHT
        }
        _events.tryEmit(
            UiEvent.OpenBatch(
                side = side,
                // treeUri 可空（还没选文件夹）；listNames 要的是非空 Uri，
                // 用 EMPTY 退化成"空名单"，对话框照常打开、用户仍能先调参数
                existing = docs.listNames(s.pane(side).treeUri ?: Uri.EMPTY),
                initialMode = BatchMode.FIX,
            ),
        )
    }

    // ---------------- 一键流水线 ----------------

    /** 打开流水线配置。先让用户选步骤，不闷头执行。 */
    fun requestPipeline() {
        val s = _ui.value
        if (s.left.treeUri == null || s.right.treeUri == null) {
            emitMsg(getString(R.string.msg_need_both))
            return
        }
        _events.tryEmit(UiEvent.OpenPipeline)
    }

    /**
     * 一键流水线：按顺序自动执行「补数据 → 配对 → 统一」。
     *
     * 手动路径是：读 EXIF → 按内容校验 → 按配对统一，三步三堆点击。
     * 而这三步几乎每次都要做，合成一键能省掉大量重复劳动。
     *
     * 设计上坚持两点：
     * 1. **每步可选**，默认都开，但可以只跑其中一步
     * 2. **统一前一定先预览**，绝不直接改文件
     *
     * @return 通过事件把预览交给界面；任何一步无数据就跳过而不是报错。
     */
    fun runPipeline(
        readExif: Boolean = true,
        verifyContent: Boolean = false,
        syncAfter: Boolean = true,
        direction: SyncDirection = SyncDirection.LEFT_TO_RIGHT,
    ) {
        cancellable {
            _ui.update { it.copy(busy = true, progress = 0 to 3) }
            try {
                // 第 1 步：补拍摄时间。有权限时是 File 直读，很快
                if (readExif && isActive) {
                    _ui.update { it.copy(progress = 0 to 3) }
                    readExifSuspend()
                }
                // 第 2 步：按内容校验。默认关 —— 要读文件内容，慢
                if (verifyContent && isActive) {
                    _ui.update { it.copy(progress = 1 to 3) }
                    verifyByContentSuspend()
                }
                _ui.update { it.copy(progress = 2 to 3) }
                // 配对结果会自动重算，这里取最新的
                val s = _ui.value
                if (!isActive) return@cancellable
                if (syncAfter) {
                    if (s.matched.isEmpty()) {
                        emitMsg(getString(R.string.msg_no_pair))
                    } else {
                        // 统一前必须预览 —— 配对本质是推算，直接改风险太大
                        emitMsg(getString(R.string.msg_pipeline_ready, s.matched.size / 2))
                        openSyncSuspend(direction)
                    }
                } else {
                    emitMsg(getString(R.string.msg_pipeline_done, s.matched.size / 2))
                }
                _ui.update { it.copy(progress = 3 to 3) }
            } finally {
                _ui.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    /**
     * 发出「按配对统一」的预览事件（挂起版本）。
     *
     * 流水线走完配对后不直接改文件 —— 配对本质是推算，
     * 一定要先给用户看一眼计划。
     */
    private suspend fun openSyncSuspend(direction: SyncDirection) {
        val s = _ui.value
        if (s.left.treeUri == null || s.right.treeUri == null) {
            emitMsg(getString(R.string.msg_need_both))
            return
        }
        _events.emit(
            UiEvent.OpenSync(
                leftNames = docs.listNames(s.left.treeUri),
                rightNames = docs.listNames(s.right.treeUri),
                direction = direction,
            ),
        )
    }

    // ---------------- 最近文件夹 ----------------

    /** 记录一次目录选择，供下次快速切回。 */
    private fun rememberFolder(uri: Uri, label: String) {
        io {
            runCatching { recentFolders.touch(uri, label) }
        }
    }

    fun removeRecent(key: String) {
        io { runCatching { recentFolders.remove(key) } }
    }

    fun clearRecents() {
        io { runCatching { recentFolders.clear() } }
    }

    /** 直接切到最近用过的某个文件夹（省掉层层点选）。 */
    fun useRecent(side: Side, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        onFolderPicked(side, uri)
    }

    // ---------------- 多选拖放 ----------------

    /**
     * 多选拖放：把一组来源文件的名字，按顺序应用到一组目标文件。
     *
     * 单文件拖放只能一个一个来，几十张图时很痛苦。
     * 勾选多个后拖动，即可批量把名字套过去（按顺序一一对应）。
     *
     * 数量不等时以少的为准，多出来的不动 —— 宁可少改也不能乱配。
     */
    fun applyNamesInOrder(
        sources: List<ImageItem>,
        targets: List<ImageItem>,
        overridePolicy: ConflictPolicy,
    ) {
        val pairs = minOf(sources.size, targets.size)
        if (pairs <= 0) {
            emitMsg(getString(R.string.msg_nothing))
            return
        }
        cancellable {
            _ui.update { it.copy(busy = true, progress = 0 to pairs) }
            val steps = mutableListOf<RenameStep>()
            // 目标目录的已有名字，**按目录缓存一次**。
            //
            // 原来是在循环里逐个 `docs.listNames(to.treeUri)` —— 那是每次一个
            // 完整的 DocumentsProvider query（一次 IPC + 一遍全目录扫描）。
            // 拖 300 张就是 300 次，整体 O(n²)：
            // 目录越大越慢，正好在用户最需要它快的时候最慢。
            // 缓存之后还得自己维护：改过的名字要立刻占位，
            // 否则同一批里后面的文件会算出和前面撞车的名字。
            val dirNames = HashMap<String, MutableSet<String>>()
            val ledger = BatchLedger(pairs)
            var stopped = false
            try {
                for (i in 0 until pairs) {
                    if (!isActive) {
                        stopped = true
                        break
                    }
                    val from = sources[i]
                    val to = targets[i]
                    // 拖到自己身上：无需任何操作。也要记账，
                    // 否则报告里 成功 + 无需改动 + 问题 会小于总数，看着像丢了东西
                    if (from.key == to.key) {
                        ledger.onUnchanged()
                        continue
                    }
                    val st = settings.value
                    val ext = if (st.extensionPolicy == ExtensionPolicy.USE_SOURCE) {
                        Naming.extensionOf(from.displayName)
                    } else {
                        Naming.extensionOf(to.displayName)
                    }
                    val desiredBase = Naming.sanitize(
                        Naming.withTemplate(
                            Naming.baseOf(from.displayName),
                            st.prefix,
                            st.suffix,
                        ),
                        reserve = ext.length + 1,
                    )
                    val existing = dirNames.getOrPut(to.treeUri.toString()) {
                        docs.listNames(to.treeUri).toMutableSet()
                    }
                    // 自己当前的名字不算占用（马上要改走）
                    existing.remove(to.displayName)
                    val candidate = Naming.join(desiredBase, ext)
                    val finalName = if (existing.any { it.equals(candidate, ignoreCase = true) }) {
                        when (overridePolicy) {
                            ConflictPolicy.SKIP -> {
                                ledger.onIssue(
                                    BatchIssue(
                                        PlanRow(to, to.displayName, candidate),
                                        IssueKind.CONFLICT,
                                    ),
                                )
                                progressEvery(i + 1, pairs) { pg -> _ui.update { it.copy(progress = pg) } }
                                continue
                            }
                            else -> Naming.resolveConflict(desiredBase, ext, existing, st.numbering)
                        }
                    } else {
                        candidate
                    }
                    val row = PlanRow(to, to.displayName, finalName)
                    val uri = renameOrFind(to, finalName)
                    if (uri == null) {
                        // 这个文件没改成，它的原名**还在磁盘上占着**。
                        // 上面 `existing.remove(to.displayName)` 是按"马上要改走"做的，
                        // 现在改不走了就必须放回去 —— 否则同一批里后面的文件
                        // 会把这个仍被占用的名字算成空闲，接着撞一次。
                        existing.add(to.displayName)
                        if (!ledger.onIssue(BatchIssue(row, IssueKind.FAILED))) {
                            // 同上：熔断只设 aborted，不设 stopped
                            break
                        }
                    } else {
                        // 立刻占位，供同批后续文件避让
                        existing.add(finalName)
                        steps += RenameStep(uri, to.displayName, finalName, to.side)
                        ledger.onChanged()
                    }
                    progressEvery(i + 1, pairs) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                val report = ledger.report(
                    title = getString(R.string.label_apply_order),
                    stopped = stopped,
                )
                if (steps.isNotEmpty()) {
                    pushUndo(UndoEntry(steps, getString(R.string.label_batch_order, steps.size)))
                }
                publishBatchResult(report, steps.size, pairs)
            } finally {
                _ui.update { it.copy(busy = false, progress = null) }
                patchAfterRename(steps)
            }
        }
    }

    // ---------------- 交换 / 剪贴板 ----------------

    /**
     * 交换左右两栏。
     *
     * 选错顺序时不用重新选两个文件夹（大目录重选很慢）。
     *
     * 关键点：**撤销栈里的每一步都记着 side**。交换后 side 的含义反了，
     * 不修正的话撤销会把名字改到另一栏去 —— 又是那种「看起来成功、实际改错」
     * 的错误。所以这里把所有步骤的 side 翻转，而不是简单丢掉撤销记录。
     */
    fun swapPanes() {
        mutationIo {
            val s = _ui.value
            if (s.left.treeUri == null && s.right.treeUri == null) {
                emitMsg(getString(R.string.msg_nothing))
                return@mutationIo
            }
            invalidateFolderLoad(Side.LEFT)
            invalidateFolderLoad(Side.RIGHT)
            _ui.update {
                it.copy(
                    left = it.right,
                    right = it.left,
                    singleSide = if (it.singleSide == Side.LEFT) Side.RIGHT else Side.LEFT,
                    // 这两个存的是带 side 的对象，交换后 side 指的是另一栏了。
                    // 留着会让后续拖放/应用改到错误的栏，必须清掉。
                    // （checked 用 Uri 做 key，与 side 无关，可以保留）
                    selectedSource = null,
                    dragSource = null,
                )
            }
            flipUndoStackSides()
            recomputeMatch()
            emitMsg(getString(R.string.msg_panes_swapped))
        }
    }

    /** 把撤销/重做栈里每步的 side 翻转（配合交换两栏）。 */
    private fun flipUndoStackSides() {
        undoHistory.flipSides()
    }

    /**
     * 把文件名复制到剪贴板（勾选了就复制勾选的，否则复制当前栏全部）。
     * 每行一个，方便贴到表格或发给别人对单。
     */
    fun copyNamesToClipboard(side: Side) {
        val s = _ui.value
        val picked = s.checkedItems().filter { it.side == side }.ifEmpty { s.pane(side).items }
        if (picked.isEmpty()) {
            emitMsg(getString(R.string.msg_nothing))
            return
        }
        val text = picked.joinToString("\n") { it.displayName }
        val ok = runCatching {
            val cm = getApplication<Application>()
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("filenames", text))
            true
        }.getOrDefault(false)
        emitMsg(
            if (ok) getString(R.string.msg_copied_names, picked.size)
            else getString(R.string.msg_clip_failed),
        )
    }

    // ---------------- 选择增强 ----------------

    /**
     * 范围选择：把 [from, to] 之间（按当前可见顺序）全部勾上。
     *
     * 一张张点太累，几百张图时这是刚需。
     */
    fun selectRange(visible: List<ImageItem>, fromKey: String, toKey: String) {
        val a = visible.indexOfFirst { it.key == fromKey }
        val b = visible.indexOfFirst { it.key == toKey }
        if (a < 0 || b < 0) return
        val (lo, hi) = minOf(a, b) to maxOf(a, b)
        _ui.update { s ->
            val next = s.checked.toMutableSet()
            (lo..hi).forEach { next.add(visible[it].key) }
            s.copy(checked = next, showCheckboxes = true)
        }
    }

    /** 当前可见条目（左右两栏合计）。过滤/搜索后会被缩小。 */
    fun visibleItems(): List<ImageItem> {
        val s = _ui.value
        return Side.entries.flatMap { side ->
            applyFilter(
                s.pane(side).items, s.query, s.matchFilter, s.synced, s.matched,
                s.extraFilter, s.weakKeys, s.conflictKeys,
            )
        }
    }

    /** 反选：已选的取消、未选的勾上（只在可见范围内）。 */
    fun invertSelection(visible: List<ImageItem> = visibleItems()) {
        _ui.update { s ->
            val vis = visible.map { it.key }.toSet()
            val next = s.checked.toMutableSet()
            visible.forEach {
                if (it.key in next) next.remove(it.key) else next.add(it.key)
            }
            // 不可见的一律清掉，避免误操作到看不见的文件
            next.removeAll { it !in vis }
            s.copy(checked = next, showCheckboxes = next.isNotEmpty())
        }
    }

    /**
     * 按条件选择 —— 这个工具的核心价值之一：
     * 几百张里一键找出"没配对的""名字有问题的"，而不是肉眼翻。
     */
    fun selectByCondition(condition: SelectCondition, visible: List<ImageItem> = visibleItems()) {
        val s = _ui.value
        val matched = s.matched
        val hit = visible.filter { item ->
            when (condition) {
                SelectCondition.UNPAIRED -> item.key !in matched
                SelectCondition.PAIRED -> item.key in matched
                SelectCondition.UNSYNCED -> item.key in matched && item.key !in s.synced
                SelectCondition.NO_EXIF -> item.takenAt <= 0
                SelectCondition.HAS_EXIF -> item.takenAt > 0
                SelectCondition.DIRTY_NAME -> Naming.isDirtyName(item.displayName)
                SelectCondition.LARGE -> item.size >= LARGE_FILE_THRESHOLD
                SelectCondition.CANNOT_RENAME -> !item.canRename
            }
        }
        if (hit.isEmpty()) {
            emitMsg(getString(R.string.msg_no_match))
            return
        }
        _ui.update {
            it.copy(
                checked = hit.map { i -> i.key }.toSet(),
                showCheckboxes = true,
            )
        }
        emitMsg(getString(R.string.msg_selected, hit.size))
    }

    /** 各条件的当前命中数，用于在菜单上显示（0 的可以置灰）。 */
    fun countByCondition(condition: SelectCondition, visible: List<ImageItem> = visibleItems()): Int {
        val s = _ui.value
        return visible.count { item ->
            when (condition) {
                SelectCondition.UNPAIRED -> item.key !in s.matched
                SelectCondition.PAIRED -> item.key in s.matched
                SelectCondition.UNSYNCED -> item.key in s.matched && item.key !in s.synced
                SelectCondition.NO_EXIF -> item.takenAt <= 0
                SelectCondition.HAS_EXIF -> item.takenAt > 0
                SelectCondition.DIRTY_NAME -> Naming.isDirtyName(item.displayName)
                SelectCondition.LARGE -> item.size >= LARGE_FILE_THRESHOLD
                SelectCondition.CANNOT_RENAME -> !item.canRename
            }
        }
    }

    // ---------------- 批量操作取消 ----------------

    /**
     * 正在跑的可取消长任务。**用集合而不是单个 Job 槽**。
     *
     * 为什么不能用单槽：新任务启动时会顶掉旧任务，而旧任务是在
     * **新任务完成登记之后**才执行自己的 `finally` 的 ——
     * 那句 `runningJob = null` 会把新任务的登记一并抹掉，
     * 于是"取消"再也取消不到人。
     *
     * 改成集合 + `invokeOnCompletion` 自摘：每个任务只清掉自己那一格，
     * 谁也不影响谁。
     */
    private val runningJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    /** 用户点了"取消"。已完成的步骤**保留**（不回滚），下次可继续。 */
    fun cancelRunning() {
        val jobs = runningJobs.toList()
        if (jobs.isEmpty()) {
            // 没有可取消的任务却回一句"已取消"，用户会以为操作真的停了 ——
            // 而后台其实还在写文件。宁可如实说"没有正在执行的操作"。
            emitMsg(getString(R.string.msg_nothing_running))
            return
        }
        jobs.forEach { runCatching { it.cancel() } }
        runningJobs.clear()
        _ui.update { it.copy(busy = false, progress = null, canCancel = false) }
        emitMsg(getString(R.string.msg_cancelled))
    }

    /**
     * 进度更新节流。
     *
     * 每步都 `_ui.update` 意味着每步一次全局重组 ——
     * 5000 个文件就是 5000 次重组，界面会被自己拖死。
     * 改成每 [step] 步更新一次；但**最后一步一定更新**，
     * 否则进度条会停在 4980/5000 让人以为卡住了。
     */
    private inline fun progressEvery(
        done: Int,
        total: Int,
        step: Int = 20,
        block: (Pair<Int, Int>) -> Unit,
    ) {
        if (done % step == 0 || done == total) block(done to total)
    }

    /**
     * 启动一个可取消的长任务。
     *
     * 批量改几百张时想中途停手，原来只能等它跑完 ——
     * 现在随时能停。已改的不回退，因为撤销栈记得每一步。
     *
     * 同一时刻只允许一个长任务：新的顶掉旧的。但**必须说清楚** ——
     * 静默杀掉一个刚跑了一半的归档，用户会以为它还在继续。
     *
     * 登记与注销完全由这里负责（`invokeOnCompletion` 自摘），
     * 调用方**不要**再手写 `runningJob = null` —— 那正是上面说的那个竞态。
     */
    private fun cancellable(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val stale = runningJobs.toList()
        if (stale.isNotEmpty()) {
            stale.forEach { runCatching { it.cancel() } }
            runningJobs.clear()
            emitMsg(getString(R.string.msg_prev_interrupted))
        }
        val job = viewModelScope.launch(Dispatchers.IO + crashGuard) {
            fileMutationMutex.withLock { block.invoke(this) }
        }
        runningJobs.add(job)
        _ui.update { it.copy(canCancel = true) }
        // 回调在任务完成的那个线程上跑；_ui 是 StateFlow，跨线程更新是安全的
        job.invokeOnCompletion {
            runningJobs.remove(job)
            if (runningJobs.isEmpty()) _ui.update { it.copy(canCancel = false) }
        }
    }

    // ---------------- 撤销 / 重做 ----------------

    fun undo() = mutationIo {
        val entry = undoHistory.peekUndo()
        val outcome = undoOne()
        refreshIfNeeded()
        syncHistory()
        // 撤完要吭一声：静默的撤销会让人不确定到底撤了没有
        if (entry != null) {
            emitMsg(
                if (outcome.ok) {
                    getString(R.string.msg_undone, entry.total)
                } else {
                    failureMsg(R.string.msg_undo_failed, R.string.msg_undo_failed_n, outcome.failed)
                },
            )
        }
    }

    fun redo() = mutationIo {
        var entry = undoHistory.takeRedo() ?: return@mutationIo
        var ok = true
        val failed = ArrayList<FailedItem>()
        val documentMoves = entry.documentMoves.toMutableList()
        documentMoves.indices.forEach { index ->
            val step = documentMoves[index]
            if (step.atTarget) return@forEach
            val movedUri = docs.moveTo(step.currentUri, step.fromTree, step.toTree)
            if (movedUri == null) {
                ok = false
                failed.add(FailedItem(step.name, UndoWhy.FAILED))
            } else {
                documentMoves[index] = step.copy(currentUri = movedUri, atTarget = true)
            }
        }
        // 重做：先移动，再改名
        entry.moves.forEach { mv ->
            val why = when (Organizer.moveFile(mv.fromPath, mv.toPath)) {
                Organizer.MoveOutcome.OK -> null
                Organizer.MoveOutcome.OCCUPIED -> UndoWhy.OCCUPIED
                Organizer.MoveOutcome.MISSING -> UndoWhy.MISSING
                Organizer.MoveOutcome.FAILED -> UndoWhy.FAILED
            }
            if (why != null) {
                ok = false
                failed.add(FailedItem(baseNameOf(mv.toPath), why))
            }
        }
        entry.steps.forEach { step ->
            val tree = treeFor(step)
            // 同 undoOne：重做也是改名，目标名被别的文件占了就不动它。
            if (nameTaken(tree, step.docUri, step.newName)) {
                ok = false
                failed.add(FailedItem(step.newName, UndoWhy.OCCUPIED))
                return@forEach
            }
            val done = docs.rename(step.docUri, step.newName, tree)
            if (done == null) {
                ok = false
                failed.add(FailedItem(step.newName, UndoWhy.FAILED))
            }
        }
        // 重做复制：再拷一份。
        //
        // **必须用返回的新 Uri 覆盖 entry.created**：撤销按 `docUri` 删，
        // 而重做会复制出一个**新文档**（新 Uri）。不更新的话，
        //「重做 → 再撤销」这条链上删的仍是上一轮那个已经不存在的 Uri：
        // 删除失败 → 报"撤销失败"，而磁盘上那个新副本**永远留下来了** ——
        // 用户以为撤干净了，其实每走一轮这条路就多一个孤儿文件。
        //
        // 只有整批都成功才覆盖：部分成功时列表长度对不上，
        // 硬塞进去会让没重做的那几条指向不存在的文件。
        if (entry.created.isNotEmpty()) {
            val produced = ArrayList<CreatedFile>(entry.created.size)
            entry.created.forEach { c ->
                val nf = docs.copyTo(c.sourceUri, c.targetTree)
                if (nf == null) {
                    ok = false
                    failed.add(FailedItem(c.name, UndoWhy.FAILED))
                } else {
                    produced.add(CreatedFile(nf, c.sourceUri, c.targetTree, c.name))
                }
            }
            if (produced.size == entry.created.size) entry = entry.copy(created = produced)
        }
        // 重做删除：再移回回收站
        entry.trashed.forEach { t ->
            if (trash.movePathToTrash(t.originalPath) == null) {
                // 重做删除 = 把文件再移回回收站；失败几乎只有一种原因：
                // 原路径上已经没有这个文件了（被移走 / 被删）。
                ok = false
                failed.add(FailedItem(t.displayName, UndoWhy.MISSING))
            }
        }
        entry = entry.copy(documentMoves = documentMoves)
        if (ok) undoHistory.movedToUndo(entry) else undoHistory.pushBackRedo(entry)
        syncHistory()
        emitMsg(
            if (ok) {
                getString(R.string.msg_redone, entry.total)
            } else {
                // 上一版这里用的是 msg_undo_failed —— 用户点的是「重做」，
                // 报的却是"撤销失败"。文案错了会让人往完全相反的方向排查。
                failureMsg(R.string.msg_redo_failed, R.string.msg_redo_failed_n, failed)
            },
        )
        if (ok && entry.steps.isNotEmpty()) {
            patchAfterRename(entry.steps)
        } else if (entry.documentMoves.isNotEmpty() || entry.moves.isNotEmpty() || !ok) {
            refreshBoth()
        }
    }


    // ---------------- 批量参数模板 ----------------

    /**
     * 把当前参数存成模板。同名会覆盖（避免越存越乱）。
     */
    fun saveTemplate(name: String, mode: BatchMode, params: BatchParams) {
        val clean = name.trim()
        if (clean.isBlank()) {
            emitMsg(getString(R.string.msg_need_name))
            return
        }
        io {
            templateRepo.add(
                BatchTemplate(
                    id = System.currentTimeMillis(),
                    name = clean,
                    mode = mode,
                    params = params,
                ),
            )
            loadTemplates()
            emitMsg(getString(R.string.msg_template_saved, clean))
        }
    }

    fun removeTemplate(id: Long) {
        io {
            templateRepo.remove(id)
            loadTemplates()
        }
    }

    private suspend fun loadTemplates() {
        templateRepo.templates.collect { list ->
            _ui.update { it.copy(templates = list) }
        }
    }

    /** 记录一次"套用了模板"，方便回溯。 */
    fun emitTemplateApplied(name: String) =
        emitMsg(getString(R.string.msg_template_applied, name))

    // ---------------- 撤销栈可视化 ----------------

    /**
     * 刷新撤销栈的可读标签。
     *
     * 为什么要这个：用户做完几步批量操作后，往往记不清"刚才改了什么"。
     * 看不见就只能盲撤 —— 而盲撤正是"改错文件"的常见入口。
     */
    private fun syncHistory() {
        _ui.update {
            it.copy(
                undoCount = undoHistory.undoCount,
                redoCount = undoHistory.redoCount,
                undoLabels = undoHistory.labels(),
                undoDroppedEntries = undoHistory.droppedEntries,
                undoDroppedSteps = undoHistory.droppedSteps,
            )
        }
    }

    /**
     * 撤销到指定步（一次性回退 N 步）。
     *
     * 比一步步点撤销可靠：中间任何一步失败就停下并报告，
     * 不会"看起来撤了很多，其实只撤了一半"。
     */
    fun undoUntil(index: Int) {
        mutationIo {
            var n = 0
            var failed: List<FailedItem>? = null
            while (undoHistory.undoCount > index) {
                val outcome = undoOne()
                if (!outcome.ok) {
                    failed = outcome.failed
                    break
                }
                n++
            }
            refreshIfNeeded()
            val f = failed
            emitMsg(
                when {
                    f == null -> getString(R.string.msg_undone, n)
                    // 中途失败必须说清**撤了几步、卡在哪**：
                    // 只报"撤销失败"，用户分不清到底撤了没有、撤到哪一条了，
                    // 而这时候磁盘已经处于一个**中间状态**，比全失败更需要交代。
                    f.isEmpty() -> getString(R.string.msg_undone_partial, n)
                    else -> {
                        getString(R.string.msg_undone_partial_n, n, f.size, headOf(f)) +
                            reasonNotes(f)
                    }
                },
            )
        }
    }

    /** 单步撤销，返回结果。抽出来给 undo() 和 undoUntil() 共用。 */
    private fun undoOne(): UndoOutcome {
        val entry = undoHistory.takeUndo() ?: return UndoOutcome(false)
        var ok = true
        val failed = ArrayList<FailedItem>()
        val documentMoves = entry.documentMoves.toMutableList()
        documentMoves.indices.reversed().forEach { index ->
            val step = documentMoves[index]
            if (!step.atTarget) return@forEach
            val movedUri = docs.moveTo(step.currentUri, step.toTree, step.fromTree)
            if (movedUri == null) {
                ok = false
                failed.add(FailedItem(step.name, UndoWhy.FAILED))
            } else {
                documentMoves[index] = step.copy(currentUri = movedUri, atTarget = false)
            }
        }
        // 先把归档移动的文件移回原处 —— 必须排在改名之前：
        // 文件还在新目录里时按旧名改回去，会改到错误的路径上。
        entry.moves.asReversed().forEach { mv ->
            val why = when (Organizer.moveBack(mv.toPath, mv.fromPath)) {
                Organizer.MoveOutcome.OK -> null
                Organizer.MoveOutcome.OCCUPIED -> UndoWhy.OCCUPIED
                Organizer.MoveOutcome.MISSING -> UndoWhy.MISSING
                Organizer.MoveOutcome.FAILED -> UndoWhy.FAILED
            }
            if (why != null) {
                ok = false
                failed.add(FailedItem(baseNameOf(mv.fromPath), why))
            }
        }
        entry.steps.asReversed().forEach { step ->
            val tree = treeFor(step)
            // 想改回的名字已经被别的文件占了 → 不改，报失败。
            // 原因见 nameTaken 的注释：renameDocument 会静默替换目标。
            if (nameTaken(tree, step.docUri, step.previousName)) {
                ok = false
                failed.add(FailedItem(step.previousName, UndoWhy.OCCUPIED))
                return@forEach
            }
            val done = docs.rename(step.docUri, step.previousName, tree)
            if (done == null) {
                ok = false
                // 报"想改回的那个名字"：用户拿着它才能去文件管理里找
                failed.add(FailedItem(step.previousName, UndoWhy.FAILED))
            }
        }
        entry.created.asReversed().forEach { c ->
            if (!docs.delete(c.docUri)) {
                ok = false
                failed.add(FailedItem(c.name, UndoWhy.FAILED))
            }
        }
        entry.trashed.asReversed().forEach { t ->
            // 只有 OK 才算撤回来。OCCUPIED / MISSING / FAILED 都必须报出来：
            // 这时磁盘处于中间状态，用户得知道有东西还在回收站里。
            val why = when (trash.restore(t)) {
                TrashRepository.RestoreResult.OK -> null
                TrashRepository.RestoreResult.OCCUPIED -> UndoWhy.OCCUPIED
                TrashRepository.RestoreResult.MISSING -> UndoWhy.MISSING
                TrashRepository.RestoreResult.FAILED -> UndoWhy.FAILED
            }
            if (why != null) {
                ok = false
                failed.add(FailedItem(t.displayName, why))
            }
        }
        val updatedEntry = entry.copy(documentMoves = documentMoves)
        if (ok) {
            undoHistory.movedToRedo(updatedEntry)
            // 撤销是反向改名，缓存必须跟着迁回旧名 ——
            // 否则撤销后 EXIF 和指纹还挂在新名下，配对会错。
            //
            // **顺序必须和上面的磁盘操作一致：`asReversed()`。**
            // 注意上面那段是 `entry.steps.asReversed().forEach { ... }` ——
            // 撤销一个多步操作（置换是 A→temp、B→A、temp→B 三步）时，
            // 磁盘是**倒着**执行的。缓存迁移如果按正序喂进去，
            // 迁移路径和真实路径就对不上：以置换为例，正序迁移的最终结果是
            // 所有元信息都被搬到那个中间临时名上（临时名随后就不存在了），
            // 于是 A、B 两张图的拍摄时间和内容指纹**同时丢失** ——
            // 而 MetaCache 存在的全部意义就是保住它们，刷新后配对才不会
            // 悄悄退回推算。单步改名时正序倒序一样，所以这个缺陷只在
            // 置换 / 批量链式改名 + 撤销的组合下才暴露。
            metaCache.migrate(
                entry.steps.asReversed().map {
                    // 参数顺序是 (docUri, previousName, newName, side)：这里要的是
                    // **反向**迁移，所以 newName / previousName 是故意对调的。
                    // treeUri 必须原样带过去（见 treeFor）。
                    RenameStep(it.docUri, it.newName, it.previousName, it.side, it.treeUri)
                },
            ) { side -> _ui.value.pane(side).treeUri }
        } else {
            undoHistory.pushBack(updatedEntry)
        }
        return UndoOutcome(ok, failed)
    }

    /**
     * 撤销 / 重做失败的提示文案。
     *
     * "撤销失败，请手动检查"这句本身没有信息量 —— 用户拿着它不知道该检查什么。
     * 记得住失败清单时就带上数量和前几个文件名；万一清单是空的
     * （理论上不该发生），退回那句通用文案，别弹一个"失败：0 个文件"。
     *
     * v5.15.0 起**带原因**：「名字被占」的用户有明确动作（把同名文件改名或
     * 移走，再点一次撤销），「文件不见了」的没有（外部动过）。
     * 两种混在一句"没撤回来"里，用户不知道该处理哪一个 ——
     * 所以按原因各附一句，各带各的名字。清单是单原因时不啰嗦，
     * 原因不可知（FAILED）时维持原样。
     */
    private fun failureMsg(plainRes: Int, countRes: Int, failed: List<FailedItem>): String {
        if (failed.isEmpty()) return getString(plainRes)
        val head = failed.take(3).joinToString("、") { it.name }
        val ellipsis = if (failed.size > 3) " 等" else ""
        return getString(countRes, failed.size, head + ellipsis) + reasonNotes(failed)
    }

    /** 按原因分组的补充说明（拼在失败清单后面，可能为空串）。 */
    private fun reasonNotes(failed: List<FailedItem>): String {
        val occupied = failed.filter { it.why == UndoWhy.OCCUPIED }
        val missing = failed.filter { it.why == UndoWhy.MISSING }
        if (occupied.isEmpty() && missing.isEmpty()) return ""
        val out = StringBuilder()
        if (occupied.isNotEmpty()) {
            out.append(getString(R.string.msg_undo_why_occupied,
                occupied.size, headOf(occupied)))
        }
        if (missing.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("；")
            out.append(getString(R.string.msg_undo_why_missing,
                missing.size, headOf(missing)))
        }
        return "。" + out
    }

    private fun headOf(items: List<FailedItem>): String {
        val head = items.take(3).joinToString("、") { it.name }
        return head + if (items.size > 3) " 等" else ""
    }

    private fun refreshIfNeeded() {
        syncHistory()
        refreshBoth()
    }

    /**
     * 换了目录：整条撤销栈失效。
     *
     * 撤销栈是左右**共用**的一条，所以任一边换目录都得清 ——
     * 栈里每一步都用文档 Uri 定位文件，换了目录之后那些 Uri 指向的是**上一个目录**。
     * 硬留着会有两种后果，两条都比"撤不了"糟得多：
     *
     *   1. 旧目录的授权还在 → 改名照样成功，但改的是**另一个目录**里的文件，
     *      而界面显示"已撤销"；
     *   2. 旧 Uri 失效 → 代码会回退到"在新目录里按名字找同名文件"这一步，
     *      于是把新目录里一个**毫不相干**的同名文件改掉。
     *
     * 两条都是这个工具一直在防的那类错误：提示成功、实际改错。
     * 所以宁可直接清空，并把"清掉了 N 步"明确说出来 ——
     * 静默清空会让用户在需要撤销时发现按钮空了，却不知道为什么。
     *
     * **刷新同一个目录不清**：那时 Uri 还是那些 Uri，撤销依然有效，
     * 清掉等于把用户刚做的事凭空丢掉一半。
     */
    private fun resetUndoForFolderChange() {
        val n = undoHistory.undoCount
        val hadRedo = undoHistory.redoCount > 0
        if (n == 0 && !hadRedo) return
        // clear() 会把丢包计数也归零 —— 这正合适：新目录就是新的一本账
        undoHistory.clear()
        syncHistory()
        if (n > 0) {
            emitMsg(getString(R.string.msg_undo_cleared_for_folder, n))
        } else {
            emitMsg(getString(R.string.msg_undo_cleared_for_folder_redo))
        }
    }

    /**
     * 这一步该在**哪个目录**里操作、以及缓存该往哪里迁移。
     *
     * 优先用记录里钉住的目录（见 [RenameStep.treeUri]）。
     * v5.3.0 之前落的盘 / 导出的 CSV 没有这个字段，读回来是 null，
     * 才回退到"当前打开的目录" —— 那是旧行为，只为了让老数据还能用。
     */
    private fun treeFor(step: RenameStep): Uri = step.treeUri ?: paneUri(step.side)

    /**
     * 目标名是不是已经被**别的**文档占了。
     *
     * 撤销 / 重做同样是一次改名，而目标名可能在这期间被别人占住 ——
     * 用户在文件管理器里新建了个同名文件、或另一个 App 写了进来。
     * `DocumentsContract.renameDocument` 底下是 provider 的 `File.renameTo`，
     * Linux 上**目标存在会被静默替换**：用户点一次"撤销"，
     * 丢掉的是另一个文件，而提示还写着"已撤销 N 项"。
     *
     * 判据故意偏保守：只要找到一个"不是本次要改的那个文档"的同名文档就拦。
     * 个别 provider 改名后会换 documentId，理论上可能误拦一次 ——
     * 那时的结果是"撤销失败，磁盘没被改动"，用户重试即可；
     * 反过来漏拦的代价是丢文件。两害相权取其轻。
     */
    private fun nameTaken(tree: Uri, docUri: Uri, want: String): Boolean {
        val found = docs.findByName(tree, want) ?: return false
        return found != docUri
    }

    private fun pushUndo(entry: UndoEntry) {
        // **趁着还在这个目录，把每一步的"出身"钉下来。**
        //
        // 撤销 / 重做 / 缓存迁移原本都回头去问"当前打开的目录"（`paneUri(step.side)`）——
        // 那是个隐含的全局状态：用户一换目录，同一条记录就指向了别的目录，
        // 于是改名改到别的目录去、缓存迁移因为查不到旧键而**静默什么都不做**。
        //
        // 上一轮补的「换目录就清空撤销栈」是**事后补救**；这里补的是**纵深防御**：
        // 记录自己知道该回哪儿，就算哪天又冒出一条绕过守卫的换目录路径，
        // 撤销最多是撤不动，绝不会改错文件。
        //
        // 唯一正确的时刻就是这里 —— 操作刚完成，此刻的 paneUri 必然就是刚才动手的目录。
        // 让 14 个构造点各自填一遍，等于把同一件事抄 14 遍，漏一个就埋一个雷。
        // Uri.EMPTY（那一栏还没选目录）钉 null，保持"不知道"的语义。
        val stamped = entry.copy(
            steps = entry.steps.map { st ->
                if (st.treeUri != null) {
                    st
                } else {
                    st.copy(treeUri = paneUri(st.side).takeIf { it != Uri.EMPTY })
                }
            },
        )
        migrateMeta(stamped.steps)
        // 双重上限：条目数 + 总步数。
        // 只限条目数是不够的 —— 64 次「5000 步」的批量操作就是 32 万个
        // RenameStep，能把内存吃光。
        // 循环条件带 isNotEmpty()，所以「单条就超限」时会丢掉全部旧的、
        // 再把这一条加进去 —— 超大批量操作依然可撤销，只是占些内存。
        undoHistory.push(stamped)
        syncHistory()
        persistHistory(stamped)
    }

    /**
     * 把操作追加到磁盘。
     * 撤销栈在内存里，进程一回收就没了；批量改完隔天发现错了还能救回来，
     * 靠的就是这份落盘记录。
     */
    private fun persistHistory(entry: UndoEntry) {
        // 只有改名步骤才落盘。归档/复制/删除这类变动记了也还原不了
        // （CSV 只存"旧名→新名"），存进去只是占用空间和干扰视线。
        if (entry.steps.isEmpty()) return
        io {
            history.append(
                HistoryEntry(
                    id = System.currentTimeMillis(),
                    time = System.currentTimeMillis(),
                    label = entry.label,
                    steps = entry.steps,
                ),
            )
        }
    }

    // ---------------- 改名历史 ----------------

    /** 打开历史面板。 */
    fun requestHistory() {
        io {
            _events.emit(UiEvent.OpenHistory(history.load()))
        }
    }

    /** 导出历史为 CSV，返回文本内容交给 UI 走 SAF 保存。 */
    fun requestExportHistory() {
        io {
            val all = history.load()
            if (all.isEmpty()) {
                emitMsg(getString(R.string.msg_history_empty))
                return@io
            }
            _events.emit(UiEvent.SaveHistory(history.toCsv(all)))
        }
    }

    /**
     * 按 CSV 还原：只有左右目录能唯一定位、且全计划无目标冲突时才开始写盘。
     * 逆序执行以还原多步改名链；身份不明时整批预检失败，不按名称猜文件。
     */
    fun restoreFromCsv(text: String) {
        once("restore") {
            val mapping = history.parseCsv(text)
            if (mapping.isEmpty()) {
                emitMsg(getString(R.string.msg_csv_bad))
                return@once
            }
            val trees = listOfNotNull(_ui.value.left.treeUri, _ui.value.right.treeUri).distinct()
            if (trees.isEmpty()) {
                emitMsg(getString(R.string.msg_need_both))
                return@once
            }
            val initial = trees.associateWith { docs.nameIndex(it).toMutableMap() }
            val simulated = trees.associateWith { initial.getValue(it).toMutableMap() }
            val plan = ArrayList<Triple<Uri, String, String>>()

            // 先模拟整条链。若任何名字在左右两栏重复、缺失或目标被占用，零写盘退出。
            for ((currentName, originalName) in mapping.asReversed()) {
                if (currentName == originalName) continue
                val candidates = trees.mapNotNull { tree ->
                    simulated[tree]?.get(currentName)?.let { tree to it }
                }
                if (candidates.size != 1) {
                    emitMsg(getString(R.string.msg_csv_ambiguous))
                    return@once
                }
                val (tree, docUri) = candidates.single()
                val index = simulated.getValue(tree)
                if (index.any { (name, uri) -> name.equals(originalName, ignoreCase = true) && uri != docUri }) {
                    emitMsg(getString(R.string.msg_csv_ambiguous))
                    return@once
                }
                plan += Triple(tree, currentName, originalName)
                index.remove(currentName)
                index[originalName] = docUri
            }
            if (plan.isEmpty()) {
                emitMsg(getString(R.string.msg_restore_none))
                return@once
            }

            _ui.update { it.copy(busy = true, progress = 0 to plan.size) }
            val steps = mutableListOf<RenameStep>()
            try {
                val live = trees.associateWith { initial.getValue(it).toMutableMap() }
                for ((indexInPlan, op) in plan.withIndex()) {
                    if (!currentCoroutineContext().isActive) break
                    val (tree, currentName, originalName) = op
                    val docUri = live[tree]?.get(currentName)
                    if (docUri == null) break
                    val newUri = docs.rename(docUri, originalName, tree) ?: break
                    val side = if (tree == _ui.value.left.treeUri) Side.LEFT else Side.RIGHT
                    steps += RenameStep(newUri, currentName, originalName, side, tree, docUri)
                    live.getValue(tree).remove(currentName)
                    live.getValue(tree)[originalName] = newUri
                    progressEvery(indexInPlan + 1, plan.size) { pg -> _ui.update { it.copy(progress = pg) } }
                }
                val notDone = plan.size - steps.size
                if (steps.isNotEmpty()) {
                    pushUndo(UndoEntry(steps, getString(R.string.label_restore_csv, steps.size)))
                }
                if (notDone == 0) {
                    emitMsg(getString(R.string.msg_restored, steps.size), withUndo = steps.isNotEmpty())
                } else if (steps.isNotEmpty()) {
                    emitMsg(
                        getString(R.string.msg_restore_partial, steps.size, plan.size),
                        withUndo = true,
                    )
                } else {
                    emitMsg(getString(R.string.msg_restore_none))
                }
            } finally {
                _ui.update { it.copy(busy = false, progress = null) }
                refreshBoth()
            }
        }
    }

    /** 生成「按拍摄日期归档」计划并打开预览。 */
    fun requestArchive(side: Side) {
        io {
            val pane = _ui.value.pane(side)
            val targets = _ui.value.checkedItems().filter { it.side == side }.ifEmpty { pane.items }
            val rows = Organizer.planArchive(targets)
            if (rows.isEmpty()) {
                emitMsg(getString(R.string.msg_archive_none))
                return@io
            }
            _events.emit(UiEvent.ShowArchivePlan(rows))
        }
    }

    /** 执行归档（移动文件到日期子目录）。 */
    fun executeArchive(rows: List<Organizer.MoveRow>) {
        once("archive") {
            if (!MediaStoreMeta.hasAllFilesAccess()) {
                emitMsg(getString(R.string.msg_need_allfiles))
                return@once
            }
            _ui.update { it.copy(busy = true, progress = 0 to rows.size) }
            try {
                val r = Organizer.executeArchive(rows)
                // 归档是移动文件，同样必须可撤销 —— 否则移错了没有退路。
                //
                // 撤销步骤只能由**执行结果**构造，不能拿计划里的 rows：
                // 计划里可能包含源文件已丢失、或目标已被同名文件占住而没动的行，
                // 把这些行塞进撤销栈，撤销时会把占着那个位置的文件移走，
                // 用户凭空丢一张图（而且和他刚做的操作看起来毫无关系）。
                val moves = Organizer.toMoveSteps(r)
                if (moves.isNotEmpty()) {
                    pushUndo(
                        UndoEntry(
                            label = getString(R.string.label_archive, moves.size),
                            moves = moves,
                        ),
                    )
                }
                // 后缀拼装必须加括号：`A + if (x) b else "" + if (y) c else ""`
                // 在 Kotlin 里会被解析成 `A + if (x) b else ("" + if (y) c else "")`，
                // 跳过数 >0 时"可撤销"那句就被吞掉了。
                val skippedNote =
                    if (r.skipped > 0) getString(R.string.msg_archived_skipped, r.skipped) else ""
                val undoNote = if (moves.isNotEmpty()) getString(R.string.msg_can_undo) else ""
                emitMsg(
                    // 因同名跳过的不算失败，但必须说 —— 否则用户以为全归档好了
                    getString(R.string.msg_archived, r.moved, r.failed) + skippedNote + undoNote,
                    withUndo = moves.isNotEmpty(),
                )
            } finally {
                invalidateMetaOnFileChange()
                _ui.update { it.copy(busy = false, progress = null) }
                refreshBoth()
            }
        }
    }

    /** 全盘大文件排行。 */
    fun requestBigFiles() {
        io {
            if (!MediaStoreMeta.hasAllFilesAccess()) {
                emitMsg(getString(R.string.msg_need_allfiles))
                return@io
            }
            _events.emit(
                UiEvent.ShowBigFiles(
                    Organizer.findBigFiles(getApplication()),
                    Organizer.summary(getApplication()),
                ),
            )
        }
    }

    /**
     * 把一组重复图片中「多余的」移入回收站（保留第一个）。
     * 因为回收站可恢复，这个操作才是安全的 —— 否则不敢让人一键清几百张。
     */
    fun trashDuplicates(group: DuplicateFinder.DuplicateGroup) {
        once("trashDup") {
            val extra = group.members.drop(1)
            var n = 0
            val trashedItems = ArrayList<com.yuanbao.pairrename.data.TrashRepository.TrashedItem>()
            extra.forEach { m ->
                trash.movePathToTrash(m.path)?.let { trashedItems += it; n++ }
            }
            if (trashedItems.isNotEmpty()) {
                pushUndo(
                    UndoEntry(
                        trashed = trashedItems,
                        label = getString(R.string.label_delete, trashedItems.size),
                    ),
                )
            }
            emitMsg(
                getString(R.string.msg_dup_trashed, n, extra.size) +
                    if (trashedItems.isNotEmpty()) getString(R.string.msg_can_undo) else "",
                withUndo = trashedItems.isNotEmpty(),
            )
            // 文件被移走了，MediaStore 缓存必须失效
            invalidateMetaOnFileChange()
            refreshBoth()
        }
    }

    fun clearHistory() {
        io {
            history.clear()
            emitMsg(getString(R.string.msg_history_cleared))
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 改名后就地更新受影响的条目，**不重新扫描整个目录**。
     *
     * 这是「每改一个名就转圈半天」的根因修复：原来改名后一律 refreshBoth()，
     * 于是 loading=true 盖住整栏、30 张缩略图全部重新解码。
     * 实际上改名只影响一两个文件，完全没必要全量重扫。
     *
     * 拿不到新 Uri 时（少数 provider 行为异常）退回全量刷新，保证不会出错。
     */
    private fun patchAfterRename(steps: List<RenameStep>) {
        if (steps.isEmpty()) return
        val snapshot = _ui.value
        var allResolved = true
        Side.entries.forEach { side ->
            val tree = snapshot.pane(side).treeUri ?: return@forEach
            val mine = steps.filter { it.side == side }
            if (mine.isEmpty()) return@forEach
            // 新步骤带 previousUri：renameDocument 已经返回新 Uri，不必为单次拖动
            // 再把整目录 query 一遍。旧历史/批处理步骤没有该字段时保留兼容回退。
            val byPrevious = mine.mapNotNull { step ->
                step.previousUri?.let { (it.toString() to step.previousName) to step }
            }.toMap()
            val legacy = mine.filter { it.previousUri == null }
            val index = if (legacy.isNotEmpty()) docs.nameIndex(tree) else emptyMap()
            val byOldKey = legacy.associateBy { it.docUri.toString() }
            val producedStates = mine.mapTo(HashSet()) { it.docUri.toString() to it.newName }
            val expectedStarts = mine.mapNotNull { step ->
                step.previousUri?.let { it.toString() to step.previousName }
            }.filterNot { it in producedStates }.toSet()
            val resolvedStarts = HashSet<Pair<String, String>>()
            updatePane(side) { pane ->
                val next = pane.items.map { item ->
                    var currentUri = item.docUri
                    var currentName = item.displayName
                    var hops = 0
                    while (hops < mine.size) {
                        val state = currentUri.toString() to currentName
                        val step = byPrevious[state] ?: break
                        resolvedStarts += state
                        currentUri = step.docUri
                        currentName = step.newName
                        hops++
                    }
                    if (currentUri != item.docUri || currentName != item.displayName) {
                        // docId 也要跟着换，否则后续操作会指向旧文档。
                        val newId = runCatching {
                            android.provider.DocumentsContract.getDocumentId(currentUri)
                        }.getOrDefault(item.docId)
                        item.copy(
                            docUri = currentUri,
                            docId = newId,
                            displayName = currentName,
                            canRename = true,
                        )
                    } else {
                        // 兼容尚未携带 previousUri 的旧步骤。
                        val st = byOldKey[item.key] ?: return@map item
                        val newUri = index[st.newName]
                        if (newUri == null) {
                            allResolved = false
                            return@map item
                        }
                        val newId = runCatching {
                            android.provider.DocumentsContract.getDocumentId(newUri)
                        }.getOrDefault(item.docId)
                        item.copy(
                            docUri = newUri,
                            docId = newId,
                            displayName = st.newName,
                            canRename = true,
                        )
                    }
                }
                // 同目录改名（左右栏指向同一个文件夹、或目标名与某张已有卡片同名）会把
                // 两个条目映射到同一个 URI。LazyVerticalGrid 以 docUri 为 key，
                // 重复 key 会让 subcompose 直接抛 IllegalArgumentException 让应用崩溃。
                // 不猜哪一条才对：判定为「对不上」，交给下面的 refreshBoth 兜底。
                val seenKeys = HashSet<String>(next.size)
                if (next.any { !seenKeys.add(it.key) }) {
                    allResolved = false
                    return@updatePane pane
                }
                pane.copy(items = next)
            }
            if (!resolvedStarts.containsAll(expectedStarts)) allResolved = false
        }
        if (allResolved) {
            recomputeMatch()
        } else {
            // 兜底：有对不上的，宁可慢一次也不能显示错
            refreshBoth()
        }
    }

    private fun refreshBoth() {
        Side.entries.forEach { side ->
            _ui.value.pane(side).treeUri?.let { load(side, it) }
        }
    }

    /** 单栏页要显示的那一栏。 */
    fun singlePane(): PaneState = _ui.value.pane(_ui.value.singleSide)

    private fun paneUri(side: Side): Uri = _ui.value.pane(side).treeUri ?: Uri.EMPTY

    private fun emitMsg(text: String, withUndo: Boolean = false) {
        _events.tryEmit(UiEvent.Message(text, withUndo))
    }

    private fun getString(res: Int, vararg args: Any?): String =
        getApplication<Application>().getString(res, *args)

    private fun updatePane(side: Side, block: (PaneState) -> PaneState) {
        _ui.update { s ->
            if (side == Side.LEFT) s.copy(left = block(s.left)) else s.copy(right = block(s.right))
        }
    }

    private fun nextFolderLoadGeneration(side: Side): Long =
        folderLoadGeneration.computeIfAbsent(side) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()

    private fun invalidateFolderLoad(side: Side) {
        nextFolderLoadGeneration(side)
    }

    private fun isCurrentFolderLoad(side: Side, uri: Uri, generation: Long): Boolean =
        folderLoadGeneration[side]?.get() == generation && _ui.value.pane(side).treeUri == uri

    private fun updatePaneForLoad(
        side: Side,
        uri: Uri,
        generation: Long,
        block: (PaneState) -> PaneState,
    ) {
        _ui.update { state ->
            if (folderLoadGeneration[side]?.get() != generation || state.pane(side).treeUri != uri) {
                state
            } else if (side == Side.LEFT) {
                state.copy(left = block(state.left))
            } else {
                state.copy(right = block(state.right))
            }
        }
    }

    private fun List<ImageItem>.sortedWith(s: AppSettings): List<ImageItem> = when (s.sortOrder) {
        SortOrder.NAME -> sortedWith(compareBy<ImageItem> { Naming.naturalKey(it.displayName) })
        SortOrder.DATE_DESC -> sortedWith(
            compareByDescending<ImageItem> { it.lastModified }
                .thenBy { Naming.naturalKey(it.displayName) },
        )
        SortOrder.SIZE_DESC -> sortedWith(
            compareByDescending<ImageItem> { it.size }
                .thenBy { Naming.naturalKey(it.displayName) },
        )
        SortOrder.TAKEN -> sortedWith(
            // 没拍到时间（0）的排最后，而不是混在最前面
            compareByDescending<ImageItem> { it.takenAt }
                .thenBy { Naming.naturalKey(it.displayName) },
        )
    }
}

/**
 * 一次撤销 / 重做的结果。
 *
 * 从裸 `Boolean` 升上来的原因：`false` 只说明"有东西没成"，
 * 而用户需要知道**是哪几个文件**。一次撤销 20 步、第 13 步失败时，
 * 光弹"撤销失败"等于让他自己在那 20 个文件里逐个试。
 *
 * [failed] 里放的是**用户能拿去搜索的名字**：
 * 改名的放"想改回的那个名字"，归档的放文件名，删除的放原文件名。
 * 不放 Uri —— Uri 在系统文件管理器里搜不到。
 *
 * [FailedItem.why] 说明**为什么**没撤回来。这不是锦上添花：
 * 「名字被占」用户有明确动作（把同名文件改名或移走，再点一次撤销），
 * 「文件不见了」则通常意味着外部动过（被移走/被删）。
 * 两种混在同一个"失败"里，用户拿着提示不知道该处理哪一个。
 */
private class UndoOutcome(
    val ok: Boolean,
    val failed: List<FailedItem> = emptyList(),
)

/** 撤销 / 重做中一个没处理成功的文件。 */
private class FailedItem(
    val name: String,
    val why: UndoWhy,
)

/** 失败原因。OCCUPIED 有用户动作可做，MISSING / FAILED 没有。 */
private enum class UndoWhy { OCCUPIED, MISSING, FAILED }

/** 从绝对路径取文件名。Windows 用 `\`、SAF 路径用 `/`，两种都要认。 */
private fun baseNameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')
