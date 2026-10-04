"""第十二轮验证：工作现场（手工配对方案）的持久化。

## 本轮加的东西

之前 `manualPairs` / `unlinked` **只活在内存里**。用户花二十分钟把 200 张图
的配对纠正完，应用被系统回收或者手机重启之后全部归零 —— 而且他看不出来
丢了什么，只是觉得"配对怎么又乱了，又得从头来一遍"。

现在按「左右目录」组成的工作现场存档，下次打开自动恢复。

## 本脚本要证明的四件事

1. **编码能无损往返**：文件名里真的会有控制字符（见 `CleanOptions.stripControl`），
   所以转义不是可选项。带 \\u001C~\\u001F、反斜杠、等号的名字必须原样还原。
2. **安全闸（`sanitizePairs`）是承重的**：快照是上一次会话的，
   目录内容随时在变。判定条件四个（存在 / k≠v / 跨栏 / 双向一致），
   少一个就会把**永远生效不了的垃圾**在每次启动时带回来。
   —— 用"无护栏实现会漏出东西"的反例来证明这几道闸不是摆设。
3. **恢复出来的必须恰好是"能真正生效"的那批**：
   直接复刻 `applyManualOverrides` 的生效口径（byPairKey 映射 + 同侧跳过），
   断言护栏版恢复出来的每一条都真的产生了一对配对。
4. **一个现场一份存档、只留最近 N 个**：同指纹覆盖、空快照等于删除、
   超过上限淘汰最旧的（用户会在两三个目录对之间来回用）。
5. **撤回凭据也跟着存档走**（R25）：存档早就让手工配对跨启动存活了，而凭据没有，
   于是重启后界面自相矛盾 —— 那一批"由某次操作写出来的配对"明明都恢复了，
   却没有任何入口能把它们一次撤回。第七组钉住编码无损 + **两道闸**
   （保存侧只存"属于本现场且一条没被动过"的，恢复侧要求"整批成立"）。
"""

# ---------------------------------------------------------------- 复刻
# 与 SessionStore.kt 一一对应。改了 Kotlin 就要同步改这里。

from pathlib import Path

REC = "\x1e"
FLD = "\x1f"
ITEM = "\x1d"
KV = "\x1c"

MAX_SCENES = 8


def esc(s: str) -> str:
    """复刻 escSession：只转 5 个字符，反斜杠必须第一个转。"""
    out = []
    for c in s:
        if c == "\\":
            out.append("\\\\")
        elif c == KV:
            out.append("\\a")
        elif c == ITEM:
            out.append("\\b")
        elif c == REC:
            out.append("\\c")
        elif c == FLD:
            out.append("\\d")
        else:
            out.append(c)
    return "".join(out)


def unesc(s: str) -> str:
    """复刻 unescSession：未知转义 / 尾部孤立反斜杠都丢弃反斜杠。"""
    if "\\" not in s:
        return s
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        if i + 1 >= len(s):
            i += 1
            continue
        n = s[i + 1]
        if n == "\\":
            out.append("\\")
        elif n == "a":
            out.append(KV)
        elif n == "b":
            out.append(ITEM)
        elif n == "c":
            out.append(REC)
        elif n == "d":
            out.append(FLD)
        else:
            out.append(n)
        i += 2
    return "".join(out)


def encode_pairs(pairs: dict) -> str:
    return ITEM.join(esc(k) + KV + esc(v) for k, v in pairs.items())


def decode_pairs(raw: str) -> dict:
    if raw == "":
        return {}
    out = {}
    for chunk in raw.split(ITEM):
        parts = chunk.split(KV, 1)          # limit = 2（Python 的 maxsplit 语义）
        if len(parts) == 2:
            out[unesc(parts[0])] = unesc(parts[1])
    return out


def encode_creds(lst: list) -> str:
    """复刻 encodeCreds：每条 4 段（种类 / 现场戳 / 配对表 / 解除集合），整体再转义一层。"""
    return ITEM.join(
        KV.join([
            esc(c["kind"]),
            esc(c["session"]),
            esc(encode_pairs(c["pairs"])),
            esc(ITEM.join(esc(x) for x in c["unlinked"])),
        ])
        for c in lst
    )


