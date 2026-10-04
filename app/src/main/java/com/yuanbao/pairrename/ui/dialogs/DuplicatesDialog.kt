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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.DuplicateFinder
import com.yuanbao.pairrename.util.Naming

/**
 * 全盘重复图片结果。
 *
 * 只做展示，不直接提供删除 —— 删全盘文件是不可逆的高危操作，
 * 让用户自己在图库里核对更稳妥。这里给出路径、份数和可释放空间。
 */
@Composable
fun DuplicatesDialog(
    groups: List<DuplicateFinder.DuplicateGroup>,
    onDismiss: () -> Unit,
    /** 把这一组里「多余的」移入回收站（保留第一个）。 */
    onTrashGroup: (DuplicateFinder.DuplicateGroup) -> Unit,
) {
    val reclaimable = DuplicateFinder.reclaimable(groups)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dup_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.dup_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.msg_dup_none),
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
                                text = "找到 ${groups.size} 组重复",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.dup_reclaim, Naming.formatSize(reclaimable)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    groups.take(10).forEach { g ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        R.string.dup_group,
                                        g.members.size,
                                        Naming.formatSize(g.size),
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            g.members.forEach { m ->
                                Text(
                                    text = if (m.path.isNotBlank()) m.path else m.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            ) {
                                Text(
                                    text = "指纹 ${com.yuanbao.pairrename.util.ContentHash.short(g.hash)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onTrashGroup(g) }) {
                                    Text(
                                        stringResource(
                                            R.string.dup_trash_group,
                                            g.members.size - 1,
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    if (groups.size > 10) {
                        Text(
                            text = "… 其余 ${groups.size - 10} 组",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        text = "点「移入回收站」只移动多余的那几张（每组保留第 1 张），" +
                            "文件不真删，可在回收站恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}
