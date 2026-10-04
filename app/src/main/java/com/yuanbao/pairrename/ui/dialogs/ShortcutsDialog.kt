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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R

/**
 * 快捷键说明。
 *
 * 手机上用处有限，但接了外接键盘或在大屏上操作时，
 * 不知道有这些键就跟没有一样 —— 所以必须有个地方能查到。
 */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcuts_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                KeyRow("Ctrl / Cmd + Z", "撤销")
                KeyRow("Ctrl / Cmd + Shift + Z", "重做")
                KeyRow("Ctrl / Cmd + A", "全选当前可见项")
                KeyRow("Ctrl / Cmd + F", "聚焦搜索框")
                KeyRow("F", "循环切换过滤")
                KeyRow("Esc", "收起搜索 → 清除勾选 → 清除选中")

                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.shortcuts_mouse),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.shortcuts_mouse_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun KeyRow(keys: String, desc: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Surface(
            tonalElevation = 3.dp,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = keys,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
