package com.yuanbao.pairrename.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.Screen
import com.yuanbao.pairrename.vm.UiState
import com.yuanbao.pairrename.model.Side

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    ui: UiState,
    searchActive: Boolean,
    onSearchToggle: () -> Unit,
    onMenu: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFilter: () -> Unit,
    /** 打开扩展名 / 体积过滤。 */
    onExtFilter: () -> Unit = {},
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onQueryChange: (String) -> Unit,
    /** 打开操作记录（撤销栈可视化）。 */
    onUndoList: () -> Unit = {},
    /** 键盘快捷键说明（外接键盘 / 大屏时有用）。 */
    onShortcuts: () -> Unit = {},
    /** 二级页返回（一级页时不会被调用）。 */
    onBack: () -> Unit = {},
) {
    var showMore by remember { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        navigationIcon = {
            if (ui.screen.isSub) {
                // 二级页：返回箭头优先，让层级关系清楚
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = "菜单")
                }
            }
        },
        title = {
            if (searchActive) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column {
                    Text(
                        text = titleFor(ui.screen),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitleFor(ui),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            if (searchActive) {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
                return@TopAppBar
            }
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_hint))
            }
            IconButton(enabled = ui.undoCount > 0, onClick = onUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
            }
            IconButton(enabled = ui.redoCount > 0, onClick = onRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo))
            }
            // 过滤开启时用填充图标，一眼看出当前处于过滤态
            // 扩展名/体积过滤：用另一个图标区分，避免和"过滤循环"混淆
            IconButton(onClick = onExtFilter) {
                Icon(
                    imageVector = if (ui.extraFilter.isEmpty) {
                        Icons.Outlined.FilterList
                    } else {
                        Icons.Filled.FilterList
                    },
                    contentDescription = stringResource(R.string.ext_filter),
                    tint = if (ui.extraFilter.isEmpty) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            IconButton(onClick = onFilter) {
                Icon(
                    imageVector = if (ui.matchFilter == MatchFilter.ALL) {
                        Icons.Outlined.FilterAlt
                    } else {
                        Icons.Filled.FilterAlt
                    },
                    contentDescription = stringResource(R.string.match_filter),
                    tint = if (ui.matchFilter == MatchFilter.ALL) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            IconButton(onClick = { showMore = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_undo_list)) },
                    enabled = ui.undoCount > 0,
                    onClick = { showMore = false; onUndoList() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.refresh)) },
                    onClick = { showMore = false; onRefresh() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_shortcuts)) },
                    onClick = { showMore = false; onShortcuts() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
                    onClick = { showMore = false; onSettings() },
                )
                DropdownMenuItem(
                    text = { Text("关于") },
                    onClick = { showMore = false; onAbout() },
                )
            }
        },
    )
}

/** 底部只放这三个一级页面。 */
private val TOP_SCREENS = listOf(Screen.COMPARE, Screen.SINGLE, Screen.TOOLS)

@Composable
fun AppNavBar(
    current: Screen,
    badgeCount: Int,
    onSelect: (Screen) -> Unit,
) {
    // 只显示一级页面：对照 / 单栏 / 工具。
    // 二级页（改名/整理/识别）不进底栏，用顶栏返回键导航，
    // 避免大量功能和主流程挤在一个屏里。
    NavigationBar {
        TOP_SCREENS.forEach { screen ->
            NavigationBarItem(
                // 二级页时高亮所属的一级页，否则底栏看起来"谁都没选中"
                selected = current == screen || current.parent == screen,
                onClick = { onSelect(screen) },
                alwaysShowLabel = true,
                icon = {
                    val navIcon: @Composable () -> Unit = {
                        Icon(
                            imageVector = when (screen) {
                                Screen.COMPARE -> Icons.Default.ViewColumn
                                Screen.SINGLE -> Icons.Default.Collections
                                else -> Icons.Default.Tune
                            },
                            contentDescription = null,
                        )
                    }
                    if (screen == Screen.TOOLS && badgeCount > 0) {
                        BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { navIcon() }
                    } else {
                        navIcon()
                    }
                },
                label = { Text(titleFor(screen)) },
            )
        }
    }
}

@Composable
private fun titleFor(screen: Screen): String = when (screen) {
    Screen.COMPARE -> "对照"
    Screen.SINGLE -> "单栏"
    Screen.TOOLS -> "工具"
    Screen.RENAME -> "改名"
    Screen.ORGANIZE -> "整理"
    Screen.ANALYZE -> "识别"
}

@Composable
private fun subtitleFor(ui: UiState): String = when (ui.screen) {
    Screen.COMPARE -> {
        val paired = ui.matched.size / 2
        val pending = paired - ui.synced.size / 2
        "左 ${ui.left.items.size} · 右 ${ui.right.items.size} · 待处理 $pending"
    }
    Screen.SINGLE -> pathLine(
        ui.pane(ui.singleSide).pathLabel,
        ui.pane(ui.singleSide).items.size,
        stringResource(R.string.empty_folder),
    ) + if (ui.singleSide == Side.LEFT) "（左）" else "（右）"
    Screen.TOOLS -> "选一类操作"
    Screen.RENAME -> "把名字改成想要的样子"
    Screen.ORGANIZE -> "移动、去重、清理"
    Screen.ANALYZE -> "搞清楚哪些是同一张"
} + filterSuffix(ui.matchFilter)

private fun pathLine(path: String, count: Int, emptyLabel: String): String =
    if (path.isEmpty()) emptyLabel else "$path · $count 张"

/**
 * 过滤状态后缀。
 *
 * 注意：这是 **when 表达式**（返回值），必须穷举所有枚举值，
 * 否则编译不过。当初给 MatchFilter 加了 UNPAIRED / ONLY_LEFT /
 * ONLY_RIGHT 三个值，这里漏了 —— 差点导致整个项目编译失败。
 * 所以三个新值必须在这里一并列出来。
 */
private fun filterSuffix(filter: MatchFilter): String = when (filter) {
    MatchFilter.ALL -> ""
    MatchFilter.DONE -> "（仅已统一）"
    MatchFilter.TODO -> "（仅待处理）"
    MatchFilter.UNPAIRED -> "（仅未配对）"
    MatchFilter.SUSPECT -> "（仅弱依据）"
    MatchFilter.CONFLICT -> "（仅时间冲突）"
    MatchFilter.ONLY_LEFT -> "（仅左栏独有）"
    MatchFilter.ONLY_RIGHT -> "（仅右栏独有）"
}
