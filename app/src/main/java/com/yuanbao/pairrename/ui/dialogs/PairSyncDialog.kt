package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.model.SyncDirection
import com.yuanbao.pairrename.util.Pairing

/**
 * 「按配对统一」面板 —— 本应用的主操作。
 *
 * 为什么需要它：单文件操作一次只能改一个，而「顺序对齐」要求两边
 * 严格一一对应，只要数量不等（缺几张、多几张截图）尾部就会整体错位。
 * 这里直接走配对映射，天然容忍数量不等和中间缺失。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PairSyncDialog(
    left: List<ImageItem>,
    right: List<ImageItem>,
    leftNames: Set<String>,
    rightNames: Set<String>,
    partner: Map<String, String>,
    synced: Set<String>,
    settings: AppSettings,
    /** 初始统一方向（工具页「左→右 / 右→左」两个入口会传不同值）。 */
    initialDirection: SyncDirection = SyncDirection.LEFT_TO_RIGHT,
    onDismiss: () -> Unit,
    onApply: (List<PlanRow>) -> Unit,
) {
    var direction by remember { mutableStateOf(initialDirection) }

    val plan = remember(left, right, partner, direction, leftNames, rightNames, settings) {
        Pairing.buildPairPlan(left, right, partner, direction, leftNames, rightNames, settings)
    }
    val syncedPairs = remember(synced) { synced.size / 2 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.sync_direction),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.align_left_to_right), direction == SyncDirection.LEFT_TO_RIGHT) {
                        direction = SyncDirection.LEFT_TO_RIGHT
                    }
                    Chip(stringResource(R.string.align_right_to_left), direction == SyncDirection.RIGHT_TO_LEFT) {
                        direction = SyncDirection.RIGHT_TO_LEFT
                    }
                }

                if (syncedPairs > 0) {
                    Text(
                        text = stringResource(R.string.sync_skip_done, syncedPairs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                if (plan.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sync_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sync_count, plan.size + syncedPairs, plan.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // 冲突数在循环外算一次，否则 5000 张时是 O(n²)
                    val conflictCount = plan.count { it.conflict }
                    plan.take(8).forEach { row ->
                        Text(
                            text = "${row.oldName}  ←  ${row.sourceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        Text(
                            text = "   ⇒ ${row.newName}" + if (row.conflict) "  （已避让）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.conflict) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (conflictCount > 0) {
                        Text(
                            text = "有 $conflictCount 个名字会与现有文件冲突，已自动加序号避让",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (plan.size > 8) {
                        Text(
                            text = "… 其余 ${plan.size - 8} 对",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = "改名不会重编码图片，只改文件名，不损失画质。可用顶栏撤销整体回退。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = plan.isNotEmpty(), onClick = { onApply(plan) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
