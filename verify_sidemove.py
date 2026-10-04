"""第二十一轮验证：「单侧搬移」（findSideMoves）。

前几轮修的错配，都是"两张都配错了、能互相接手"（R18 互换）或者"两边都还没配上"
（R19 漏配）。本轮补上第三种：**只有一对配错，而那张图真正的对象还空着**。

例子（左栏 2 张、右栏 3 张，多出来的那张是真的多余的）：

| 左 | 引擎按顺序配给的右 | 时间差 | 真实对应 |
| --- | --- | --- | --- |
| a（100s） | p（300s） | 200s ✗ | a ↔ z（100s） |
| b（200s） | q（200s） | 0 ✓ | b ↔ q |
| —— | z（100s）未配对 | | p 才是多余的 |

互换救不了它（b 已经是对的，怎么换都不会让两对同时变好），
唯一的解法就是把 a 从 p 挪到 z、让 p 回到未配对 —— p 本来就该是孤儿。

R18 的注释里明确拒绝过单侧搬移，理由是"只会让 B 变成孤儿，把错配换成漏配"。
那个判断**只对当时那把尺子成立**：那时候选只有"名字/顺序"级别的线索。
本轮补上两条独立硬证据，两把尺子必须**同时**成立：

  证据 1（尺寸）：候选与搬动对象的宽 × 高 × 体积**完全一致**；
  证据 2（时间）：候选与搬动对象的拍摄时间差**落在 2 秒容差内**。

于是搬完是"一个确定错的配对 → 一个确定对的配对"，代价只是一张卡片回到未配对，
而它确实是多余的。这份脚本要证明的是**每一道闸都在承重**，而不是"看起来对"。

跑法：python verify_sidemove.py
"""

from pathlib import Path

LEFT, RIGHT = "LEFT", "RIGHT"
TOL = 2000  # EXIF_TOLERANCE_MS

problems = []


def check(label, cond):
    if cond:
        print(f"  [OK]   {label}")
    else:
        print(f"  [FAIL] {label}")
        problems.append(label)


# ---------------------------------------------------------------- 模型


class Item:
    __slots__ = ("key", "name", "side", "width", "height", "size", "taken_at")

    def __init__(self, key, name, side, width=0, height=0, size=0, taken_at=0):
        self.key = key
        self.name = name
        self.side = side
        self.width = width
        self.height = height
        self.size = size
        self.taken_at = taken_at

    @property
    def pair_key(self):
        return self.name.rsplit(".", 1)[0].lower()


# ------------------------------------- Kotlin 复刻：collectConflicts（唯一的冲突判定）
# 返回 [(左 key, 右 key, 差值)]，按差值降序 → 左 key → 右 key（全序，确定性）。
# R21 把原来散在 findTimeConflicts / findTimeSwaps 里的两份判定抽成了一个函数，
# 这里的复刻同样只写一份 —— 三处用同一把尺子才不会出现
# "面板报了冲突、另一边却说没法修"。


def collect_conflicts(partner, reason, by_key, tol=TOL):
    if not partner:
        return []
    out, counted = [], set()
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
        abs_d = abs(a.taken_at - b.taken_at)
        if abs_d <= tol:
            continue
        out.append((a_key, b_key, abs_d) if a.side == LEFT else (b_key, a_key, abs_d))
    out.sort(key=lambda t: (-t[2], t[0], t[1]))
    return out


# ------------------------------------------- Kotlin 复刻：findSideMoves
# 返回 [(move_key, from_key, to_key, from_delta, to_delta)]


