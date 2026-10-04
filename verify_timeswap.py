"""第十八轮验证：「照拍摄时间换过来」（findTimeSwaps）。

上一轮（v5.5.0）把时间冲突搬上了卡片 —— 用户能**看见**哪一对配错了。
但看见之后呢？第十四轮只给了"解除配对"这一条路：错位如果是整体移了一位，
用户得解除一遍、再手动配一遍，重复 N 次。而这类错位是**成对可换**的。

本轮的主张必须被证明，而不是"看起来对"：

  主张 A（能换）：
      两边各自从 0001 编号造成整体错位时，把 (左1,右1) 与 (左2,右2)
      互换对象，两对的时间差会**同时**变小 —— 一次操作修好两对。

  主张 B（三道闸都在承重）：
      1. 交换后两对必须**都**严格变好 —— 不能为了修一对弄坏另一对；
      2. 至少有一对真的进容差 —— 否则只是把矛盾挪了个位置，白动两对文件；
      3. 一个文件只出现在一条建议里 —— 否则两条建议互相踩，
         执行完第一条，第二条的前提就不成立。
      每条闸都要有一个"关掉它就会出错"的反例，而不是一句"我们检查了"。

  主张 C（确定性）：
      建议必须与 Map 的遍历顺序无关。同一个目录刷新两次给出不同的建议，
      用户会以为界面在乱跳 —— 这类"不确定"比错更让人不敢用。

另外验 VM 层的两步（apply / undo）语义：写进 manualPairs 是不是四条双向、
撤回是不是只删"值仍然匹配"的条目（用户之后自己改过的配对不能被抹掉）。

> 写这份脚本时踩过的一个坑，留在这里当记录：第一版把时间基准写成 0，
> 结果**所有"应当有建议"的用例全部返回空**。原因是 `takenAt <= 0`
> 在这套代码里是"没读到拍摄时间"的哨兵 —— 用 0 当正常时间，
> 引擎会把这一对整个跳过。也就是说：这些"空结果"反过来证明了哨兵在承重。
> 下面统一用 `BASE`，只有 C4 故意留 0。

跑法：python verify_timeswap.py
"""

import pathlib

LEFT, RIGHT = "LEFT", "RIGHT"
TOL = 2000  # EXIF_TOLERANCE_MS
# 拍摄时间基准。必须 > 0：见文件头关于哨兵的说明。
BASE = 1_600_000_000_000

problems = []


def check(label, cond):
    if cond:
        print(f"  [OK]   {label}")
    else:
        print(f"  [FAIL] {label}")
        problems.append(label)


# ---------------------------------------------------------------- 模型


class Item:
    __slots__ = ("key", "name", "side", "taken_at")

    def __init__(self, key, name, side, taken_at=0):
        self.key = key
        self.name = name
        self.side = side
        self.taken_at = taken_at


def build(spec):
    """
    spec: [(lkey, ltime, rkey, rtime, reason)]
    reason 为 None 表示这一对是**手工指定**的（Kotlin 侧 reason 条目被抹掉）。
    """
    items, partner, reason = {}, {}, {}
    for lk, lt, rk, rt, rs in spec:
        items[lk] = Item(lk, lk + ".jpg", LEFT, lt)
        items[rk] = Item(rk, rk + ".jpg", RIGHT, rt)
        partner[lk] = rk
        partner[rk] = lk
        if rs:
            reason[lk] = rs
            reason[rk] = rs
    return items, partner, reason


# ------------------------------------------------- Kotlin 复刻：findTimeConflicts


def find_time_conflicts(partner, reason, by_key, tolerance=TOL):
    if not partner:
        return {}
    out, counted = {}, set()
    for a_key, b_key in partner.items():
        if a_key in counted:
            continue
        counted.add(a_key)
        counted.add(b_key)
        r = reason.get(a_key)
        if r is None or r in ("EXIF", "CONTENT"):
            continue
        a, b = by_key.get(a_key), by_key.get(b_key)
        if a is None or b is None:
            continue
        if a.taken_at <= 0 or b.taken_at <= 0:
            continue
        diff = abs(a.taken_at - b.taken_at)
        if diff <= tolerance:
            continue
        out[a_key] = diff
        out[b_key] = diff
    return out


# --------------------------------------------------- Kotlin 复刻：findTimeSwaps
#
# 返回 (left_key, old_right_key, new_right_key, other_left_key,
#       before_ms, after_ms, other_before_ms, other_after_ms)


