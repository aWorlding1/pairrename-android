package com.yuanbao.pairrename.util

import com.yuanbao.pairrename.model.CaseOp
import com.yuanbao.pairrename.model.CleanOptions
import com.yuanbao.pairrename.model.NumberingStyle

/**
 * 纯 Kotlin 命名工具：不依赖 Android，便于单独校验。
 */
object Naming {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\r\n\t]")

    /** 文件名字节上限。留足余量：扩展名 + provider 自动追加的 " (1)"。 */
    private const val MAX_BYTES = 240

    /** 序号位数上限。超过就当它是时间戳 / 哈希。 */
    private const val MAX_SEQ_DIGITS = 9

    /**
     * 去掉文件系统非法字符，避免以点/空格结尾，并截断到安全的字节长度。
     * @param reserve 为扩展名等预留的字节数。
     */
    fun sanitize(input: String, reserve: Int = 0): String {
        val cleaned = ILLEGAL.replace(input, "_").trim().trimEnd('.', ' ').ifEmpty { "unnamed" }
        return truncateBytes(cleaned, (MAX_BYTES - reserve).coerceAtLeast(16))
    }

    /** 扩展名只保留字母数字，杜绝 "a/b"、空格之类导致 rename 失败的取值。 */
    fun sanitizeExt(input: String): String =
        input.filter { it.isLetterOrDigit() }.take(12)

    /** 按 UTF-8 字节截断，且不会把多字节字符截成半截。 */
    fun truncateBytes(input: String, maxBytes: Int): String {
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val out = StringBuilder()
        var used = 0
        for (ch in input) {
            val cost = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + cost > maxBytes) break
            out.append(ch)
            used += cost
        }
        return out.toString().trim().trimEnd('.', ' ').ifEmpty { "unnamed" }
    }

    /** 拆分主文件名与扩展名：a.b.jpg -> ("a.b","jpg")；无扩展名 -> (原名,"")。 */
    fun splitExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        return name.substring(0, dot) to name.substring(dot + 1)
    }

    fun join(base: String, ext: String): String =
        if (ext.isEmpty()) base else "$base.$ext"

    fun extensionOf(name: String): String = splitExt(name).second

    fun baseOf(name: String): String = splitExt(name).first

    /** 套用前缀/后缀模板。 */
    fun withTemplate(base: String, prefix: String, suffix: String): String = "$prefix$base$suffix"

    fun withNumber(base: String, index: Int, style: NumberingStyle): String = when (style) {
        NumberingStyle.PARENTHESES -> "$base ($index)"
        NumberingStyle.UNDERSCORE -> "${base}_$index"
    }

    /** 固定位数的编号：7, 3 -> "007"。 */
    fun padded(index: Int, digits: Int): String = index.toString().padStart(digits.coerceIn(1, 8), '0')

    /**
     * 一整个括号（中英文圆括号 / 方括号）连同里面的内容。
     *
     * 系统复制出来的文件会带上 `(1)`、`（2）` 这种尾巴，里面的数字是
     * 「第几个副本」，不是序号。**凡是取序号的代码都必须先过这一关** ——
     * 否则 `IMG_1234 (1).jpg` 会取到 1 而不是 1234。
     * 而「名字对不上、还拖着个 (1) 尾巴」恰恰是用户打开这个应用时最常见的输入。
     */
    private val ANY_BRACKET = Regex("""[\(\（\[\【][^\)\）\]\】]*[\)\）\]\】]""")

    /**
     * 文件名里「最后一段连续数字」的**原始文本**；没有 / 过长则 null。
     *
     * 序号几乎总落在末尾，所以从后往前找。位数上限 9：再长多半是
     * 时间戳（`20240920143015`）或哈希，不是序号。
     *
     * 返回**文本**而不是 Int 是刻意的：「4 位且像年份」这条策略需要知道
     * 原始位数（`0042` 和 `42` 数值一样，但前者是补零序号、后者不是），
     * 所以解析放到上层做。
     */
    private fun lastDigitRun(s: String): String? {
        var end = s.length
        while (end > 0 && !s[end - 1].isDigit()) end--
        if (end == 0) return null
        var start = end
        while (start > 0 && s[start - 1].isDigit()) start--
        return s.substring(start, end).takeIf { it.length <= MAX_SEQ_DIGITS }
    }

    /**
     * 取序号的像素：先剥括号再取末尾数字，剥完取不到就退回原样再取一次。
     *
     * 「先干净版、再退回原样」是刻意的：这样**原本有值的名字不可能变成 null**，
     * 只会变得更准（`IMG_1234 (1)` 从 1 变成 1234，而不是变成 null）。
     */
    private fun seqDigits(name: String): Pair<String, Int>? {
        val base = splitExt(name).first
        val clean = ANY_BRACKET.replace(base, " ")
        val raw = lastDigitRun(clean) ?: lastDigitRun(base) ?: return null
        val value = raw.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return raw to value
    }

    /**
     * 提取文件名里的序号（`IMG_0042` → 42），没有则返回 null。
     *
     * **全项目唯一的序号判定。** 以前这里和 `Pairing.extractSeq` 是两套独立
     * 实现，同名文件会得出相反结论，所以现在统一到这一个函数上，
     * 差异只通过下面这个参数显式表达。
     *
     * @param treatYearAsSeq 4 位数且落在 1900..2099 时，算不算序号。
     *
     *   - **false（默认）**：当成年份，返回 null。
     *     适合"这批文件有没有序号体系"这类**统计**判断 ——
     *     否则一文件夹的 `2024` 会被算成序号，得出"两边都带序号体系"的错误结论。
     *   - **true**：算序号。**配对引擎必须用这个**：
     *     相机连拍编号真的会走到 2000+（`DSC_2099.JPG`），
     *     把 2000~2099 排除掉会让这批文件整体退化成"按排列顺序"配对，
     *     反而更不准 —— 而顺序配对是最弱的一档依据。
     */
    fun seqOf(name: String, treatYearAsSeq: Boolean = false): Int? {
        val (raw, value) = seqDigits(name) ?: return null
        if (!treatYearAsSeq && raw.length == 4 && value in 1900..2099) return null
        return value
    }

    // ------------------------------------------------------------------
    // 文件名清理
    // ------------------------------------------------------------------

    /**
     * Windows / macOS 不允许的字符。
     * 手机上能存，一传到电脑就报错，属于典型的"现在不痛以后痛"。
     */
    private val ILLEGAL_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 一键清理文件名。
     *
     * 返回 null 表示「无需改动」——调用方据此过滤出真正需要处理的文件，
     * 避免生成一堆"旧名等于新名"的空操作。
     */
    fun fixName(name: String, opt: CleanOptions): String? {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""

        var b = base
        var e = ext

        if (opt.stripControl) {
            // 控制字符（含换行、制表）—— 显示时会把布局撑坏
            b = b.filter { it.code >= 32 && it.code != 127 }
            e = e.filter { it.code >= 32 && it.code != 127 }
        }
        if (opt.stripIllegal) {
            b = b.filter { it !in ILLEGAL_CHARS }
            e = e.filter { it !in ILLEGAL_CHARS }
        }
        if (opt.collapseSpace) {
            b = Regex("""\s{2,}""").replace(b, " ")
            e = Regex("""\s{2,}""").replace(e, " ")
        }
        if (opt.trimSpace) {
            b = b.trim()
            e = e.trim()
        }
        if (opt.normalizeExtCase) e = e.lowercase()

        val out = if (e.isEmpty()) b else "$b.$e"
        // 清理后为空（比如文件名全是非法字符）就别动了，
        // 交给调用方提示，总比改成空名字强
        if (out.isBlank()) return null
        return if (out == name) null else out
    }

    /** 名字是否"脏"：存在可清理的问题。用于按条件选择。 */
    fun isDirtyName(name: String): Boolean {
        if (name != name.trim()) return true
        if (Regex("""\s{2,}""").containsMatchIn(name)) return true
        if (name.any { it.code < 32 || it.code == 127 }) return true
        if (name.any { it in ILLEGAL_CHARS }) return true
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot + 1)
            // 扩展名里有大写，且不是刻意全大写的可疑情况
            if (ext.isNotEmpty() && ext != ext.lowercase()) return true
        }
        return false
    }

    fun trimNumbering(base: String): String {
        var out = base.trim()
        // 反复删，处理 `photo (1) (2)` 这种叠加的情况
        var changed = true
        while (changed) {
            changed = false
            for (re in TRIM_PATTERNS) {
                val m = re.find(out) ?: continue
                val next = out.removeRange(m.range)
                if (next.isNotBlank() && next != out) {
                    out = next
                    changed = true
                }
                break
            }
        }
        // 整串都是数字就别删了，删完没名字了
        return out.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) } ?: base
    }

    /**
     * 末尾序号的匹配模式，按优先级排列。
     * 全部要求匹配到字符串**末尾**，避免误伤中间的数字。
     */
    private val TRIM_PATTERNS: List<Regex> = listOf(
        Regex("""\s*\(\d+\)\s*$"""),          // (1)
        Regex("""\s*[_\-]\d{6,}\s*$"""),      // _20240315 / -143022（时间戳）
        // _1 / -12 / 空格+数字。
        // 排除疑似年份（19xx/20xx）：`IMG_2024` 可能是年份而非序号，
        // 删掉年份比留着序号更糟，这种歧义下选择保守。
        Regex("""\s*[\s_\-](?!(?:19|20)\d{2}$)\d{1,5}\s*$"""),
    )

    /** 首字母大写：只改大小写，保留原有的空格/下划线/连字符。 */
    fun applyCase(base: String, op: CaseOp): String = when (op) {
        CaseOp.LOWER -> base.lowercase()
        CaseOp.UPPER -> base.uppercase()
        CaseOp.TITLE -> {
            var capitalizeNext = true
            buildString(base.length) {
                for (ch in base) {
                    when {
                        !ch.isLetterOrDigit() -> {
                            append(ch)
                            capitalizeNext = true
                        }
                        capitalizeNext -> {
                            append(ch.uppercaseChar())
                            capitalizeNext = false
                        }
                        else -> append(ch.lowercaseChar())
                    }
                }
            }
        }
    }

    /** 主文件名内的查找替换（不区分大小写）。 */
    fun replaceIn(base: String, find: String, replace: String): String =
        if (find.isEmpty()) base else base.replace(find, replace, ignoreCase = true)

    // ------------------------------------------------------------------
    // 命名预设
    // ------------------------------------------------------------------

    /** 常用命名规则。 */
    enum class Preset {
        /** 20240315_143022 —— 精确到秒，连拍也不会撞名。 */
        DATETIME,
        /** 20240315 —— 只有日期。同一天的会撞，交给自动编号避让。 */
        DATE,
        /** 2024-03 —— 按月份归档用。 */
        YEAR_MONTH,
        /** 相机型号 + 序号，如 Canon EOS R6_0001。 */
        CAMERA_SEQ,
        /** 原名前 12 字 + 序号。 */
        ORIGINAL_SEQ,
    }

    /**
     * 按预设生成名字。
     *
     * @param takenAt 拍摄时间，0 表示没有
     * @param camera 相机型号，空表示没有
     * @param seq 序号（从 1 开始）
     * @param digits 序号位数
     * @return 生成的名字；**缺数据时返回 null**
     *
     * 为什么不降级：缺了拍摄时间还硬编出 `19700101_xxx` 是灾难 ——
     * 用户扫一眼以为是对的，实际上整批名字都错了。返回 null，
     * 由调用方明确告诉用户"这些文件没有拍摄时间，不能用这个规则"。
     */
    fun presetName(
        preset: Preset,
        base: String,
        takenAt: Long,
        camera: String,
        seq: Int,
        digits: Int,
    ): String? {
        val n = padded(seq, digits)
        return when (preset) {
            Preset.DATETIME -> if (takenAt > 0) {
                formatStamp(takenAt, "yyyyMMdd_HHmmss")
            } else {
                null
            }
            Preset.DATE -> if (takenAt > 0) {
                formatStamp(takenAt, "yyyyMMdd")
            } else {
                null
            }
            Preset.YEAR_MONTH -> if (takenAt > 0) {
                formatStamp(takenAt, "yyyy-MM")
            } else {
                null
            }
            Preset.CAMERA_SEQ -> {
                // 相机型号可能为空或含非法字符，清洗后仍为空则退回原名
                val cam = sanitize(camera.replace(" ", "_"), reserve = n.length + 1)
                    .trim('_')
                if (cam.isBlank()) null else "${cam}_$n"
            }
            Preset.ORIGINAL_SEQ -> {
                val head = sanitize(base, reserve = n.length + 1).take(12).trim('_')
                if (head.isBlank()) null else "${head}_$n"
            }
        }
    }

    // ------------------------------------------------------------------
    // 正则 / 截取 / 插入删除
    // ------------------------------------------------------------------

    /**
     * 正则替换。
     *
     * 返回 null 表示「模式不合法」，调用方要据此提示用户，
     * 而不是静默返回原名 —— 否则用户会以为替换成功了。
     */
    fun regexReplace(
        base: String,
        pattern: String,
        replacement: String,
        ignoreCase: Boolean = false,
    ): String? {
        if (pattern.isBlank()) return base
        val opt = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val re = runCatching { Regex(pattern, opt) }.getOrNull() ?: return null
        val out = runCatching { re.replace(base, replacement) }.getOrNull() ?: return null
        // 替换成空了就保持原名：空文件名是文件系统不允许的，
        // 用户写 `.*` -> "" 多半是笔误，不该把文件改坏
        return out.ifEmpty { base }
    }

    /** 正则是否合法（用于输入时实时提示）。 */
    fun isValidRegex(pattern: String): Boolean =
        pattern.isBlank() || runCatching { Regex(pattern) }.isSuccess

    /**
     * 截取：保留 [start, end) 区间的字符。
     *
     * 支持负数从末尾算（-1 = 最后一个字符），这是用户最自然的写法。
     * 区间非法（start > end）时返回 null，让调用方提示而不是悄悄返回空串 ——
     * 空文件名是文件系统不允许的，必须挡在前面。
     */
    fun substring(base: String, start: Int, end: Int?): String? {
        if (base.isEmpty()) return null
        val n = base.length
        val s0 = if (start < 0) (n + start).coerceAtLeast(0) else start.coerceAtMost(n)
        val e0 = if (end == null) n else if (end < 0) (n + end).coerceAtLeast(0) else end.coerceAtMost(n)
        if (s0 >= e0) return null
        return base.substring(s0, e0)
    }

    /** 在指定位置插入文本。负数位置从末尾算。 */
    fun insertAt(base: String, pos: Int, text: String): String {
        if (text.isEmpty()) return base
        val n = base.length
        val p = if (pos < 0) (n + pos + 1).coerceIn(0, n) else pos.coerceIn(0, n)
        return base.substring(0, p) + text + base.substring(p)
    }

    /**
     * 删除 [start, end) 区间。
     *
     * **删光了就返回原名**：用户填「从 0 删到 99」多半是想删到末尾，
     * 而不是想让文件名变成空 —— 空名是文件系统不允许的，
     * 一旦产出去改就会直接失败（甚至更糟）。所以这里宁可不动。
     */
    fun deleteRange(base: String, start: Int, end: Int): String {
        val n = base.length
        if (n == 0) return base
        val s0 = if (start < 0) (n + start).coerceAtLeast(0) else start.coerceAtMost(n)
        val e0 = if (end < 0) (n + end + 1).coerceIn(0, n) else end.coerceAtMost(n)
        if (s0 >= e0) return base
        // 关键：整段被删空时保持原名，绝不产出空字符串
        if (s0 == 0 && e0 >= n) return base
        return base.substring(0, s0) + base.substring(e0)
    }

    /**
     * 在已有名字集合中为期望名找到一个不冲突的名字。
     * 首个候选就是期望名本身（无冲突时原样返回）。
     */
    fun resolveConflict(
        base: String,
        ext: String,
        existing: Collection<String>,
        style: NumberingStyle,
        forceNumber: Boolean = false,
    ): String {
        val full = join(base, ext)
        if (!forceNumber && !existing.any { it.equals(full, ignoreCase = true) }) return full
        var i = 1
        while (i < 10_000) {
            val candidate = join(withNumber(base, i, style), ext)
            if (!existing.any { it.equals(candidate, ignoreCase = true) }) return candidate
            i++
        }
        // 连续编号被占满（几乎不可能，但"几乎"不是理由）。
        //
        // 原来这里是 `withNumber(base, (1..99999).random(), style)` ——
        // **返回前根本没检查这个随机号是否已被占用**。撞上就是一次静默覆盖：
        // 改名会把别人顶掉，而且没有任何提示。
        // 改成从时间戳起步逐个验证：仍然是单调递增的编号风格，
        // 但出口唯一 —— 一定是一个没被占用的名字。
        var seed = (System.currentTimeMillis() % 100_000).toInt()
        while (true) {
            val candidate = join(withNumber(base, seed, style), ext)
            if (!existing.any { it.equals(candidate, ignoreCase = true) }) return candidate
            seed++
        }
    }

    /** 生成临时中转名（同目录交换时使用）。 */
    fun tempName(existing: Collection<String>): String {
        var i = 0
        while (i < 10_000) {
            val candidate = ".pairrename_tmp_$i"
            if (!existing.any { it.equals(candidate, ignoreCase = true) }) return candidate
            i++
        }
        return ".pairrename_tmp_fallback"
    }

    /** 自然排序键：把数字段按位数补零，使 img2 < img10。 */
    fun naturalKey(name: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c.isDigit()) {
                var j = i
                while (j < name.length && name[j].isDigit()) j++
                sb.append(name.substring(i, j).padStart(12, '0'))
                i = j
            } else {
                sb.append(c.lowercaseChar())
                i++
            }
        }
        return sb.toString()
    }

    fun naturalCompare(a: String, b: String): Int = naturalKey(a).compareTo(naturalKey(b))

    /**
     * 把毫秒差变成人话："3 秒" / "2 分 15 秒" / "1 小时 30 分" / "5 天 3 小时"。
     *
     * 只保留**两级**精度：这里要回答的是"差得远不远"，
     * 精确到毫秒没有意义，为了一秒之差多显示一位反而更难扫。
     *
     * 有两个消费者：对比面板的时间冲突横幅、以及卡片上的"对面：xxx · 差 2 小时"。
     * 两处合成一处，否则迟早出现"面板说 2 小时、卡片说 2 时 0 分"这种自相矛盾。
     */
    fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        if (sec <= 0) return "不到 1 秒"
        if (sec < 60) return "$sec 秒"
        val min = sec / 60
        if (min < 60) return "$min 分 ${sec % 60} 秒"
        val hour = min / 60
        if (hour < 24) return "$hour 小时 ${min % 60} 分"
        val day = hour / 24
        return "$day 天 ${hour % 24} 小时"
    }

    /** 1024 进制的可读体积。 */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "-"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else "%.1f %s".format(value, units[i])
    }

    /**
     * 按指定模式格式化时间戳。
     *
     * 用 Locale.US 而不是默认地区：日期格式里若混入地区文字
     * （比如中文的"3月"），文件名在别的系统上会出问题。
     */
    fun formatStamp(millis: Long, pattern: String): String {
        val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
        return sdf.format(java.util.Date(millis))
    }

    /** 毫秒时间戳的可读形式。 */
    fun formatTime(millis: Long): String {
        if (millis <= 0) return "-"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}
