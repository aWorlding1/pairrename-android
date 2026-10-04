package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.util.TimeRealign

/**
 * 「整批重配」的逐条预览。
 *
 * ## 为什么要有这一层
 *
 * 互换 / 搬移只动一对（最多两对），错了一眼能看出来。整批重配动的是**一整串**：
 * 用户按下按钮，眼前这一对的搭档换了，别处可能还有七八对的搭档也一起换了。
 * 在此之前只剩两条信息 —— 状态条上一句"能重配 N 对"，加上对比面板里
 * "这一对会改成 X"。前者只有一个数，后者只管眼前这一张。
 *
 * 这个项目和另外三处批量操作（改名方案确认 / 归档计划 / 顺序对齐）一样，
 * 破坏性批量操作一律先摊开再确认；整批重配是唯一漏掉的一处，这里补上。
 *
 * ## 只列**会变**的
 *
 * [TimeRealign.pairs] 里每一条都已经过了引擎的"严格变好"闸（新时间差 < 旧时间差），
 * 等价于"确实换了对象" —— 没换成的早被 `continue` 掉了。所以这里不需要再算一遍
 * "变了没"，逐条列出来就是全部变动，不多不少。
 *
 * @param plan    重配方案（发出瞬间的快照；确认时以 vm 里的最新方案为准）。
 * @param byKey   key → 条目，用来把内部 key 翻成用户看得见的文件名。
 * @param partner 当前 key → 对方 key，用来标出"原来是哪一张"。
 */
@Composable
fun RealignPreviewDialog(
    plan: TimeRealign,
    byKey: Map<String, ImageItem>,
    partner: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.realign_preview_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.realign_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Text(
                    stringResource(
                        R.string.realign_preview_pairs,
                        plan.pairs.size,
                        plan.beforeConflicts,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )

                // 逐条摊开。上限只是防"整柜子照片"这种极端规模把对话框撑成一张无限长列表；
                // 上限之内的每一对都列全 —— 预览截断一半，等于又回到"不知道会改什么"。
                plan.pairs.take(PREVIEW_MAX).forEach { (lKey, rKey) ->
                    val left = byKey[lKey]
                    val right = byKey[rKey]
                    Text(
                        text = "${left?.displayName ?: lKey}  ↔  ${right?.displayName ?: rKey}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    // "原来是 X" 不是可有可无的：换搭档的另一面就是"原来那个搭档被让出来了"，
                    // 而用户往往正是靠旧名字认出这是自己刚才在面板里看到的那一对。
                    val beforeName = partner[lKey]?.let { byKey[it]?.displayName }
                    if (beforeName != null) {
                        Text(
                            text = stringResource(R.string.realign_preview_before, beforeName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (plan.pairs.size > PREVIEW_MAX) {
                    Text(
                        text = stringResource(
                            R.string.realign_preview_more,
                            plan.pairs.size - PREVIEW_MAX,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                val freedCount = plan.freedLeft.size + plan.freedRight.size
                if (freedCount > 0) {
                    Text(
                        text = stringResource(R.string.realign_preview_freed_title, freedCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // 左右分开列：它们回到的是各自的未配对区，标出来才知道该去哪一栏找。
                    plan.freedLeft.forEach { key ->
                        FreedRow(R.string.realign_preview_freed_left, byKey[key]?.displayName ?: key)
                    }
                    plan.freedRight.forEach { key ->
                        FreedRow(R.string.realign_preview_freed_right, byKey[key]?.displayName ?: key)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.realign_preview_confirm, plan.pairs.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 「回到未配对」的一行。 [textRes] 决定标"（左）"还是"（右）"。 */
@Composable
private fun FreedRow(textRes: Int, name: String) {
    Text(
        text = stringResource(textRes, name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    )
}

/** 逐条列出时的上限，超出部分只报数量。 */
private const val PREVIEW_MAX = 24
