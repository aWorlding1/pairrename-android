package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.HistoryEntry
import com.yuanbao.pairrename.util.Naming

/**
 * 改名历史面板。
 *
 * 关键点是把「撤销」与「历史」的区别讲清楚：
 * 撤销只在本次打开有效，关掉就没了；历史存在本机，隔天也能按对照表还原。
 */
@Composable
fun HistoryDialog(
    entries: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    val totalSteps = entries.sumOf { it.steps.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.history_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.history_count, entries.size, totalSteps),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    entries.take(12).forEach { e ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        ) {
                            Text(
                                text = if (e.label.isNotBlank()) e.label else stringResource(R.string.action_batch),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${e.steps.size} 项 · ${Naming.formatTime(e.time)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (entries.size > 12) {
                        Text(
                            text = "… 其余 ${entries.size - 12} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    TextButton(enabled = entries.isNotEmpty(), onClick = onExport) {
                        Text(stringResource(R.string.action_export))
                    }
                    TextButton(onClick = onRestore) {
                        Text(stringResource(R.string.action_restore))
                    }
                }
                Text(
                    text = "导出会生成 CSV 对照表（原文件名 / 新文件名），" +
                        "可用 Excel 打开存档。还原时选择这个文件即可整体改回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("清空历史") }
            }
        },
    )
}
