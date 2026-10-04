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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.TrashRepository
import com.yuanbao.pairrename.util.Naming

/**
 * 回收站。
 *
 * 删除默认只把文件移到这里，可随时按「恢复」放回原路径 ——
 * 批量操作几百个文件时，这是唯一能让人放心按下去的设计。
 */
@Composable
fun TrashDialog(
    items: List<TrashRepository.TrashedItem>,
    onDismiss: () -> Unit,
    onRestore: (TrashRepository.TrashedItem) -> Unit,
    onEmpty: () -> Unit,
) {
    // 清空回收站是永久删除、不可恢复，必须二次确认
    var confirmEmpty by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.trash_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.trash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${items.size} 个文件 · 共 ${
                                    Naming.formatSize(items.sumOf { it.size })
                                }",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    items.take(15).forEach { t ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = t.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRestore(t) }) {
                                    Text(stringResource(R.string.action_restore_one))
                                }
                            }
                            Text(
                                text = "原位置：${t.originalPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${Naming.formatSize(t.size)} · ${Naming.formatTime(t.time)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (items.size > 15) {
                        Text(
                            text = "… 其余 ${items.size - 15} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (items.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        if (confirmEmpty) {
                            Text(
                                text = "确定永久删除这 ${items.size} 个？此操作不可恢复。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { confirmEmpty = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                            TextButton(onClick = {
                                confirmEmpty = false
                                onEmpty()
                            }) {
                                Text("确认清空", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { confirmEmpty = true }) {
                                Text(
                                    stringResource(R.string.action_empty_trash),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}
