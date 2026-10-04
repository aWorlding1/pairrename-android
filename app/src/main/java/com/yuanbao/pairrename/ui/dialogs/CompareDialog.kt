package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.data.ExifMeta
import com.yuanbao.pairrename.ui.ZoomableImage
import com.yuanbao.pairrename.ui.rotationFor
import com.yuanbao.pairrename.util.Naming

/**
 * 双击卡片打开的预览 / 对比面板。
 *
 * 存在的意义：把 A 的名字给 B 之前，得先确认 A 和 B 确实是同一张图。
 * 列表里 118dp 的缩略图太小，HEIC 之类还可能根本不出图，
 * 光看缩略图就改名字很容易改错。
 */
@Composable
fun CompareDialog(
    item: ImageItem,
    partner: ImageItem?,
    /** 目标文件按当前设置会被改成什么名字。 */
    preview: String,
    canApply: Boolean,
    onDismiss: () -> Unit,
    /** 把当前文件的名字应用到对方身上。 */
    onApply: () -> Unit,
    /** 反过来：把对方的名字应用到当前文件。 */
    onApplyReverse: () -> Unit,
    /** 配对错了 —— 解除它，避免照着错的配对改名。 */
    onUnlink: () -> Unit,
    /** 这一对为什么配在一起。 */
    reasonText: String,
    /**
     * 这一对是不是弱依据（靠序号 / 顺序猜出来的）。
     *
     * true 时把依据一行改成错误色并加一条横幅 —— 「复核可疑配对」正是靠它
     * 让用户知道"这一对值得多看两眼"。默认 false：普通调用点不受影响。
     */
    weak: Boolean = false,
    /**
     * 这一对两边拍摄时间的差值（毫秒），0 表示不冲突。
     *
     * 与 [weak] **正交**：weak 问"凭什么配上的"，这个是"配上了但证据打架吗"。
     * 一个对可能两边都占（猜出来的、又和拍摄时间矛盾）——那种要同时顶两条横幅。
     */
    conflictDeltaMs: Long = 0,
    /**
     * 「照拍摄时间把这一对（连同被换的另一对）换过来」—— null 表示这一对没有可换的方案。
     *
     * 只对**成对互换**给入口（参与交换的两对都得是冲突对）。
     * 具体换法与三道闸见 [com.yuanbao.pairrename.util.findTimeSwaps]。
     */
    onSwapTime: (() -> Unit)? = null,
    /**
     * 「把这一张搬到对的那张上去」—— null 表示没有可搬的方案。
     *
     * 与 [onSwapTime] 互补：互换要求两对都配错、且能互相接手；
     * 搬移针对"只有这一对配错、它真正的对象还空着"。判据更硬
     * （尺寸体积全同 + 搬完进 2 秒容差），代价是原来配着的那张回到未配对 ——
     * 它本来就没对象。见 [com.yuanbao.pairrename.util.findSideMoves]。
     */
    onSideMove: (() -> Unit)? = null,
    /**
     * 这一对参与的「整批按拍摄时间重配」能修好几对；0 表示不显示这个块。
     *
     * 与 [onSwapTime] / [onSideMove] 的层级不同：那两条是**逐对**修法，
     * 这个动的是**一批**文件。均匀错位（两边各自从 0001 编号）下每一张的真对象
     * 都配着别人，逐对判据一条都出不来 —— 用户能做的决定只有"整批重配"或"算了"。
     * 这正是用户点了「去核对」之后落到的那个面板，所以出路必须摆在这里，
     * 而不是让他退回状态条去找。见 [com.yuanbao.pairrename.util.findTimeRealign]。
     */
    realignPairs: Int = 0,
    /**
     * 整批重配后**我方这一张**会改配到谁（null = 这一张不变、或没参与重配）。
     *
     * 只说"能重配 N 对"是不够的：整批操作最该回答的是"我眼前这一对会变成什么"。
     */
    realignToName: String? = null,
    /**
     * 整批重配后会**回到未配对**的那一张的文件名（null = 这一对里没有）。
     *
     * 必须单独说出来：用户按了整批重配，结果眼前这一对散了一个 ——
     * 事先不讲清楚，他会当成"把配对弄丢了"。
     */
    realignFreedName: String? = null,
    /**
     * 打开整批重配的**逐条预览**（null = 没有可用方案）。
     *
     * 注意它不直接写文件：这一条动的是**一整串**配对，面板上只能讲清"你眼前这一对"，
     * 所以按钮交给上一层的 [RealignPreviewDialog] 把每一对摊开，确认后才落盘。
     */
    onRealign: (() -> Unit)? = null,
    /** 左右两侧的 EXIF 证据（拍摄时间等），null 表示没读过。 */
    takenLeft: Long = 0,
    takenRight: Long = 0,
    /** 应用名字后自动跳下一对（形成逐个确认的流水线）。 */
    onApplyAndNext: (() -> Unit)? = null,
    /** 跳到上一对（null 表示不可用）。 */
    onPrev: (() -> Unit)? = null,
    /** 跳到下一对（null 表示不可用）。 */
    onNext: (() -> Unit)? = null,
    /** 还有多少对没确认（显示进度）。 */
    pendingCount: Int = 0,
) {
    val shape = RoundedCornerShape(12.dp)
    val ctx = LocalContext.current
    // 只为了拿 EXIF 方向：方向错了图片会躺倒，
    // 一张躺倒一张正立会让人误判成两张不同的图
    var rotLeft by remember(item.key) { mutableFloatStateOf(0f) }
    var rotRight by remember(item.key) { mutableFloatStateOf(0f) }
    LaunchedEffect(item.key) {
        withContext(Dispatchers.IO) {
            rotLeft = rotationFor(ExifMeta.read(ctx, item))
            val p2 = partner
            if (p2 != null) rotRight = rotationFor(ExifMeta.read(ctx, p2))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (partner != null) "确认是同一张？" else "预览",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (partner == null) {
                    // 没配上对，只显示这一张
                    SinglePreview(item, shape)
                    InfoLines(item)
                    Text(
                        text = "这一张在另一栏没找到配对对象，" +
                            "可能是另一栏缺这张，也可能是两边序号差太多。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    val (left, right) = if (item.side == Side.LEFT) item to partner else partner to item
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            SideLabel(if (left.side == Side.LEFT) "左" else "右")
                            ZoomableImage(
                                item = left,
                                rotation = if (left.side == Side.LEFT) rotLeft else rotRight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(shape),
                            )
                            FileName(left.displayName, highlight = left.side == item.side)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SideLabel(if (right.side == Side.LEFT) "左" else "右")
                            ZoomableImage(
                                item = right,
                                rotation = if (right.side == Side.LEFT) rotLeft else rotRight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(shape),
                            )
                            FileName(right.displayName, highlight = right.side == item.side)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        Text(
                            text = if (canApply) "应用后：" else "两边名字已经一致",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (canApply) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = Naming.baseOf(partner.displayName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Text(
                        text = "配对依据：$reasonText",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (weak) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    // 弱依据（靠序号 / 顺序猜的）额外顶一条醒目横幅。
                    // 用户点开对比面板就是为了确认"这俩到底是不是同一张"，
                    // 而"该不该多看一眼"这件事必须一眼看出来 ——
                    // 一行灰色的"配对依据：序号都是 0007"太容易被略过。
                    if (weak) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = shape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Text(
                                text = "这一对是「猜」的：没有内容或拍摄时间做凭证，" +
                                    "只是编号或排位碰巧对上。确认前请多看一眼，" +
                                    "不对就点「不是同一张，解除配对」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    // 时间冲突：比「猜的」更硬的一条警告。
                    //
                    // 「猜的」只是"凭证不够"，用户还可能赌一把；时间冲突是"凭证之间互相打架" ——
                    // 两边都有拍摄时间却被配成一对，只有两种可能：配错了，或者至少一边的时间是错的。
                    // 无论哪种，照这个配对改名都会改错文件，所以必须比「猜的」更醒目。
                    if (conflictDeltaMs > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = shape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Text(
                                text = "这一对自相矛盾：两边都有拍摄时间，却相差 " +
                                    Naming.formatDuration(conflictDeltaMs) +
                                    "。拍摄时间比序号、顺序、甚至名字都硬 —— " +
                                    "多半是两边各自从 0001 开始编号造成的错配，" +
                                    "照这样改名就会改错文件。请核对后再决定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    // 「整批重配」的出路 —— 这条**不是**警告，是答案。
                    //
                    // 上面一条只说"这一对不对劲"，没说它能不能修好。均匀错位
                    // （两边各自从 0001 编号）下逐对判据一条都出不来，唯一的解法是整批重配；
                    // 而用户此刻正站在最该知道这件事的地方 —— 他是点「去核对」进来的。
                    // 把出路留在状态条上，等于让他退出去别处找。
                    //
                    // 用 tertiaryContainer 而不是 errorContainer：上面是"出问题了"，
                    // 这里是"有办法" —— 两种语气共用一个颜色，用户会以为又报了一个错。
                    if (realignPairs > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = shape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "这一批是整体错位：两边各自从 0001 编号，" +
                                        "按序号配出来的每一对都差差不多一个量。这种错位" +
                                        "逐对修不了（怎么换都会弄坏另一对），只能整批按拍摄时间" +
                                        "重配，一次能修好 $realignPairs 对；配不上的会明确" +
                                        "回到未配对，改错了也能整体撤回。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                // 整批操作最该回答的其实是"我眼前这一对会变成什么"。
                                // 只报一个对数，就是让用户在不知道后果的情况下改一批配对。
                                //
                                // 两条**各自独立**判断，不写成 if/else：最典型的那种变化
                                // 正是"我这张换到别人那儿去了（有去向）+ 对面那张空出来
                                // （被腾出）"两件事同时发生 —— 写成 else 会把后者藏掉，
                                // 用户按下按钮才发现对面那张不见了。
                                val to = realignToName
                                if (to != null) {
                                    Text(
                                        text = "这一对会改成：$to",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                val freed = realignFreedName
                                if (freed != null) {
                                    // 用户按下整批重配，结果眼前这一对散了一个 ——
                                    // 事先不讲清楚，他会当成"把配对弄丢了"。
                                    Text(
                                        text = "$freed 会回到未配对：它在这一批里找不到" +
                                            "拍摄时间对得上的对象。与其继续配错，不如空着。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    // 拍摄时间是判断「是不是同一张」最硬的证据：
                    // 缩略图看不准，但两张图的拍摄时间完全一致，几乎不可能是巧合。
                    if (takenLeft > 0 || takenRight > 0) {
                        val agree = takenLeft > 0 && takenLeft == takenRight
                        Surface(
                            tonalElevation = if (agree) 2.dp else 0.dp,
                            color = if (agree) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = if (agree) {
                                        "拍摄时间完全一致 —— 几乎可以确定是同一张"
                                    } else {
                                        "拍摄时间不一致，请确认是否同一张"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (agree) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    },
                                )
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                ) {
                                    Text(
                                        text = "左 ${ExifMeta.timestampName(takenLeft).ifEmpty { "无" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (agree) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        },
                                    )
                                    Text(
                                        text = "右 ${ExifMeta.timestampName(takenRight).ifEmpty { "无" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (agree) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "未读取拍摄时间。可在工具页点「读取拍摄时间」获得更可靠的判断依据。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // 规格对比：尺寸和体积是判断"是不是同一张"的关键线索。
                    // 一张 4000×3000、另一张 800×600 —— 那多半是缩略图不是原图。
                    //
                    // 这里**不写** `if (partner != null)`：本行已经在
                    // `partner == null` 的 else 分支里，partner 必然非空，
                    // 编译器会直接报 "Condition is always 'true'"。
                    SpecCompare(left = item, right = partner)

                    // 配对是推算出来的，必然有误配。
                    // 照着错的配对改名就是改错文件，所以纠正入口必须显眼。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        // 「整批重配」排在最前：它与下面两条不是同一层级 —— 互换 / 搬移
                        // 只动这一对（最多两对），重配动的是**一整串**。Advisor 的次序
                        // 也是它优先（495 > 490 > 480，面最大的先做，剩下的零头再逐对处理）。
                        // 面板里保持同一个次序，否则用户在不同入口看到的"推荐先做哪个"
                        // 是互相矛盾的 —— 那正是这个项目反复修的那类内部打架。
                        val realign = onRealign
                        if (realign != null) {
                            FilledTonalButton(
                                onClick = realign,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    Icons.Default.AutoFixHigh,
                                    null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(" 整批重配 $realignPairs 对")
                            }
                        }
                        // 「照拍摄时间换过来」排在「解除」**之前**：换回来是把这一对**修对**，
                        // 解除只是"不认这一对"。用户真正想要的是正确的配对，不是没有配对 ——
                        // 能修好的选项理应更靠前，也更容易够到。
                        val swap = onSwapTime
                        if (swap != null) {
                            FilledTonalButton(
                                onClick = swap,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(" 照拍摄时间换过来")
                            }
                        }
                        // 「搬到对的那张」也排在「解除」之前，理由同上：修对 > 不认。
                        // 与「换过来」区分：换过来是两张互相接手（都还在配对里），
                        // 搬过去是这一张换对象、原来那张空出来（因为它的真对象不在这批里）。
                        val move = onSideMove
                        if (move != null) {
                            FilledTonalButton(
                                onClick = move,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    Icons.Default.MoveUp,
                                    null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(" 搬到对的那张")
                            }
                        }
                        TextButton(onClick = onUnlink) {
                            Icon(
                                Icons.Default.LinkOff,
                                null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                " 不是同一张，解除配对",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (partner != null && canApply) {
                FilledTonalButton(onClick = onApply) {
                    Text("用「${sideName(item.side)}」的名字")
                }
                // 「应用并下一个」：逐个确认时最省事的一步，
                // 省掉「关掉 → 找下一对 → 双击」三次操作
                if (onApplyAndNext != null && onNext != null) {
                    Button(
                        onClick = onApplyAndNext,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("应用并看下一对") }
                }

                // 导航：确认完直接跳下一对，不用关掉再找
                if (onPrev != null || onNext != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        if (onPrev != null) {
                            OutlinedButton(
                                onClick = onPrev,
                                modifier = Modifier.weight(1f),
                            ) { Text("← 上一对") }
                        }
                        if (onNext != null) {
                            OutlinedButton(
                                onClick = onNext,
                                modifier = Modifier.weight(1f),
                            ) { Text("下一对 →") }
                        }
                    }
                    if (pendingCount > 0) {
                        Text(
                            text = "还有 $pendingCount 对没确认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = {
            if (partner != null) {
                if (canApply) {
                    OutlinedButton(onClick = onApplyReverse) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(" 反向应用", modifier = Modifier.padding(start = 2.dp))
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        },
    )
}

@Composable
private fun sideName(side: Side): String = if (side == Side.LEFT) "左" else "右"

@Composable
private fun SideLabel(text: String) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun FileName(name: String, highlight: Boolean) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

@Composable
private fun SinglePreview(item: ImageItem, shape: RoundedCornerShape) {
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.docUri)
                .size(1024)
                .crossfade(true)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape),
        )
    }
}

@Composable
private fun InfoLines(item: ImageItem) {
    Text(
        text = item.displayName,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
    Text(
        text = listOf(item.dimension, Naming.formatSize(item.size))
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 规格对比（尺寸 / 体积）。
 *
 * 为什么需要：光看缩略图很难判断，但规格差异是硬证据。
 * 尺寸差很多基本可以断定不是同一张原图；
 * 体积差很多则可能是不同压缩率或不同导出质量 —— 属于"值得看一眼"的级别。
 */
@Composable
private fun SpecCompare(left: ImageItem, right: ImageItem) {
    val sizeDiff = if (left.size > 0 && right.size > 0) {
        val ratio = maxOf(left.size, right.size).toFloat() /
            minOf(left.size, right.size).toFloat()
        ratio
    } else {
        0f
    }
    // 尺寸不同是强信号：分辨率都不一样，不可能还是同一张原图
    val dimMismatch = (left.width > 0 && right.width > 0) &&
        (left.width != right.width || left.height != right.height)

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "左 ${left.dimension.ifEmpty { "未知" }} · ${Naming.formatSize(left.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "右 ${right.dimension.ifEmpty { "未知" }} · ${Naming.formatSize(right.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                dimMismatch -> {
                    Text(
                        text = "尺寸不同 —— 很可能不是同一张原图（比如一边是缩略图）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                sizeDiff >= 3f -> {
                    Text(
                        text = "体积相差 ${"%.1f".format(sizeDiff)} 倍 —— 压缩率差异较大，留意一下",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