def find_side_moves(partner, reason, by_key, unlinked=None, content_keys=None, tol=TOL):
    unlinked = unlinked or set()
    content_keys = content_keys or {}
    conflicts = collect_conflicts(partner, reason, by_key, tol)
    if not conflicts:
        return []

    def usable(it):
        return (it.key not in partner and it.pair_key not in unlinked
                and it.width > 0 and it.height > 0 and it.size > 0)

    sig = lambda it: (it.width, it.height, it.size)  # noqa: E731

    free_by_side, side_sig_count = {}, {}
    for it in by_key.values():
        if usable(it):
            free_by_side.setdefault(it.side, {}).setdefault(sig(it), []).append(it)
        if it.width > 0 and it.height > 0 and it.size > 0:
            m = side_sig_count.setdefault(it.side, {})
            m[sig(it)] = m.get(sig(it), 0) + 1

    out, used = [], set()
    for (l_key, r_key, before) in conflicts:
        for direction in (0, 1):
            move_key = l_key if direction == 0 else r_key
            from_key = r_key if direction == 0 else l_key
            if move_key in used or from_key in used:
                continue
            move = by_key.get(move_key)
            if move is None:
                continue
            target_side = RIGHT if direction == 0 else LEFT
            cands = free_by_side.get(target_side, {}).get(sig(move))
            if not cands or len(cands) != 1:
                continue
            if side_sig_count.get(move.side, {}).get(sig(move)) != 1:
                continue
            to = cands[0]
            if to.key == from_key or to.key in used:
                continue
            if to.taken_at <= 0:
                continue
            after = abs(move.taken_at - to.taken_at)
            if after > tol:
                continue
            mk, tk = content_keys.get(move.key), content_keys.get(to.key)
            if mk is not None and tk is not None and mk != tk:
                continue
            out.append((move_key, from_key, to.key, before, after))
            used.update({move_key, from_key, to.key})
    return out


# ------------------------------------- Kotlin 复刻：linkSideMoves / undoSideMoves


def link_side_moves(state, moves):
    """返回实际搬成的张数；state 里模拟 manualPairs / unlinked / 凭据三件套。"""
    if not moves:
        return 0
    by_key, pk = state["byKey"], state["pairKey"]
    pairs = dict(state["manualPairs"])
    unlinked = set(state["unlinked"])
    written, freed, moved = {}, set(), 0
    for (move_key, from_key, to_key, _b, _a) in moves:
        move, frm, to = by_key.get(move_key), by_key.get(from_key), by_key.get(to_key)
        if move is None or frm is None or to is None:
            continue
        if pk[move_key] == pk[to_key]:
            continue
        old = pairs.pop(pk[move_key], None)
        if old is not None:
            pairs.pop(old, None)
        pairs[pk[move_key]] = pk[to_key]
        pairs[pk[to_key]] = pk[move_key]
        written[pk[move_key]] = pk[to_key]
        written[pk[to_key]] = pk[move_key]
        old = pairs.pop(pk[from_key], None)
        if old is not None:
            pairs.pop(old, None)
        unlinked.add(pk[from_key])
        freed.add(pk[from_key])
        moved += 1
    if moved == 0:
        return 0
    state["manualPairs"] = pairs
    state["unlinked"] = unlinked
    state["sideMovePairs"] = written
    state["sideMoveUnlinked"] = freed
    return moved


def undo_side_moves(state, session_key, current_session):
    """现场一致 → 只删值仍匹配的条目 + 把 freed 从 unlinked 拿掉；否则整批作废。"""
    if not state.get("sideMovePairs"):
        return "noop"
    if session_key is None or state.get("sideMoveSession") != current_session:
        state["sideMovePairs"] = {}
        state["sideMoveUnlinked"] = set()
        return "stale"
    cred = state["sideMovePairs"]
    state["manualPairs"] = {k: v for k, v in state["manualPairs"].items() if cred.get(k) != v}
    state["unlinked"] = set(state["unlinked"]) - state["sideMoveUnlinked"]
    state["sideMovePairs"] = {}
    state["sideMoveUnlinked"] = set()
    return "undone"


def make_state(items, matches, unlinked=None):
    by_key = {it.key: it for it in items}
    return {
        "pairKey": {it.key: it.pair_key for it in items},
        "byKey": by_key,
        "manualPairs": {},
        "unlinked": set(unlinked or ()),
        "sizeMatches": matches,
        "sideMovePairs": {},
        "sideMoveUnlinked": set(),
        "sideMoveSession": "",
    }


