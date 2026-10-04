@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.util.ExtraFilter

private const val MB = 1024L * 1024

/** 体积区间预设。null 表示不限。 */
data class SizePreset(val labelRes: Int, val min: Long?, val max: Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtFilterDialog(
    /** 目录里实际出现过的扩展名（扩展名 → 数量）。 */
    exts: List<Pair<String, Int>>,
    current: ExtraFilter,
    onToggleExt: (String) -> Unit,
    onSizeRange: (Long?, Long?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(
        SizePreset(R.string.size_any, null, null),
        SizePreset(R.string.size_lt1m, null, MB),
        SizePreset(R.string.size_1to10m, MB, 10 * MB),
        SizePreset(R.string.size_gt10m, 10 * MB, null),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_filter_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (exts.isEmpty()) {
                    Text(
                        text = "当前目录里没有可筛选的类型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        exts.forEach { (ext, count) ->
                            FilterChip2(
                                text = ".$ext ($count)",
                                selected = ext in current.exts,
                                onClick = { onToggleExt(ext) },
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.ext_filter_size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    presets.forEach { p ->
                        val sel = current.minBytes == p.min && current.maxBytes == p.max
                        FilterChip2(
                            text = stringResource(p.labelRes),
                            selected = sel,
                            onClick = { onSizeRange(p.min, p.max) },
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.ext_filter_clear))
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
private fun FilterChip2(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
    )
}
