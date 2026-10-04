package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.SyncDirection

/**
 * 一键流水线。
 *
 * 手动路径是「读 EXIF → 按内容校验 → 按配对统一」三步三堆点击，
 * 而这三步几乎每次都要做。这里合成一键。
 *
 * 内容校验默认关闭 —— 它要读文件内容，明显更慢，
 * 只在序号/时间都对不上时才值得开。
 */
@Composable
fun PipelineDialog(
    /** 是否已经读过 EXIF（读过的话默认不再重复读）。 */
    hasExif: Boolean,
    /** 是否已经做过内容校验。 */
    hasContent: Boolean,
    onDismiss: () -> Unit,
    onRun: (readExif: Boolean, verifyContent: Boolean, syncAfter: Boolean, direction: SyncDirection) -> Unit,
) {
    var readExif by remember { mutableStateOf(!hasExif) }
    var verifyContent by remember { mutableStateOf(false) }
    var syncAfter by remember { mutableStateOf(true) }
    var direction by remember { mutableStateOf(SyncDirection.LEFT_TO_RIGHT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pipeline_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pipeline_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                StepRow(
                    text = stringResource(R.string.pipeline_exif) +
                        if (hasExif) "（已有数据）" else "",
                    checked = readExif,
                    onCheckedChange = { readExif = it },
                )
                StepRow(
                    text = stringResource(R.string.pipeline_content) +
                        if (hasContent) "（已校验）" else "",
                    checked = verifyContent,
                    onCheckedChange = { verifyContent = it },
                )
                StepRow(
                    text = stringResource(R.string.pipeline_sync),
                    checked = syncAfter,
                    onCheckedChange = { syncAfter = it },
                )

                if (syncAfter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        TwoChoice(
                            text = stringResource(R.string.align_left_to_right),
                            selected = direction == SyncDirection.LEFT_TO_RIGHT,
                            modifier = Modifier.weight(1f),
                        ) { direction = SyncDirection.LEFT_TO_RIGHT }
                        TwoChoice(
                            text = stringResource(R.string.align_right_to_left),
                            selected = direction == SyncDirection.RIGHT_TO_LEFT,
                            modifier = Modifier.weight(1f),
                        ) { direction = SyncDirection.RIGHT_TO_LEFT }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // 三个都不选就没有意义了
                enabled = readExif || verifyContent || syncAfter,
                onClick = { onRun(readExif, verifyContent, syncAfter, direction) },
            ) { Text(stringResource(R.string.start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun StepRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TwoChoice(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) { Text(text) }
}
