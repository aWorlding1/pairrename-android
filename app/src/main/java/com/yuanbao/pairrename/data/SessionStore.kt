package com.yuanbao.pairrename.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore("pair_sessions")

/**
 * 一条**可撤回**的操作凭据，随快照一起落盘。
 *
 * 为什么它也必须落盘：[PlanSnapshot] 早就让手工配对跨启动存活了，而**凭据没有** ——
 * 于是重启之后界面自相矛盾：那一批"由某次操作写出来的配对"明明都被恢复了，
 * 却没有任何入口能把它们一次撤回。用户看到的是"配对回来了，撤回按钮不见了"。
 * 恢复一半的状态比不恢复更让人不敢动手。
 *
 * 为什么存成"列表 + 种类"而不是三个字段：三种凭据形状完全一样
 * （见 `MainViewModel.Credential`），只有撤回后那句提示不同。R22 刚把三个字段
 * 收敛成一个类型；持久化这边如果再摊成三个字段，就是把刚合上的口子重新撕开。
 *
 * @param kind 操作种类，取值见 [CredentialKind]。**改了就等于丢掉旧快照里的凭据。**
 * @param session 签发时的现场指纹。保存侧保证它**等于快照自身的 fingerprint**，
 *   所以"这份凭据属于这个现场"是存储层的不变量，读取侧不必再判一次。
 */
data class StoredCredential(
    val kind: String,
    val session: String,
    val pairs: Map<String, String>,
    val unlinked: Set<String>,
) {
    /** 本次操作写进去的对数（双向存储，所以要除以 2）。 */
    val pairCount: Int get() = pairs.size / 2
}

/**
 * [StoredCredential.kind] 的取值。
 *
 * 是**持久化格式的一部分**：这些字符串会写进用户的存档里，改名就等于
 * 让旧存档里的凭据全部失效（读出来认不出种类 → 丢掉）。
 * 丢掉是安全方向（少一条"可撤回"），但没必要白白丢，所以别动这些值。
 */
object CredentialKind {
    const val SWAP = "swap"
    const val SIDE_MOVE = "sideMove"
    const val REALIGN = "realign"
}

/**
 * 一个「工作现场」的快照。
 *
 * 为什么需要它：手工配对的结果（[pairs] / [unlinked]）之前只活在内存里。
 * 花二十分钟把 200 张图的配对纠正完，一旦应用被系统回收、或者重启手机，
 * 全部归零 —— 而且**用户看不出来丢了什么**，只是觉得"配对怎么全乱了，
 * 又得从头来一遍"。这是这个工具最贵的失败模式：
 * 手工劳动的成果必须比进程活得久。
 *
 * 与设置（[com.yuanbao.pairrename.data.SettingsRepository]）分开存：
 * 设置是"我的偏好"，快照是"这个目录对的工作进度"，生命周期完全不同。
 *
 * @param fingerprint 左右目录 Uri 组成的现场指纹。**刻意不含文件名 / 文件数** ——
 *   用户在这个现场里就是在改名，如果指纹跟着文件名变，
 *   那快照在第一次改名后就会失效，这个功能也就没意义了。
 * @param label 左右目录的显示名，只用于展示（形如「相机 ↔ 微信」）。
 * @param pairs 手动指定的配对（pairKey 双向存储，与 `UiState.manualPairs` 一致）。
 * @param unlinked 手动解除的配对（pairKey 集合）。
 * @param useExif 保存时是否已启用 EXIF 配对。
 * @param savedAt 保存时刻（毫秒），用于按新旧排序与展示。
 * @param credentials 随这次会话一起存档的撤回凭据（0~3 条）。只含属于本现场的那些。
 */
data class PlanSnapshot(
    val fingerprint: String,
    val label: String,
    val pairs: Map<String, String>,
    val unlinked: Set<String>,
    val useExif: Boolean,
    val savedAt: Long,
    val credentials: List<StoredCredential> = emptyList(),
) {
    /** 手动指定的**对数**（内部双向存储，所以要除以 2）。 */
    val pairCount: Int get() = pairs.size / 2

    /** 一条记录都没提到，就不值得存。 */
    val isEmpty: Boolean get() = pairs.isEmpty() && unlinked.isEmpty()
}

