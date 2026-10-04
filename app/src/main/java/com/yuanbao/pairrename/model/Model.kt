package com.yuanbao.pairrename.model

/** 左右两栏。 */
enum class Side { LEFT, RIGHT }

/**
 * 排序方式。
 *
 * TAKEN 按 EXIF 拍摄时间排 —— 文件名乱了（比如 `mmexport1a2b3c.jpg`）时，
 * 这是唯一还有意义的顺序，也是「按排列顺序配对」的准确依据。
 */
enum class SortOrder { NAME, DATE_DESC, SIZE_DESC, TAKEN }

/** 目标名称已存在时的处理策略。 */
enum class ConflictPolicy { AUTO_RENAME, SKIP, OVERWRITE, ASK }

/** 自动编号样式：name (1).jpg / name_1.jpg */
enum class NumberingStyle { PARENTHESES, UNDERSCORE }

/** 跨扩展名拖动时，采用谁的扩展名。 */
enum class ExtensionPolicy { KEEP_TARGET, USE_SOURCE }

/** 同目录拖放时的行为。 */
enum class SameFolderMode { SWAP, SHIFT }

/** 主题。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 卡片尺寸，决定网格列宽。 */
enum class CardSize(val minDp: Int) { SMALL(92), MEDIUM(120), LARGE(160) }

/**
 * 列表过滤。
 *
 * 主语义是「改名进度」而不是「配对与否」：
 * 用户真正关心的是还剩哪些没统一，而不是两边名字是否恰好相同。
 *
 * UNPAIRED / ONLY_LEFT / ONLY_RIGHT 用于「差异」：
 * 只显示这边有、对面没有的文件，一眼找出多出来的那几张。
 *
 * SUSPECT 用于「复核」：过滤语义此前只有"改了没有"（进度）和"对面有没有"（差异），
 * 唯独没有"这个配对有多可信"。而配对里 `顺序` / `序号` 是**碰巧对上**的弱证据 ——
 * 两张毫不相干的图完全可能都叫 001。统计页把"靠顺序 5 对"报出来之后，
 * 用户下一步必然是"是哪 5 对" —— 这个枚举值就是回答。
 *
 * CONFLICT 用于「矛盾」：与 SUSPECT **正交**，不是程度差别而是性质差别。
 * SUSPECT 问的是"这个依据本身够不够硬"；CONFLICT 问的是
 * "这个配对和另一条更强的证据打不打架" ——
 * 依据可以很强（比如主文件名相同），两边拍摄时间却相差两小时。
 * 只看依据强弱永远看不到这一类，所以必须单独一个枚举值。
 */
enum class MatchFilter { ALL, TODO, DONE, UNPAIRED, SUSPECT, CONFLICT, ONLY_LEFT, ONLY_RIGHT }

/**
 * 一级：底部导航（COMPARE / SINGLE / TOOLS）
 * 二级：从 TOOLS 进入的分组页（RENAME / ORGANIZE / ANALYZE），
 *       点顶栏返回键回到 TOOLS。这样功能不再堆在一个屏里。
 */
enum class Screen {
    COMPARE, SINGLE, TOOLS,
    RENAME, ORGANIZE, ANALYZE;

    /** 是否属于二级页（决定顶栏是否显示返回箭头）。 */
    val isSub: Boolean get() = this == RENAME || this == ORGANIZE || this == ANALYZE

    /** 二级页返回到哪。 */
    val parent: Screen get() = if (isSub) TOOLS else this
}

/** 批量改名模式。 */
/** 批量改名模式。TRIM = 去掉名字末尾的自动编号/序号。 */
/** 批量改名模式。TRIM = 去末尾序号，FIX = 一键清理常见脏名字。 */
/**
 * 批量改名的模式。
 *
 * REGEX / SUBSTR / INSERT / DELETE 是后来补的：
 * 实际用起来，「把文件名里第 3~8 位去掉」「在中间插一段」
 * 这类需求用查找替换做不到，只能一张张手改。
 */
enum class BatchMode {
    NUMBER, REPLACE, AFFIX, CASE, TRIM, FIX,
    /** 正则替换。 */
    REGEX,
    /** 截取：只保留指定区间。 */
    SUBSTR,
    /** 在指定位置插入文本。 */
    INSERT,
    /** 删除指定区间。 */
    DELETE,
}

