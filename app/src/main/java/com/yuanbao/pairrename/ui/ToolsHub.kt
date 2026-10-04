package com.yuanbao.pairrename.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.Screen
import com.yuanbao.pairrename.util.Advice
import com.yuanbao.pairrename.util.AdviceAction
import com.yuanbao.pairrename.util.Naming
import com.yuanbao.pairrename.vm.MainViewModel
import com.yuanbao.pairrename.vm.UiState

/**
 * 工具首页 = 二级菜单入口。
 *
 * 之前所有功能堆在一个长列表里，主次不分、要滚很久。
 * 现在这里只放三个分组，点进去才是具体操作。
 */
@Composable
fun ToolsHub(
    vm: MainViewModel,
    ui: UiState,
    onOpen: (Screen) -> Unit,
    /** 打开诊断面板。 */
    onDiagnose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = ui.matched.size / 2 - ui.synced.size / 2
    val bothReady = ui.left.items.isNotEmpty() && ui.right.items.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 最该做的事放最上面，一眼看到
        if (bothReady && pending > 0) {
            Surface(
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "还有 $pending 对没统一",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "下一步：进入「改名」按配对统一",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // 智能建议放在最上面：功能多了反而不知道该点哪个，
        // 这里直接根据当前数据告诉用户下一步做什么
        if (ui.advices.isNotEmpty()) {
            AdviceCard(
                advices = ui.advices,
                onAction = { act -> vm.runAdvice(act) },
            )
        }

        // 数据概览：数字一眼看全，不用进二级页才知道现在什么情况
        val all = ui.left.items + ui.right.items
        if (all.isNotEmpty()) {
            OverviewCard(
                left = ui.left.items.size,
                right = ui.right.items.size,
                paired = ui.matched.size / 2,
                dirty = all.count { Naming.isDirtyName(it.displayName) },
                noExif = all.count { it.takenAt <= 0 },
            )
        }

        MenuGroup(
            icon = Icons.Default.DriveFileRenameOutline,
            title = "改名",
            desc = "按配对统一、批量改名、顺序对齐、按拍摄时间命名",
            hint = if (pending > 0) "$pending 对待处理" else null,
            onClick = { onOpen(Screen.RENAME) },
        )
        MenuGroup(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            title = "整理",
            desc = "按日期归档、查重、大文件、回收站、复制",
            onClick = { onOpen(Screen.ORGANIZE) },
        )
        MenuGroup(
            icon = Icons.Default.Search,
            title = "识别",
            desc = "读取拍摄时间、按内容校验、配对统计、历史",
            hint = if (ui.contentKeys.isNotEmpty()) "已校验" else null,
            onClick = { onOpen(Screen.ANALYZE) },
        )

        // 诊断放在最后：日常用不到，但排查卡顿时它是关键
        MenuGroup(
            icon = Icons.Default.Info,
            title = stringResource(R.string.action_diagnostics),
            desc = "扫描耗时、图片数、权限、配对状态。卡顿时把这一屏发给我。",
            hint = "${ui.lastScanMs}ms",
            onClick = onDiagnose,
        )
    }
}

@Composable
private fun MenuGroup(
    icon: ImageVector,
    title: String,
    desc: String,
    hint: String? = null,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 一眼看清当前数据状况，尤其是"有多少需要收拾"。 */
@Composable
private fun OverviewCard(
    left: Int,
    right: Int,
    paired: Int,
    dirty: Int,
    noExif: Int,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "数据概览",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                Stat("左栏", left.toString())
                Stat("右栏", right.toString())
                Stat("已配对", paired.toString())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                // 这两个是"还需要处理"的量，用主色突出
                Stat("待清理", dirty.toString(), highlight = dirty > 0)
                Stat("无时间", noExif.toString(), highlight = noExif > 0)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 建议卡：只显示最该做的那条，其余折叠。
 *
 * 全列出来等于没建议 —— 用户还是会犯选择困难。
 * 所以默认只展示优先级最高的，想看更多再展开。
 */
@Composable
private fun AdviceCard(
    advices: List<Advice>,
    onAction: (AdviceAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val top = advices.first()
    val rest = advices.drop(1)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lightbulb,
                    null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "建议",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            AdviceItem(advice = top, onAction = onAction)

            if (rest.isNotEmpty()) {
                if (expanded) {
                    rest.forEach {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        AdviceItem(advice = it, onAction = onAction)
                    }
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(if (expanded) "收起" else "还有 ${rest.size} 条建议")
                }
            }
        }
    }
}

@Composable
private fun AdviceItem(
    advice: Advice,
    onAction: (AdviceAction) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = advice.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = advice.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (advice.action != AdviceAction.NONE) {
            TextButton(
                onClick = { onAction(advice.action) },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(advice.action.label())
            }
        }
    }
}

@Composable
private fun AdviceAction.label(): String = when (this) {
    AdviceAction.NONE -> ""
    AdviceAction.READ_EXIF -> stringResource(R.string.action_read_exif)
    AdviceAction.VERIFY_CONTENT -> "按内容校验"
    AdviceAction.ALIGN -> "去对齐"
    AdviceAction.SYNC -> "去统一"
    AdviceAction.PIPELINE -> "跑流水线"
    AdviceAction.FIX_NAMES -> "去清理"
    AdviceAction.PICK_FOLDER -> "去选择"
    AdviceAction.REVIEW_SUSPECT -> "去复核"
    // 与「去复核」区分开：那条是"依据不硬，看看有没有事"，
    // 这条是"和拍摄时间打架了，去核对一下"。两条建议可能同屏，文案必须不一样。
    AdviceAction.REVIEW_CONFLICT -> "去核对"
    // 「换回」是动词、且明确说了依据（拍摄时间），避免被当成又一次"去核对"；
    // 「撤回」必须说清撤的是什么，否则用户不敢点 —— 这是唯一的退路入口。
    AdviceAction.SWAP_CONFLICT -> "照时间换回"
    AdviceAction.UNDO_SWAP -> "撤回重配"
    // 「一键配上」要说清证据是什么（尺寸和体积），否则用户不知道按了什么。
    // 与「去对齐」区分：那条是"序号错位，滑一滑试试"，这条是"证据已经足够，直接配"。
    AdviceAction.LINK_SIZE -> "一键配上"
    // 「搬过去」说清了动作方向（换到对的那张上），与「照时间换回」区分：
    // 换回是两对互换、双方都还在配对里；搬过去是这一张换对象、原来那张空出来。
    AdviceAction.SIDE_MOVE -> "搬过去"
    AdviceAction.UNDO_SIDE_MOVE -> "撤回搬移"
    // 「整批重配」说清动的是一批（与「搬过去」这种单张动作区分开）
    // 用词与状态条、对比面板、预览对话框完全一致 —— 同一个动作在一屏里叫两个名字，
    // 用户会当成两件事（这个项目已经因为这种"内部打架"改过好几轮）。
    AdviceAction.REALIGN_TIME -> "整批重配"
    AdviceAction.UNDO_REALIGN -> "撤回整批重配"
}
