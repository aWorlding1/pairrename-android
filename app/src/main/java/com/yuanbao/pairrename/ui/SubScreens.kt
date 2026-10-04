package com.yuanbao.pairrename.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.util.PairReason
import com.yuanbao.pairrename.vm.MainViewModel
import com.yuanbao.pairrename.vm.UiState

/**
 * 「依据构成」的展示顺序：由强到弱，与配对引擎的出手顺序一致。
 * 放在文件顶层而不是函数里，避免每次重组都重新构造这个列表。
 */
private val REASON_ORDER = listOf(
    PairReason.CONTENT to "内容",
    PairReason.EXIF to "拍摄时间",
    PairReason.NAME to "名字",
    PairReason.SIMILAR to "相似名",
    PairReason.SEQ to "序号",
    PairReason.ORDER to "顺序",
)

/** 二级页的统一容器：一个可滚动的卡片列表。 */
@Composable
fun SubScreenScaffold(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/**
 * 一张「分组卡」：标题 + 说明 + 若干按钮。
 * 二级页里的每个功能都用它，视觉上主次分明。
 */
@Composable
fun ActionGroup(
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
fun TwoButtons(
    left: String,
    right: String,
    leftEnabled: Boolean = true,
    rightEnabled: Boolean = true,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = leftEnabled,
            onClick = onLeft,
            modifier = Modifier.weight(1f),
        ) { Text(left) }
        OutlinedButton(
            enabled = rightEnabled,
            onClick = onRight,
            modifier = Modifier.weight(1f),
        ) { Text(right) }
    }
}

@Composable
fun PrimaryRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
}

// ---------------- 改名 ----------------

@Composable
fun RenameScreen(vm: MainViewModel, ui: UiState) {
    val pending = ui.matched.size / 2 - ui.synced.size / 2
    val bothReady = ui.left.items.isNotEmpty() && ui.right.items.isNotEmpty()
    val left = ui.left.items
    val right = ui.right.items

    SubScreenScaffold {
        // 一键流水线放最上面：它替掉下面三步里的重复劳动
        ActionGroup(
            title = stringResource(R.string.action_pipeline),
            desc = "自动按顺序执行：补数据 → 配对 → 统一。省掉反复点三步。",
        ) {
            PrimaryRow("开始", enabled = bothReady) { vm.requestPipeline() }
        }

        ActionGroup(
            title = stringResource(R.string.sync_title),
            desc = "把一边的整套名字应用到另一边。配对是推算的，误配可在「识别」里纠正。",
            status = if (pending > 0) "$pending 对待处理" else "已全部统一",
        ) {
            TwoButtons(
                left = stringResource(R.string.align_left_to_right),
                right = stringResource(R.string.align_right_to_left),
                leftEnabled = bothReady,
                rightEnabled = bothReady,
                onLeft = { vm.requestSyncAll(com.yuanbao.pairrename.model.SyncDirection.LEFT_TO_RIGHT) },
                onRight = { vm.requestSyncAll(com.yuanbao.pairrename.model.SyncDirection.RIGHT_TO_LEFT) },
            )
            // 几百对一个个点开核对不现实，导出成表格扫一眼更快
            OutlinedButton(
                onClick = { vm.exportPairReport() },
                enabled = bothReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) { Text("导出配对报告…") }
        }

        ActionGroup(
            title = stringResource(R.string.action_batch),
            desc = "对勾选的文件做编号、查找替换、加前后缀、大小写转换。执行前可预览。",
        ) {
            PrimaryRow("批量改名…", enabled = left.isNotEmpty() || right.isNotEmpty()) {
                // 工具页没有"当前栏"的概念：勾选集中在哪一栏就按哪一栏开，
                // 两边都没勾（或勾得一样多）时用有文件的左栏兜底。
                val leftHits = left.count { it.key in ui.checked }
                val rightHits = right.count { it.key in ui.checked }
                val target = when {
                    leftHits > rightHits -> Side.LEFT
                    rightHits > leftHits -> Side.RIGHT
                    left.isNotEmpty() -> Side.LEFT
                    else -> Side.RIGHT
                }
                vm.requestBatch(target)
            }
            // 批量跑到一半能停手 —— 已改的不回退，撤销栈记得每一步。
            // 注意判的是 canCancel 而不是 busy：复制 / 删除 / 归档 之类
            // 走的是 once()，压根不受取消影响，给它们显示"取消"按钮
            // 只会制造"点了没用"的假象。
            if (ui.canCancel) {
                OutlinedButton(
                    onClick = { vm.cancelRunning() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_cancel)) }
            }
            TwoButtons(
                left = "顺序对齐（左→右）",
                right = "顺序对齐（右→左）",
                leftEnabled = bothReady,
                rightEnabled = bothReady,
                onLeft = { vm.requestAlign(Side.LEFT) },
                onRight = { vm.requestAlign(Side.RIGHT) },
            )
        }

        ActionGroup(
            title = "按拍摄时间命名",
            desc = "改成 20240315_143022 这样的名字。需要先在「识别」里读取拍摄时间。",
            status = if (left.any { it.takenAt > 0 } || right.any { it.takenAt > 0 }) "已就绪" else "未读取",
        ) {
            TwoButtons(
                left = "左栏",
                right = "右栏",
                leftEnabled = left.any { it.takenAt > 0 },
                rightEnabled = right.any { it.takenAt > 0 },
                onLeft = { vm.renameByExif(Side.LEFT) },
                onRight = { vm.renameByExif(Side.RIGHT) },
            )
        }
    }
}