def find_time_swaps(partner, reason, by_key, tolerance=TOL):
    if len(partner) < 4:
        return []

    # 1) 冲突对。判定必须与 findTimeConflicts 完全一致
    conflicts, seen = [], set()
    for a_key, b_key in partner.items():
        if a_key in seen:
            continue
        seen.add(a_key)
        seen.add(b_key)
        r = reason.get(a_key)
        if r is None or r in ("EXIF", "CONTENT"):
            continue
        a, b = by_key.get(a_key), by_key.get(b_key)
        if a is None or b is None:
            continue
        if a.taken_at <= 0 or b.taken_at <= 0:
            continue
        diff = abs(a.taken_at - b.taken_at)
        if diff <= tolerance:
            continue
        if a.side == LEFT:
            conflicts.append((a_key, b_key, diff))
        else:
            conflicts.append((b_key, a_key, diff))
    if len(conflicts) < 2:
        return []

    # 2) 全序排序：只按差值排的话，并列项的顺序随 Map 遍历变化
    conflicts.sort(key=lambda t: (-t[2], t[0], t[1]))

    out, used = [], set()
    for l_key, r_key, before1 in conflicts:
        if l_key in used or r_key in used:
            continue
        l, r = by_key.get(l_key), by_key.get(r_key)
        if l is None or r is None:
            continue
        best = None
        b_after1 = b_after2 = 0
        for l2_key, r2_key, other_before in conflicts:
            if l2_key == l_key or r2_key == r_key:
                continue
            if l2_key in used or r2_key in used:
                continue
            l2, r2 = by_key.get(l2_key), by_key.get(r2_key)
            if l2 is None or r2 is None:
                continue
            after1 = abs(l.taken_at - r2.taken_at)
            after2 = abs(l2.taken_at - r.taken_at)
            # 闸 1：两对都必须严格变好
            if after1 >= before1:
                continue
            if after2 >= other_before:
                continue
            # 闸 2：至少一对真的进容差
            if after1 > tolerance and after2 > tolerance:
                continue
            score = after1 + after2
            cur = (b_after1 + b_after2) if best is not None else 0x7FFFFFFFFFFFFFFF
            better = score < cur or (
                score == cur and max(after1, after2) < max(b_after1, b_after2)
            )
            if better:
                best = (l_key, r_key, r2_key, l2_key, before1, after1, other_before, after2)
                b_after1, b_after2 = after1, after2
        if best is not None:
            out.append(best)
            used |= {best[0], best[1], best[2], best[3]}
    return out


# --------------------------------------------------- Kotlin 复刻：VM 两步语义


def apply_time_swap(state, swap):
    """复刻 MainViewModel.applyTimeSwap。"""
    l_key, old_r, new_r, other_l = swap[0], swap[1], swap[2], swap[3]
    pk = state["pairKey"]
    if pk[l_key] == pk[new_r] or pk[other_l] == pk[old_r]:
        state["rejected"] = True
        return
    pairs = dict(state["manualPairs"])
    pairs[pk[l_key]] = pk[new_r]
    pairs[pk[new_r]] = pk[l_key]
    pairs[pk[other_l]] = pk[old_r]
    pairs[pk[old_r]] = pk[other_l]
    state["manualPairs"] = pairs
    un = set(state["unlinked"])
    un -= {pk[l_key], pk[old_r], pk[new_r], pk[other_l]}
    state["unlinked"] = un
    state["timeSwapPairs"] = {
        pk[l_key]: pk[new_r],
        pk[new_r]: pk[l_key],
        pk[other_l]: pk[old_r],
        pk[old_r]: pk[other_l],
    }
    state["timeSwapSession"] = state.get("sessionKey", "")


def undo_time_swaps(state):
    """复刻 MainViewModel.undoTimeSwaps：只删**值仍然匹配**的条目。"""
    ts = state["timeSwapPairs"]
    if not ts:
        return
    state["manualPairs"] = {k: v for k, v in state["manualPairs"].items() if ts.get(k) != v}
    state["timeSwapPairs"] = {}


# ================================================================ A. 能换回来

print("=" * 64)
print("A. 整体错位能成对换回来")
print("=" * 64)

# 右栏的时间整体挪了一位：序号配对全错，真实对应是「左边第 i 张 ↔ 右边第 i+1 张」
_L = [BASE + i * 100_000 for i in range(6)]
_R = [BASE + 100_000, BASE, BASE + 300_000, BASE + 200_000, BASE + 500_000, BASE + 400_000]
spec_a = [(f"L{i + 1}", _L[i], f"R{i + 1}", _R[i], "SEQ") for i in range(6)]
items_a, partner_a, reason_a = build(spec_a)
swaps_a = find_time_swaps(partner_a, reason_a, items_a)