def decode_creds(raw: str) -> list:
    """复刻 decodeCreds。第 4 段内部是 ITEM 分隔的名字表，所以 maxsplit = 3。"""
    if raw == "":
        return []
    out = []
    for chunk in raw.split(ITEM):
        parts = chunk.split(KV, 3)
        if len(parts) != 4:
            continue
        kind = unesc(parts[0])
        if kind == "":
            continue
        pairs = decode_pairs(unesc(parts[2]))
        if not pairs:
            continue
        ul_raw = unesc(parts[3])
        out.append({
            "kind": kind,
            "session": unesc(parts[1]),
            "pairs": pairs,
            "unlinked": set(unesc(x) for x in ul_raw.split(ITEM)) if ul_raw else set(),
        })
    return out


def encode_snapshot(s: dict) -> str:
    return FLD.join([
        esc(s["fingerprint"]),
        esc(s["label"]),
        str(s["savedAt"]),
        "1" if s["useExif"] else "0",
        esc(encode_pairs(s["pairs"])),
        esc(ITEM.join(esc(x) for x in s["unlinked"])),
        esc(encode_creds(s.get("credentials", []))),
    ])


def decode_snapshot(raw: str):
    f = raw.split(FLD)
    if len(f) < 6:
        return None
    try:
        saved_at = int(f[2])
    except ValueError:
        return None
    fp = unesc(f[0])
    if fp == "":
        return None
    unlinked_raw = unesc(f[5])
    return {
        "fingerprint": fp,
        "label": unesc(f[1]),
        "pairs": decode_pairs(unesc(f[4])),
        "unlinked": set(unesc(x) for x in unlinked_raw.split(ITEM)) if unlinked_raw else set(),
        "useExif": f[3] == "1",
        "savedAt": saved_at,
        # 第 7 段是 R25 加的：旧存档只有 6 段，读出来是空列表（等价于"没得撤"）
        "credentials": decode_creds(unesc(f[6])) if len(f) >= 7 else [],
    }


def encode_all(lst: list) -> str:
    return REC.join(encode_snapshot(s) for s in lst)


def decode_all(raw: str) -> list:
    if not raw.strip():
        return []
    out = []
    for chunk in raw.split(REC):
        if not chunk.strip():
            continue
        snap = decode_snapshot(chunk)
        if snap is not None:
            out.append(snap)
    return out


def pair_count(pairs: dict) -> int:
    return len(pairs) // 2


def fingerprint(left, right):
    if not left or not right:
        return None
    return f"{left}{ITEM}{right}"


# ---- 安全闸 ----

def sanitize_pairs(saved: dict, left_keys: set, right_keys: set) -> dict:
    """复刻 sanitizePairs：四道闸缺一不可。"""
    known = left_keys | right_keys
    out = {}
    for k, v in saved.items():
        if k == v:
            continue
        if k not in known or v not in known:
            continue
        cross = (k in left_keys) != (v in left_keys)
        if not cross:
            continue
        if saved.get(v) != k:
            continue
        out[k] = v
    return out


def sanitize_pairs_naive(saved: dict, left_keys: set, right_keys: set) -> dict:
    """**反例**：只检查"两个键都还在"。看起来够了，实际会把垃圾永久带下去。"""
    known = left_keys | right_keys
    return {k: v for k, v in saved.items() if k in known and v in known}


def sanitize_unlinked(saved: set, left_keys: set, right_keys: set) -> set:
    known = left_keys | right_keys
    return {x for x in saved if x in known}


# ---- 生效口径（复刻 applyManualOverrides 的一段） ----

def effective_pairs(items: list, pairs: dict) -> dict:
    """which pairs actually take effect after applyManualOverrides.

    `items` 是 [{"pk": pairKey, "side": "L"|"R", "key": docKey}]。
    复刻两处关键行为：
      · `byPairKey[pk] = item` 是**覆盖**写法 —— 同 pairKey 只有最后一个存活；
      · `if (a.side == b.side) return@forEach` —— 同侧配对被静默跳过。
    """
    by_pk = {}
    for it in items:
        by_pk[it["pk"]] = it
    partner = {}
    for a_pk, b_pk in pairs.items():
        a = by_pk.get(a_pk)
        b = by_pk.get(b_pk)
        if a is None or b is None:
            continue
        if a["side"] == b["side"]:
            continue
        partner[a["key"]] = b["key"]
        partner[b["key"]] = a["key"]
    return partner


