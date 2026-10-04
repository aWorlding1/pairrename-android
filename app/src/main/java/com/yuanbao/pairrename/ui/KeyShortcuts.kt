package com.yuanbao.pairrename.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 键盘快捷键（外接键盘 / 平板 / 桌面模式）。
 *
 * 手机上用处不大，但接了键盘或在大屏上操作时，
 * 少了这些键会明显感觉到"笨"。
 *
 * 统一约定：
 * - Ctrl/Cmd + Z 撤销，Ctrl/Cmd + Shift + Z 重做
 * - Ctrl/Cmd + A 全选本栏可见项
 * - Ctrl/Cmd + F 聚焦搜索框
 * - F 循环切换过滤
 * - Esc 清除勾选 / 退出
 */
@Composable
fun Modifier.shortcuts(
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectAll: () -> Unit,
    onSearch: () -> Unit,
    onCycleFilter: () -> Unit,
    onEscape: () -> Unit,
): Modifier {
    return this.onKeyEvent { event ->
        // 只处理按下，避免抬起时重复触发
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val ctrl = event.isCtrlPressed
        when {
            ctrl && event.key == Key.Z -> {
                if (event.isShiftPressed) onRedo() else onUndo()
                true
            }
            ctrl && event.key == Key.A -> { onSelectAll(); true }
            ctrl && event.key == Key.F -> { onSearch(); true }
            !ctrl && event.key == Key.F -> { onCycleFilter(); true }
            event.key == Key.Escape -> { onEscape(); true }
            else -> false
        }
    }
}