/**
 * 现场指纹：左边目录 + 右边目录。
 *
 * 左右**必须成对**才是一个现场：
 * 单独换掉一边就不再是同一份工作，两份快照混用只会错配。
 * 顺序也敏感 —— 左右互换意味着同步方向、配对方向整个反过来。
 */
fun sessionFingerprint(leftUri: String?, rightUri: String?): String? {
    if (leftUri.isNullOrBlank() || rightUri.isNullOrBlank()) return null
    return "$leftUri\u001D$rightUri"
}

/**
 * 把保存的配对过滤成「当前这批文件里真正生效得了」的那些。
 *
 * 这是整个恢复流程里**唯一承重的安全闸**。快照是上一次会话留下的，
 * 而目录内容随时在变：文件可能被删了、在系统相册里被改了名、
 * 或者这个 pairKey 现在指向另一个文件。
 *
 * 判定条件必须是**四个**，少一个就会把垃圾永久带下去：
 *
 * 1. 两个键都还在当前目录里（否则是删掉的文件）；
 * 2. `k != v`（同名配对等于没配，且默认实现会把它当同侧直接跳过）；
 * 3. 一个在左、一个在右（`linkSelected` 就是这么强制的；
 *    同侧配对照样会被 `applyManualOverrides` 跳过）；
 * 4. `saved[v] == k`，即**双向一致**（单向残留说明快照写坏过，
 *    这种条目在界面上一会儿生效一会儿不生效，比不恢复更糟）。
 *
 * 说白了：恢复出来的必须**恰好**是 `applyManualOverrides` 能真正应用的条目。
 * 任何恢复后仍然生效不了的条目都是纯垃圾 —— 它会在每次启动时被带回来。
 */
fun sanitizePairs(
    saved: Map<String, String>,
    leftKeys: Set<String>,
    rightKeys: Set<String>,
): Map<String, String> {
    val known = leftKeys + rightKeys
    val out = LinkedHashMap<String, String>()
    saved.forEach { (k, v) ->
        if (k == v) return@forEach
        if (k !in known || v !in known) return@forEach
        val cross = (k in leftKeys) != (v in leftKeys)
        if (!cross) return@forEach
        if (saved[v] != k) return@forEach
        out[k] = v
    }
    return out
}

/**
 * [sanitizePairs] 的解除版：只要求"这个 pairKey 还在"。
 *
 * 解除不需要跨栏：`unlinked` 的语义是"凡是叫这个名字的都别自动配对"，
 * 它本来就允许（也应该）对两侧同名的文件同时生效。
 */
fun sanitizeUnlinked(
    saved: Set<String>,
    leftKeys: Set<String>,
    rightKeys: Set<String>,
): Set<String> {
    val known = leftKeys + rightKeys
    return saved.filterTo(LinkedHashSet()) { it in known }
}

/**
 * 配对方案快照的持久化。
 *
 * 存最近 [MAX] 个现场（按 savedAt 更新，最旧的淘汰）——
 * 用户多半在两三个目录对之间来回用，只留一个会让"切回来"失效，
 * 无限增长又没有任何回收时机。
 */
class SessionStore(private val context: Context) {

    private object K {
        val SAVED = stringPreferencesKey("saved")
    }

    /**
     * 全部快照（最新在前）的响应式视图。
     *
     * 诊断面板要同步渲染"一共存了几个现场"，不能在组合函数里挂起读盘；
     * 而 `recents` / `templates` 都是这么接的，保持同一种写法。
     */
    val snapshots: Flow<List<PlanSnapshot>> = context.sessionDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { p -> decodeAll(p[K.SAVED].orEmpty()).sortedByDescending { it.savedAt } }