# ---- 存档的持久化语义 ----

def store_save(store: dict, snap: dict) -> dict:
    """复刻 SessionStore.save：空快照 = 删除该现场；同指纹覆盖；按 savedAt 取最近 MAX。"""
    if not snap["pairs"] and not snap["unlinked"]:
        store.pop(snap["fingerprint"], None)
        return store
    kept = [v for k, v in store.items() if k != snap["fingerprint"]]
    ordered = sorted([snap] + kept, key=lambda x: -x["savedAt"])[:MAX_SCENES]
    return {s["fingerprint"]: s for s in ordered}


problems = []


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    if not cond:
        problems.append(name)


def snap(fp, pairs=None, unlinked=None, label="相机 ↔ 微信", saved_at=0, use_exif=True,
         creds=None):
    return {
        "fingerprint": fp,
        "label": label,
        "pairs": pairs or {},
        "unlinked": set(unlinked or ()),
        "useExif": use_exif,
        "savedAt": saved_at,
        "credentials": list(creds or []),
    }


def cred(kind, session, pairs, unlinked=()):
    """复刻 StoredCredential。"""
    return {
        "kind": kind,
        "session": session,
        "pairs": dict(pairs),
        "unlinked": set(unlinked),
    }


KINDS = ("swap", "sideMove", "realign")


def credential_intact(pairs, unlinked, session, live, live_unlinked, fp):
    """复刻 credentialIntact：落盘与恢复**共用**的那一道闸。

    一条凭据要整批成立：现场对得上、有内容、它写进去的每一条配对原样还在、
    它加进解除集合的名字一条不少。
    """
    # 没有现场就谈不上"属于哪个现场"——两个空串相比会"相等"，这一句不能省
    if fp == "":
        return False
    if session != fp:
        return False
    if not pairs:
        return False
    for k, v in pairs.items():
        if live.get(k) != v:
            return False
    for k in unlinked:
        if k not in live_unlinked:
            return False
    return True


def stored_credentials(ui, fp):
    """复刻 storedCredentials：把三个槽位整理成可落盘的 0~3 条。"""
    out = []
    for kind in KINDS:
        c = ui["cred"][kind]
        if not credential_intact(c["pairs"], c["unlinked"], c["session"],
                                 ui["manualPairs"], ui["unlinked"], fp):
            continue
        out.append(cred(kind, c["session"], c["pairs"], c["unlinked"]))
    return out


def restore_credentials(stored, pairs, unlinked, fp):
    """复刻 restoreCredentials：认得出的种类 + 过同一道闸，才给回槽位。"""
    out = {}
    for c in stored:
        if c["kind"] not in KINDS:
            continue
        if not credential_intact(c["pairs"], c["unlinked"], c["session"],
                                 pairs, unlinked, fp):
            continue
        out[c["kind"]] = c
    return out


def partial_credentials(stored, pairs, unlinked, fp):
    """**无闸版**：只留"还能对上的那几条"，其余丢掉。

    这是刻意留的反例 —— 它看起来更"宽容"，实际会造出一份
    "半批可撤"的凭据：点撤回时删掉一半、留下一半，比不撤更难收拾。
    """
    out = []
    for c in stored:
        if c["session"] != fp:
            continue
        keep = {k: v for k, v in c["pairs"].items() if pairs.get(k) == v}
        if not keep:
            continue
        out.append(cred(c["kind"], c["session"], keep,
                        set(x for x in c["unlinked"] if x in unlinked)))
    return out


print("=" * 72)
print("一、转义：文件名里的控制字符必须原样还原")
print("=" * 72)

nasty = [
    "IMG_0001",                                  # 普通
    "a\x1cb", "a\x1db", "a\x1eb", "a\x1fb",      # 四个分隔符各来一个
    "a\\b", "\\", "a\\\\b",                      # 反斜杠
    "back\\",                                    # 尾部孤立反斜杠
    "a\\ab",                                     # 看起来像转义序列的原文
    "照片 (1) = 好.jpg",                          # 等号 + 空格 + 中文
    "  ",                                        # 全空格
    "",                                          # 空串
]
for s in nasty:
    check(f"esc/unesc 往返：{s!r}", unesc(esc(s)) == s, repr(unesc(esc(s))))

