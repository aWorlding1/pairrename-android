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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.util.Batch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SequenceAlignDialog(
    left: List<ImageItem>,
    right: List<ImageItem>,
    leftNames: Set<String>,
    rightNames: Set<String>,
    settings: AppSettings,
    /** 配对引擎算出的建议偏移量，作为初值。 */
    suggestedOffset: Int = 0,
    /** 初始基准栏：true = 以左栏为准（左→右）。 */
    initialLeftToRight: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (List<PlanRow>) -> Unit,
) {
    var leftToRight by remember { mutableStateOf(initialLeftToRight) }
    var offset by remember { mutableIntStateOf(suggestedOffset.coerceAtLeast(0)) }

    val maxOffset = if (leftToRight) (right.size - 1).coerceAtLeast(0) else (left.size - 1).coerceAtLeast(0)
    val safeOffset = offset.coerceIn(0, maxOffset)

    val plan = remember(left, right, leftToRight, safeOffset, leftNames, rightNames, settings) {
        if (leftToRight) {
            Batch.buildAlignPlan(left, right, safeOffset, rightNames, settings)
        } else {
            Batch.buildAlignPlan(right, left, safeOffset, leftNames, settings)
        }
    }
    val changed = plan.count { it.changed }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.align_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.align_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Text(
                    stringResource(R.string.align_direction),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.align_left_to_right), leftToRight) {
                        leftToRight = true
                        offset = suggestedOffset.coerceAtLeast(0)
                    }
                    Chip(stringResource(R.string.align_right_to_left), !leftToRight) {
                        leftToRight = false
                        offset = suggestedOffset.coerceAtLeast(0)
                    }
                }

                Text(
                    stringResource(R.string.align_offset, safeOffset),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = safeOffset > 0, onClick = { offset = safeOffset - 1 }) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    if (maxOffset > 0) {
                        Slider(
                            value = safeOffset.toFloat(),
                            onValueChange = { offset = it.roundToInt().coerceIn(0, maxOffset) },
                            valueRange = 0f..maxOffset.toFloat(),
                            steps = (maxOffset - 1).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            "0",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
                    IconButton(enabled = safeOffset < maxOffset, onClick = { offset = safeOffset + 1 }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }

                if (plan.isEmpty()) {
                    Text(
                        stringResource(R.string.align_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.align_pairs, plan.size, changed),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    plan.take(8).forEach { row ->
                        Text(
                            text = "${row.oldName}  ←  ${row.sourceName}" +
                                if (row.conflict) " （已避让）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.conflict) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        )
                        if (row.changed) {
                            Text(
                                text = "   ⇒ ${row.newName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                    if (plan.size > 8) {
                        Text(
                            "… 其余 ${plan.size - 8} 对",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed > 0, onClick = { onApply(plan) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 供外部判断当前勾选集中于哪一栏。 */
fun dominantSide(items: List<ImageItem>): Side =
    if (items.none { it.side == Side.RIGHT }) Side.LEFT else Side.RIGHT