check("A1 六对里能凑出三条互换建议", len(swaps_a) == 3)
s0 = swaps_a[0] if swaps_a else None
check(
    "A2 第一条就是 (L1,R1) ↔ (L2,R2)",
    s0 is not None and (s0[0], s0[1], s0[2], s0[3]) == ("L1", "R1", "R2", "L2"),
)
check("A3 换前差 100 秒、换后差 0", s0 is not None and s0[4] == 100_000 and s0[5] == 0)
check("A4 另一半那对换前换后同样改善", s0 is not None and s0[6] == 100_000 and s0[7] == 0)
check(
    "A5 每条建议都真的让两对同时变好",
    all(s[5] < s[4] and s[7] < s[6] for s in swaps_a),
)
check(
    "A6 每条建议里至少一对落进容差",
    all(s[5] <= TOL or s[7] <= TOL for s in swaps_a),
)
_all_keys = [k for s in swaps_a for k in s[:4]]
check("A7 三条建议的 12 个 key 两两不重复", len(_all_keys) == len(set(_all_keys)))
check(
    "A8 三条建议恰好覆盖全部六对（L1/L3/L5 打头）",
    [s[0] for s in swaps_a] == ["L1", "L3", "L5"],
)


# ================================================================ B. 三道闸

print()
print("=" * 64)
print("B. 三道闸各自承重（都有反例）")
print("=" * 64)

# 闸 1 反例：交换会让这一对变得**更差**
items_b1, partner_b1, reason_b1 = build([
    ("L1", BASE, "R1", BASE + 100_000, "SEQ"),
    ("L2", BASE + 1_000_000, "R2", BASE + 1_100_000, "SEQ"),
])
check("B1 交换后会更差 → 不给建议（闸 1 承重）",
      find_time_swaps(partner_b1, reason_b1, items_b1) == [])

# 闸 2 反例：两对都变好，但都没进容差 —— 只是把矛盾挪了个位置
items_b2, partner_b2, reason_b2 = build([
    ("L1", BASE, "R1", BASE + 100_000, "SEQ"),
    ("L2", BASE + 200_000, "R2", BASE + 50_000, "SEQ"),
])
swaps_b2 = find_time_swaps(partner_b2, reason_b2, items_b2)
_after_ok = (50_000 < 100_000) and (100_000 < 150_000)  # 两对确实都变好了
check("B2 前提复核：这两对交换后确实都变好、但都不进容差", _after_ok)
check("B2 两对都没进容差 → 不给建议（闸 2 承重）", swaps_b2 == [])

# 闸 3：三对时只能出 1 条 —— 剩下的那一对找不到"未占用"的伙伴
items_b3, partner_b3, reason_b3 = build([
    ("L1", BASE, "R1", BASE + 100_000, "SEQ"),
    ("L2", BASE + 100_000, "R2", BASE, "SEQ"),
    ("L3", BASE + 200_000, "R3", BASE + 300_000, "SEQ"),
])
swaps_b3 = find_time_swaps(partner_b3, reason_b3, items_b3)
check("B3 三对只出一条（用掉 4 个文件后剩的单对无法互换）", len(swaps_b3) == 1)
check("B3 用掉的是 L1/L2/R1/R2",
      bool(swaps_b3) and (swaps_b3[0][0], swaps_b3[0][2]) == ("L1", "R2"))


# ================================================================ C. 排除闸

print()
print("=" * 64)
print("C. 与「冲突」同源的四条排除闸")
print("=" * 64)

_items_c1, _partner_c1, _reason_c1 = build([("L1", BASE, "R1", BASE + 100_000, "SEQ")])
check("C1 只有一对冲突 → 空（构不成互换）",
      find_time_swaps(_partner_c1, _reason_c1, _items_c1) == [])

# 手工指定的配对：reason 被抹掉，不再被反向质疑
items_c2, partner_c2, reason_c2 = build([
    ("L1", BASE, "R1", BASE + 100_000, None),
    ("L2", BASE + 100_000, "R2", BASE, None),
])
check("C2 手工配对（reason 为 null）不参与交换",
      find_time_swaps(partner_c2, reason_c2, items_c2) == [])

items_c3, partner_c3, reason_c3 = build([
    ("L1", BASE, "R1", BASE + 100_000, "EXIF"),
    ("L2", BASE + 100_000, "R2", BASE, "EXIF"),
])
check("C3 EXIF 依据（差 ≤ 容差才可能配上）不参与交换",
      find_time_swaps(partner_c3, reason_c3, items_c3) == [])