# 转义之后不能再出现任何裸分隔符 —— 否则外层拼接就会被拆坏
for s in nasty:
    e = esc(s)
    check(f"转义后不含裸分隔符：{s!r}",
          all(x not in e for x in (KV, ITEM, REC, FLD)), repr(e))

print()
print("=" * 72)
print("二、快照编码：带脏名字的配对表要无损往返")
print("=" * 72)

dirty_pairs = {
    "a\x1cb": "x\x1dy",
    "img_1": "img_2",
    "带\\反斜杠": "带\x1f分隔符",
    "照片 (1)": "照片 (2)",
}
dirty_unlinked = {"a\x1db", "普通名", "带\\斜杠"}

s0 = snap(
    fp=fingerprint("content://tree/L\x1fx", "content://tree/R"),
    pairs=dirty_pairs,
    unlinked=dirty_unlinked,
    label="左\x1d右",
    saved_at=1700000000123,
)
one = decode_all(encode_all([s0]))
check("单条快照往返后条数不变", len(one) == 1)
check("指纹无损", one and one[0]["fingerprint"] == s0["fingerprint"])
check("显示名无损", one and one[0]["label"] == s0["label"])
check("配对表无损", one and one[0]["pairs"] == dirty_pairs, repr(one[0]["pairs"]) if one else "")
check("解除集合无损", one and one[0]["unlinked"] == dirty_unlinked)
check("useExif / savedAt 无损",
      one and one[0]["useExif"] is True and one[0]["savedAt"] == 1700000000123)
check("对数 = 条目数 / 2", pair_count(one[0]["pairs"]) == 2, str(pair_count(one[0]["pairs"])))

# 多条 + 乱序 + 坏条
multi = [snap(f"fp{i}", pairs={"a": "b", "b": "a"}, saved_at=i) for i in range(3)]
raw = encode_all(multi)
check("多条快照往返", len(decode_all(raw)) == 3)
broken = raw + REC + "只有一段" + REC + "a\x1fb\x1fc\x1fd\x1fe\x1ff"
check("单条坏掉不拖垮其他条（容错是刻意的）", len(decode_all(broken)) == 3,
      str(len(decode_all(broken))))
check("savedAt 坏掉的条被丢掉", decode_all(FLD.join(["fp", "lb", "notanumber", "1", "", ""]))
      is None or decode_all(FLD.join(["fp", "lb", "notanumber", "1", "", ""])) == [])
check("指纹为空的条被丢掉", decode_all(FLD.join(["", "lb", "1", "1", "", ""])) == [])
check("空字符串解出空列表", decode_all("") == [])

print()
print("=" * 72)
print("三、指纹：左右成对才算一个现场，且顺序敏感")
print("=" * 72)
check("缺一边 → 无现场", fingerprint("content://L", None) is None
      and fingerprint(None, "content://R") is None
      and fingerprint("", "content://R") is None)
check("左右互换是不同现场（同步方向整个反过来）",
      fingerprint("content://L", "content://R") != fingerprint("content://R", "content://L"))
check("同样两个目录 → 同一现场（改名不影响）",
      fingerprint("content://L", "content://R") == fingerprint("content://L", "content://R"))

print()
print("=" * 72)
print("四、安全闸：四道闸缺一不可（用无护栏反例证明）")
print("=" * 72)

LEFT = {"img_1", "img_3", "shared", "dup"}
RIGHT = {"img_2", "img_4", "shared", "dup"}
ITEMS = [
    {"pk": "img_1", "side": "L", "key": "doc-L1"},
    {"pk": "img_3", "side": "L", "key": "doc-L3"},
    {"pk": "shared", "side": "L", "key": "doc-Ls"},
    {"pk": "dup", "side": "L", "key": "doc-Ld"},
    {"pk": "img_2", "side": "R", "key": "doc-R2"},
    {"pk": "img_4", "side": "R", "key": "doc-R4"},
    {"pk": "shared", "side": "R", "key": "doc-Rs"},
    {"pk": "dup", "side": "R", "key": "doc-Rd"},
]

