package com.yuanbao.pairrename.ui.dialogs

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.Organizer

/**
 * 按拍摄日期归档的预览。
 *
 * 移动文件比改名更难撤销，所以这里是**必须先看清再执行**的操作：
 * 列出每个文件从哪来到哪去，确认后再真正移动。
 */
@Composable
fun ArchiveDialog(
    rows: List<Organizer.MoveRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 目标目录去重统计，一眼看出会生成几个文件夹
    val dirs = rows.map { it.toPath.substringBeforeLast('/') }.distinct().size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.archive_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "将移动 ${rows.size} 个文件到 $dirs 个日期目录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                rows.take(12).forEach { r ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            text = r.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "→ ${r.toPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rows.size > 12) {
                    Text(
                        text = "… 其余 ${rows.size - 12} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "在同一存储内移动是瞬时的；跨存储会拷贝后再删原文件，" +
                            "拷贝失败会保留原文件，不会丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
