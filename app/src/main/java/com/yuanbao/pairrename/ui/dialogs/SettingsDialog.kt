package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.MediaStoreMeta
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.CardSize
import com.yuanbao.pairrename.model.ConflictPolicy
import com.yuanbao.pairrename.model.ExtensionPolicy
import com.yuanbao.pairrename.model.NumberingStyle
import com.yuanbao.pairrename.model.SameFolderMode
import com.yuanbao.pairrename.model.SortOrder
import com.yuanbao.pairrename.model.ThemeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onChange: (AppSettings) -> Unit,
    /** 是否已有「管理所有文件」权限。 */
    allFilesGranted: Boolean = false,
    /** 跳转系统设置页申请权限；null 表示当前系统不支持。 */
    onGrantAllFiles: (() -> Unit)? = null,
    /** 是否已读到 EXIF 时间（没有数据时不显示开关）。 */
    hasExif: Boolean = false,
    /** 显示重组计数叠层（性能诊断用）。 */
    showPerf: Boolean = false,
    onShowPerfChange: (Boolean) -> Unit = {},
    useExif: Boolean = false,
    onUseExifChange: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Group(stringResource(R.string.set_conflict)) {
                    Chip(stringResource(R.string.auto_rename), settings.conflictPolicy == ConflictPolicy.AUTO_RENAME) {
                        onChange(settings.copy(conflictPolicy = ConflictPolicy.AUTO_RENAME))
                    }
                    Text(
                        stringResource(R.string.conflict_overwrite_hint),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Chip(stringResource(R.string.skip), settings.conflictPolicy == ConflictPolicy.SKIP) {
                        onChange(settings.copy(conflictPolicy = ConflictPolicy.SKIP))
                    }
                    Chip("每次询问", settings.conflictPolicy == ConflictPolicy.ASK) {
                        onChange(settings.copy(conflictPolicy = ConflictPolicy.ASK))
                    }
                }

                Group(stringResource(R.string.set_numbering)) {
                    Chip("name (1).jpg", settings.numbering == NumberingStyle.PARENTHESES) {
                        onChange(settings.copy(numbering = NumberingStyle.PARENTHESES))
                    }
                    Chip("name_1.jpg", settings.numbering == NumberingStyle.UNDERSCORE) {
                        onChange(settings.copy(numbering = NumberingStyle.UNDERSCORE))
                    }
                }

                Group(stringResource(R.string.set_extension)) {
                    Chip("保留目标扩展名", settings.extensionPolicy == ExtensionPolicy.KEEP_TARGET) {
                        onChange(settings.copy(extensionPolicy = ExtensionPolicy.KEEP_TARGET))
                    }
                    Chip("使用来源扩展名", settings.extensionPolicy == ExtensionPolicy.USE_SOURCE) {
                        onChange(settings.copy(extensionPolicy = ExtensionPolicy.USE_SOURCE))
                    }
                }

                Group(stringResource(R.string.set_same_folder)) {
                    Chip("交换两个名字", settings.sameFolderMode == SameFolderMode.SWAP) {
                        onChange(settings.copy(sameFolderMode = SameFolderMode.SWAP))
                    }
                    Chip("来源自动编号让位", settings.sameFolderMode == SameFolderMode.SHIFT) {
                        onChange(settings.copy(sameFolderMode = SameFolderMode.SHIFT))
                    }
                }

                Group(stringResource(R.string.set_sort)) {
                    Chip("文件名", settings.sortOrder == SortOrder.NAME) {
                        onChange(settings.copy(sortOrder = SortOrder.NAME))
                    }
                    Chip("时间新→旧", settings.sortOrder == SortOrder.DATE_DESC) {
                        onChange(settings.copy(sortOrder = SortOrder.DATE_DESC))
                    }
                    Chip("体积大→小", settings.sortOrder == SortOrder.SIZE_DESC) {
                        onChange(settings.copy(sortOrder = SortOrder.SIZE_DESC))
                    }
                    Chip("拍摄时间", settings.sortOrder == SortOrder.TAKEN) {
                        onChange(settings.copy(sortOrder = SortOrder.TAKEN))
                    }
                }

                Group(stringResource(R.string.set_theme)) {
                    Chip(stringResource(R.string.theme_system), settings.themeMode == ThemeMode.SYSTEM) {
                        onChange(settings.copy(themeMode = ThemeMode.SYSTEM))
                    }
                    Chip(stringResource(R.string.theme_light), settings.themeMode == ThemeMode.LIGHT) {
                        onChange(settings.copy(themeMode = ThemeMode.LIGHT))
                    }
                    Chip(stringResource(R.string.theme_dark), settings.themeMode == ThemeMode.DARK) {
                        onChange(settings.copy(themeMode = ThemeMode.DARK))
                    }
                }

                Group(stringResource(R.string.set_card)) {
                    Chip(stringResource(R.string.card_small), settings.cardSize == CardSize.SMALL) {
                        onChange(settings.copy(cardSize = CardSize.SMALL))
                    }
                    Chip(stringResource(R.string.card_medium), settings.cardSize == CardSize.MEDIUM) {
                        onChange(settings.copy(cardSize = CardSize.MEDIUM))
                    }
                    Chip(stringResource(R.string.card_large), settings.cardSize == CardSize.LARGE) {
                        onChange(settings.copy(cardSize = CardSize.LARGE))
                    }
                }

                Group(stringResource(R.string.set_number_opts)) {
                    Stepper(stringResource(R.string.batch_start), settings.startIndex, 0..9999) {
                        onChange(settings.copy(startIndex = it))
                    }
                    Stepper(stringResource(R.string.batch_digits), settings.digits, 1..8) {
                        onChange(settings.copy(digits = it))
                    }
                }

                Text(
                    text = stringResource(R.string.set_template),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = settings.prefix,
                    onValueChange = { onChange(settings.copy(prefix = it)) },
                    label = { Text("前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.suffix,
                    onValueChange = { onChange(settings.copy(suffix = it)) },
                    label = { Text("后缀") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )

                RowSwitch("扫描后自动读取拍摄时间", settings.autoExif ?: MediaStoreMeta.hasAllFilesAccess()) {
                    onChange(settings.copy(autoExif = it))
                }
                RowSwitch("显示性能叠层（诊断卡顿用）", showPerf) { onShowPerfChange(it) }
                if (hasExif) {
                    RowSwitch(stringResource(R.string.set_use_exif), useExif) {
                        onUseExifChange(it)
                    }
                }
                RowSwitch(stringResource(R.string.set_recursive), settings.recursive) {
                    onChange(settings.copy(recursive = it))
                }
                if (settings.recursive) {
                    Text(
                        text = "会扫描最多 ${com.yuanbao.pairrename.data.DocsRepository.MAX_DEPTH} 层子文件夹，" +
                            "单栏最多 ${com.yuanbao.pairrename.data.DocsRepository.MAX_ITEMS} 张。顶层有图时建议关掉以加快扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.set_allfiles),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = when {
                                onGrantAllFiles == null -> stringResource(R.string.set_allfiles_unsupported)
                                allFilesGranted -> stringResource(R.string.set_allfiles_on)
                                else -> stringResource(R.string.set_allfiles_off)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allFilesGranted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (onGrantAllFiles != null && !allFilesGranted) {
                        TextButton(onClick = onGrantAllFiles) { Text("去开启") }
                    }
                }
                Text(
                    text = stringResource(R.string.set_allfiles_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                RowSwitch(stringResource(R.string.set_confirm), settings.confirmBeforeApply) {
                    onChange(settings.copy(confirmBeforeApply = it))
                }
                RowSwitch(stringResource(R.string.set_confirm_batch), settings.confirmBatch) {
                    onChange(settings.copy(confirmBatch = it))
                }
                RowSwitch(stringResource(R.string.set_meta), settings.showMeta) {
                    onChange(settings.copy(showMeta = it))
                }
                RowSwitch(stringResource(R.string.set_bounds), settings.readBounds) {
                    onChange(settings.copy(readBounds = it))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 6.dp))
        IconButton(
            onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
            modifier = Modifier.padding(0.dp),
        ) {
            Icon(Icons.Default.Remove, contentDescription = null)
        }
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(range.last)) }) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
    }
}

@Composable
private fun RowSwitch(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
