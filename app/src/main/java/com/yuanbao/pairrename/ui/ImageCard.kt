package com.yuanbao.pairrename.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
// 别名导入：本文件同时用到 foundation 的 Image 可组合函数和
// material icons 里的 Image 图标属性，同名会构成 import 冲突。
// 注意 filled.Image 是声明在 Icons.Filled 上的**扩展属性**，
// 所以别名调用也要带接收者：Icons.Filled.ImageIcon。
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.util.Naming

/** 列表缩略图边长（像素）。卡片实际 150~200dp，320 足够且省内存。 */
private const val THUMB_PX = 320

/** 拖拽把手边长。36dp：比图标的 20dp 大不少，手指命中率够用，又不会挤掉文件名。 */
private const val GRIP_SIZE = 36

/**
 * 拖拽改名把手 —— **整个应用里唯一能起拖的地方**。
 *
 * 为什么要有它：滑动列表和拖动卡片本来在抢同一条手势。纯靠方向去猜（横向归拖拽、
 * 纵向归滚动）在 Compose 里虽然成立，但那是「判定」出来的，不是「分开」的 ——
 * 斜着划、起始点落在按钮附近、连续快速操作时都可能出现模棱两可的中间态。
 * 把起拖收进一个专属小区域之后，两者不再是同一个手势的两种解释，
 * 而是**物理上就不在同一个区域**，不需要任何仲裁。
 *
 * 仍然保留横向限制：万一有人从把手上起手却想上下滑，让位给列表比抢过来更符合直觉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DragGrip(
    itemKey: String,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // dragAndDropSource 的 pointerInput 无 key（见 ImageCard 里的说明），
    // 闭包捕获的值不会随重组更新，所以这里一律经 rememberUpdatedState 读。
    val keyState = rememberUpdatedState(itemKey)
    val startState = rememberUpdatedState(onDragStart)

    Box(
        modifier = modifier
            .size(GRIP_SIZE.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.tertiary,
                shape = CircleShape,
            )
            .dragAndDropSource {
                detectHorizontalDragGestures(
                    onDragStart = {
                        startState.value.invoke()
                        startTransfer(
                            DragAndDropTransferData(
                                clipData = ClipData.newPlainText("pairrename", keyState.value),
                            ),
                        )
                    },
                    onHorizontalDrag = { change, _ -> change.consume() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "按住拖到另一栏即可改名",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageCard(
    item: ImageItem,
    selected: Boolean,
    isDragSource: Boolean,
    checked: Boolean,
    showCheckbox: Boolean,
    /** 配对双方主文件名已经一致。 */
    synced: Boolean,
    hasPartner: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 配对对方的文件名（没配对时为 null）。
     *
     * 直接显示"对面叫什么"，省得左右来回看 —— 一眼就能判断这一对要不要改。
     */
    partnerName: String? = null,
    /**
     * 这一对两边拍摄时间的差值（毫秒），0 表示不冲突（见 findTimeConflicts）。
     *
     * 卡片上直接显示"差 2 小时"而不是只在对比面板里说：
     * 复核时间冲突时用户要做的正是**扫一遍列表找出哪几对不对劲**，
     * 一对一对点开看的话，这个功能等于不存在。
     */
    conflictDeltaMs: Long = 0,
    /**
     * 漏配候选对方的文件名（没配对、且没有候选时为 null，见 findSizeMatches）。
     *
     * 这张卡没配上，但对面有一张宽 × 高 × 体积完全一样的 ——
     * 卡片是"漏配"最贴近现场的露出通道：角标点一下直接配上（只配这一对）。
     */
    sizeMatchName: String? = null,
    /** 点漏配角标：配上这一对（只动这一对，不动其它建议）。 */
    onSizeLink: () -> Unit = {},
    showMeta: Boolean,
    /** 诊断用：显示这张卡被重组了多少次。 */
    showPerf: Boolean = false,
    /** 双栏显式拖拽模式：越过触摸阈值立即拖动，不再等待长按。 */
    dragMode: Boolean = false,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    /** 打开详细信息（含完整 EXIF）。 */
    onInfo: () -> Unit = {},
    onToggleCheck: () -> Unit,
    /** 长按勾选框：从上次点选到这一张，全选一段。 */
    onRangeSelect: () -> Unit = {},
    onLocate: () -> Unit,
    onPreview: () -> Unit,
    onDragStart: () -> Unit,
    onDrop: () -> Unit,
) {
    val context = LocalContext.current
    var over by remember { mutableStateOf(false) }
    val highlight = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.secondary

    // dragAndDropSource 内部的 pointerInput 是「无 key」的（DragAndDropSourceNode 直接
    // new SuspendingPointerInputModifierNode(handler)，没有 key1）：dragMode 变化时这个节点
    // 不会重建，里面那个 lambda 会一直持有**创建那一刻**捕获的 dragMode 值。
    // 后果正是「关闭拖拽改名关不掉」——卡片继续跑拖拽检测器，之后怎么滑都会误触发拖拽。
    // rememberUpdatedState 的 State 对象身份跨重组稳定、value 恒为最新；
    // 每次手势会话（pointer down 时 handler 被重启）都会读到当前模式。
    val dragModeState = rememberUpdatedState(dragMode)

    val target = remember(item) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { over = true }
            override fun onExited(event: DragAndDropEvent) { over = false }
            override fun onEnded(event: DragAndDropEvent) { over = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                onDrop()
                return true
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    ElevatedCard(
        modifier = modifier
            .dragAndDropSource {
                // 这层留在卡片本体上，只是为了拿到 startTransfer 的 scope ——
                // 拖拽模式开启时，本体**完全不接管拖拽**，起拖只发生在下方的把手上。
                if (dragModeState.value) {
                    // 拖拽模式：本体只保留点选与双击。
                    // 没有长按、没有拖拽检测器，也就不会有任何位移被消费 ——
                    // 纵向滑动 100% 留给 LazyVerticalGrid，横向滑动什么也不发生。
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onPreview() },
                    )
                } else {
                    // 常规模式：保留 v6.0.0 起就有的点按 / 双击 / 长按拖拽。
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onPreview() },
                        onLongPress = {
                            onDragStart()
                            startTransfer(
                                DragAndDropTransferData(
                                    clipData = ClipData.newPlainText("pairrename", item.key),
                                ),
                            )
                        },
                    )
                }
            }
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
            .border(
                width = if (over) 3.dp else if (selected || isDragSource || dragMode) 2.dp else 0.dp,
                color = when {
                    over -> highlight
                    dragMode -> MaterialTheme.colorScheme.tertiary
                    else -> selectedColor
                },
                shape = shape,
            ),
        shape = shape,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 用 painter 而不是 AsyncImage：可以拿到加载状态，
                // 加载中显示静态占位图（而不是一个一直转的圈），
                // 失败时显示图标 —— 无论哪种都不会卡在"转圈"上。
                val painter = rememberAsyncImagePainter(
                    model = remember(item.docUri) {
                        ImageRequest.Builder(context)
                            .data(item.docUri)
                            .size(THUMB_PX)
                            .memoryCacheKey(item.docUri.toString())
                            .diskCacheKey(item.docUri.toString())
                            .crossfade(false)
                            .build()
                    },
                )
                val state = painter.state
                if (state is AsyncImagePainter.State.Success) {
                    Image(
                        painter = painter,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(shape),
                    )
                } else {
                    // 占位 / 失败：静态内容，不消耗渲染资源
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(
                            imageVector = if (state is AsyncImagePainter.State.Error) {
                                Icons.Default.BrokenImage
                            } else {
                                Icons.Filled.ImageIcon
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                if (showPerf) {
                    RecompositionBadge(modifier = Modifier.align(Alignment.TopEnd))
                }
                if (showCheckbox) {
                    IconButton(
                        onClick = onToggleCheck,
                        // 注意：modifier 只能出现一次。
                        // 之前加了 combinedClickable 之后又写了一个 modifier，
                        // 重复命名参数会直接编译失败。
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(30.dp)
                            .combinedClickable(
                                onClick = onToggleCheck,
                                onLongClick = onRangeSelect,
                            ),
                    ) {
                        Icon(
                            imageVector = if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(26.dp),
                ) {
                    Icon(
                        imageVector = if (item.canRename) Icons.Default.Edit else Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                // 详情按钮放底部右侧：顶部已被编辑/勾选占满，
                // 再塞一个会挤到看不清
                FilledTonalIconButton(
                    onClick = onInfo,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(26.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (!hasPartner && !sizeMatchName.isNullOrBlank()) {
                    // 漏配角标：这张没配上，但对面有一张宽×高×体积完全一样的。
                    // 位置与配对角标相同（BottomStart）—— 没配对的卡那个位置本来空着；
                    // 用同一位置表达"配对关系"这一件事：有配对是跳转，没配对但能认出来是配上。
                    FilledTonalIconButton(
                        onClick = onSizeLink,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(26.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "有一张尺寸和体积完全一样的，点此配上",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                if (hasPartner) {
                    // 配对角标可点击：一键滚动到另一栏的对应文件。
                    //
                    // 时间冲突时整颗变警示色并换成警告图标 ——
                    // "这一对配错了"比"已统一 / 待处理"重要得多，该盖过它们。
                    // 底行文字也会说差多少，两处一起做是为了扫列表时先看到色块、
                    // 定睛再看具体差值。
                    val clash = conflictDeltaMs > 0
                    FilledTonalIconButton(
                        onClick = onLocate,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(26.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = when {
                                clash -> MaterialTheme.colorScheme.errorContainer
                                synced -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = when {
                                clash -> Icons.Default.ErrorOutline
                                synced -> Icons.Default.CheckCircle
                                else -> Icons.Default.Link
                            },
                            contentDescription = when {
                                clash -> "两边拍摄时间对不上，点此查看另一栏"
                                synced -> "已统一，点此跳到另一栏"
                                else -> "点此跳到配对文件"
                            },
                            tint = when {
                                clash -> MaterialTheme.colorScheme.onErrorContainer
                                synced -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            // 拖拽模式下，文件名这一行右侧让出位置给拖拽把手。
            // 位置取舍：放缩略图上会挡住照片本身（靠认图区分文件正是这个列表的用途）；
            // 放四角则与编辑 / 详情 / 勾选 / 配对角标打架（四个角已被占满）。
            // 文件名行本来就是"信息区"，把手放这儿既不遮图也不挤按钮。
            if (dragMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                ) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    DragGrip(
                        itemKey = item.key,
                        onDragStart = onDragStart,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            } else {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            // 直接显示对面叫什么：不用左右来回看，一眼判断这一对要不要改。
            // 名字一致时显示"已统一"，避免误以为还要操作。
            //
            // 时间冲突（配上了但两边拍摄时间差得离谱）也挤在这一行 ——
            // 缩略图四个角已经被勾选 / 编辑 / 配对角标 / 详情占满，再塞一个只会挤到看不清；
            // 而这一行本来就是"配对信息区"，冲突恰恰是关于配对的判断。
            // 用红色 + 具体差值，扫列表时能一眼挑出来。
            if (!partnerName.isNullOrBlank()) {
                val same = partnerName.equals(item.displayName, ignoreCase = true)
                val clash = conflictDeltaMs > 0
                Text(
                    text = buildString {
                        append(if (same) "对面：同名" else "对面：$partnerName")
                        if (clash) append(" · 差 ${Naming.formatDuration(conflictDeltaMs)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        clash -> MaterialTheme.colorScheme.error
                        same -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                )
            } else if (!sizeMatchName.isNullOrBlank()) {
                // 没配对但有漏配候选：同一行位置告诉用户"对面有一张能对上的"。
                // 扫列表时这行就是漏配的文字露出（角标是色块、这行是名字），
                // 与冲突的"色块 + 具体差值"同一个设计。
                Text(
                    text = "能对上：$sizeMatchName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                )
            }
            if (showMeta) {
                Text(
                    text = listOf(item.dimension, Naming.formatSize(item.size))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * 诊断叠层：显示这张卡被重组了多少次。
 *
 * 用法：设置里打开「显示性能叠层」，然后在列表里滚一滚。
 * - 计数基本不动 → 重组没问题，卡在图片解码/磁盘 IO 上；
 * - 滚一下就猛涨 → 重组失控，是 Compose 写法的问题（参数不稳定等）。
 *
 * 这样排查卡顿就不用靠猜了。记得排查完关掉，它本身也会触发一次重组。
 */
@Composable
private fun RecompositionBadge(modifier: Modifier = Modifier) {
    var count by remember { mutableIntStateOf(0) }
    SideEffect { count++ }
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier
            .padding(4.dp)
            .background(
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