// ---------------- 整理 ----------------

@Composable
fun OrganizeScreen(vm: MainViewModel, ui: UiState) {
    val left = ui.left.items
    val right = ui.right.items
    val hasTime = { list: List<com.yuanbao.pairrename.model.ImageItem> ->
        list.any { it.takenAt > 0 }
    }

    SubScreenScaffold {
        ActionGroup(
            title = stringResource(R.string.action_archive),
            desc = "按拍摄时间把图片移到「年-月」子目录。会先预览再执行，可撤销。",
        ) {
            TwoButtons(
                left = "左栏",
                right = "右栏",
                leftEnabled = hasTime(left),
                rightEnabled = hasTime(right),
                onLeft = { vm.requestArchive(Side.LEFT) },
                onRight = { vm.requestArchive(Side.RIGHT) },
            )
        }

        ActionGroup(
            title = "全盘清理",
            desc = "查找重复图片、看大文件排行。需要「管理所有文件」权限。",
        ) {
            PrimaryRow("查找重复图片") { vm.findDuplicates() }
            PrimaryRow(stringResource(R.string.action_bigfiles)) { vm.requestBigFiles() }
        }

        ActionGroup(
            title = "复制与删除",
            desc = "删除默认进回收站，可恢复。清空回收站才是永久删除。",
        ) {
            TwoButtons(
                left = "复制左 → 右",
                right = "复制右 → 左",
                leftEnabled = left.isNotEmpty(),
                rightEnabled = right.isNotEmpty(),
                onLeft = { vm.copyAllTo(Side.RIGHT) },
                onRight = { vm.copyAllTo(Side.LEFT) },
            )
            TwoButtons(
                left = "删除勾选",
                right = "回收站",
                leftEnabled = ui.checked.isNotEmpty(),
                onLeft = { vm.requestDelete() },
                onRight = { vm.requestTrash() },
            )
        }

        ActionGroup(
            title = stringResource(R.string.action_move),
            desc = stringResource(R.string.move_desc),
            status = if (ui.checked.isNotEmpty()) "已勾选 ${ui.checked.size} 个" else null,
        ) {
            TwoButtons(
                left = "移到右边",
                right = "移到左边",
                leftEnabled = ui.checked.isNotEmpty(),
                rightEnabled = ui.checked.isNotEmpty(),
                onLeft = { vm.moveCheckedTo(Side.RIGHT) },
                onRight = { vm.moveCheckedTo(Side.LEFT) },
            )
        }

        // 补齐：只复制对面没有的。整栏复制会制造一堆重名文件，
        // 这不是补齐，是制造混乱 —— 所以单独做成一项。
        val (uLeft, uRight) = remember(ui.matched, left, right) { vm.uniqueCounts() }
        ActionGroup(
            title = stringResource(R.string.action_sync_unique),
            desc = stringResource(R.string.sync_unique_desc),
            status = if (uLeft + uRight > 0) "左独有 $uLeft · 右独有 $uRight" else "两边已对齐",
        ) {
            TwoButtons(
                left = "补到右边（$uLeft）",
                right = "补到左边（$uRight）",
                leftEnabled = uLeft > 0,
                rightEnabled = uRight > 0,
                onLeft = { vm.syncUnique(Side.RIGHT) },
                onRight = { vm.syncUnique(Side.LEFT) },
            )
        }
    }
}