# ================================================================ A. 能搬

print("=" * 64)
print("A. 一对配错 + 真对象还空着 → 搬过去")
print("=" * 64)

# a 是冲突那张（配给了 p），z 是它真正的对象（未配对）。
# b 已经配对了，所以互换救不了这一对 —— 只能搬。
# 时间单位用秒，直接当毫秒看（差值比较只看相对大小）。
a_l = Item("La", "a.jpg", LEFT, 4000, 3000, 2_300_000, taken_at=100_000)
b_l = Item("Lb", "b.jpg", LEFT, 4000, 3000, 2_410_500, taken_at=200_000)
p_r = Item("Rp", "p.jpg", RIGHT, 4000, 3000, 2_500_000, taken_at=300_000)  # 多余的那张
q_r = Item("Rq", "q.jpg", RIGHT, 4000, 3000, 2_410_500, taken_at=200_000)
z_r = Item("Rz", "z.jpg", RIGHT, 4000, 3000, 2_300_000, taken_at=100_500)  # a 的真对象

items_a = [a_l, b_l, p_r, q_r, z_r]
by_a = {it.key: it for it in items_a}
# 引擎按顺序配的：a↔p（差 200s）、b↔q（差 0）
partner_a = {"La": "Rp", "Rp": "La", "Lb": "Rq", "Rq": "Lb"}
reason_a = {"La": "SEQ", "Rp": "SEQ", "Lb": "SEQ", "Rq": "SEQ"}

conf_a = collect_conflicts(partner_a, reason_a, by_a)
check("A1 冲突只有一对（a↔p）", len(conf_a) == 1)
moves_a = find_side_moves(partner_a, reason_a, by_a)
check("A2 找出一条搬移建议", len(moves_a) == 1)
if moves_a:
    mv, frm, to, before, after = moves_a[0]
    check("A3 搬的是 a，从 p 搬到 z", (mv, frm, to) == ("La", "Rp", "Rz"))
    check("A4 搬前差 200s（> 容差）、搬后差 500ms（≤ 容差）", (before, after) == (200_000, 500))
else:
    check("A3 搬的是 a，从 p 搬到 z", False)
    check("A4 搬前差 200s（> 容差）、搬后差 500ms（≤ 容差）", False)

check("A5 互换救不了这一对（b 已经是对的）——建议里没有 TimeSwap 式解法",
      len(collect_conflicts(partner_a, reason_a, by_a, TOL)) == 1
      and find_side_moves(partner_a, reason_a, by_a, tol=TOL) != [])


# ================================================================ B. 每道闸各自承重

print()
print("=" * 64)
print("B. 六道闸：每条配一个「关掉它就会出错」的反例")
print("=" * 64)


def with_taken(key, value):
    """在 A 组数据上只改某一张的拍摄时间（其余照旧）—— 用来单测某一道闸。"""
    import copy
    its = copy.deepcopy(items_a)
    for it in its:
        if it.key == key:
            it.taken_at = value
    return {it.key: it for it in its}


check("B1 闸 1（必须在冲突里）：把 a 的时间改成与 p 一致 → 不搬",
      find_side_moves(partner_a, reason_a, with_taken("La", 300_000)) == [])
check("B1b 闸 1（依据是 EXIF 的不算冲突）：理由改成 EXIF → 不搬",
      find_side_moves(partner_a, {**reason_a, "La": "EXIF"}, by_a) == [])

# 闸 2：候选必须未配对
partner_b2 = {**partner_a, "Rz": "La", "La": "Rz"}
check("B2 闸 2（候选必须未配对）：z 已经配上了 → 不搬",
      find_side_moves(partner_b2, reason_a, by_a) == [])
check("B2b 闸 2（被用户解除过的不再建议）：z 解除过 → 不搬",
      find_side_moves(partner_a, reason_a, by_a, unlinked={"z"}) == [])