/** 按配对关系统一名字时的方向。 */
enum class SyncDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/** 大小写转换。 */
enum class CaseOp { LOWER, UPPER, TITLE }

data class BatchParams(
    val baseName: String = "IMG",
    val startIndex: Int = 1,
    val digits: Int = 3,
    val find: String = "",
    val replace: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val caseOp: CaseOp = CaseOp.LOWER,
    // ---- REGEX ----
    /** 正则表达式。 */
    val pattern: String = "",
    /** 替换串，支持 $1 反向引用。 */
    val replacement: String = "",
    val ignoreCase: Boolean = false,
    // ---- SUBSTR / INSERT / DELETE ----
    /** 起始下标，支持负数（从末尾算）。 */
    val from: Int = 0,
    /** 结束下标（不含），null 表示到末尾。 */
    val to: Int? = null,
    /** INSERT 模式要插入的文本。 */
    val insertText: String = "",
    val keepExtension: Boolean = true,
    /** FIX 模式的清理项。 */
    val clean: CleanOptions = CleanOptions(),
)

/**
 * 文件名清理项。
 *
 * 从相册/聊天工具/网盘导出的文件名常有各种脏东西：
 * 首尾空格、连续空格、`.JPG` 大写扩展名、Windows 不允许的字符、
 * 甚至换行符。这些在手机上多半看不出问题，一传到电脑就报错。
 */
data class CleanOptions(
    val trimSpace: Boolean = true,
    val collapseSpace: Boolean = true,
    val normalizeExtCase: Boolean = true,
    val stripIllegal: Boolean = true,
    val stripControl: Boolean = true,
) {
    /** 一个都没勾时视为无效（避免误点导致"什么都没改"）。 */
    val isEmpty: Boolean get() = !trimSpace && !collapseSpace &&
        !normalizeExtCase && !stripIllegal && !stripControl
}

/**
 * 按条件选择。
 *
 * 手动一张张勾太累 —— 几百张里找出"没配对的""名字有问题的"
 * 正是这个工具的价值所在，必须能一键选出来。
 */
enum class SelectCondition {
    UNPAIRED,       // 没配上对的
    PAIRED,         // 已配对
    UNSYNCED,       // 已配对但名字还没统一
    NO_EXIF,        // 没有拍摄时间
    HAS_EXIF,       // 有拍摄时间
    DIRTY_NAME,     // 名字需要清理（空格/大写扩展名/非法字符）
    LARGE,          // 体积大于阈值
    CANNOT_RENAME,  // 该存储不支持改名
}

/** 常用批量参数模板，存起来下次直接套用。 */
data class BatchTemplate(
    val id: Long = 0,
    val name: String = "",
    val mode: BatchMode = BatchMode.REPLACE,
    val params: BatchParams = BatchParams(),
)

data class AppSettings(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.AUTO_RENAME,
    val numbering: NumberingStyle = NumberingStyle.PARENTHESES,
    val extensionPolicy: ExtensionPolicy = ExtensionPolicy.KEEP_TARGET,
    val sameFolderMode: SameFolderMode = SameFolderMode.SWAP,
    val prefix: String = "",
    val suffix: String = "",
    val confirmBeforeApply: Boolean = false,
    /** 批量执行前再弹一次确认（预览对话框之外）。 */
    val confirmBatch: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME,
    /** 是否连子文件夹一起扫描。 */
    val recursive: Boolean = false,
    /**
     * 扫描后自动读取 EXIF 拍摄时间。
     * 有「管理所有文件」权限时走文件路径直读，很快，默认开；
     * 没权限时走 SAF 流会逐张 IPC，默认关以免拖慢扫描。
     */
    val autoExif: Boolean? = null,
    /** 是否在卡片上显示重组/加载计数（排查卡顿时打开）。 */
    val showPerf: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cardSize: CardSize = CardSize.MEDIUM,
    val showMeta: Boolean = true,
    /**
     * 扫描时是否逐个读取图片宽高。
     *
     * **默认关闭**：每读一张都要走一次 DocumentsProvider 的 IPC，
     * 60 张就是 60 次往返，扫描会明显变慢（表现就是一直转圈）。
     * 需要看尺寸时再在设置里打开。
     */
    val readBounds: Boolean = false,
    val digits: Int = 3,
    val startIndex: Int = 1,
)