// ---------------- 识别 ----------------

@Composable
fun AnalyzeScreen(
    vm: MainViewModel,
    ui: UiState,
    /** 对某一栏做体检（结果在主界面弹出）。 */
    onStats: (Side) -> Unit = {},
) {
    val all = ui.left.items + ui.right.items
    val timed = all.count { it.takenAt > 0 }
    val verified = ui.contentKeys.size

    SubScreenScaffold {
        ActionGroup(
            title = stringResource(R.string.action_read_exif),
            desc = stringResource(R.string.exif_hint),
            status = if (timed > 0) "已读 $timed 张" else null,
        ) {
            PrimaryRow(stringResource(R.string.action_read_exif), enabled = all.isNotEmpty()) { vm.readExif() }
            if (timed > 0) {
                TwoButtons(
                    left = "用时间配对：${if (ui.useExif) "开" else "关"}",
                    right = "清除",
                    onLeft = { vm.toggleUseExif(!ui.useExif) },
                    onRight = { vm.clearAllVerification() },
                )
            }
        }

        ActionGroup(
            title = "按内容校验",
            desc = "读取文件内容算指纹，能 100% 确定两张图是否相同。只校验体积相同的候选，通常很快。",
            status = if (verified > 0) "已校验 $verified 个" else null,
        ) {
            PrimaryRow(
                stringResource(R.string.action_verify_content),
                enabled = ui.left.items.isNotEmpty() && ui.right.items.isNotEmpty(),
            ) { vm.verifyByContent() }
            if (verified > 0) {
                PrimaryRow("清除校验结果") { vm.clearContentKeys() }
            }
        }

        ActionGroup(
            title = "配对统计",
            desc = "配对按「内容 → 时间 → 名字 → 相似名 → 序号 → 顺序」推算，谁先命中就归谁，不确定时可以手动纠正。",
        ) {
            val manual = ui.manualPairs.size / 2
            Text(
                text = buildString {
                    append("已配对 ${ui.matched.size / 2} 对")
                    if (ui.bySimilar > 0) append("，靠相似名 ${ui.bySimilar} 对")
                    if (ui.bySeq > 0) append("，靠序号 ${ui.bySeq} 对")
                    if (ui.byOrder > 0) append("，靠顺序 ${ui.byOrder} 对")
                    if (manual > 0) append("，手动 $manual 对")
                    if (ui.unlinked.isNotEmpty()) append("，已解除 ${ui.unlinked.size / 2} 对")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            // 依据构成：reason 表里每一对都记了左右两条，所以除以 2 才是对数。
            // 手动配对会把 reason 抹掉（见 applyManualOverrides），所以它不在这里，
            // 由上面那行的「手动 N 对」单独承担 —— 两处口径不会重复计。
            val breakdown = run {
                val count = ui.reason.values.groupingBy { it }.eachCount()
                REASON_ORDER.mapNotNull { (reason, label) ->
                    val n = (count[reason] ?: 0) / 2
                    if (n > 0) "$label $n 对" else null
                }
            }
            if (breakdown.isNotEmpty()) {
                Text(
                    text = "依据构成：${breakdown.joinToString(" · ")}（共 ${ui.matched.size / 2} 对）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 时间冲突汇总 + 出口。**排在「弱依据」之前**：
            // 弱依据只是"这条线索本身不够硬"，时间冲突是"线索和更硬的证据直接打架" ——
            // 矛盾比猜测更该先处理，因为照错配改名会直接改错文件。
            if (ui.conflictPairs > 0) {
                Text(
                    text = "其中 ${ui.conflictPairs} 对跟拍摄时间打架：两边都有拍摄时间，" +
                        "却相差超过 2 秒。多半是各自从 0001 开始编号造成的错配，" +
                        "照这样改名就会改错文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                PrimaryRow("复核这 ${ui.conflictPairs} 对") { vm.reviewConflicts() }
            }
            // 弱依据汇总 + 出口。
            //
            // 上一版只把"靠顺序 5 对"报在数字里就算完了 —— 用户看完必然要问
            // "那是哪 5 对"。统计若不给出路，等于把问题摆出来却不给解法。
            // 这里紧跟一行提示和一个按钮，点了直接进复核流水线。
            if (ui.weakPairs > 0) {
                Text(
                    text = "其中 ${ui.weakPairs} 对是靠序号 / 顺序猜的：没有内容和拍摄时间" +
                        "做凭证，只是编号或排位碰巧对上，最可能改错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                PrimaryRow("复核这 ${ui.weakPairs} 对") { vm.reviewSuspects() }
            }
            if (manual > 0 || ui.unlinked.isNotEmpty()) {
                PrimaryRow(stringResource(R.string.action_reset_pair)) { vm.resetPairingOverrides() }
            }
        }

        // 工作现场：手工配对的成果必须比进程活得久。
        //
        // 之前这里什么都没有，是因为手工配对只活在内存里 —— 关一次应用就没了，
        // 连"要不要丢掉"都没得选。现在它按左右目录存档，所以也必须给出丢弃的出口：
        // 一个只能自动恢复、不能主动清除的持久化，是比没有持久化更烦人的东西。
        val scene = ui.sessionKey?.let { k -> ui.sessionScenes.firstOrNull { it.fingerprint == k } }
        ActionGroup(
            title = "工作现场",
            desc = "手工指定的配对会按左右目录自动保存，下次打开这个目录对时自动恢复" +
                "（文件已被删掉或改名的条目会被自动丢弃）。",
            status = if (ui.sessionScenes.isEmpty()) {
                null
            } else {
                "已存 ${ui.sessionScenes.size} 个现场"
            },
        ) {
            if (scene != null && scene.pairCount > 0) {
                Text(
                    text = "本目录对已保存 ${scene.pairCount} 对手工配对。" +
                        "若本次没能恢复，多半是这些文件已被改动。" +
                        // 撤回凭据也一起存档了 —— 这件事不说出来，
                        // 用户不会想到"重启之后还能撤回上一次的操作"
                        if (scene.credentials.isEmpty()) {
                            ""
                        } else {
                            "另有 ${scene.credentials.size} 步操作连同存档一起保留，重启后仍可撤回。"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryRow("丢弃本目录对保存的方案（${scene.pairCount} 对）") {
                    vm.clearSavedSession()
                }
            } else if (ui.sessionScenes.isNotEmpty()) {
                Text(
                    text = "本目录对当前没有存档，其他目录对还有 ${ui.sessionScenes.size} 个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryRow("清空所有保存的方案") { vm.clearAllSavedSessions() }
            } else {
                Text(
                    text = "还没有保存的方案。手动配对（勾选两张后点「配对」）之后会自动存下来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ActionGroup(
            title = "目录体检",
            desc = "统计格式分布，找出 0 字节的坏文件、超大文件、不能改名的和文件名有问题的。",
        ) {
            TwoButtons(
                left = "体检左栏",
                right = "体检右栏",
                leftEnabled = ui.left.items.isNotEmpty(),
                rightEnabled = ui.right.items.isNotEmpty(),
                onLeft = { onStats(Side.LEFT) },
                onRight = { onStats(Side.RIGHT) },
            )
        }

        ActionGroup(
            title = stringResource(R.string.history_title),
            desc = "每次改名都会记录，可导出 CSV 对照表，需要时整体还原。",
        ) {
            PrimaryRow("查看历史") { vm.requestHistory() }
        }
    }
}