saved = {
    # ① 合法：跨栏 + 双向一致 + 都在
    "img_1": "img_2", "img_2": "img_1",
    # ② 文件已被删掉（img_9 不存在）
    "img_9": "img_3", "img_3": "img_9",
    # ③ 同名自配对（linkSelected 真会造出来：两边都有 shared → 都是 "shared"）
    "shared": "shared",
    # ④ 同侧配对（dup 两侧同名，byPairKey 塌成一个 → 必然同侧）
    "dup": "dup",
    # ⑤ 单向残留（快照写坏过）
    "img_3": "img_4",
}
guarded = sanitize_pairs(saved, LEFT, RIGHT)
naive = sanitize_pairs_naive(saved, LEFT, RIGHT)

print(f"  护栏版: {dict(sorted(guarded.items()))}")
print(f"  无护栏: {dict(sorted(naive.items()))}")

check("合法的那一对被保留",
      guarded.get("img_1") == "img_2" and guarded.get("img_2") == "img_1")
check("文件已删除的条目被丢弃", "img_9" not in guarded and guarded.get("img_3") != "img_9")
check("同名自配对被丢弃", guarded.get("shared") != "shared")
check("同侧配对被丢弃", guarded.get("dup") != "dup")
check("单向残留被丢弃", guarded.get("img_3") is None,
      str(guarded.get("img_3")))

# ★ 承重：无护栏实现确实会漏出这些垃圾（证明 fixture 真的踩到了这些分支）
check("**无护栏实现会漏出自配对（反例成立）**", naive.get("shared") == "shared")
check("**无护栏实现会漏出同侧配对（反例成立）**", naive.get("dup") == "dup")
check("**无护栏实现会漏出单向残留（反例成立）**", naive.get("img_3") == "img_4")

# ★★ 最关键的一条：恢复出来的必须**恰好**是能真正生效的那些
eff_guarded = effective_pairs(ITEMS, guarded)
eff_naive = effective_pairs(ITEMS, naive)
print(f"  护栏版真正生效: {len(eff_guarded)} 个键（期望 {len(guarded)}）")
print(f"  无护栏真正生效: {len(eff_naive)} 个键（期望 {len(naive)}）")
check("★ 护栏版恢复出的每一条都真的生效（键数等于条目数）",
      len(eff_guarded) == len(guarded),
      f"{len(eff_guarded)} vs {len(guarded)}")
check("★ 无护栏版带着一批永远生效不了的条目（垃圾会被永久带下去）",
      len(eff_naive) < len(naive),
      f"{len(eff_naive)} vs {len(naive)}")

# 解除：只要键还在就保留（解除本来就允许两侧同名同时生效）
un = sanitize_unlinked({"img_1", "img_9", "shared", "不存在的"}, LEFT, RIGHT)
check("解除：还在的保留、不在的丢弃", un == {"img_1", "shared"}, str(sorted(un)))
check("解除允许两侧同名同时生效（不需要跨栏）", "shared" in un)

print()
print("=" * 72)
print("五、存档语义：空快照 = 删除，同指纹覆盖，只留最近 8 个")
print("=" * 72)

store = {}
store = store_save(store, snap("fpA", pairs={"a": "b", "b": "a"}, saved_at=1))
check("写入一个有内容的现场", list(store) == ["fpA"])

store = store_save(store, snap("fpA", pairs={"c": "d", "d": "c"}, saved_at=2))
check("同指纹覆盖而不是追加", list(store) == ["fpA"] and pair_count(store["fpA"]["pairs"]) == 1)

store = store_save(store, snap("fpA", saved_at=3))
check("**空快照 = 删除该现场**（用户把干预全撤了要能记下来）", store == {}, str(store))

# 空解除 + 空配对 → 也算空
store = store_save({}, snap("fpZ", pairs={}, unlinked=set(), saved_at=1))
check("完全没有手动干预的快照不占位置", store == {})

# LRU：写 10 个不同现场
store = {}
for i in range(10):
    store = store_save(store, snap(f"fp{i}", pairs={"a": "b", "b": "a"}, saved_at=i))
check(f"只保留最近 {MAX_SCENES} 个现场", len(store) == MAX_SCENES, str(len(store)))
check("最新的那个在", "fp9" in store)
check("最旧的两个被淘汰", "fp0" not in store and "fp1" not in store)

