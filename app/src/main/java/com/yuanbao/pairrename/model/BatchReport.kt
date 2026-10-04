package com.yuanbao.pairrename.model

/**
 * 批量执行里一条没改成的记录，属于哪一类。
 *
 * 三者必须分开计：它们的**可行动性**完全不同。
 * 只读和冲突重试一次还是同样结果（要么去改权限，要么手动腾名字），
 * 只有真失败值得"再试一次"。
 */
enum class IssueKind {
    /** 该存储不允许改名（只读目录 / 无权限 / provider 拒绝）。 */
    READONLY,

    /** 目标名已被占用，按用户设的冲突策略跳过。 */
    CONFLICT,

    /** 真失败：provider 报了错。只有这一类可以重试。 */
    FAILED,
}

/**
 * 一条批量执行的失败 / 跳过记录。
 *
 * 存整个 [row] 而不是只存两个名字：报告里要显示"想改成什么"，
 * 而重试需要原样的 `PlanRow`（含 `ImageItem`）。
 * 失败的文件在磁盘上**没有动过**，所以它的 `target` 依然有效 ——
 * 这一点是重试能成立的前提。
 */
data class BatchIssue(
    val row: PlanRow,
    val kind: IssueKind,
    /** 额外的技术细节（异常信息等），可为空。 */
    val detail: String = "",
) {
    val oldName: String get() = row.oldName
    val newName: String get() = row.newName
    val side: Side get() = row.target.side
}

/**
 * 一次批量执行的完整结果。
 *
 * 存在的理由：原来两条批量路径（批量改名 / 按配对统一）都是**第一个失败就回滚整批并中止**，
 * 而且只弹一句 snackbar。后果是双重的 ——
 *
 *   · **一张改不了的图会让另外 199 张全部白改**（都已改的还会被回滚）；
 *   · 批量改 200 张、失败 5 张时，用户**根本不知道是哪 5 张**，
 *     只看到一句"改名失败：IMG_0042.jpg"。
 *
 * 批量工具的价值就在"一次处理很多个"，所以单点失败必须只影响它自己。
 * 报告则保证"哪些没成功"是一个可以逐条看清、还能直接重试的事实。
 */
data class BatchReport(
    /** 这次是什么操作（「批量改名」/「按配对统一」），报告标题用。 */
    val title: String,
    /** 计划处理多少个。 */
    val total: Int,
    /** 真的改成功的个数。 */
    val changed: Int,
    /** 名字本来就一样、无需改的个数。 */
    val unchanged: Int,
    val issues: List<BatchIssue>,
    /** 用户中途点了「停止」。 */
    val stopped: Boolean = false,
    /**
     * 因为**连续失败**触发的自我保护式中止。
     *
     * 与 [stopped] 是两件事：前者是用户让停，后者是"环境看起来坏了"。
     * 一直失败还硬跑完剩下的几百个，既慢又毫无意义，
     * 所以连续失败到阈值就停下 —— 但必须**明确告诉用户是为什么停的**，
     * 否则看起来就像程序自己坏了。
     */
    val aborted: Boolean = false,
) {
    val readonly: Int get() = issues.count { it.kind == IssueKind.READONLY }
    val conflict: Int get() = issues.count { it.kind == IssueKind.CONFLICT }
    val failed: Int get() = issues.count { it.kind == IssueKind.FAILED }

    /** 值得"再试一次"的：只有真失败。只读和冲突重试还是同样结果。 */
    val retryRows: List<PlanRow> get() = issues.filter { it.kind == IssueKind.FAILED }.map { it.row }

    val hasIssues: Boolean get() = issues.isNotEmpty()

    /**
     * 根本没轮到的项目数（用户中止 / 熔断时才会大于 0）。
     *
     * 必须单独报出来：中止时 `成功 + 无需改动 + 问题` 会**小于** [total]，
     * 用户按加总去核对就会以为数字对不上、报告漏了东西。
     * `.coerceAtLeast(0)` 是防御 —— 正常情况下三者之和永远不该超过 total。
     */
    val notAttempted: Int
        get() = (total - changed - unchanged - issues.size).coerceAtLeast(0)

    /** 是不是"全都没成功"（用来区分"完全失败"和"部分成功"）。 */
    val nothingChanged: Boolean get() = changed == 0
}

/**
 * 连续多少次**真失败**就认为环境坏了。
 *
 * 取 5 是刻意的折中：3 太小（偶发两三次瞬时错误很常见，会误停），
 * 10 太大（真遇到只读挂载 / 拔掉的 U 盘，要空跑十次才停）。
 *
 * 注意**只统计 [IssueKind.FAILED]**：只读和冲突是预期内的跳过，
 * 一个目录里有一半文件在只读子目录里完全正常，不能拿来当"环境坏了"的证据。
 */
const val CONSECUTIVE_FAILURE_LIMIT = 5

/**
 * 批量执行的**记账器**。
 *
 * 刻意做成不碰 IO 的纯逻辑：批量的正确性全在"记什么、什么时候停"这里，
 * 把它从 `viewModelScope` + SAF 调用里剥出来，才能用一个 Python 复刻
 * 逐条验证（见 `verify_batch_report.py`）—— 而这两条路径恰恰是最难手工测的
 * （要造出"第 47 个文件改不了"这种局面）。
 */
class BatchLedger(
    private val total: Int,
    private val failureLimit: Int = CONSECUTIVE_FAILURE_LIMIT,
) {
    private val collected = mutableListOf<BatchIssue>()
    private var consecutiveFailures = 0

    var changed = 0
        private set

    var unchanged = 0
        private set

    /** 是否因为连续失败熔断了。 */
    var aborted = false
        private set

    val issues: List<BatchIssue> get() = collected

    /** 名字本来就一样，跳过。既不算成功也不算问题。 */
    fun onUnchanged() {
        unchanged++
    }

    /**
     * 改成功一个。
     *
     * **只有这里会重置连续失败计数。** 只读 / 冲突的跳过都不重置 ——
     * 它们不构成"provider 是好的"的证据（冲突跳过压根没碰 provider），
     * 所以失败间隔里的跳过不应该掩盖一个正在坏掉的环境。
     */
    fun onChanged() {
        changed++
        consecutiveFailures = 0
    }

    /**
     * 记下一条没成功的记录。
     *
     * @return `false` 表示连续失败已达上限，调用方**应当停止**这一批。
     */
    fun onIssue(issue: BatchIssue): Boolean {
        collected += issue
        if (issue.kind != IssueKind.FAILED) return true
        consecutiveFailures++
        if (consecutiveFailures >= failureLimit) {
            aborted = true
            return false
        }
        return true
    }

    fun report(title: String, stopped: Boolean): BatchReport = BatchReport(
        title = title,
        total = total,
        changed = changed,
        unchanged = unchanged,
        issues = collected.toList(),
        stopped = stopped,
        aborted = aborted,
    )
}