# 闸 3：签名唯一
extra_same_sig_right = Item("Rz2", "z2.jpg", RIGHT, 4000, 3000, 2_300_000, 100_400)
check("B3 闸 3（候选侧唯一）：右栏再来一张同签名、未配对的 → 不搬",
      find_side_moves(partner_a, reason_a, {**by_a, "Rz2": extra_same_sig_right}) == [])
extra_same_sig_left = Item("La2", "a2.jpg", LEFT, 4000, 3000, 2_300_000, 100_200)
check("B3b 闸 3（搬动侧唯一）：左栏还有一张同签名的 → 不搬（搬哪张都是猜）",
      find_side_moves(partner_a, reason_a, {**by_a, "La2": extra_same_sig_left}) == [])
check("B3c 闸 3 计的是**所有**同签名文件（不论它自己配没配上）",
      find_side_moves({**partner_a, "La2": "Rq"}, reason_a, {**by_a, "La2": extra_same_sig_left}) == [])

# 签名必须**三项全同**
swapped = Item("Rz", "z.jpg", RIGHT, 3000, 4000, 2_300_000, 100_500)
check("B4 闸 3（签名）：宽高对调 → 不搬",
      find_side_moves(partner_a, reason_a, {**by_a, "Rz": swapped}) == [])
off_by_one = Item("Rz", "z.jpg", RIGHT, 4000, 3000, 2_300_001, 100_500)
check("B4b 闸 3（签名）：体积差 1 字节 → 不搬",
      find_side_moves(partner_a, reason_a, {**by_a, "Rz": off_by_one}) == [])

# 闸 4：必须真的进容差
check("B5 闸 4（搬完进容差）：候选时间差 5s（比 200s 好，但仍超容差）→ 不搬",
      find_side_moves(partner_a, reason_a,
                      {**by_a, "Rz": Item("Rz", "z.jpg", RIGHT, 4000, 3000, 2_300_000, 105_000)}) == [])
check("B5b 闸 4：候选没有拍摄时间（0 是「没读到」的哨兵）→ 不搬",
      find_side_moves(partner_a, reason_a,
                      {**by_a, "Rz": Item("Rz", "z.jpg", RIGHT, 4000, 3000, 2_300_000, 0)}) == [])
check("B5c 闸 4 边界：恰好等于容差（2000ms）→ 搬（与引擎的 ≤ 一致）",
      len(find_side_moves(partner_a, reason_a,
                          {**by_a, "Rz": Item("Rz", "z.jpg", RIGHT, 4000, 3000, 2_300_000, 102_000)})) == 1)

# 闸 5：内容指纹否决
check("B6 闸 5（指纹否决）：两边都有指纹且不同 → 不搬",
      find_side_moves(partner_a, reason_a, by_a,
                      content_keys={"La": "h1", "Rz": "h2"}) == [])
check("B6b 闸 5：只有一边有指纹 → 信息不足，仍搬",
      len(find_side_moves(partner_a, reason_a, by_a, content_keys={"La": "h1"})) == 1)

# 闸 6：一个文件只出现在一条建议里
# 两个左栏冲突对同时想抢右栏那一张未配对的 z → 只出一条
l1 = Item("L1", "1.jpg", LEFT, 4000, 3000, 2_300_000, 100_000)
l2 = Item("L2", "2.jpg", LEFT, 4000, 3000, 2_300_000, 100_100)  # 同签名 → 触发闸 3（搬动侧不唯一）
items_b6 = [l1, l2, p_r, q_r, z_r]
by_b6 = {it.key: it for it in items_b6}
partner_b6 = {"L1": "Rp", "Rp": "L1", "L2": "Rq", "Rq": "L2"}
check("B6c 闸 3+6：两个同签名冲突对抢同一张候选 → 一条都不出（签名不唯一）",
      find_side_moves(partner_b6, {k: "SEQ" for k in partner_b6}, by_b6) == [])

