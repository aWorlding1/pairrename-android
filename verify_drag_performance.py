"""Source-level regression guards for the explicit drag mode and rename hot path.

These checks protect architectural intent; they do not replace Android instrumentation
or frame-time profiling on a physical device.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CARD = (ROOT / "app/src/main/java/com/yuanbao/pairrename/ui/ImageCard.kt").read_text()
APP = (ROOT / "app/src/main/java/com/yuanbao/pairrename/ui/PairRenameApp.kt").read_text()
MODEL = (ROOT / "app/src/main/java/com/yuanbao/pairrename/model/ImageItem.kt").read_text()
VM = (ROOT / "app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt").read_text()


def section(source: str, start: str, end: str) -> str:
    a = source.index(start)
    b = source.index(end, a)
    return source[a:b]


# Mode entry point and clear in/out affordance.
assert 'var dragMode by rememberSaveable' in APP
assert 'text = if (dragMode)' in APP
assert 'Text(if (dragMode) "退出" else "开启")' in APP
assert APP.count('dragMode = dragMode') >= 2, "both comparison panes must receive the mode"
assert 'gesturesEnabled = !(ui.screen == Screen.COMPARE && dragMode)' in APP, "disable drawer edge-swipe only during compare drag mode"
assert 'onMenu = { scope.launch { drawerState.open() } }' in APP, "keep explicit toolbar access to the drawer"
assert '侧滑栏手势暂停' in APP, "make the gesture conflict behavior visible to the user"
assert '按住卡片右侧把手拖动' in APP, "the new drag handle must be discoverable from the mode hint"

# 手势仲裁 v2：起拖收进**专属把手区域**，不再靠方向去猜。
#
# 背景：滑动列表与拖动卡片抢同一条手势。纯方向判定（横向归拖拽、纵向归滚动）虽然成立，
# 但那是"判定"出来的，不是"分开"的——斜划、起始点落在按钮附近、快速连划时都有中间态。
# 把起拖限制在把手上之后，两者不是同一手势的两种解释，而是物理上就不在同一区域。
#
# 另：dragAndDropSource 内部的 pointerInput 是「无 key」的（DragAndDropSourceNode 直接
# new SuspendingPointerInputModifierNode(handler)，没有 key1），闭包捕获的值不随重组更新，
# 所以模式判断与被捕获的变量都必须经 rememberUpdatedState 读。
grip = section(CARD, 'private fun DragGrip(', 'fun ImageCard(')
body = section(CARD, 'ElevatedCard(\n        modifier = modifier', '.dragAndDropTarget(')
body_drag = section(body, 'if (dragModeState.value) {', '} else {')

# 把手：唯一的起拖点。
assert 'detectHorizontalDragGestures(' in grip, "把手必须用带方向锁的横向检测器"
assert 'startTransfer(' in grip, "起拖必须发生在把手上"
assert 'rememberUpdatedState' in grip, "把手捕获的 itemKey/onDragStart 必须经 rememberUpdatedState 读"
assert 'Icons.Default.DragHandle' in grip, "把手需要可识别的视觉提示"
assert 'detectDragGestures(' not in grip, "把手不得使用无方向锁的 detectDragGestures"

# 卡片本体：拖拽模式下完全不接管拖拽。
assert 'onLongPress' not in body_drag, "拖拽模式下本体不得保留长按起拖（那是把手的职责）"
assert 'detectDragGestures(' not in body_drag
assert 'detectHorizontalDragGestures(' not in body_drag
assert 'startTransfer(' not in body_drag, "拖拽模式下本体不得起拖"
assert 'onTap' in body_drag and 'onDoubleTap' in body_drag, "本体仍需保留点选与双击"

# 常规模式：v6.0.0 起就有的长按拖拽必须保留。
assert 'onLongPress = {' in CARD, "legacy long-press gesture must remain available"
assert 'startTransfer(' in body, "常规模式长按仍应能起拖"

# 全局：整个卡片里不得再出现无方向锁的检测器。
assert CARD.count('detectDragGestures(') == 0, \
    "must not use direction-unlocked detectDragGestures (it steals vertical scroll)"

# 模式开关可逆 + 离开栏目清残留。
assert 'val dragModeState = rememberUpdatedState(dragMode)' in CARD
assert 'if (dragModeState.value)' in CARD
assert 'LaunchedEffect(ui.screen)' in APP, "leaving COMPARE must clear the stale drag source"
assert 'vm.setDragSource(null)' in APP

# Rename UI patch must be able to resolve provider-renewed document URIs without a directory scan.
assert 'val previousUri: Uri? = null' in MODEL
patch = section(VM, 'private fun patchAfterRename(steps: List<RenameStep>)', 'private fun refreshBoth()')
assert 'step.previousUri' in patch
assert 'val byPrevious' in patch
assert 'val index = if (legacy.isNotEmpty()) docs.nameIndex(tree) else emptyMap()' in patch
assert 'currentUri = step.docUri' in patch

# The common single-file drag path keeps one SAF directory listing snapshot.
perform = section(VM, 'private fun perform(from: ImageItem', 'private fun renameOrFind(')
assert perform.count('docs.listNames(') == 1, "perform() must reuse its directory-name snapshot"

# Confirmation/skip/ask branches do not trigger a full refresh without disk mutation.
apply = section(VM, 'fun applyRename(', 'fun commitManualRename(')
assert 'shouldRefresh = false' in apply
assert 'shouldRefresh = true' in apply
assert 'done.isNotEmpty() -> patchAfterRename(done)' in apply
assert 'shouldRefresh -> refreshBoth()' in apply

print("PASS: explicit drag mode, drawer-swipe isolation, legacy gesture, URI-chain patching, and one-snapshot rename hot path")
