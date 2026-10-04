@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yuanbao.pairrename.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.Screen
import com.yuanbao.pairrename.vm.UiState
import kotlinx.coroutines.launch
import com.yuanbao.pairrename.model.Side

@Composable
fun AppDrawer(
    drawerState: DrawerState,
    ui: UiState,
    onPickLeft: () -> Unit,
    onPickRight: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRefresh: () -> Unit,
    onCopyLeftToRight: () -> Unit,
    onCopyRightToLeft: () -> Unit,
    onDelete: () -> Unit,
    onReadExif: () -> Unit,
    onTrash: () -> Unit,
    onVerifyContent: () -> Unit,
    onFindDuplicates: () -> Unit,
    onHistory: () -> Unit,
    onSyncAll: () -> Unit,
    /** 交换左右两栏。 */
    onSwap: () -> Unit,
    /** 最近用过的文件夹（显示名 → Uri 字符串）。 */
    recents: List<Pair<String, String>> = emptyList(),
    /** 选一个最近用过的文件夹，直接切过去。 */
    onUseRecent: (Side, String) -> Unit = { _, _ -> },
    /** 移除一条最近记录。 */
    onRemoveRecent: (String) -> Unit = {},
    /** 清空最近记录。 */
    onClearRecents: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    /** 打开快捷键说明。 */
    onShortcuts: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { drawerState.close() } }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Text(
                text = "左：${ui.left.pathLabel.ifEmpty { "未选择" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "右：${ui.right.pathLabel.ifEmpty { "未选择" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            DrawerAction(
                label = "选择左文件夹",
                icon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onPickLeft() },
            )
            DrawerAction(
                label = "选择右文件夹",
                icon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onPickRight() },
            )
            // 最近用过：目录层级深时，这一项最省事
            if (recents.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recent_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClearRecents) {
                        Text(stringResource(R.string.action_clear_recent))
                    }
                }
                recents.forEach { (label, uri) ->
                    DrawerAction(
                        label = label,
                        icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(22.dp)) },
                        onClick = { close(); onUseRecent(Side.LEFT, uri) },
                        onLongClick = { onRemoveRecent(uri) },
                    )
                }
            }

            DrawerAction(
                label = stringResource(R.string.action_shortcuts),
                icon = { Icon(Icons.Default.Keyboard, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onShortcuts() },
            )
            DrawerAction(
                label = stringResource(R.string.refresh),
                icon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onRefresh() },
            )
            // 选错顺序时救命：不用重新选两个文件夹（大目录重选很慢）
            DrawerAction(
                label = stringResource(R.string.action_swap),
                icon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onSwap() },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "页面",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            DrawerAction(
                label = "对照双栏",
                icon = { Icon(Icons.Default.ViewColumn, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.COMPARE) },
            )
            DrawerAction(
                label = "单栏查看",
                icon = { Icon(Icons.Default.Collections, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.SINGLE) },
            )
            DrawerAction(
                label = "工具",
                icon = { Icon(Icons.Default.Tune, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.TOOLS) },
            )
            DrawerAction(
                label = "改名",
                icon = { Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.RENAME) },
            )
            DrawerAction(
                label = "整理",
                icon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.ORGANIZE) },
            )
            DrawerAction(
                label = "识别",
                icon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onNavigate(Screen.ANALYZE) },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "操作",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            DrawerAction(
                label = stringResource(R.string.undo),
                icon = { Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.undoCount > 0,
                onClick = { close(); onUndo() },
            )
            DrawerAction(
                label = stringResource(R.string.redo),
                icon = { Icon(Icons.AutoMirrored.Filled.Redo, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.redoCount > 0,
                onClick = { close(); onRedo() },
            )
            DrawerAction(
                // 主操作：按配对统一。放在批量操作第一位。
                label = stringResource(R.string.sync_title) + pendingSuffix(ui),
                icon = { Icon(Icons.Default.SyncAlt, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.left.treeUri != null && ui.right.treeUri != null,
                onClick = { close(); onSyncAll() },
            )
            DrawerAction(
                label = stringResource(R.string.action_batch),
                icon = { Icon(Icons.Default.EditNote, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.checked.isNotEmpty(),
                onClick = { close(); onNavigate(Screen.TOOLS) },
            )
            DrawerAction(
                label = stringResource(R.string.action_align),
                icon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.left.treeUri != null && ui.right.treeUri != null,
                onClick = { close(); onNavigate(Screen.TOOLS) },
            )
            DrawerAction(
                label = "复制左 → 右",
                icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.left.items.isNotEmpty() && ui.right.treeUri != null,
                onClick = { close(); onCopyLeftToRight() },
            )
            DrawerAction(
                label = "复制右 → 左",
                icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.right.items.isNotEmpty() && ui.left.treeUri != null,
                onClick = { close(); onCopyRightToLeft() },
            )
            DrawerAction(
                label = stringResource(R.string.action_delete) +
                    if (ui.checked.isNotEmpty()) "（${ui.checked.size}）" else "",
                icon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.checked.isNotEmpty(),
                onClick = { close(); onDelete() },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            DrawerAction(
                label = stringResource(R.string.match_filter),
                icon = {
                    Icon(
                        if (ui.matchFilter == MatchFilter.ALL) Icons.Default.LinkOff else Icons.Default.Link,
                        null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = { close(); onNavigate(Screen.COMPARE) },
            )
            DrawerAction(
                label = stringResource(R.string.action_read_exif),
                icon = { Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.left.items.isNotEmpty() || ui.right.items.isNotEmpty(),
                onClick = { close(); onReadExif() },
            )
            DrawerAction(
                label = stringResource(R.string.action_trash),
                icon = { Icon(Icons.Default.RestoreFromTrash, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onTrash() },
            )
            DrawerAction(
                label = stringResource(R.string.action_verify_content),
                icon = { Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(22.dp)) },
                enabled = ui.left.items.isNotEmpty() && ui.right.items.isNotEmpty(),
                onClick = { close(); onVerifyContent() },
            )
            DrawerAction(
                label = stringResource(R.string.action_find_dup),
                icon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onFindDuplicates() },
            )
            DrawerAction(
                label = stringResource(R.string.action_history),
                icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onHistory() },
            )
            DrawerAction(
                label = stringResource(R.string.settings),
                icon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onSettings() },
            )
            DrawerAction(
                label = "关于",
                icon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(22.dp)) },
                onClick = { close(); onAbout() },
            )
        }
    }
}

/** 抽屉里「按配对统一」尾部显示待处理数量，让主操作自带进度感。 */
private fun pendingSuffix(ui: UiState): String {
    val pending = (ui.matched.size / 2) - (ui.synced.size / 2)
    return if (pending > 0) "（$pending）" else ""
}

@Composable
private fun DrawerAction(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    /** 长按：用于"移除这条最近记录"这类破坏性操作，避免误触。 */
    onLongClick: (() -> Unit)? = null,
) {
    val interactionModifier = if (onLongClick == null) {
        Modifier
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = icon,
        // modifier 只能写一次，两个都要就链式合并
        modifier = interactionModifier.then(Modifier.padding(horizontal = 12.dp)),
        selected = false,
        onClick = { if (enabled) onClick() },
    )
}