# 闸 6 的反面：两个候选、两张不同的冲突对 → 各出一条
l1c = Item("L1", "1.jpg", LEFT, 4000, 3000, 2_300_000, 100_000)
l2c = Item("L2", "2.jpg", LEFT, 4000, 3000, 2_999_999, 100_100)
p1 = Item("Rp1", "p1.jpg", RIGHT, 4000, 3000, 2_500_000, 900_000)
p2 = Item("Rp2", "p2.jpg", RIGHT, 4000, 3000, 2_888_888, 900_100)
z1 = Item("Rz1", "z1.jpg", RIGHT, 4000, 3000, 2_300_000, 100_200)
z2 = Item("Rz2", "z2.jpg", RIGHT, 4000, 3000, 2_999_999, 100_300)
items_b6d = [l1c, l2c, p1, p2, z1, z2]
by_b6d = {it.key: it for it in items_b6d}
partner_b6d = {"L1": "Rp1", "Rp1": "L1", "L2": "Rp2", "Rp2": "L2"}
moves_b6d = find_side_moves(partner_b6d, {k: "SEQ" for k in partner_b6d}, by_b6d)
check("B6d 闸 6 的反面：两对各自有候选 → 两条建议，谁也不抢谁",
      sorted((m[0], m[2]) for m in moves_b6d) == [("L1", "Rz1"), ("L2", "Rz2")])


# ================================================================ C. 反方向：搬右栏那张

print()
print("=" * 64)
print("C. 反方向：冲突对里该搬走的是右栏那张 → 搬到左栏未配对的那张")
print("=" * 64)

# 右栏那张的真对象在左栏、且没配上
r_l = Item("Lr", "r.jpg", LEFT, 4000, 3000, 2_300_000, 500_000)   # 未配对，是 Rp2 的真对象
x_l = Item("Lx", "x.jpg", LEFT, 4000, 3000, 2_500_000, 600_000)   # 配给了 Rp2（错的）
y_r = Item("Ry", "y.jpg", RIGHT, 4000, 3000, 2_500_000, 300_000)
rp2 = Item("Rp2", "p2.jpg", RIGHT, 4000, 3000, 2_300_000, 499_000)  # 真对象是 Lr
items_c = [r_l, x_l, y_r, rp2]
by_c = {it.key: it for it in items_c}
partner_c = {"Lx": "Rp2", "Rp2": "Lx"}
moves_c = find_side_moves(partner_c, {"Lx": "SEQ", "Rp2": "SEQ"}, by_c)
check("C1 搬的是右栏那张（Rp2），从 Lx 搬到 Lr",
      len(moves_c) == 1 and (moves_c[0][0], moves_c[0][1], moves_c[0][2]) == ("Rp2", "Lx", "Lr"))
check("C2 搬后差值 1s（≤ 容差）", moves_c and moves_c[0][4] == 1_000)


# ================================================================ D. 确定性

print()
print("=" * 64)
print("D. 确定性：输入顺序变了，建议必须逐字相同")
print("=" * 64)

by_rev = {k: by_a[k] for k in reversed(list(by_a.keys()))}
partner_rev = {k: partner_a[k] for k in reversed(list(partner_a.keys()))}
check("D1 把 byKey / partner 都反过来插入 → 建议逐字相同",
      find_side_moves(partner_rev, reason_a, by_rev) == moves_a)
check("D2 两条冲突的排序是全序（差值降序 → 左 key → 右 key）",
      [t[0] for t in collect_conflicts(partner_b6d, {k: "SEQ" for k in partner_b6d}, by_b6d)]
      == ["L1", "L2"])


# ================================================================ E. 执行语义

print()
print("=" * 64)
print("E. 搬移写什么：两条配对 + 被腾出来的进解除集合 + 凭据")
print("=" * 64)

st_e = make_state(items_a, [])
moved = link_side_moves(st_e, moves_a)
check("E1 搬成 1 张", moved == 1)
check("E2 a ↔ z 双向都写了",
      st_e["manualPairs"].get("a") == "z" and st_e["manualPairs"].get("z") == "a")