# 这一条故意保留哨兵 0：左边那张"没读到拍摄时间"
items_c4, partner_c4, reason_c4 = build([
    ("L1", 0, "R1", BASE + 100_000, "SEQ"),
    ("L2", 0, "R2", 0, "SEQ"),
])
check("C4 takenAt=0（哨兵：没读到）不参与交换",
      find_time_swaps(partner_c4, reason_c4, items_c4) == [])

# 容差内不算冲突：差 1 秒是**支持**这一对的证据
items_c5, partner_c5, reason_c5 = build([
    ("L1", BASE, "R1", BASE + 1_000, "SEQ"),
    ("L2", BASE, "R2", BASE, "SEQ"),
])
check("C5 容差内的配对不算冲突、不参与交换",
      find_time_swaps(partner_c5, reason_c5, items_c5) == [])

# 混合：两对冲突 + 两对正常 → 只动冲突的那四张
items_c6, partner_c6, reason_c6 = build([
    ("L1", BASE, "R1", BASE + 100_000, "SEQ"),
    ("L2", BASE + 100_000, "R2", BASE, "SEQ"),
    ("L3", BASE + 200_000, "R3", BASE + 200_500, "SEQ"),   # 差 0.5 秒：正常
    ("L4", BASE + 300_000, "R4", BASE + 300_500, "SEQ"),   # 差 0.5 秒：正常
])
swaps_c6 = find_time_swaps(partner_c6, reason_c6, items_c6)
check("C6 混合数据只出一条建议", len(swaps_c6) == 1)
check("C6 且只涉及冲突的那四张，绝不碰正常配好的两对",
      bool(swaps_c6) and set(swaps_c6[0][:4]) == {"L1", "R1", "R2", "L2"})


# ================================================================ D. 确定性

print()
print("=" * 64)
print("D. 与 Map 遍历顺序无关")
print("=" * 64)

# 用完全相反的插入顺序重建同一份数据
items_d, partner_d, reason_d = {}, {}, {}
for lk, lt, rk, rt, rs in reversed(spec_a):
    items_d[lk] = Item(lk, lk + ".jpg", LEFT, lt)
    items_d[rk] = Item(rk, rk + ".jpg", RIGHT, rt)
    partner_d[lk] = rk
    partner_d[rk] = lk
    reason_d[lk] = rs
    reason_d[rk] = rs
check("D1 反过来插入 partner，建议完全一致",
      find_time_swaps(partner_d, reason_d, items_d) == swaps_a)
check("D2 重复调用结果稳定（无隐藏状态）",
      len({str(find_time_swaps(partner_a, reason_a, items_a)) for _ in range(20)}) == 1)


# ================================================================ E. VM 两步

print()
print("=" * 64)
print("E. 执行与撤回（manualPairs 的写 / 撤回只删仍匹配的）")
print("=" * 64)

swap_a = swaps_a[0]


def fresh_state():
    return {
        "pairKey": {k: k.lower() for k in items_a},   # 这里主文件名不撞车
        "manualPairs": {},
        "unlinked": set(),
        "timeSwapPairs": {},
        "timeSwapSession": "",
        "sessionKey": "siteA",                        # 当前现场
        "rejected": False,
    }


_state = fresh_state()
apply_time_swap(_state, swap_a)

check("E1 写进四条（两对、双向）", len(_state["manualPairs"]) == 4)
check("E2 L1 ↔ R2 双向都写了",
      _state["manualPairs"].get("l1") == "r2" and _state["manualPairs"].get("r2") == "l1")
check("E3 L2 ↔ R1 双向都写了",
      _state["manualPairs"].get("l2") == "r1" and _state["manualPairs"].get("r1") == "l2")

# 交换前如果这几个文件被"解除"过，交换必须把那几条解除一并撤掉 ——
# 否则 unlinked 会抢在 manualPairs 之前生效，表现为"点了换回、配对没变"
_state2 = fresh_state()
_state2["unlinked"] = {"l1", "r2", "l2", "r1"}
apply_time_swap(_state2, swap_a)
check("E4 交换会撤掉这四个文件已有的解除记录", _state2["unlinked"] == set())

# 撤回：用户之后自己改过的那条必须留下
_state["manualPairs"]["l1"] = "别的"
undo_time_swaps(_state)
check("E5 撤回只删仍匹配的条目，用户改过的那条保留",
      _state["manualPairs"] == {"l1": "别的"})
check("E6 撤回后没有人再可以撤回", _state["timeSwapPairs"] == {})

# 没改过的情形：撤回应当干净回到原状
_state3 = fresh_state()
apply_time_swap(_state3, swap_a)
undo_time_swaps(_state3)
check("E7 原封不动地撤回 → 回到毫无手动干预", _state3["manualPairs"] == {})

