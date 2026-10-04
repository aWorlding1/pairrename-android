package com.yuanbao.pairrename.vm

import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.model.UndoEntry

/**
 * 撤销 / 重做栈。
 *
 * 从 MainViewModel 抽出来的原因：它有两重上限（条目数 + 总步数），
 * 且要求「push 时清 redo」「撤销失败要塞回去」等不变量，
 * 集中管理比散在 ViewModel 里更不容易写错。
 *
 * **双重上限**是必要的：只限条目数（64 条）远远不够 ——
 * 64 次「5000 步」的批量操作就是 32 万个 RenameStep，约 61MB。
 *
 * 单条就超过总上限时，会丢掉全部旧的、再放入这一条。
 * 这是刻意的取舍：超大批量操作**依然可撤销**，只是占些内存 ——
 * 正确性优先于省内存。
 */
class UndoStack {

    private val undo = ArrayList<UndoEntry>()
    private val redo = ArrayList<UndoEntry>()

    @get:Synchronized
    val undoCount: Int get() = undo.size
    @get:Synchronized
    val redoCount: Int get() = redo.size

    /**
     * 因为超出容量上限而被丢掉的条目数 / 总步数。
     *
     * **为什么要记这个**：上限是必须的（见上面那段注释），但丢包是**静默**的 ——
     * 用户做完 70 步操作，回到操作记录里一数只剩 64 条，界面上没有任何解释。
     * 更糟的是他可能以为"没做过的操作不在这儿"，而实际上那 6 步**已经改到磁盘上了**，
     * 只是从这里撤不回来。这是"看起来能撤、其实撤不了"的一类静默错误，
     * 和这个工具一直在防的那些是同一类。
     *
     * 记下来之后，界面可以明确说「更早的 N 步（共 M 项）已超出上限」，
     * 用户至少知道该去翻改名历史（那份记录不设上限）。
     */
    var droppedEntries: Int = 0
        private set
    var droppedSteps: Int = 0
        private set

    @Synchronized fun canUndo(): Boolean = undo.isNotEmpty()
    @Synchronized fun canRedo(): Boolean = redo.isNotEmpty()

    /** 压入一条撤销记录，同时清空重做栈（常规语义）。 */
    @Synchronized fun push(entry: UndoEntry) {
        // 循环条件带 isNotEmpty()，所以「单条就超限」时不会把自己也弹掉
        while (undo.isNotEmpty() &&
            (undo.size >= MAX_ENTRIES || undo.sumOf { it.total } + entry.total > MAX_TOTAL_STEPS)
        ) {
            val gone = undo.removeAt(0)
            droppedEntries++
            droppedSteps += gone.total
        }
        undo.add(entry)
        redo.clear()
    }

    /** 取出最近一条待撤销的记录（不移除，失败时由 [pushBack] 放回）。 */
    @Synchronized fun peekUndo(): UndoEntry? = undo.lastOrNull()

    /** 移除并返回最近一条待撤销的记录。 */
    @Synchronized fun takeUndo(): UndoEntry? = if (undo.isEmpty()) null else undo.removeAt(undo.size - 1)

    /** 撤销失败时把记录放回原处。 */
    @Synchronized fun pushBack(entry: UndoEntry) {
        undo.add(entry)
    }

    /** 撤销成功：把这条移入重做栈。 */
    @Synchronized fun movedToRedo(entry: UndoEntry) {
        redo.add(entry)
    }

    @Synchronized fun takeRedo(): UndoEntry? = if (redo.isEmpty()) null else redo.removeAt(redo.size - 1)

    /** 重做失败时放回。 */
    @Synchronized fun pushBackRedo(entry: UndoEntry) {
        redo.add(entry)
    }

    /** 重做成功：移回撤销栈。 */
    @Synchronized fun movedToUndo(entry: UndoEntry) {
        undo.add(entry)
    }

    /**
     * 撤销栈的可读快照（最新的在前），用于「操作记录」界面。
     * 元素是 (栈内下标, 标签)，下标用于一次性回退到某一步。
     */
    @Synchronized fun labels(): List<Pair<Int, String>> =
        undo.mapIndexed { i, e -> i to e.label }.reversed()

    /**
     * 交换左右两栏时，把每步记录的 side 翻转。
     *
     * **必须做**：撤销栈里每步都记着 side，交换后 side 的含义反了。
     * 不翻转的话，撤销会把名字改到**另一栏**去 ——
     * 又是那种「提示成功、实际改错」的错误。
     */
    @Synchronized fun flipSides() {
        val flip: (UndoEntry) -> UndoEntry = { e ->
            e.copy(
                steps = e.steps.map { st ->
                    st.copy(side = if (st.side == Side.LEFT) Side.RIGHT else Side.LEFT)
                },
            )
        }
        repeat(undo.size) { i -> undo[i] = flip(undo[i]) }
        repeat(redo.size) { i -> redo[i] = flip(redo[i]) }
    }

    /**
     * 清空两条栈。
     *
     * 丢弃计数**一并归零**：它描述的是"当前这条栈里少了几步"。
     * 栈都清空了（换目录 / 刷新后重来），还挂着上一轮的"更早的 3 步已丢弃"
     * 就是在说一件已经不存在的事 —— 用户点进去看不到那 3 步，只会更困惑。
     */
    @Synchronized fun clear() {
        undo.clear()
        redo.clear()
        droppedEntries = 0
        droppedSteps = 0
    }

    private companion object {
        const val MAX_ENTRIES = 64
        const val MAX_TOTAL_STEPS = 20_000
    }
}