check("E3 原来的 a ↔ p 那一对不在配对里了（p 也不再是任何人的对象）",
      st_e["manualPairs"].get("p") is None and "p" not in st_e["manualPairs"].values())
check("E4 p 进了解除集合（否则引擎下次重算会按顺序把它配给别人）", st_e["unlinked"] == {"p"})
check("E5 凭据记下了这次写进去的两条", st_e["sideMovePairs"] == {"a": "z", "z": "a"})
check("E6 凭据记下了被腾出来的那张", st_e["sideMoveUnlinked"] == {"p"})

# 空建议不动状态
st_e2 = make_state(items_a, [])
check("E7 空建议 → 返回 0、什么都不写",
      link_side_moves(st_e2, []) == 0 and st_e2["manualPairs"] == {} and st_e2["unlinked"] == set())

# pairKey 撞车：两边主文件名相同
same_l = Item("Ls", "same.jpg", LEFT, 4000, 3000, 2_300_000, 100_000)
same_r = Item("Rs", "same.png", RIGHT, 4000, 3000, 2_300_000, 100_500)
wrong_r = Item("Rx", "x.jpg", RIGHT, 4000, 3000, 2_500_000, 900_000)
items_e3 = [same_l, wrong_r, same_r]
by_e3 = {it.key: it for it in items_e3}
partner_e3 = {"Ls": "Rx", "Rx": "Ls"}
moves_e3 = find_side_moves(partner_e3, {"Ls": "SEQ", "Rx": "SEQ"}, by_e3)
st_e3 = make_state(items_e3, [])
check("E8 签名相同、时间进容差 → 建议照样给出（撞车在执行时才拦）",
      len(moves_e3) == 1 and link_side_moves(st_e3, moves_e3) == 0)
check("E9 撞车时不动状态（宁可不动，也不要点了没反应还留下半条）",
      st_e3["manualPairs"] == {} and st_e3["unlinked"] == set())

# 撤回：只删值仍匹配的条目 + 把 freed 从 unlinked 拿掉
st_e["sideMoveSession"] = "fp1"
st_e["manualPairs"]["extra"] = "extra2"  # 用户后来自己加的
st_e["manualPairs"]["extra2"] = "extra"
st_e["manualPairs"]["a"] = "changed"     # 用户后来把搬移的那条改掉了
st_e["manualPairs"].pop("z", None)
res = undo_side_moves(st_e, "fp1", "fp1")
check("E10 撤回成功", res == "undone")
check("E11 用户后来改过的那条**保留**（值不匹配就不删）",
      st_e["manualPairs"].get("a") == "changed" and st_e["manualPairs"].get("z") is None)
check("E12 用户自己加的那条不受影响",
      st_e["manualPairs"].get("extra") == "extra2" and st_e["manualPairs"].get("extra2") == "extra")
check("E13 被腾出来的 p 不再处于解除状态", "p" not in st_e["unlinked"])
check("E14 凭据清空", st_e["sideMovePairs"] == {} and st_e["sideMoveUnlinked"] == set())

# 现场换了：整批作废、不动任何配对
st_f = make_state(items_a, [])
link_side_moves(st_f, moves_a)
st_f["sideMoveSession"] = "fp_old"
before_pairs = dict(st_f["manualPairs"])
before_unlinked = set(st_f["unlinked"])
check("E15 换现场 → 作废", undo_side_moves(st_f, "fp_old", "fp_new") == "stale")
check("E16 作废时**不动任何配对**（同名文件的 pairKey 撞车是常态）",
      st_f["manualPairs"] == before_pairs and st_f["unlinked"] == before_unlinked)
check("E17 没有凭据时 → noop", undo_side_moves(make_state(items_a, []), "fp", "fp") == "noop")


# ================================================================ F. 结构断言