# pairKey 撞车：两边主文件名相同时会被 applyManualOverrides 静默跳过
_state4 = fresh_state()
_state4["pairKey"] = {"L1": "same", "R2": "same", "R1": "r1", "L2": "l2"}
apply_time_swap(_state4, swap_a)
check("E8 pairKey 撞车时提前拒绝，不动任何状态",
      _state4["rejected"] and _state4["manualPairs"] == {})


# ================================================================ F. 同源

print()
print("=" * 64)
print("F. 与「时间冲突」同源")
print("=" * 64)

conf_a = find_time_conflicts(partner_a, reason_a, items_a)
_swap_keys = {k for s in swaps_a for k in s[:4]}
check("F1 建议涉及的 key 全部来自冲突集合",
      bool(_swap_keys) and _swap_keys.issubset(set(conf_a)))
check(
    "F2 每条建议的「原配对」本身就是一个冲突对",
    all(conf_a.get(s[0]) == s[4] for s in swaps_a),
)
check(
    "F3 冲突对数 ≥ 建议数（换不动的那些只能人工核对）",
    len(conf_a) // 2 >= len(swaps_a),
)

# ================================================================ G. 现场作用域

print()
print("=" * 64)
print("G. 撤回凭据必须属于当前现场（换目录后不能误删）")
print("=" * 64)


def swap_undo_available(state):
    """复刻 MainViewModel.swapUndoAvailable。"""
    return bool(state["timeSwapPairs"]) and state["timeSwapSession"] == state["sessionKey"]


def undo_time_swaps_scoped(state):
    """复刻加了现场作用域的 undoTimeSwaps。返回是否**真的撤回**了。"""
    if not state["timeSwapPairs"]:
        return False
    if not swap_undo_available(state):
        # 现场已换：凭据整批作废，一条都不许碰
        state["timeSwapPairs"] = {}
        state["timeSwapSession"] = ""
        return False
    ts = state["timeSwapPairs"]
    state["manualPairs"] = {k: v for k, v in state["manualPairs"].items() if ts.get(k) != v}
    state["timeSwapPairs"] = {}
    state["timeSwapSession"] = ""
    return True


# 同一现场：撤得动
_g1 = fresh_state()
apply_time_swap(_g1, swap_a)
check("G1 同一现场下凭据有效", swap_undo_available(_g1))
check("G2 同一现场下撤回真的动手了", undo_time_swaps_scoped(_g1) is True)
check("G2 撤回后 manualPairs 清空", _g1["manualPairs"] == {})

# 换现场：撤不动，而且**一条都不能删**
_g3 = fresh_state()
apply_time_swap(_g3, swap_a)
_g3["manualPairs"]["别的目录同名配对"] = "另一个值"   # 换目录后进来的、与本凭据无关的配对
_snapshot = dict(_g3["manualPairs"])
_g3["sessionKey"] = "siteB"                          # 换目录
check("G3 换现场后凭据失效", not swap_undo_available(_g3))
check("G4 失效时撤回不动任何配对", undo_time_swaps_scoped(_g3) is False
      and _g3["manualPairs"] == _snapshot)
check("G5 失效后凭据被清（不再挂着一条假的'可撤回'）", _g3["timeSwapPairs"] == {})

# 结构断言：改一处忘一处的风险，用源码扫描钉住
_vm_path = (
    pathlib.Path(__file__).resolve().parent
    / "app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt"
)
_vm_src = _vm_path.read_text(encoding="utf-8")
check("G6 swapUndoAvailable 被定义", "private fun swapUndoAvailable(" in _vm_src)
check("G7 撤回与建议都走同一个口径（出现 ≥3 次：定义 + 撤回 + 重算）",
      _vm_src.count("swapUndoAvailable(") >= 3)
# R22 起三种凭据收敛成一个 Credential 类型（pairs + unlinked + session 一体）。
# 现场戳不再是独立字段，而是签发时一起写进 swapCredential.session。
check("G8 applyTimeSwap 会给凭据打上现场戳",
      "swapCredential = Credential(" in _vm_src
      and "session = s.sessionKey" in _vm_src)
check("G9 恢复现场时会作废旧凭据",
      _vm_src.count("swapCredential = Credential()") >= 3)
check("G10 清除手动干预时也一并作废凭据",
      "swapCredential = Credential()" in _vm_src.split("fun resetPairingOverrides")[1])

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：整体错位能成对换回来，三道闸都承重，结果与遍历顺序无关；")
print("执行写四条双向、撤回只删仍匹配的条目。")