# 覆盖一个旧现场会把它"顶"到最新（savedAt 变大）→ 不该被淘汰
store = store_save(store, snap("fp2", pairs={"x": "y", "y": "x"}, saved_at=99))
check("覆盖旧现场后它成为最新的（不会被当旧数据淘汰）",
      store["fp2"]["savedAt"] == 99 and len(store) == MAX_SCENES)

# 用户会在两三个目录对之间来回用：切回来时必须还在
store2 = {}
for r in range(3):
    for name in ("A", "B", "C"):
        store2 = store_save(store2, snap(f"scene{name}",
                                        pairs={"a": "b", "b": "a"},
                                        saved_at=r * 10 + ord(name) - 65))
check("三个目录对来回切换后都还在（只留一个是不够的）",
      set(store2) == {"sceneA", "sceneB", "sceneC"}, str(sorted(store2)))

print()
print("=" * 72)
print("六、端到端：存 → 编 → 解 → 过闸 → 生效")
print("=" * 72)
saved_pairs = {"img_1": "img_2", "img_2": "img_1", "img_3": "img_4", "img_4": "img_3"}
store = store_save({}, snap(fingerprint("L", "R"), pairs=saved_pairs,
                            unlinked={"gone"}, saved_at=5))
raw = encode_all([store[k] for k in sorted(store, key=lambda x: -store[x]["savedAt"])])
back = decode_all(raw)
check("端到端：一条存档", len(back) == 1)
load = back[0]
LEFT2, RIGHT2 = {"img_1", "img_3"}, {"img_2"}          # img_4 被删了
p = sanitize_pairs(load["pairs"], LEFT2, RIGHT2)
u = sanitize_unlinked(load["unlinked"], LEFT2, RIGHT2)
check("端到端：还成立的那一对恢复", p == {"img_1": "img_2", "img_2": "img_1"}, str(p))
check("端到端：涉及已删文件的配对整对丢弃（双向一起）", "img_3" not in p and "img_4" not in p)
check("端到端：已消失的解除项被丢弃", u == set(), str(u))
check("端到端：失效对数 = 原对数 - 恢复对数（用于给用户提示）",
      pair_count(load["pairs"]) - pair_count(p) == 1)

print()
print("=" * 72)
print("七、撤回凭据随存档一起走（R25：重启后仍能撤回）")
print("=" * 72)
# 存档早就让手工配对跨启动存活了，而**凭据没有** —— 于是重启后界面自相矛盾：
# 那一批"由某次操作写出来的配对"明明都恢复了，却没有任何入口能把它们一次撤回。
# 这一组钉住两件事：编码无损，以及**两道闸**（保存侧过滤 + 恢复侧复核）都承重。

FP = fingerprint("content://L", "content://R")

# 场景：一次「整批重配」写了 a1↔b2、a2↔b1，并把 a3 / b3 腾回未配对
RE_PAIRS = {"a1": "b2", "b2": "a1", "a2": "b1", "b1": "a2"}
RE_UNLINKED = {"a3", "b3"}
LIVE = {"a1": "b2", "b2": "a1", "a2": "b1", "b1": "a2"}
LIVE_UN = {"a3", "b3"}

# ---- 编码：脏名字也要无损

dirty_cred = cred("realign", FP,
                  {"a\x1cb": "x\x1dy", "x\x1dy": "a\x1cb", "带\\斜杠": "带\x1f分隔"},
                  {"a\x1db", "普通", "带\\斜杠"})
dirty_snap = snap(FP, pairs=LIVE, unlinked=LIVE_UN, saved_at=7, creds=[dirty_cred])
back = decode_all(encode_all([dirty_snap]))
check("带脏名字的凭据往返后还在", len(back) == 1 and len(back[0]["credentials"]) == 1)
got = back[0]["credentials"][0] if back and back[0]["credentials"] else {}
check("凭据种类无损", got.get("kind") == "realign", repr(got.get("kind")))
check("凭据现场戳无损", got.get("session") == FP)
check("凭据配对表无损", got.get("pairs") == dirty_cred["pairs"], repr(got.get("pairs")))
check("凭据解除集合无损", got.get("unlinked") == dirty_cred["unlinked"])

