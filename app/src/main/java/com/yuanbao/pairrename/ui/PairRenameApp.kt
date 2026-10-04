package com.yuanbao.pairrename.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.MatchFilter
import com.yuanbao.pairrename.model.PlanRow
import com.yuanbao.pairrename.model.Screen
import com.yuanbao.pairrename.model.BatchMode
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.model.SyncDirection
import com.yuanbao.pairrename.model.SelectCondition
import com.yuanbao.pairrename.ui.dialogs.AskConflictDialog
import com.yuanbao.pairrename.ui.dialogs.BatchRenameDialog
import com.yuanbao.pairrename.ui.dialogs.BatchReportDialog
import com.yuanbao.pairrename.ui.dialogs.CompareDialog
import com.yuanbao.pairrename.ui.dialogs.DiagnosticsDialog
import com.yuanbao.pairrename.ui.dialogs.UndoListDialog
import com.yuanbao.pairrename.ui.dialogs.PipelineDialog
import com.yuanbao.pairrename.ui.dialogs.DirStatsDialog
import com.yuanbao.pairrename.ui.dialogs.ExtFilterDialog
import com.yuanbao.pairrename.ui.dialogs.ShortcutsDialog
import com.yuanbao.pairrename.ui.dialogs.ConfirmApplyDialog
import com.yuanbao.pairrename.ui.dialogs.ArchiveDialog
import com.yuanbao.pairrename.ui.dialogs.BigFilesDialog
import com.yuanbao.pairrename.ui.dialogs.DuplicatesDialog
import com.yuanbao.pairrename.ui.dialogs.TrashDialog
import com.yuanbao.pairrename.ui.dialogs.HistoryDialog
import com.yuanbao.pairrename.ui.dialogs.PairSyncDialog
import com.yuanbao.pairrename.ui.dialogs.RenameDialog
import com.yuanbao.pairrename.ui.dialogs.RealignPreviewDialog
import com.yuanbao.pairrename.ui.dialogs.SequenceAlignDialog
import com.yuanbao.pairrename.ui.dialogs.SettingsDialog
import com.yuanbao.pairrename.ui.dialogs.dominantSide
import com.yuanbao.pairrename.util.Naming
import com.yuanbao.pairrename.util.TimeRealign
import com.yuanbao.pairrename.util.availableExts
import com.yuanbao.pairrename.ui.theme.PairRenameTheme
import com.yuanbao.pairrename.vm.MainViewModel
import com.yuanbao.pairrename.vm.UiEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairRenameApp(
    vm: MainViewModel,
    onPickLeft: () -> Unit,
    onPickRight: () -> Unit,
    onSaveCsv: (String) -> Unit,
    onOpenCsv: () -> Unit,
    allFilesGranted: () -> Boolean = { false },
    onGrantAllFiles: (() -> Unit)? = null,
) {
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val latestUi by rememberUpdatedState(ui)
    val latestSettings by rememberUpdatedState(settings)

    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var narrowHintDismissed by rememberSaveable { mutableStateOf(false) }
    var dragMode by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ImageItem?>(null) }
    var previewTarget by remember { mutableStateOf<ImageItem?>(null) }
    var confirmApply by remember { mutableStateOf<UiEvent.ConfirmApply?>(null) }
    var askConflict by remember { mutableStateOf<UiEvent.AskConflict?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // 每次打开设置页重新读一次，用户从系统设置页返回后状态才对
    var allFilesState by remember { mutableStateOf(false) }
    LaunchedEffect(showSettings) {
        if (showSettings) allFilesState = allFilesGranted()
    }
    // 离开双栏对照页时清掉拖拽源。拖拽状态只在「按下 → 落到目标」这一小段里有意义；
    // 拖到空白处取消、或中途切去别的栏目之后，留下的 dragSource 会让某张卡一直挂着
    // 拖拽高亮，而且下一次拖拽若先落到它身上，就会被当成这次的来源。
    LaunchedEffect(ui.screen) {
        if (ui.screen != Screen.COMPARE) vm.setDragSource(null)
    }
    var showAbout by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showUndoList by remember { mutableStateOf(false) }
    var showPipeline by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var statsFor by remember { mutableStateOf<Side?>(null) }
    var showExtFilter by remember { mutableStateOf(false) }
    var batchFor by remember { mutableStateOf<Triple<Side, Set<String>, BatchMode>?>(null) }
    var alignData by remember { mutableStateOf<AlignArgs?>(null) }
    // 整批重配的逐条预览（见 RealignPreviewDialog）。存的是发出瞬间的方案快照。
    var realignPreview by remember { mutableStateOf<TimeRealign?>(null) }
    var syncData by remember { mutableStateOf<SyncArgs?>(null) }
    var historyEntries by remember { mutableStateOf<List<com.yuanbao.pairrename.data.HistoryEntry>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var dupGroups by remember { mutableStateOf<List<com.yuanbao.pairrename.data.DuplicateFinder.DuplicateGroup>>(emptyList()) }
    var showDuplicates by remember { mutableStateOf(false) }
    var trashItems by remember { mutableStateOf<List<com.yuanbao.pairrename.data.TrashRepository.TrashedItem>>(emptyList()) }
    var showTrash by remember { mutableStateOf(false) }
    var archiveRows by remember { mutableStateOf<List<com.yuanbao.pairrename.data.Organizer.MoveRow>>(emptyList()) }
    var showArchive by remember { mutableStateOf(false) }
    var bigFiles by remember { mutableStateOf<List<com.yuanbao.pairrename.data.Organizer.BigFile>>(emptyList()) }
    var bigSummary by remember { mutableStateOf(com.yuanbao.pairrename.data.Organizer.StorageSummary(0, 0)) }
    var showBigFiles by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<List<PlanRow>>(emptyList()) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.text,
                        actionLabel = if (event.withUndo) "撤销" else null,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undo()
                }
                is UiEvent.ConfirmApply -> confirmApply = event
                is UiEvent.AskConflict -> askConflict = event
                is UiEvent.OpenBatch -> batchFor = Triple(event.side, event.existing, event.initialMode)
                is UiEvent.OpenAlign -> alignData = AlignArgs(
                    event.leftNames, event.rightNames, event.suggestedOffset, event.source,
                )
                is UiEvent.OpenSync -> syncData = SyncArgs(
                    event.leftNames, event.rightNames, event.direction,
                )
                // 自动生成的名字必须过一遍预览再落盘
                is UiEvent.ConfirmPlan -> pendingPlan = event.rows
                UiEvent.OpenSettings -> showSettings = true
                is UiEvent.OpenHistory -> {
                    historyEntries = event.entries
                    showHistory = true
                }
                is UiEvent.OpenPipeline -> showPipeline = true
                is UiEvent.AskDelete -> askDelete = true
                is UiEvent.SaveHistory -> onSaveCsv(event.csv)
                is UiEvent.SavePlan -> onSaveCsv(event.csv)
                is UiEvent.ShowDuplicates -> {
                    dupGroups = event.groups
                    showDuplicates = true
                }
                is UiEvent.ShowTrash -> {
                    trashItems = event.items
                    showTrash = true
                }
                is UiEvent.ShowArchivePlan -> {
                    archiveRows = event.rows
                    showArchive = true
                }
                    is UiEvent.ShowBigFiles -> {
                        bigFiles = event.files
                        bigSummary = event.summary
                        showBigFiles = true
                    }
                    // 复核可疑配对：ViewModel 已经把过滤切好，这里只负责把面板打开。
                    // 用事件里带过来的 item（发出瞬间的快照），不读 ui 状态 ——
                    // 这个收集器是 LaunchedEffect(Unit)，读状态会读到过期值。
                    is UiEvent.OpenCompare -> previewTarget = event.item
                    // 整批重配：先摊开再确认。同一份方案会同时关掉对比面板 ——
                    // 两个 AlertDialog 叠着，用户按返回只会关掉上面那层，容易以为丢了东西。
                    is UiEvent.PreviewRealign -> {
                        previewTarget = null
                        realignPreview = event.plan
                    }
                }
        }
    }

    /** 搜索展开时，返回键先收起搜索框；抽屉打开时先关抽屉。 */
    // 二级页按系统返回键回到工具页（和顶栏箭头一致）
    BackHandler(enabled = latestUi.screen.isSub) {
        vm.backFromSub()
    }

    BackHandler(enabled = searchActive) {
        searchActive = false
        vm.setQuery("")
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 有对话框打开时，返回键先关最上面那个对话框。
    // 没有这个的话，按返回会直接退出应用 —— 而用户以为自己只是关个框。
    // 顺序即优先级：越靠前越"上层"。
    val anyDialogOpen = listOf(
        confirmApply != null,
        askConflict != null,
        batchFor != null,
        alignData != null,
        realignPreview != null,
        syncData != null,
        showSettings,
        showHistory,
        showPipeline,
        askDelete,
        pendingPlan.isNotEmpty(),
        showExtFilter,
        showShortcuts,
        showAbout,
        showDiagnostics,
        showDuplicates,
        showTrash,
        showArchive,
        showBigFiles,
        statsFor != null,
        renameTarget != null,
        previewTarget != null,
        showUndoList,
    ).any { it }

    BackHandler(enabled = anyDialogOpen) {
        // 一次性全部收起：这些状态互斥，理论上只有一个非空，
        // 全部清空也不会误伤，反而更保险
        confirmApply = null
        askConflict = null
        batchFor = null
        alignData = null
        realignPreview = null
        syncData = null
        showSettings = false
        showHistory = false
        showPipeline = false
        askDelete = false
        pendingPlan = emptyList()
        showExtFilter = false
        showShortcuts = false
        showAbout = false
        showDiagnostics = false
        showDuplicates = false
        showTrash = false
        showArchive = false
        showBigFiles = false
        statsFor = null
        renameTarget = null
        previewTarget = null
        showUndoList = false
    }

    val isNarrow = LocalConfiguration.current.screenWidthDp < 480
    val showNarrowHint = isNarrow &&
        ui.screen == Screen.COMPARE &&
        !narrowHintDismissed &&
        ui.left.treeUri != null &&
        ui.right.treeUri != null
    val hasSelection = ui.checked.isNotEmpty()

    /** 根据“批量执行前二次确认”决定直接执行还是再问一次。 */
    val applyPlan: (List<PlanRow>) -> Unit = { rows ->
        if (latestSettings.confirmBatch && rows.isNotEmpty()) {
            pendingPlan = rows
        } else {
            vm.executePlan(rows)
        }
    }

    PairRenameTheme(settings.themeMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 双栏快速拖动与 Drawer 的横向 edge-swipe 会抢同一条手势。
            // 拖拽改名开启时暂停抽屉滑动开合；顶栏菜单按钮仍可显式打开抽屉。
            gesturesEnabled = !(ui.screen == Screen.COMPARE && dragMode),
            drawerContent = {
                AppDrawer(
                    drawerState = drawerState,
                    ui = ui,
                    onPickLeft = onPickLeft,
                    onPickRight = onPickRight,
                    onNavigate = { vm.setScreen(it) },
                    onUndo = { vm.undo() },
                    onRedo = { vm.redo() },
                    onRefresh = { vm.refreshAll() },
                    onReadExif = { vm.readExif() },
                    onTrash = { vm.requestTrash() },
                    onVerifyContent = { vm.verifyByContent() },
                    onFindDuplicates = { vm.findDuplicates() },
                    onHistory = { vm.requestHistory() },
                    onSyncAll = { vm.requestSyncAll() },
                    onSwap = { vm.swapPanes() },
                    recents = latestUi.recents,
                    onUseRecent = { side, uri -> vm.useRecent(side, uri) },
                    onRemoveRecent = { vm.removeRecent(it) },
                    onClearRecents = { vm.clearRecents() },
                    onCopyLeftToRight = { vm.copyAllTo(Side.RIGHT) },
                    onCopyRightToLeft = { vm.copyAllTo(Side.LEFT) },
                    onDelete = { askDelete = true },
                    onSettings = { showSettings = true },
                    onAbout = { showAbout = true },
                    onShortcuts = { showShortcuts = true },
                )
            },
        ) {
            Scaffold(
                modifier = Modifier.shortcuts(
                    onUndo = { vm.undo() },
                    onRedo = { vm.redo() },
                    onSelectAll = { vm.checkAllOf() },
                    onSearch = { searchActive = true },
                    onCycleFilter = { vm.cycleMatchFilter() },
                    onEscape = {
                        if (searchActive) searchActive = false
                        else if (latestUi.checked.isNotEmpty()) vm.clearChecked()
                        else if (latestUi.selectedSource != null) vm.clearSelection()
                    },
                ),
                topBar = {
                    AppTopBar(
                        ui = ui,
                        searchActive = searchActive,
                        onSearchToggle = {
                            searchActive = !searchActive
                            if (searchActive) vm.setQuery("")
                        },
                        onMenu = { scope.launch { drawerState.open() } },
                        onUndo = { vm.undo() },
                        onRedo = { vm.redo() },
                        onFilter = { vm.cycleMatchFilter() },
                        onExtFilter = { showExtFilter = true },
                        onRefresh = { vm.refreshAll() },
                        onSettings = { showSettings = true },
                        onAbout = { showAbout = true },
                    onShortcuts = { showShortcuts = true },
                        onQueryChange = vm::setQuery,
                        onBack = { vm.backFromSub() },
                        onUndoList = { showUndoList = true },
                    )
                },
                bottomBar = {
                    AppNavBar(
                        current = ui.screen,
                        badgeCount = ui.checked.size,
                        onSelect = { vm.setScreen(it) },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    StatusStrip(vm = vm, ui = ui)
                    if (showNarrowHint) {
                        NarrowHint(onDismiss = { narrowHintDismissed = true })
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (ui.screen) {
                            Screen.COMPARE -> Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (dragMode) {
                                            "拖拽改名已开启：按住卡片右侧把手拖动；卡片可正常上下滑，侧滑栏手势暂停"
                                        } else {
                                            "拖拽改名：开启后按住卡片右侧把手拖动，无需长按"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            if (dragMode) vm.setDragSource(null)
                                            dragMode = !dragMode
                                        },
                                    ) {
                                        Text(if (dragMode) "退出" else "开启")
                                    }
                                }
                                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                PaneColumn(
                                    matched = ui.matched,
                                    partnerNames = ui.partnerNames,
                                    extraFilter = ui.extraFilter,
                                    weakKeys = ui.weakKeys,
                                    conflictKeys = ui.conflictKeys,
                                    conflictDelta = ui.conflictDelta,
                                    sizeMatchPartner = ui.sizeMatchPartner,
                                    vm = vm,
                                    side = Side.LEFT,
                                    state = ui.left,
                                    query = ui.query,
                                    matchFilter = ui.matchFilter,
                                    synced = ui.synced,
                                    focusKey = ui.focusKey,
                                    focusNonce = ui.focusNonce,
                                    selectedKey = ui.selectedSource?.key,
                                    dragKey = ui.dragSource?.key,
                                    checked = ui.checked,
                                    showCheckboxes = ui.showCheckboxes,
                                    cardSize = settings.cardSize,
                                    showMeta = settings.showMeta,
                                    showPerf = settings.showPerf,
                                    dragMode = dragMode,
                                    modifier = Modifier.weight(1f),
                                    onPick = onPickLeft,
                                    onEdit = { renameTarget = it },
                                    onPreview = { previewTarget = it },
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                                )
                                PaneColumn(
                                    matched = ui.matched,
                                    partnerNames = ui.partnerNames,
                                    extraFilter = ui.extraFilter,
                                    weakKeys = ui.weakKeys,
                                    conflictKeys = ui.conflictKeys,
                                    conflictDelta = ui.conflictDelta,
                                    sizeMatchPartner = ui.sizeMatchPartner,
                                    vm = vm,
                                    side = Side.RIGHT,
                                    state = ui.right,
                                    query = ui.query,
                                    matchFilter = ui.matchFilter,
                                    synced = ui.synced,
                                    focusKey = ui.focusKey,
                                    focusNonce = ui.focusNonce,
                                    selectedKey = ui.selectedSource?.key,
                                    dragKey = ui.dragSource?.key,
                                    checked = ui.checked,
                                    showCheckboxes = ui.showCheckboxes,
                                    cardSize = settings.cardSize,
                                    showMeta = settings.showMeta,
                                    showPerf = settings.showPerf,
                                    dragMode = dragMode,
                                    modifier = Modifier.weight(1f),
                                    onPick = onPickRight,
                                    onEdit = { renameTarget = it },
                                    onPreview = { previewTarget = it },
                                )
                                }
                            }

                            // 单栏：左右用一个开关切，不再占两个底栏 Tab
                            Screen.SINGLE -> Column(modifier = Modifier.fillMaxSize()) {
                                SingleSideSwitch(
                                    side = ui.singleSide,
                                    onChange = { vm.setSingleSide(it) },
                                )
                                PaneColumn(
                                    matched = ui.matched,
                                    partnerNames = ui.partnerNames,
                                    extraFilter = ui.extraFilter,
                                    weakKeys = ui.weakKeys,
                                    conflictKeys = ui.conflictKeys,
                                    conflictDelta = ui.conflictDelta,
                                    sizeMatchPartner = ui.sizeMatchPartner,
                                    vm = vm,
                                    side = ui.singleSide,
                                    state = ui.pane(ui.singleSide),
                                    query = ui.query,
                                    matchFilter = ui.matchFilter,
                                    synced = ui.synced,
                                    focusKey = ui.focusKey,
                                    focusNonce = ui.focusNonce,
                                    selectedKey = ui.selectedSource?.key,
                                    dragKey = ui.dragSource?.key,
                                    checked = ui.checked,
                                    showCheckboxes = ui.showCheckboxes,
                                    cardSize = settings.cardSize,
                                    showMeta = settings.showMeta,
                                    showPerf = settings.showPerf,
                                    scale = 1.5f,
                                    modifier = Modifier.fillMaxSize(),
                                    onPick = if (ui.singleSide == Side.LEFT) onPickLeft else onPickRight,
                                    onEdit = { renameTarget = it },
                                    onPreview = { previewTarget = it },
                                )
                            }

                            // 工具：二级菜单入口
                            Screen.TOOLS -> ToolsHub(
                                vm = vm,
                                ui = ui,
                                onOpen = { vm.setScreen(it) },
                                onDiagnose = { showDiagnostics = true },
                            )

                            Screen.RENAME -> RenameScreen(vm = vm, ui = ui)
                            Screen.ORGANIZE -> OrganizeScreen(vm = vm, ui = ui)
                            Screen.ANALYZE -> AnalyzeScreen(
                                vm = vm,
                                ui = ui,
                                onStats = { side ->
                                    statsFor = side
                                    vm.analyzeDir(side)
                                },
                            )
                        }
                    }
                    if (hasSelection) {
                        SelectionBar(
                            count = ui.checked.size,
                            canLink = latestUi.checkedItems().size == 2 &&
                                latestUi.checkedItems()[0].side != latestUi.checkedItems()[1].side,
                            bytes = latestUi.checkedItems().sumOf { it.size },
                            onLink = { vm.linkSelected() },
                            onBatch = { vm.requestBatch(dominantSide(latestUi.checkedItems())) },
                            onAlign = { vm.requestAlign() },
                            onCopy = {
                                val side = dominantSide(latestUi.checkedItems())
                                vm.copyCheckedTo(if (side == Side.LEFT) Side.RIGHT else Side.LEFT)
                            },
                            onCopyNames = { vm.copyNamesToClipboard(dominantSide(latestUi.checkedItems())) },
                            onInvert = { vm.invertSelection() },
                            onSelectBy = { vm.selectByCondition(it) },
                            countOf = { vm.countByCondition(it) },
                            onDelete = { askDelete = true },
                            onClear = { vm.clearChecked() },
                        )
                    }
                }
            }

            // ---- 对话框 ----

            renameTarget?.let { item ->
                val siblings = remember(item, latestUi) {
                    latestUi.pane(item.side).items.map { it.displayName }.toSet() - item.displayName
                }
                RenameDialog(
                    item = item,
                    siblings = siblings,
                    numbering = settings.numbering,
                    onDismiss = { renameTarget = null },
                    onConfirm = { base, ext ->
                        vm.commitManualRename(item, base, ext)
                        renameTarget = null
                    },
                )
            }

            previewTarget?.let { item ->
                val partnerKey = latestUi.partner[item.key]
                val partner = remember(latestUi, partnerKey) {
                    partnerKey?.let { k ->
                        (latestUi.left.items + latestUi.right.items).firstOrNull { it.key == k }
                    }
                }
                val preview = remember(item, partner, latestSettings) {
                    partner?.let { vm.previewName(item, it) } ?: ""
                }
                val canApply = partner != null &&
                    Naming.baseOf(item.displayName) != Naming.baseOf(partner.displayName) &&
                    partner.canRename
                // 这一对是不是某条「照拍摄时间换过来」的建议涉及的。
                // 按钮就放在这里，因为用户此刻正在核对这一对 ——
                // 需要的判断摆在他眼前，不该再让他退出面板去别处找入口。
                val pendingSwap = latestUi.timeSwaps.firstOrNull { item.key in it.keys }
                // 这一对是不是某条「搬到对的那张」的建议涉及的。
                // 与 pendingSwap 并列：同一对可能两种修法都有（互换 vs 搬移），
                // 两个按钮同时给出，让用户自己选 —— 判据不同，结果也不同。
                val pendingMove = latestUi.sideMoveByKey[item.key]
                // 这一对参与的「整批重配」——层级与上面两条不同：互换 / 搬移是**逐对**修法，
                // 这条动的是**一整串**。所以除了"有没有出路"，还要说清"这一对会变成什么"：
                // 整批操作最该回答的就是这个，只报一个对数等于让用户在不知道后果时改一批配对。
                val realignPlan = latestUi.timeRealign
                val realignPairs = realignPlan?.pairs?.size ?: 0
                val realignToName = latestUi.realignTargetName[item.key]
                // 会被腾出（回到未配对）的**全部**成员：这一对里可能有 0、1 或 2 张。
                // 两种都常见 —— "这一张换到别人那儿去了，对面那张空出来"是最典型的那种，
                // 所以不能只报一张，也不能和"去向"二选一：那两件事是同时发生的。
                // 用 displayName 而不是 key —— 用户看的是文件名，不是内部 key。
                // partner 在这里可空（面板允许"还没配上"的项打开），所以走 takeIf：
                // Kotlin 不会为一串 ?. 做智能转换。
                val realignFreedName = listOfNotNull(
                    item.takeIf { it.key in latestUi.realignFreedKeys },
                    partner?.takeIf { it.key in latestUi.realignFreedKeys },
                ).joinToString("、") { it.displayName }.ifEmpty { null }
                CompareDialog(
                    item = item,
                    pendingCount = vm.pendingPairCount(),
                    onApplyAndNext = {
                        // partner 为 null 时不做任何事：canApply 已经保证过，
                        // 但这里仍不用 !! —— 强制解包一旦为空就是闪退
                        val target = partner
                        if (target != null && canApply) {
                            vm.applyRename(item, target)
                            // 改名是异步的，这里名单还没更新，
                            // 所以「当前项 +1」拿到的正是下一对（正确行为）
                            previewTarget = vm.compareNeighbor(item, +1)
                        }
                    },
                    onPrev = {
                        vm.compareNeighbor(item, -1)?.let { previewTarget = it }
                    },
                    onNext = {
                        vm.compareNeighbor(item, +1)?.let { previewTarget = it }
                    },
                    partner = partner,
                    preview = preview,
                    canApply = canApply,
                    onDismiss = { previewTarget = null },
                    onApply = {
                        previewTarget = null
                        // 用 ?.let 而不是 !!：lambda 里拿不到非空保证，
                        // 宁可静默什么都不做，也不要崩
                        partner?.let { vm.applyRename(item, it) }
                    },
                    onApplyReverse = {
                        previewTarget = null
                        partner?.let { vm.applyRename(it, item) }
                    },
                    onUnlink = {
                        previewTarget = null
                        vm.unlinkPartner(item.key)
                    },
                    reasonText = vm.reasonText(item.key),
                    weak = vm.isWeakPair(item.key),
                    conflictDeltaMs = latestUi.conflictDelta[item.key] ?: 0L,
                    takenLeft = item.takenAt,
                    takenRight = partner?.takenAt ?: 0L,
                    // 只有在这一对确实参与了某条互换建议时才非空 —— 面板据此显示按钮。
                    // 传 null 而不是"传了但按钮禁用"：没有建议就是没有，不该留一个灰按钮占位。
                    onSwapTime = pendingSwap?.let { sw ->
                        {
                            previewTarget = null
                            vm.applyTimeSwap(sw)
                        }
                    },
                    // 同理：只有这一对确实参与某条搬移建议时才给入口
                    onSideMove = pendingMove?.let { mv ->
                        {
                            previewTarget = null
                            vm.applySideMove(mv)
                        }
                    },
                    // 整批重配：与上面两条同一个约定 —— 没有方案就不给入口（不留灰按钮）。
                    // 方案存在即 pairs.size >= 2（闸 1/2 保证），所以用 plan 判空即可。
                    //
                    // 这里**不直接写**（与互换 / 搬移不同）：那两条只动一两对，按下去就能看出对不对；
                    // 这条一次动一串，先摊开每一对"旧 → 新"再让他点头。实测过"只报对数"的版本 ——
                    // 面板上只写"这一对会改成 X"，用户不知道别处那几对会变成什么。
                    realignPairs = realignPairs,
                    realignToName = realignToName,
                    realignFreedName = realignFreedName,
                    onRealign = realignPlan?.let { { vm.previewRealign() } },
                )
            }

            syncData?.let { args ->
                PairSyncDialog(
                    left = latestUi.left.items,
                    right = latestUi.right.items,
                    leftNames = args.leftNames,
                    rightNames = args.rightNames,
                    partner = latestUi.partner,
                    synced = latestUi.synced,
                    settings = latestSettings,
                    initialDirection = args.direction,
                    onDismiss = { syncData = null },
                    onApply = { rows ->
                        syncData = null
                        applyPlan(rows)
                    },
                )
            }

            confirmApply?.let { ev ->
                ConfirmApplyDialog(
                    fromName = ev.from.displayName,
                    toName = ev.to.displayName,
                    preview = ev.preview,
                    onDismiss = { confirmApply = null },
                    onConfirm = {
                        vm.applyRename(ev.from, ev.to, confirmed = true)
                        confirmApply = null
                    },
                )
            }

            askConflict?.let { ev ->
                AskConflictDialog(
                    suggested = ev.suggested,
                    conflictName = ev.conflictName,
                    onDismiss = { askConflict = null },
                    onChoose = { policy ->
                        vm.applyRename(ev.from, ev.to, overridePolicy = policy, confirmed = true)
                        askConflict = null
                    },
                )
            }

            batchFor?.let { (side, existing, initialMode) ->
                val items = remember(latestUi, side) {
                    latestUi.pane(side).items.filter { it.key in latestUi.checked }
                }
                BatchRenameDialog(
                    items = items,
                    existing = existing,
                    settings = latestSettings,
                    templates = latestUi.templates,
                    initialMode = initialMode,
                    onSaveTemplate = { name, mode, params ->
                        vm.saveTemplate(name, mode, params)
                    },
                    onApplyTemplate = { t -> vm.emitTemplateApplied(t.name) },
                    onRemoveTemplate = { vm.removeTemplate(it) },
                    onExportPlan = { csv -> vm.requestExportPlan(csv) },
                    onDismiss = { batchFor = null },
                    onApply = { rows ->
                        batchFor = null
                        applyPlan(rows)
                    },
                )
            }

            alignData?.let { args ->
                SequenceAlignDialog(
                    left = latestUi.left.items,
                    right = latestUi.right.items,
                    leftNames = args.leftNames,
                    rightNames = args.rightNames,
                    settings = latestSettings,
                    suggestedOffset = args.offset,
                    initialLeftToRight = args.source == Side.LEFT,
                    onDismiss = { alignData = null },
                    onApply = { rows ->
                        alignData = null
                        applyPlan(rows)
                    },
                )
            }

            realignPreview?.let { plan ->
                // byKey / partner 每次都从最新 ui 状态取：方案是快照，但"文件现在叫什么"
                // 只能读当下 —— 拿快照里的旧名字去对，用户会看到改名前的东西。
                RealignPreviewDialog(
                    plan = plan,
                    byKey = (latestUi.left.items + latestUi.right.items).associateBy { it.key },
                    partner = latestUi.partner,
                    onDismiss = { realignPreview = null },
                    onConfirm = {
                        realignPreview = null
                        vm.applyTimeRealign()
                    },
                )
            }

            if (pendingPlan.isNotEmpty()) {
                PlanConfirmDialog(
                    rows = pendingPlan,
                    onDismiss = { pendingPlan = emptyList() },
                    onConfirm = {
                        vm.executePlan(pendingPlan)
                        pendingPlan = emptyList()
                    },
                )
            }

            if (askDelete) {
                val count = latestUi.checkedItems().size
                AlertDialog(
                    onDismissRequest = { askDelete = false },
                    title = { Text(stringResource(R.string.confirm_delete_title, count)) },
                    text = { Text(stringResource(R.string.confirm_delete_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.deleteChecked()
                            askDelete = false
                        }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { askDelete = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            if (showHistory) {
                HistoryDialog(
                    entries = historyEntries,
                    onDismiss = { showHistory = false },
                    onExport = { vm.requestExportHistory() },
                    onRestore = {
                        showHistory = false
                        onOpenCsv()
                    },
                    onClear = {
                        vm.clearHistory()
                        historyEntries = emptyList()
                        showHistory = false
                    },
                )
            }

            if (showDuplicates) {
                DuplicatesDialog(
                    groups = dupGroups,
                    onDismiss = { showDuplicates = false },
                    onTrashGroup = { vm.trashDuplicates(it) },
                )
            }

            if (showArchive) {
                ArchiveDialog(
                    rows = archiveRows,
                    onDismiss = { showArchive = false },
                    onConfirm = {
                        showArchive = false
                        vm.executeArchive(archiveRows)
                    },
                )
            }

            if (showBigFiles) {
                BigFilesDialog(
                    files = bigFiles,
                    summary = bigSummary,
                    onDismiss = { showBigFiles = false },
                )
            }

            if (showTrash) {
                TrashDialog(
                    items = trashItems,
                    onDismiss = { showTrash = false },
                    onRestore = { vm.restoreTrashed(it) },
                    onEmpty = { vm.emptyTrash() },
                )
            }

            latestUi.dirStats?.let { stats ->
                statsFor?.let { side ->
                    DirStatsDialog(
                        stats = stats,
                        sideName = if (side == Side.LEFT) "左栏" else "右栏",
                        onDismiss = { statsFor = null; vm.clearDirStats() },
                    )
                }
            }

            if (showExtFilter) {
                val all = latestUi.left.items + latestUi.right.items
                ExtFilterDialog(
                    exts = availableExts(all),
                    current = latestUi.extraFilter,
                    onToggleExt = { vm.toggleExt(it) },
                    onSizeRange = { mn, mx -> vm.setSizeRange(mn, mx) },
                    onClear = { vm.clearExtraFilter() },
                    onDismiss = { showExtFilter = false },
                )
            }

            if (showShortcuts) {
                ShortcutsDialog(onDismiss = { showShortcuts = false })
            }

            if (showPipeline) {
                PipelineDialog(
                    hasExif = (latestUi.left.items + latestUi.right.items).any { it.takenAt > 0 },
                    hasContent = latestUi.contentKeys.isNotEmpty(),
                    onDismiss = { showPipeline = false },
                    onRun = { exif, content, sync, dir ->
                        showPipeline = false
                        vm.runPipeline(exif, content, sync, dir)
                    },
                )
            }

            // 批量结果报告：只在这批有问题时出现。
            // 挂在 UiState 上而不是本地 remember —— 批量任务跑完时界面可能已经
            // 不在原来的页了（用户去看了工具页），报告不该跟着那个页面一起消失。
            latestUi.batchReport?.let { report ->
                BatchReportDialog(
                    report = report,
                    onRetryFailed = { vm.retryFailedBatch() },
                    onDismiss = { vm.dismissBatchReport() },
                )
            }

            if (showUndoList) {
                UndoListDialog(
                    labels = latestUi.undoLabels,
                    onUndoTo = { index ->
                        vm.undoUntil(index)
                        showUndoList = false
                    },
                    onDismiss = { showUndoList = false },
                    droppedEntries = latestUi.undoDroppedEntries,
                    droppedSteps = latestUi.undoDroppedSteps,
                )
            }

            if (showDiagnostics) {
                DiagnosticsDialog(
                    ui = latestUi,
                    // 在重组时现读一次，而不是打开瞬间拍个快照：
                    // 后台读 EXIF / 算指纹时 ui 会持续变化，对话框跟着重组，
                    // 数字也就跟着动 —— 排查"缓存没生效"时这正是要看的信息
                    cacheNote = vm.metaCacheNote(),
                    sessionNote = vm.sessionNote(),
                    onDismiss = { showDiagnostics = false },
                )
            }

            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }

            if (showSettings) {
                SettingsDialog(
                    settings = settings,
                    onDismiss = { showSettings = false },
                    onChange = { vm.updateSettings(it) },
                    allFilesGranted = allFilesState,
                    onGrantAllFiles = onGrantAllFiles,
                    hasExif = (latestUi.left.items + latestUi.right.items).any { it.takenAt > 0 },
                    showPerf = latestSettings.showPerf,
                    onShowPerfChange = { vm.updateSettings(latestSettings.copy(showPerf = it)) },
                    useExif = latestUi.useExif,
                    onUseExifChange = { vm.toggleUseExif(it) },
                )
            }
        }

        ui.progress?.let { (done, total) ->
            if (total > 0 && done < total) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        tonalElevation = 6.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    ) {
                        Text(
                            text = "处理中 $done / $total",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 顶栏下方的一条状态提示：名字来源 / 时间冲突 / 过滤状态。 */
@Composable
private fun StatusStrip(vm: MainViewModel, ui: com.yuanbao.pairrename.vm.UiState) {
    val source = ui.selectedSource
    val filtering = ui.matchFilter != MatchFilter.ALL || ui.query.isNotEmpty()
    val pending = (ui.matched.size / 2) - (ui.synced.size / 2)
    val conflicts = ui.conflictPairs
    // 其中有多少处已经有确定的换法（成对互换，两对同时变好）。
    // 与 conflicts 一起构成"问题 + 现成解法"：能一键修的先修，剩下的才需要人工核对。
    val swaps = ui.timeSwaps.size
    // 没配上、但尺寸和体积都一模一样的对数（能立刻免费配上，不用跑流水线）。
    // 放在状态条而不是只放工具页：它是"数据质量立刻能改善"的机会，值得被看见。
    val sizeMatches = ui.sizeMatches.size
    // 其中有多少处能"直接搬到对的那张"（尺寸体积全同 + 搬完进容差，比互换更硬）。
    // 搬移的前提是"有一对在冲突里"，所以 moves > 0 必然 conflicts > 0 ——
    // 状态条的可见性判断不用再为它加一条：冲突在一亮，这一条就跟着亮。
    val moves = ui.sideMoves.size
    // 整体平移能一次修几对（逐对判据在均匀错位下一条都出不来）。
    // 同 moves：它也是冲突的派生物，冲突一亮它就跟着亮。
    val realign = ui.timeRealign?.pairs?.size ?: 0
    val freed = ui.timeRealign?.let { it.freedLeft.size + it.freedRight.size } ?: 0
    // 有待处理项时也显示，这样「下一个待处理」始终够得着。
    // **有冲突时更要显示** —— "这一批里有配错的"是最高级别的提示，
    // 不能因为"没选中、没在过滤、也没待统一的"就把整条状态栏藏起来。
    if (source == null && !filtering && pending <= 0 && conflicts <= 0 && sizeMatches <= 0) return

    Surface(tonalElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = when {
                    // 冲突排在最前：它比"还没统一"重要，也比"选中了哪个"更该被看见。
                    // 卡片上已经有红角标和"差 X 小时"，这里是给还没滑到那些卡片的用户看的。
                    // 有确定换法时把数字一并说出来 —— "能一键修好"和"只能自己看"，
                    // 用户接下来该做什么完全不同。三种修法的顺序按"一次解决的面"排：
                    // 整体平移（一串）→ 单侧搬移 → 成对互换 → 只能自己核对。
                    conflicts > 0 && realign > 0 ->
                        "$conflicts 对拍摄时间对不上，其中 $realign 对能整批重配（另有 $freed 张回到未配对）"
                    conflicts > 0 && moves > 0 ->
                        "$conflicts 对拍摄时间对不上，其中 $moves 处能直接搬到对的那张"
                    conflicts > 0 && swaps > 0 ->
                        "$conflicts 对拍摄时间对不上，其中 $swaps 处能一键换回"
                    conflicts > 0 -> "$conflicts 对拍摄时间对不上，照着改会改错文件"
                    source != null -> stringResource(R.string.source_selected, source.displayName)
                    // 漏配：不是错误，是"能立刻变好的机会"，所以排在「待统一」之前
                    sizeMatches > 0 -> "还有 $sizeMatches 对没配上，但尺寸和体积都对得上"
                    pending > 0 -> "还有 $pending 对没统一"
                    else -> stringResource(R.string.hint_drag)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflicts > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 流水线：一键跳到下一个还没统一的，省得满列表找。
            // 有冲突时不显示 —— 那一行要让给「去核对」，先解决配错的再谈统一。
            if (pending > 0 && conflicts <= 0) {
                TextButton(onClick = { vm.focusNextTodo() }) {
                    Text(stringResource(R.string.action_next_todo))
                }
            }
            if (realign > 0) {
                // 与对比面板里那个按钮同一个口径、同一个流程：先摊开逐条预览，再确认。
                // 两个入口走两条路（一个直接写、一个先预览）是这类项目最常见的内伤。
                TextButton(onClick = { vm.previewRealign() }) { Text("整批重配 $realign 对") }
            }
            if (moves > 0) {
                TextButton(onClick = { vm.applySideMoves() }) { Text("搬过去 $moves 处") }
            }
            if (swaps > 0) {
                TextButton(onClick = { vm.applyBestTimeSwap() }) { Text("换回 $swaps 处") }
            }
            if (conflicts > 0) {
                TextButton(onClick = { vm.reviewConflicts() }) { Text("去核对") }
            }
            // 漏配排在冲突之后：先把配错的修了，再谈把没配上的配上。
            // 与「下一个待处理」并列（都是"能做的事"，只在没冲突时出现）。
            if (sizeMatches > 0 && conflicts <= 0) {
                TextButton(onClick = { vm.applySizeMatches() }) { Text("配上 $sizeMatches 对") }
            }
            if (source != null) {
                TextButton(onClick = { vm.clearSelection() }) {
                    Text(stringResource(R.string.cancel))
                }
            } else if (filtering) {
                TextButton(onClick = { vm.resetFilters() }) { Text("清除过滤") }
            }
        }
    }
}

/** 窄屏双栏太挤时，提示可以切到单栏查看。 */
@Composable
private fun NarrowHint(onDismiss: () -> Unit) {
    Surface(tonalElevation = 1.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(
                text = "屏幕较窄，可切到底部「左栏 / 右栏」单独放大查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    canLink: Boolean,
    /** 勾选文件的总体积，超过 0 才显示。 */
    bytes: Long = 0,
    onLink: () -> Unit,
    onBatch: () -> Unit,
    onAlign: () -> Unit,
    onCopy: () -> Unit,
    /** 把勾选的文件名复制到剪贴板。 */
    onCopyNames: () -> Unit = {},
    /** 反选。 */
    onInvert: () -> Unit = {},
    /** 按条件选择。 */
    onSelectBy: (SelectCondition) -> Unit = {},
    /** 各条件的命中数（0 的置灰）。 */
    countOf: (SelectCondition) -> Int = { 0 },
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 6.dp)) {
                Text(
                    text = stringResource(R.string.sel_count, count),
                    style = MaterialTheme.typography.titleSmall,
                )
                // 勾选后顺手告诉你一共多大，删之前心里有数
                if (bytes > 0) {
                    Text(
                        text = Naming.formatSize(bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                // 配对错了要能改，所以「设为配对」放在第一位
                if (canLink) {
                    TextButton(onClick = onLink) { Text(stringResource(R.string.action_link)) }
                }
                TextButton(onClick = onCopyNames) {
                    Text(stringResource(R.string.action_copy_names))
                }
                TextButton(onClick = onInvert) {
                    Text(stringResource(R.string.action_invert))
                }
                ConditionMenu(countOf = countOf, onSelect = onSelectBy)
                TextButton(onClick = onBatch) { Text(stringResource(R.string.action_batch)) }
                TextButton(onClick = onAlign) { Text(stringResource(R.string.action_align)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_sel)) }
        }
    }
}

@Composable
private fun PlanConfirmDialog(
    rows: List<PlanRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val changed = rows.count { it.changed }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_title)) },
        text = {
            Column {
                Text(stringResource(R.string.batch_preview, rows.size, changed))
                rows.take(3).forEach { row ->
                    Text(
                        "${row.oldName}  →  ${row.newName}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                if (rows.size > 3) {
                    Text(
                        "… 其余 ${rows.size - 3} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column {
                Text(
                    "左右各选一个文件夹，并排显示图片与文件名，用拖拽或点选把一边的名字应用到另一边。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "手势：轻点选中名字来源 · 长按卡片拖拽 · 双击打开对比 · " +
                        "点圆圈勾选批量 · 点铅笔手动改名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "配对靠文件名里的序号和排列顺序推算，不要求两边名字相同；" +
                        "点卡片左下角的角标可跳到另一栏的对应文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "改名不会重编码图片，只改文件名，所以不会损失画质。所有操作都可通过顶栏撤销 / 重做回退。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "文件读写走系统 SAF，不申请存储权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "版本 ${com.yuanbao.pairrename.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/**
 * 按条件选择的下拉菜单。
 *
 * 手动一张张勾太累 —— 几百张里找出"没配对的""名字有问题的"
 * 才是这个工具真正该干的事。命中数为 0 的条件直接置灰，
 * 点开就知道有哪些可选。
 */
@Composable
private fun ConditionMenu(
    countOf: (SelectCondition) -> Int,
    onSelect: (SelectCondition) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.action_select_cond))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SelectCondition.entries.forEach { cond ->
                val n = countOf(cond)
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(cond.label())
                            Text(
                                text = n.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (n > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    },
                    enabled = n > 0,
                    onClick = {
                        expanded = false
                        onSelect(cond)
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectCondition.label(): String = when (this) {
    SelectCondition.UNPAIRED -> stringResource(R.string.cond_unpaired)
    SelectCondition.PAIRED -> stringResource(R.string.cond_paired)
    SelectCondition.UNSYNCED -> stringResource(R.string.cond_unsynced)
    SelectCondition.NO_EXIF -> stringResource(R.string.cond_no_exif)
    SelectCondition.HAS_EXIF -> stringResource(R.string.cond_has_exif)
    SelectCondition.DIRTY_NAME -> stringResource(R.string.cond_dirty_name)
    SelectCondition.LARGE -> stringResource(R.string.cond_large)
    SelectCondition.CANNOT_RENAME -> stringResource(R.string.cond_cannot_rename)
}

/**
 * 「按配对统一」对话框需要的数据。
 *
 * 用具名 data class 而不是 Triple：方向这个字段是后来加的，
 * 三元组塞不下第四个值，而 Pair<Triple, X> 读起来全是 .first/.second。
 */
private data class SyncArgs(
    val leftNames: Set<String>,
    val rightNames: Set<String>,
    val direction: SyncDirection,
)

/**
 * 「顺序对齐」对话框需要的数据。
 *
 * 同理：offset + 基准栏，两个都必须带过去，
 * 否则工具页的「左→右 / 右→左」两个入口打开的面板就一模一样了。
 */
private data class AlignArgs(
    val leftNames: Set<String>,
    val rightNames: Set<String>,
    val offset: Int,
    val source: Side,
)
