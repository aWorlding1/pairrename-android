package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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

/**
 * 操作记录（撤销栈可视化）。
 *
 * 存在的理由：做完几步批量操作后，用户往往记不清「刚才改了什么」。
 * 看不见就只能盲撤 —— 而盲撤正是「改错文件」的常见入口。
 * 这里把每一步列出来，点一条就一次性回退到那一步之前。
 */
@Composable
fun UndoListDialog(
    /** (栈内序号, 标签)，最新的在前。 */
    labels: List<Pair<Int, String>>,
    onUndoTo: (Int) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 因超上限被丢弃的条目数 / 总步数。
     *
     * 上限本身是刻意的取舍（否则 64 次 5000 步的批量能吃掉几百 MB），
     * 但**丢包不能静默** —— 那些操作已经落到磁盘上了，只是从这里撤不回来。
     * 不说的话，用户会以为"操作记录就是全部"，从而漏掉本该撤销的那几步。
     */
    droppedEntries: Int = 0,
    droppedSteps: Int = 0,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.History, null) },
        title = { Text(stringResource(R.string.undo_list_title)) },
        text = {
            if (labels.isEmpty()) {
                Text(
                    text = stringResource(R.string.undo_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.undo_list_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // 最新的在最上面 —— 用户最关心的就是刚做的那步
                    labels.forEachIndexed { display, (index, label) ->
                        Surface(
                            tonalElevation = if (display == 0) 3.dp else 0.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { onUndoTo(index) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = label.ifBlank { "改名" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.action_undo_to),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    // 被上限丢掉的那些放在列表**末尾**：列表是最新在前，
                    // 越靠下越早，所以这条提示正好落在它描述的位置上。
                    if (droppedEntries > 0) {
                        Text(
                            text = stringResource(
                                R.string.undo_dropped_hint, droppedEntries, droppedSteps,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}