three = [cred(k, FP, RE_PAIRS, RE_UNLINKED) for k in KINDS]
back = decode_all(encode_all([snap(FP, pairs=LIVE, unlinked=LIVE_UN, creds=three)]))
check("三条凭据（互换 / 搬移 / 重配）一起往返，种类与条数都对",
      [c["kind"] for c in back[0]["credentials"]] == list(KINDS), str(back[0]["credentials"]))
check("凭据列表不污染配对表与解除集合",
      back[0]["pairs"] == LIVE and back[0]["unlinked"] == LIVE_UN)

check("空凭据（一条都没写）解码时被丢掉",
      decode_creds(encode_creds([cred("realign", FP, {})])) == [])
check("**旧存档（只有 6 段）读出来是空凭据，不报错**",
      decode_all(FLD.join(["fp", "lb", "1", "1", "", ""]))[0]["credentials"] == [])
check("没有凭据的快照编码后仍能往返（第 7 段是空的）",
      decode_all(encode_all([snap("fp", pairs={"a": "b", "b": "a"})]))[0]["credentials"] == [])

print()
print("  —— 两道闸：保存侧过滤 + 恢复侧复核 ——")

UI_OK = {"cred": {"swap": cred("swap", "", {}, set()),
                  "sideMove": cred("sideMove", "", {}, set()),
                  "realign": cred("realign", FP, RE_PAIRS, RE_UNLINKED)},
         "manualPairs": LIVE, "unlinked": LIVE_UN}
check("完好无损 → 落盘 1 条", [c["kind"] for c in stored_credentials(UI_OK, FP)] == ["realign"])

UI_FOREIGN = dict(UI_OK)
UI_FOREIGN["cred"] = dict(UI_OK["cred"])
UI_FOREIGN["cred"]["realign"] = cred("realign", "另一个现场的指纹", RE_PAIRS, RE_UNLINKED)
check("**不属于本现场 → 一条都不落盘**（pairKey 跨目录撞名是常态）",
      stored_credentials(UI_FOREIGN, FP) == [])

UI_EDITED = dict(UI_OK)
UI_EDITED["manualPairs"] = dict(LIVE, a1="b3")     # 用户事后手动改过其中一条
check("用户操作后又手动改过一条 → 不落盘（这份凭据只能做半批）",
      stored_credentials(UI_EDITED, FP) == [])

UI_UNLINKED_GONE = dict(UI_OK)
UI_UNLINKED_GONE["unlinked"] = {"a3"}              # 被腾出的 b3 又被用户手动配回去了
check("凭据要拿回的解除项少了一条 → 不落盘",
      stored_credentials(UI_UNLINKED_GONE, FP) == [])

# 恢复侧：存档是上一批文件留下的，目录内容随时在变
ARCHIVE = [cred("realign", FP, RE_PAIRS, RE_UNLINKED)]
check("文件没变 → 凭据原样恢复",
      sorted(restore_credentials(ARCHIVE, LIVE, LIVE_UN, FP)) == ["realign"])

# a1 的文件被删了 → sanitizePairs 已经把 a1↔b2 那一对整对丢掉
RESTORED_SHRUNK = {"a2": "b1", "b1": "a2"}
check("★ 有一对的文件已不在 → **整条作废**（不是半批）",
      restore_credentials(ARCHIVE, RESTORED_SHRUNK, LIVE_UN, FP) == {})
check("★ 无闸版会造出「半批可撤」的凭据（反例成立：只留还能对上的那半）",
      len(partial_credentials(ARCHIVE, RESTORED_SHRUNK, LIVE_UN, FP)) == 1
      and len(partial_credentials(ARCHIVE, RESTORED_SHRUNK, LIVE_UN, FP)[0]["pairs"]) == 2,
      str(partial_credentials(ARCHIVE, RESTORED_SHRUNK, LIVE_UN, FP)))
check("被腾出的那张文件没了 → 同样整条作废",
      restore_credentials(ARCHIVE, LIVE, {"a3"}, FP) == {})
check("存档里混进认不出的种类 → 丢掉而不是猜",
      restore_credentials([cred("teleport", FP, RE_PAIRS, RE_UNLINKED)], LIVE, LIVE_UN, FP) == {})
