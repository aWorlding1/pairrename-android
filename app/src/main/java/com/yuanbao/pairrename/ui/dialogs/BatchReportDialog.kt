package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.BatchReport
import com.yuanbao.pairrename.model.IssueKind

/**
 * 批量执行结果报告。
 *
 * 存在的理由：批量执行**只弹一句 snackbar** 是不够的 ——
 * "已改 195 项"听起来一切顺利，而那 5 个没成功的就这么消失了。
 * 用户不知道是哪 5 张、为什么、更不知道能重试。
 *
 * 所以这里按**可行动性**分三段（不支持改名 / 名字冲突 / 真失败），
 * 每段给出该做什么，并只对"重试有意义"的那类提供重试按钮。
 * 把不能重试的也给个重试按钮，只会让用户点两次得到同样结果。
 */
@Composable
fun BatchReportDialog(
    report: BatchReport,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(stringResource(R.string.report_title, report.title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ---- 总览：先给"成了多少"，再给"没成多少" ----
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.report_changed, report.changed),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (report.nothingChanged) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (report.unchanged > 0) {
                            SummaryLine(stringResource(R.string.report_unchanged, report.unchanged))
                        }
                        // 熔断 / 手动停止必须说清是"为什么停"，
                        // 否则用户只会觉得程序自己崩了
                        if (report.aborted) {
                            SummaryLine(
                                stringResource(R.string.report_aborted, report.issues.size),
                                error = true,
                            )
                        } else if (report.stopped) {
                            SummaryLine(stringResource(R.string.report_stopped))
                        }
                        // 中止时三项之和会小于总数，必须把差额说出来，
                        // 否则用户按加总核对会以为报告漏了东西
                        if (report.notAttempted > 0) {
                            SummaryLine(
                                stringResource(R.string.report_not_attempted, report.notAttempted),
                            )
                        }
                    }
                }

                // ---- 三段问题清单 ----
                IssueSection(
                    title = stringResource(R.string.report_readonly, report.readonly),
                    hint = stringResource(R.string.report_hint_readonly),
                    kind = IssueKind.READONLY,
                    report = report,
                )
                IssueSection(
                    title = stringResource(R.string.report_conflict, report.conflict),
                    hint = stringResource(R.string.report_hint_conflict),
                    kind = IssueKind.CONFLICT,
                    report = report,
                )
                IssueSection(
                    title = stringResource(R.string.report_failed, report.failed),
                    hint = stringResource(R.string.report_hint_failed),
                    kind = IssueKind.FAILED,
                    report = report,
                )
            }
        },
        confirmButton = {
            // 只有真失败才给重试 —— 只读和冲突重试一次还是同样结果
            val retryable = report.retryRows.size
            if (retryable > 0) {
                TextButton(onClick = onRetryFailed) {
                    Text(stringResource(R.string.action_retry_failed, retryable))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = {
            if (report.retryRows.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
    )
}

@Composable
private fun SummaryLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 一段问题清单。数量为 0 时整段不渲染 ——
 * 空标题（"失败 0 项"）只会让用户以为漏看了什么。
 */
@Composable
private fun IssueSection(
    title: String,
    hint: String,
    kind: IssueKind,
    report: BatchReport,
) {
    val items = report.issues.filter { it.kind == kind }
    if (items.isEmpty()) return

    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
    items.forEach { issue ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = issue.oldName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (issue.newName != issue.oldName) {
                    Text(
                        // 「想改成什么」比「现在叫什么」更能说明这条为什么没成
                        text = stringResource(R.string.report_col_want) + " " + issue.newName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (issue.detail.isNotBlank()) {
                    Text(
                        text = issue.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
