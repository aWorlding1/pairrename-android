package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.ConflictPolicy
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.NumberingStyle
import com.yuanbao.pairrename.util.Naming

@Composable
fun RenameDialog(
    item: ImageItem,
    siblings: Set<String>,
    numbering: NumberingStyle,
    onDismiss: () -> Unit,
    onConfirm: (base: String, ext: String) -> Unit,
) {
    val initial = remember(item) { Naming.splitExt(item.displayName) }
    var baseText by remember(item) { mutableStateOf(initial.first) }
    var extText by remember(item) { mutableStateOf(initial.second) }

    // 预留扩展名长度，保证预览与 commitManualRename 的截断结果一致
    val finalBase = Naming.sanitize(baseText, reserve = extText.length + 1)
    val full = Naming.join(finalBase, extText)
    val conflict = siblings.any { it.equals(full, ignoreCase = true) }
    val finalName = if (conflict) Naming.resolveConflict(finalBase, extText, siblings, numbering) else full

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = baseText,
                    onValueChange = { baseText = it },
                    label = { Text(stringResource(R.string.rename_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = extText,
                    onValueChange = { extText = it.replace(".", "") },
                    label = { Text(stringResource(R.string.rename_ext_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = if (conflict) {
                        "“$full” 已存在，将自动改为：$finalName"
                    } else {
                        "将改名为：$finalName"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(finalBase, extText) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun ConfirmApplyDialog(
    fromName: String,
    toName: String,
    preview: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = { Text("把“$toName”改名为“$preview”？\n（名字来源：$fromName）") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 冲突选择（自动改名 / 跳过）。覆盖在冲突文件具备安全备份与撤销前暂不可用。
 */
@Composable
fun AskConflictDialog(
    suggested: String,
    conflictName: String,
    onDismiss: () -> Unit,
    onChoose: (ConflictPolicy) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_body, conflictName))
                Text(
                    stringResource(R.string.conflict_suggest, suggested),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    stringResource(R.string.conflict_overwrite_hint),
                    style = MaterialTheme.typography.bodySmall,
                    // 后果说明跟覆盖按钮同一个颜色，用户不用来回扫视
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(ConflictPolicy.AUTO_RENAME) }) {
                Text(stringResource(R.string.auto_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(ConflictPolicy.SKIP) }) {
                Text(stringResource(R.string.skip))
            }
        },
    )
}