check("存档里混进别的现场的凭据 → 丢掉（读侧自己成立，不依赖保存侧）",
      restore_credentials([cred("realign", "别处", RE_PAIRS, RE_UNLINKED)],
                          LIVE, LIVE_UN, FP) == {})
check("只坏一条不影响其余两条",
      sorted(restore_credentials(
          [cred("swap", FP, {"a1": "b2", "b2": "a1"}, set()),
           cred("sideMove", FP, {"a1": "b9"}, set()),
           cred("realign", "别处", RE_PAIRS, RE_UNLINKED)],
          LIVE, LIVE_UN, FP)) == ["swap"])
check("闸对空凭据也判否（没有内容就没有可撤的东西）",
      not credential_intact({}, set(), FP, LIVE, LIVE_UN, FP))
check("现场指纹为空时一律判否（两侧都空会「相等」，这一句不能省）",
      not credential_intact(RE_PAIRS, RE_UNLINKED, "", LIVE, LIVE_UN, "")
      and not credential_intact(RE_PAIRS, RE_UNLINKED, "", LIVE, LIVE_UN, FP)
      and not credential_intact(RE_PAIRS, RE_UNLINKED, FP, LIVE, LIVE_UN, ""))

print()
print("  —— 结构：一处实现、两个调用、种类是持久化格式 ——")

SESSION_KT = (Path(__file__).resolve().parent
              / "app/src/main/java/com/yuanbao/pairrename/data/SessionStore.kt")
VM_KT = (Path(__file__).resolve().parent
         / "app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt")
SUB_KT = (Path(__file__).resolve().parent
          / "app/src/main/java/com/yuanbao/pairrename/ui/SubScreens.kt")
STR_XML = (Path(__file__).resolve().parent
           / "app/src/main/res/values/strings.xml")
store_src = SESSION_KT.read_text(encoding="utf-8")
vm_src = VM_KT.read_text(encoding="utf-8")
sub_src = SUB_KT.read_text(encoding="utf-8")
str_src = STR_XML.read_text(encoding="utf-8")

check("StoredCredential 与三种种类常量都在数据层（持久化格式住在存储层）",
      "data class StoredCredential(" in store_src
      and 'const val SWAP = "swap"' in store_src
      and 'const val SIDE_MOVE = "sideMove"' in store_src
      and 'const val REALIGN = "realign"' in store_src)

check("PlanSnapshot 多了一个凭据字段（默认空列表，旧调用点不用改）",
      "val credentials: List<StoredCredential> = emptyList()" in store_src)

check("凭据单独占一个字段，且旧存档（6 段）照样能读",
      "escSession(encodeCreds(s.credentials))" in store_src
      and "if (f.size >= 7) decodeCreds(unescSession(f[6])) else emptyList()" in store_src)

check("**闸只有一处实现，落盘与恢复各调一次**（少一处就会 drift）",
      vm_src.count("private fun credentialIntact(") == 1
      and vm_src.count("credentialIntact(") == 3)

check("恢复走带闸的映射，而不是「一刀切清空」那种省事写法",
      "restoreCredentials(snap.credentials, pairs, unlinked, fp)" in vm_src
      and "swapCredential = creds[CredentialKind.SWAP] ?: Credential()" in vm_src
      and "sideMoveCredential = creds[CredentialKind.SIDE_MOVE] ?: Credential()" in vm_src
      and "realignCredential = creds[CredentialKind.REALIGN] ?: Credential()" in vm_src)

check("恢复时把「还能撤回几步」说出来（否则用户不会想到重启后还能撤）",
      'name="msg_session_restored_undo"' in str_src
      and "getString(R.string.msg_session_restored_undo, undoable)" in vm_src)

check("诊断与工作现场页都能看到「存了几步可撤回」",
      "可撤回 $undo 步" in vm_src and "scene.credentials.size" in sub_src)

print()
print("=" * 72)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过。")
print("编码对控制字符 / 反斜杠无损；安全闸四道都承重（无护栏反例漏出自配对、")
print("同侧配对、单向残留）；恢复出的每条都真正生效；存档按现场覆盖并只留最近 8 个；")
print("撤回凭据随存档一起走：保存侧只存「属于本现场且一条没被动过」的，")
print("恢复侧要求「整批成立」（否则整条作废，不给半批撤回）。")