print()
print("=" * 64)
print("F. 接线结构（行为测试看不见的地方）")
print("=" * 64)

SRC = Path(__file__).resolve().parent / "app/src/main/java/com/yuanbao/pairrename"
pair_kt = (SRC / "util/Pairing.kt").read_text(encoding="utf-8")
adv_kt = (SRC / "util/Advisor.kt").read_text(encoding="utf-8")
vm_kt = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")
app_kt = (SRC / "ui/PairRenameApp.kt").read_text(encoding="utf-8")
dlg_kt = (SRC / "ui/dialogs/CompareDialog.kt").read_text(encoding="utf-8")
hub_kt = (SRC / "ui/ToolsHub.kt").read_text(encoding="utf-8")

check("F1 冲突判定只有一份实现（collectConflicts），所有修法共用它",
      "internal fun collectConflicts(" in pair_kt
      and pair_kt.count("collectConflicts(") == 5)  # 定义 + 四个调用点（报冲突/互换/搬移/平移）
check("F2 findTimeSwaps 里没有内联的冲突判定残留",
      "val conflicts = collectConflicts(partner, reason, byKey, toleranceMs)" in pair_kt
      and "if (r == PairReason.EXIF || r == PairReason.CONTENT) return@forEach" not in
      pair_kt.split("fun findTimeSwaps")[1].split("fun ")[0])
check("F3 UiState 带 sideMoves / 反查表 / 凭据三件套",
      "val sideMoves: List<SideMove> = emptyList()" in vm_kt
      and "val sideMoveByKey: Map<String, SideMove> = emptyMap()" in vm_kt
      and "val sideMoveCredential: Credential = Credential()" in vm_kt)
check("F4 单张与批量共用一条写入路径（linkSideMoves 恰好 2 个调用点 + 1 处定义）",
      vm_kt.count("linkSideMoves(") == 3)
check("F5 撤回判定只有一个口径（sideMoveUndoAvailable 定义 + 两处引用）",
      vm_kt.count("sideMoveUndoAvailable(") == 3)
check("F6 搬移凭据有 3 个**无条件**作废出口（撤回stale / 撤回normal / 一键重置）；"
      "恢复存档那一处改成带闸恢复（R25），由 verify_session.py 钉住",
      vm_kt.count("sideMoveCredential = Credential()") == 3
      and vm_kt.count("sideMoveCredential = creds[CredentialKind.SIDE_MOVE] ?: Credential()") == 1)
check("F7 Advisor：搬移 490 排在互换 480 之前（判据更硬先执行）",
      "priority = 490" in adv_kt and "priority = 480" in adv_kt
      and adv_kt.index("priority = 490") < adv_kt.index("priority = 480"))
check("F8 Advisor：撤回搬移 455 排在撤回互换 460 之后（都是退路，不抢第一）",
      adv_kt.index("priority = 455") > adv_kt.index("priority = 460"))
check("F9 两个动作都有文案（枚举必须穷举）",
      'AdviceAction.SIDE_MOVE -> "搬过去"' in hub_kt
      and 'AdviceAction.UNDO_SIDE_MOVE -> "撤回搬移"' in hub_kt)
check("F10 对比面板给了入口（onSideMove），且排在「解除」之前",
      "onSideMove: (() -> Unit)? = null" in dlg_kt
      and dlg_kt.index("val move = onSideMove") < dlg_kt.index("TextButton(onClick = onUnlink)"))
check("F11 状态条有搬移的露出与按钮",
      "val moves = ui.sideMoves.size" in app_kt and "vm.applySideMoves()" in app_kt)
check("F12 对比面板的调用点把建议传进去了",
      "onSideMove = pendingMove?.let { mv ->" in app_kt
      and "vm.applySideMove(mv)" in app_kt)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：只有一对配错、真对象还空着时能搬过去；")
print("六道闸各自承重；被腾出来的那张进解除集合（不触发链式重排）；")
print("撤回只删值仍匹配的条目，换现场整批作废且不动任何配对。")
