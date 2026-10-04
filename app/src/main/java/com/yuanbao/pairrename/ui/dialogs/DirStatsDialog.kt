package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.util.DirStats
import com.yuanbao.pairrename.util.Naming

/**
 * 目录体检结果。
 *
 * 先列问题再列分布 —— 用户打开它是为了找问题，
 * 把格式分布放前面会让人要滚很久才能看到重点。
 */
@Composable
fun DirStatsDialog(
    stats: DirStats,
    sideName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$sideName · 体检") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "共 ${stats.total} 张 · ${Naming.formatSize(stats.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (stats.hasIssues) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        text = "发现 ${stats.issueCount} 处值得留意",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (stats.broken.isNotEmpty()) {
                        IssueGroup(
                            title = "疑似损坏（${stats.broken.size}）",
                            desc = "体积几乎为 0，在相册里通常是黑块，可以直接删掉。",
                            items = stats.broken,
                        )
                    }
                    if (stats.notRenamable.isNotEmpty()) {
                        IssueGroup(
                            title = "不支持改名（${stats.notRenamable.size}）",
                            desc = "这个存储不允许改名，批量操作会跳过它们。",
                            items = stats.notRenamable,
                        )
                    }
                    if (stats.dirtyNames.isNotEmpty()) {
                        IssueGroup(
                            title = "文件名有问题（${stats.dirtyNames.size}）",
                            desc = "含空格、大写扩展名或非法字符，传到电脑上可能打不开。",
                            items = stats.dirtyNames,
                        )
                    }
                    if (stats.huge.isNotEmpty()) {
                        IssueGroup(
                            title = "超大文件（${stats.huge.size}）",
                            desc = "超过 10MB，备份和传输都会很慢。",
                            items = stats.huge,
                        )
                    }
                } else {
                    Text(
                        text = "没发现明显问题。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (stats.formats.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        text = "格式分布",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    stats.formats.forEach { f ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        ) {
                            Text(
                                text = f.ext,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${f.count} 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = Naming.formatSize(f.bytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
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

@Composable
private fun IssueGroup(
    title: String,
    desc: String,
    items: List<ImageItem>,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp),
        )
        // 最多列 4 个，多了也没人看，还让对话框变得很长
        items.take(4).forEach {
            Text(
                text = "· ${it.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, top = 1.dp),
            )
        }
        if (items.size > 4) {
            Text(
                text = "… 另有 ${items.size - 4} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