    /** 读取全部快照（最新在前）。 */
    suspend fun loadAll(): List<PlanSnapshot> = snapshots.first()

    /** 读取指定现场的快照。 */
    suspend fun load(fingerprint: String): PlanSnapshot? =
        loadAll().firstOrNull { it.fingerprint == fingerprint }

    /** 写入 / 覆盖指定现场的快照，并按 LRU 淘汰。 */
    suspend fun save(snap: PlanSnapshot) {
        if (snap.isEmpty) {
            // 没有手动干预就别占位置 —— 顺手把该现场清掉，
            // 否则用户"清除手动干预"之后，下次启动又会把空快照读出来。
            remove(snap.fingerprint)
            return
        }
        val kept = loadAll().filter { it.fingerprint != snap.fingerprint }
        val next = (listOf(snap) + kept).sortedByDescending { it.savedAt }.take(MAX)
        write(next)
    }

    suspend fun remove(fingerprint: String) {
        val next = loadAll().filter { it.fingerprint != fingerprint }
        write(next)
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(K.SAVED) }
    }

    private suspend fun write(list: List<PlanSnapshot>) {
        val encoded = encodeAll(list)
        context.sessionDataStore.edit { pref ->
            if (encoded.isEmpty()) pref.remove(K.SAVED) else pref[K.SAVED] = encoded
        }
    }

    private companion object {
        /** 保留多少个现场。 */
        const val MAX = 8
    }
}

// ---------------------------------------------------------------------------
// 编码：单层转义，不做嵌套
//
// 记录之间 \u001E，快照字段之间 \u001F，配对项之间 \u001D，键值之间 \u001C。
//
// 关键点是**先把每个叶子值转义掉**，于是值里再也不可能出现任何分隔符，
// 也就不需要第二层转义。文件名里真的会有控制字符（见 `CleanOptions.stripControl`），
// 所以这步不能省 —— 少了它，一个名字里带 \u001F 的文件就能把整份快照拆坏。
// ---------------------------------------------------------------------------

private const val REC = '\u001E'
private const val FLD = '\u001F'
private const val ITEM = '\u001D'
private const val KV = '\u001C'

/**
 * 叶子值转义。
 *
 * 只转 5 个字符：反斜杠本身，以及四个分隔符。反斜杠**必须第一个转**，
 * 否则后面替换出来的反斜杠会被二次转义。
 */
