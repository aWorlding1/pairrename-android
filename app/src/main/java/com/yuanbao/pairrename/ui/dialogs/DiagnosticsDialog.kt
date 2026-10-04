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
import com.yuanbao.pairrename.data.MediaStoreMeta
import com.yuanbao.pairrename.vm.UiState

/**
 * 诊断面板。
 *
 * 存在的理由：这个工程一直在「猜」卡顿原因，而猜了七轮都没猜准。
 * 与其继续猜，不如把关键状态直接显示出来 ——
 * 你把这一屏的数字发给我，我就能对症下药，而不是继续拍脑袋。
 */
@Composable
fun DiagnosticsDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    /**
     * 元信息缓存的条目数（形如「时间 1200 / 相机 800 / 路径 900 / 指纹 500」）。
     *
     * 由调用方算好传进来，而不是这里直接去问 MetaCache：
     * 对话框不该知道 ViewModel 的内部结构，而且这样它在预览 / 测试里也能单独渲染。
     */
    cacheNote: String = "",
    /**
     * 已保存的工作现场（配对方案持久化）的摘要，形如「已保存 2 个现场 · 当前 12 对」。
     *
     * 与 [cacheNote] 同样由调用方算好传进来：这个数只在"手工配对没恢复出来"
     * 时才需要排查，而它到底是"没存下"还是"存下了但被安全闸全判成失效"，
     * 两句话就分得清 —— 直接盯着这一行看。
     */
    sessionNote: String = "",
) {
    val paired = ui.matched.size / 2
    val pending = paired - ui.synced.size / 2
    val scan = ui.lastScanMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 扫描耗时是判断是否「卡在扫描」的关键
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.diag_scan),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "${scan}ms",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (scan > 3000) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        Text(
                            text = if (scan > 3000) {
                                "偏慢。若图片在 SD 卡 / 网盘 / 云同步目录，属正常现象。"
                            } else {
                                "正常范围。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                DiagRow("左栏", "${ui.left.items.size} 张${stateNote(ui.left.loading, ui.left.error)}")
                DiagRow("右栏", "${ui.right.items.size} 张${stateNote(ui.right.loading, ui.right.error)}")
                DiagRow("已配对", "$paired 对")
                DiagRow("待处理", "$pending 对")
                DiagRow(
                    stringResource(R.string.diag_perm),
                    if (MediaStoreMeta.hasAllFilesAccess()) "已授予" else "未授予",
                )
                DiagRow("内容指纹", "${ui.contentKeys.size} 个")
                DiagRow("EXIF 时间", "${countTimed(ui)} 张")
                // 缓存条目数：排查"刷新后拍摄时间怎么没了"时，这一行能直接区分
                // "根本没缓存住"（全 0）和"缓存住但没回填"（有值却仍显示 0 张）
                if (cacheNote.isNotBlank()) {
                    DiagRow("元信息缓存", cacheNote)
                }
                // 手工配对有没有存下来：这一行是"我的配对为什么没恢复"的唯一线索
                if (sessionNote.isNotBlank()) {
                    DiagRow(stringResource(R.string.diag_session), sessionNote)
                }
                DiagRow(
                    "手动干预",
                    "配对 ${ui.manualPairs.size / 2} 对 · 解除 ${ui.unlinked.size} 项",
                )
                DiagRow(
                    stringResource(R.string.diag_undo),
                    buildString {
                        append("撤销 ${ui.undoCount} · 重做 ${ui.redoCount}")
                        // 因上限丢弃的必须露头：否则"撤销栈怎么比记忆里少了几步"
                        // 永远查不出来 —— 那几步其实已经改到磁盘上了
                        if (ui.undoDroppedEntries > 0) {
                            append(" · 已丢弃 ${ui.undoDroppedEntries} 条 / ${ui.undoDroppedSteps} 项")
                        }
                    },
                )

                Text(
                    text = stringResource(R.string.diag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

private fun stateNote(loading: Boolean, error: String?): String =
    when {
        loading -> "（刷新中）"
        error != null -> "（异常）"
        else -> ""
    }

private fun countTimed(ui: UiState): Int =
    (ui.left.items + ui.right.items).count { it.takenAt > 0 }

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
