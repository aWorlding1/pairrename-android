package com.yuanbao.pairrename.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.yuanbao.pairrename.ui.dialogs.ImageInfoDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.util.ExtraFilter
import com.yuanbao.pairrename.util.applyFilter
import com.yuanbao.pairrename.model.CardSize
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.PaneState
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.vm.MainViewModel

@Composable
fun PaneColumn(
    vm: MainViewModel,
    side: Side,
    state: PaneState,
    query: String,
    matchFilter: MatchFilter,
    matched: Set<String>,
    modifier: Modifier = Modifier,
    /**
     * key → 配对对方的文件名。
     *
     * 由 UiState 预计算好传进来，而不是在卡片里查：
     * 卡片有 N 张，逐个线性查找就是 O(N²)，滑动必卡。
     */
    partnerNames: Map<String, String> = emptyMap(),
    /** 扩展名 / 体积过滤。 */
    extraFilter: ExtraFilter = ExtraFilter(),
    /** 弱依据配对的 key 集合（「仅弱依据」过滤用）。 */
    weakKeys: Set<String> = emptySet(),
    /**
     * 时间冲突配对的 key 集合（「仅时间冲突」过滤用）。
     *
     * 与 [weakKeys] 正交：那个问"依据够不够硬"，这个问"和更强的证据打不打架"。
     * 一个 pair 可能同时落在两边，也可能只落在一边 —— 两个集合都得传，
     * 少传一个，「仅时间冲突」过滤就会永远空。
     */
    conflictKeys: Set<String> = emptySet(),
    /**
     * 时间冲突的 key → 时间差（毫秒），卡片上直接显示"差 2 小时"。
     *
     * 与 [conflictKeys] 同源（都是 findTimeConflicts 的产物）：集合用于过滤，
     * 映射用于展示。分开传是因为过滤只需要"在不在"，多带一个 Map 不影响 remember 的键比较。
     */
    conflictDelta: Map<String, Long> = emptyMap(),
    /**
     * 漏配候选的 key → 对方文件名（「能对上」角标用，见 findSizeMatches）。
     *
     * 与 [partnerNames] 同源同理：UiState 重算时预建成 Map，卡片 O(1) 查 —
     * 逐个线性扫建议列表就是 O(N²)。这张卡没配对、但对面有一张
     * 尺寸 + 体积完全一样的，角标会给出"点一下配上"的入口。
     */
    sizeMatchPartner: Map<String, String> = emptyMap(),
    synced: Set<String>,
    /** 请求滚动到这个文件（配对跳转用）。 */
    focusKey: String?,
    focusNonce: Int,
    selectedKey: String?,
    dragKey: String?,
    checked: Set<String>,
    showCheckboxes: Boolean,
    cardSize: CardSize,
    showMeta: Boolean,
    /** 诊断用：显示重组计数。 */
    showPerf: Boolean = false,
    /** 仅双栏对照页使用：开启后卡片可直接拖动，不需长按。 */
    dragMode: Boolean = false,
    /** 单栏显示时放大卡片，避免半屏空间浪费。 */
    scale: Float = 1f,
    onPick: () -> Unit,
    onEdit: (ImageItem) -> Unit,
    onPreview: (ImageItem) -> Unit,
) {
    // 复用 util/Filter.kt 的算法 —— 界面与 ViewModel 必须算出同一份
    // 「可见集合」，否则全选/批量会作用到被隐藏的文件上。
    // toImmutableList()：Compose 编译器对它跳过重组更激进。
    // key 必须列全：漏掉任何一个输入，过滤结果就会滞后 ——
    // 比如改了扩展名筛选后列表纹丝不动，用户会以为功能坏了。
    // （matched 和 extraFilter 尤其容易漏，因为它们看起来"不太会变"）
    //
    // 这些声明必须放在 Column 之前：顶栏的"全选"要读 visible，
    // 而弹出的 ImageInfoDialog 在 Column 之后，需要和它同一作用域。
    val visible = remember(
        state.items, query, matchFilter, synced, matched, extraFilter, weakKeys, conflictKeys,
    ) {
        applyFilter(
            state.items, query, matchFilter, synced, matched, extraFilter, weakKeys, conflictKeys,
        ).toImmutableList()
    }

    // 范围选择的起点：点一张后长按另一张，中间的全选上
    var rangeAnchor by remember { mutableStateOf<String?>(null) }
    var infoTarget by remember { mutableStateOf<ImageItem?>(null) }
    val gridState = rememberLazyGridState()

    Column(modifier.fillMaxHeight()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            FilledTonalButton(onClick = onPick, modifier = Modifier.padding(end = 6.dp)) {
                Text(
                    if (state.treeUri == null) {
                        stringResource(R.string.pick_folder)
                    } else if (side == Side.LEFT) {
                        stringResource(R.string.pick_left)
                    } else {
                        stringResource(R.string.pick_right)
                    },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.pathLabel.ifEmpty { stringResource(R.string.empty_folder) },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.items.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (state.treeUri != null && showCheckboxes) {
                IconButton(onClick = { vm.checkAllOf(visible) }) {
                    Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                }
            }
            IconButton(onClick = { vm.refresh(side) }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            if (state.treeUri != null) {
                IconButton(onClick = { vm.clearFolder(side) }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        }

        LaunchedEffect(focusNonce) {
            if (focusKey == null) return@LaunchedEffect
            val index = visible.indexOfFirst { it.key == focusKey }
            if (index >= 0) gridState.animateScrollToItem(index)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                // 关键：loading 时**不能**把列表换成转圈。
                // 原来每次刷新都让整栏消失、显示 ProgressIndicator，
                // 30 张缩略图全部重新解码 —— 表现就是"一直转圈、卡得要命"。
                // 现在只在"确实没东西可显示"时才全屏转圈。
                state.loading && visible.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> EmptyState(
                    title = state.error.orEmpty(),
                    body = stringResource(R.string.error_hint),
                    icon = { Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(40.dp)) },
                    action = stringResource(R.string.pick_folder),
                    onAction = onPick,
                )
                state.treeUri == null -> EmptyState(
                    title = if (side == Side.LEFT) "还没有选择左文件夹" else "还没有选择右文件夹",
                    body = stringResource(R.string.empty_guide),
                    icon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(40.dp)) },
                    action = stringResource(R.string.pick_folder),
                    onAction = onPick,
                )
                // 顶层没图但下面还有子文件夹时，hint 会给出明确出路
                visible.isEmpty() && state.hint != null -> EmptyState(
                    title = stringResource(R.string.no_images),
                    body = state.hint.orEmpty(),
                    icon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(40.dp)) },
                    action = stringResource(R.string.settings),
                    onAction = { vm.openSettingsFromPane() },
                )
                visible.isEmpty() -> EmptyState(
                    title = stringResource(R.string.no_images),
                    body = stringResource(R.string.no_images_hint),
                    icon = { Icon(Icons.Default.ImageNotSupported, null, modifier = Modifier.size(40.dp)) },
                    action = stringResource(R.string.refresh),
                    onAction = { vm.refresh(side) },
                )
                else -> {
                    LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = (cardSize.minDp * scale).dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = visible, key = { it.key }) { item ->
                        ImageCard(
                            item = item,
                            selected = selectedKey == item.key,
                            isDragSource = dragKey == item.key,
                            checked = item.key in checked,
                            showCheckbox = showCheckboxes,
                            synced = item.key in synced,
                            hasPartner = item.key in matched,
                            partnerName = if (item.key in matched) partnerNames[item.key] else null,
                            conflictDeltaMs = conflictDelta[item.key] ?: 0L,
                            sizeMatchName = sizeMatchPartner[item.key],
                            onSizeLink = { vm.applySizeMatch(item.key) },
                            showMeta = showMeta,
                            showPerf = showPerf,
                            dragMode = dragMode,
                            onTap = { vm.onCardTap(item) },
                            onInfo = { infoTarget = item },
                            onEdit = { onEdit(item) },
                            onToggleCheck = { vm.toggleCheck(item); rangeAnchor = item.key },
                            onRangeSelect = {
                                rangeAnchor?.let { anchor ->
                                    vm.selectRange(visible, anchor, item.key)
                                } ?: run {
                                    vm.toggleCheck(item)
                                    rangeAnchor = item.key
                                }
                            },
                            onLocate = { vm.focusPartner(item.key) },
                            onPreview = { onPreview(item) },
                            onDragStart = { vm.setDragSource(item) },
                            onDrop = { vm.onDrop(item) },
                        )
                    }
                    }
                    // 有内容时只在角落挂一个小进度条，不遮挡、不重建列表
                    if (state.loading) {
                        Surface(
                            tonalElevation = 3.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "刷新中",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    // 详情面板：现场读 EXIF（后台线程），不预先给所有图读一遍拖慢扫描
    infoTarget?.let { item ->
        ImageInfoDialog(item = item, onDismiss = { infoTarget = null })
    }}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    icon: @Composable () -> Unit,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        androidx.compose.material3.IconButton(
            onClick = onAction,
            modifier = Modifier.size(56.dp),
        ) { icon() }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