internal fun escSession(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            KV -> sb.append("\\a")
            ITEM -> sb.append("\\b")
            REC -> sb.append("\\c")
            FLD -> sb.append("\\d")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * [escSession] 的逆运算。
 *
 * 尾部孤立的反斜杠、以及未知的转义序列都直接跳过反斜杠 ——
 * `escSession` 永远不会产出它们，出现了就说明数据被外部改坏了，
 * 此时宁可丢掉一个字符，也不能让整个解码抛异常（那样这份快照就全丢了）。
 */
internal fun unescSession(s: String): String {
    if (s.indexOf('\\') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        if (i + 1 >= s.length) {
            i++
            continue
        }
        when (s[i + 1]) {
            '\\' -> sb.append('\\')
            'a' -> sb.append(KV)
            'b' -> sb.append(ITEM)
            'c' -> sb.append(REC)
            'd' -> sb.append(FLD)
            else -> sb.append(s[i + 1])
        }
        i += 2
    }
    return sb.toString()
}

internal fun encodePairs(pairs: Map<String, String>): String =
    pairs.entries.joinToString(ITEM.toString()) { escSession(it.key) + KV + escSession(it.value) }

internal fun decodePairs(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    raw.split(ITEM).forEach { chunk ->
        // limit = 2：值里不可能还有 KV（已被转义），但这样写更不容易被将来的改动弄坏
        val parts = chunk.split(KV, limit = 2)
        if (parts.size == 2) out[unescSession(parts[0])] = unescSession(parts[1])
    }
    return out
}

internal fun encodeSnapshot(s: PlanSnapshot): String = listOf(
    escSession(s.fingerprint),
    escSession(s.label),
    s.savedAt.toString(),
    if (s.useExif) "1" else "0",
    escSession(encodePairs(s.pairs)),
    escSession(s.unlinked.joinToString(ITEM.toString()) { escSession(it) }),
    escSession(encodeCreds(s.credentials)),
).joinToString(FLD.toString())

internal fun decodeSnapshot(raw: String): PlanSnapshot? {
    val f = raw.split(FLD)
    if (f.size < 6) return null
    val savedAt = f[2].toLongOrNull() ?: return null
    // 指纹是快照的身份，坏了这一条就没意义了
    val fp = unescSession(f[0])
    if (fp.isEmpty()) return null
    val unlinkedRaw = unescSession(f[5])
    return PlanSnapshot(
        fingerprint = fp,
        label = unescSession(f[1]),
        pairs = decodePairs(unescSession(f[4])),
        unlinked = if (unlinkedRaw.isEmpty()) {
            emptySet()
        } else {
            unlinkedRaw.split(ITEM).mapTo(LinkedHashSet()) { unescSession(it) }
        },
        useExif = f[3] == "1",
        savedAt = savedAt,
        // 第 7 个字段（撤回凭据）是后加的：旧存档只有 6 个字段，
        // 读出来是空列表 —— 与"还没有任何可撤回的操作"完全等价，不需要迁移。
        credentials = if (f.size >= 7) decodeCreds(unescSession(f[6])) else emptyList(),
    )
}

/**
 * 凭据列表 → 一个字段（每条 4 段：种类 / 现场戳 / 配对表 / 解除集合）。
 *
 * 嵌套方式和 [encodePairs] 那一格一样：**先把内部编好，再整体转义一次**。
 * 于是字段值里既不会出现 [KV]（段之间），也不会出现 [FLD]（快照字段之间）；
 * 第 4 段自己内部用 [ITEM] 分隔的多个名字，也各自转义过了。
 */
internal fun encodeCreds(list: List<StoredCredential>): String =
    list.joinToString(ITEM.toString()) { c ->
        listOf(
            escSession(c.kind),
            escSession(c.session),
            escSession(encodePairs(c.pairs)),
            escSession(c.unlinked.joinToString(ITEM.toString()) { escSession(it) }),
        ).joinToString(KV.toString())
    }

internal fun decodeCreds(raw: String): List<StoredCredential> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<StoredCredential>()
    raw.split(ITEM).forEach { chunk ->
        // limit = 4：第 4 段内部就是 ITEM 分隔的名字表，不能在这里被切开
        val parts = chunk.split(KV, limit = 4)
        if (parts.size != 4) return@forEach
        val kind = unescSession(parts[0])
        // 没有种类的凭据等于不知道该怎么办 —— 与其猜，不如丢掉
        if (kind.isEmpty()) return@forEach
        val unlinkedRaw = unescSession(parts[3])
        val pairs = decodePairs(unescSession(parts[2]))
        // 空凭据（一条都没写）在界面上等于"没有可撤回的操作"，
        // 存着只会让"还能不能撤回"多一种需要解释的状态
        if (pairs.isEmpty()) return@forEach
        out += StoredCredential(
            kind = kind,
            session = unescSession(parts[1]),
            pairs = pairs,
            unlinked = if (unlinkedRaw.isEmpty()) {
                emptySet()
            } else {
                unlinkedRaw.split(ITEM).mapTo(LinkedHashSet()) { unescSession(it) }
            },
        )
    }
    return out
}

internal fun encodeAll(list: List<PlanSnapshot>): String =
    list.joinToString(REC.toString()) { encodeSnapshot(it) }

internal fun decodeAll(raw: String): List<PlanSnapshot> {
    if (raw.isBlank()) return emptyList()
    return raw.split(REC).mapNotNull { chunk ->
        // 单条坏掉不该拖垮其他现场 —— 这层 mapNotNull 是刻意的容错
        if (chunk.isBlank()) null else decodeSnapshot(chunk)
    }
}
