@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.BatchMode
import com.yuanbao.pairrename.util.Naming
import com.yuanbao.pairrename.model.BatchParams
import com.yuanbao.pairrename.model.CaseOp
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.util.Batch
import com.yuanbao.pairrename.model.BatchTemplate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchRenameDialog(
    items: List<ImageItem>,
    existing: Set<String>,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onApply: (List<PlanRow>) -> Unit,
    /** 已保存的模板。 */
    templates: List<BatchTemplate> = emptyList(),
    /** 存为模板，传出当前模式与参数。 */
    onSaveTemplate: (String, BatchMode, BatchParams) -> Unit = { _, _, _ -> },
    /** 套用模板。 */
    onApplyTemplate: (BatchTemplate) -> Unit = {},
    /** 删除模板。 */
    onRemoveTemplate: (Long) -> Unit = {},
    /** 导出改名清单 CSV（几百个文件改错时靠它对回去）。 */
    onExportPlan: (String) -> Unit = {},
    /** 初始模式（智能建议跳转过来时指定）。 */
    initialMode: BatchMode = BatchMode.NUMBER,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var preset by remember { mutableStateOf<Naming.Preset?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var params by remember {
        mutableStateOf(
            BatchParams(
                baseName = "IMG",
                startIndex = settings.startIndex,
                digits = settings.digits,
            ),
        )
    }

    // preset 必须在 key 里：否则切换命名预设后预览纹丝不动，
    // 用户会以为"选了没反应"，而实际上执行时会生效 ——
    // 这种"预览与执行不一致"最要命，用户是照着预览决定要不要执行的。
    val plan = remember(items, mode, params, existing, settings, preset) {
        Batch.buildBatchPlan(items, mode, params, existing, settings, preset)
    }
    val changed = plan.count { it.changed }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.batch_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.batch_number), mode == BatchMode.NUMBER) {
                        mode = BatchMode.NUMBER
                    }
                    Chip(stringResource(R.string.batch_replace), mode == BatchMode.REPLACE) {
                        mode = BatchMode.REPLACE
                    }
                    Chip(stringResource(R.string.batch_affix), mode == BatchMode.AFFIX) {
                        mode = BatchMode.AFFIX
                    }
                    Chip(stringResource(R.string.batch_case), mode == BatchMode.CASE) {
                        mode = BatchMode.CASE
                    }
                    Chip(stringResource(R.string.batch_trim), mode == BatchMode.TRIM) {
                        mode = BatchMode.TRIM
                    }
                    Chip(stringResource(R.string.batch_regex), mode == BatchMode.REGEX) {
                        mode = BatchMode.REGEX
                    }
                    Chip(stringResource(R.string.batch_substr), mode == BatchMode.SUBSTR) {
                        mode = BatchMode.SUBSTR
                    }
                    Chip(stringResource(R.string.batch_insert), mode == BatchMode.INSERT) {
                        mode = BatchMode.INSERT
                    }
                    Chip(stringResource(R.string.batch_delete), mode == BatchMode.DELETE) {
                        mode = BatchMode.DELETE
                    }
                    Chip(stringResource(R.string.batch_fix), mode == BatchMode.FIX) {
                        mode = BatchMode.FIX
                    }
                }

                when (mode) {
                    BatchMode.NUMBER -> {
                        // 命名预设：日期、相机+序号这些常用规则一键套用，
                        // 不用每次手敲 baseName
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Chip(stringResource(R.string.preset_datetime), preset == Naming.Preset.DATETIME) {
                                preset = if (preset == Naming.Preset.DATETIME) null else Naming.Preset.DATETIME
                            }
                            Chip(stringResource(R.string.preset_date), preset == Naming.Preset.DATE) {
                                preset = if (preset == Naming.Preset.DATE) null else Naming.Preset.DATE
                            }
                            Chip(stringResource(R.string.preset_month), preset == Naming.Preset.YEAR_MONTH) {
                                preset = if (preset == Naming.Preset.YEAR_MONTH) null else Naming.Preset.YEAR_MONTH
                            }
                            Chip(stringResource(R.string.preset_camera), preset == Naming.Preset.CAMERA_SEQ) {
                                preset = if (preset == Naming.Preset.CAMERA_SEQ) null else Naming.Preset.CAMERA_SEQ
                            }
                            Chip(stringResource(R.string.preset_orig), preset == Naming.Preset.ORIGINAL_SEQ) {
                                preset = if (preset == Naming.Preset.ORIGINAL_SEQ) null else Naming.Preset.ORIGINAL_SEQ
                            }
                        }
                        if (preset != null) {
                            Text(
                                text = stringResource(R.string.preset_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            Field(params.baseName, stringResource(R.string.batch_base)) {
                                params = params.copy(baseName = it)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(
                                params.startIndex.toString(),
                                stringResource(R.string.batch_start),
                                Modifier.weight(1f),
                            ) { params = params.copy(startIndex = it) }
                            NumField(
                                params.digits.toString(),
                                stringResource(R.string.batch_digits),
                                Modifier.weight(1f),
                            ) { params = params.copy(digits = it.coerceIn(1, 8)) }
                        }
                    }

                    BatchMode.REPLACE -> {
                        Field(params.find, stringResource(R.string.batch_find)) {
                            params = params.copy(find = it)
                        }
                        Field(params.replace, stringResource(R.string.batch_replace_with)) {
                            params = params.copy(replace = it)
                        }
                    }

                    BatchMode.AFFIX -> {
                        Field(params.prefix, stringResource(R.string.batch_prefix)) {
                            params = params.copy(prefix = it)
                        }
                        Field(params.suffix, stringResource(R.string.batch_suffix)) {
                            params = params.copy(suffix = it)
                        }
                    }

                    BatchMode.TRIM -> {
                        Text(
                            text = stringResource(R.string.batch_trim_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    BatchMode.FIX -> {
                        Text(
                            text = stringResource(R.string.clean_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Chip(stringResource(R.string.clean_trim), params.clean.trimSpace) {
                                params = params.copy(clean = params.clean.copy(trimSpace = !params.clean.trimSpace))
                            }
                            Chip(stringResource(R.string.clean_collapse), params.clean.collapseSpace) {
                                params = params.copy(clean = params.clean.copy(collapseSpace = !params.clean.collapseSpace))
                            }
                            Chip(stringResource(R.string.clean_ext), params.clean.normalizeExtCase) {
                                params = params.copy(clean = params.clean.copy(normalizeExtCase = !params.clean.normalizeExtCase))
                            }
                            Chip(stringResource(R.string.clean_illegal), params.clean.stripIllegal) {
                                params = params.copy(clean = params.clean.copy(stripIllegal = !params.clean.stripIllegal))
                            }
                            Chip(stringResource(R.string.clean_control), params.clean.stripControl) {
                                params = params.copy(clean = params.clean.copy(stripControl = !params.clean.stripControl))
                            }
                        }
                    }
                    BatchMode.REGEX -> {
                        Field(params.pattern, stringResource(R.string.batch_pattern)) {
                            params = params.copy(pattern = it)
                        }
                        // 正则不合法要立刻说，别让用户点"应用"后才发现没生效
                        if (!Naming.isValidRegex(params.pattern)) {
                            Text(
                                text = stringResource(R.string.batch_regex_bad),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Field(params.replacement, stringResource(R.string.batch_replacement)) {
                            params = params.copy(replacement = it)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.batch_ignore_case),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = params.ignoreCase,
                                onCheckedChange = { params = params.copy(ignoreCase = it) },
                            )
                        }
                        Text(
                            text = stringResource(R.string.batch_regex_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    BatchMode.SUBSTR -> {
                        RangeFields(params) { params = it }
                        Text(
                            text = stringResource(R.string.batch_range_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    BatchMode.INSERT -> {
                        NumField(
                            params.from.toString(),
                            stringResource(R.string.batch_position),
                            Modifier.fillMaxWidth(),
                        ) { params = params.copy(from = it) }
                        Field(params.insertText, stringResource(R.string.batch_insert_text)) {
                            params = params.copy(insertText = it)
                        }
                        Text(
                            text = stringResource(R.string.batch_range_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    BatchMode.DELETE -> {
                        NumField(
                            params.from.toString(),
                            stringResource(R.string.batch_from),
                            Modifier.fillMaxWidth(),
                        ) { params = params.copy(from = it) }
                        NumField(
                            (params.to ?: 0).toString(),
                            stringResource(R.string.batch_to),
                            Modifier.fillMaxWidth(),
                        ) { params = params.copy(to = it) }
                        Text(
                            text = stringResource(R.string.batch_range_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    BatchMode.CASE -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(stringResource(R.string.case_lower), params.caseOp == CaseOp.LOWER) {
                                params = params.copy(caseOp = CaseOp.LOWER)
                            }
                            Chip(stringResource(R.string.case_upper), params.caseOp == CaseOp.UPPER) {
                                params = params.copy(caseOp = CaseOp.UPPER)
                            }
                            Chip(stringResource(R.string.case_title), params.caseOp == CaseOp.TITLE) {
                                params = params.copy(caseOp = CaseOp.TITLE)
                            }
                        }
                    }
                }

                // 模板：同样的改名规则会反复用，存起来下次一键套用
                TemplateRow(
                    templates = templates,
                    onApply = { t ->
                        mode = t.mode
                        params = t.params
                    },
                    onSave = { showSave = true },
                    onRemove = onRemoveTemplate,
                )
                if (showSave) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Field(
                            value = templateName,
                            label = stringResource(R.string.action_save_template),
                            modifier = Modifier.weight(1f),
                        ) { templateName = it }
                        TextButton(
                            enabled = templateName.isNotBlank(),
                            onClick = {
                                onSaveTemplate(templateName, mode, params)
                                templateName = ""
                                showSave = false
                            },
                        ) { Text(stringResource(R.string.done)) }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.batch_keep_ext), modifier = Modifier.weight(1f))
                    Switch(
                        checked = params.keepExtension,
                        onCheckedChange = { params = params.copy(keepExtension = it) },
                    )
                }

                Text(
                    text = stringResource(R.string.batch_preview, plan.size, changed),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                plan.take(6).forEach { row ->
                    PlanLine(row)
                }
                if (plan.size > 6) {
                    Text(
                        "… 其余 ${plan.size - 6} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed > 0, onClick = { onApply(plan) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            Row {
                // 导出清单：几百个文件的改名一旦出错，有这份清单能对回去
                if (plan.isNotEmpty()) {
                    TextButton(onClick = { onExportPlan(Batch.planToCsv(plan)) }) {
                        Text(stringResource(R.string.action_export_plan))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun PlanLine(row: PlanRow) {
    Text(
        text = "${row.oldName}  →  ${row.newName}" + if (row.conflict) " （已避让）" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (row.conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun Field(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun NumField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    // 本地保留一份文本：直接把 value 绑到 Int 上会有两个毛病
    //   1. 清空输入框时 toIntOrNull 失败 -> 回调不触发 -> **删不掉**
    //   2. 输入超长（十几位）同样解析失败 -> 像卡住一样打不出字
    // 所以文本自己管，只有解析成功才回传给外部。
    var text by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(9) // 9 位，防 Int 溢出
            text = digits
            digits.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun Chip(
    text: String,
    selected: Boolean,
    // onClick 必须放在最后：调用点普遍写成 Chip(text, selected) { ... } 的尾随 lambda 形式，
    // 尾随 lambda 只会绑定到**最后一个**函数类型参数。若 onLongClick 在后，
    // 47 处调用会全部落到 onLongClick 上，onClick 反而留空（编译报 No value passed）。
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    // combinedClickable 支持长按：模板删除走长按，避免误触删掉常用模板
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        modifier = if (onLongClick == null) {
            Modifier
        } else {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
        },
    )
}

/**
 * 模板行：已存的一键套用，没有时给个"存为模板"的入口。
 *
 * 用 FlowRow 而不是横向滚动列表：模板名通常很短，
 * 换行排布比左右滑动好点得多。
 */
@Composable
private fun TemplateRow(
    templates: List<BatchTemplate>,
    onApply: (BatchTemplate) -> Unit,
    onSave: () -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "模板",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (templates.isEmpty()) {
            Text(
                text = "还没有模板。调好参数后点下方「存为模板」，下次直接套用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                templates.forEach { t ->
                    // 长按删除，避免误触
                    Chip(
                        text = t.name,
                        selected = false,
                        onClick = { onApply(t) },
                        onLongClick = { onRemove(t.id) },
                    )
                }
            }
        }
    }
}

/**
 * 「起始 / 结束」两个数字输入（截取模式用）。
 *
 * 结束位置留空表示「到末尾」—— 这是最常见的用法
 * （比如「去掉前 3 个字符」），不该强制用户填一个很大的数。
 */
@Composable
private fun RangeFields(
    params: BatchParams,
    onChange: (BatchParams) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(
            params.from.toString(),
            stringResource(R.string.batch_from),
            Modifier.weight(1f),
        ) { onChange(params.copy(from = it)) }
        NumField(
            (params.to ?: 0).toString(),
            stringResource(R.string.batch_to),
            Modifier.weight(1f),
        ) { onChange(params.copy(to = it)) }
    }
}
