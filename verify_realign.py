"""第二十二轮验证：「整体平移」（findTimeRealign）。

前几轮修的错配都是**逐对**看得出来的：
  R18 互换 —— 两对都配错，且能互相接手（两对同时变好）；
  R19 漏配 —— 两边都没配上，靠尺寸+体积认出来；
  R21 搬移 —— 只有一对配错，而它真正的对象还空着。

本轮补第四种：**均匀平移**。两边各自从 0001 编号时，只要一栏多（或少）一张，
真实对应就整体挪一位，于是**每一张的真对象都配着别人**：

| 左 | 引擎按序号配给的右 | 时间差 | 真实对应 |
| --- | --- | --- | --- |
| a（200s） | p（100s） | 100s ✗ | a ↔ q（200s） |
| b（300s） | q（200s） | 100s ✗ | b ↔ r（300s） |
| c（400s） | r（300s） | 100s ✗ | c ↔ ？（池内没有对象）|
| —— | s（900s）未配对 | | s 是无关的那张 |

`findTimeSwaps` 救不了：把相邻两对互换，只会让一对变好、另一对更差（差值从
"差一位"变成"差两位"），"两对同时变好"这道闸直接挡掉 —— 互换能修的是**局部颠倒**，
不是**整体平移**。`findSideMoves` 也救不了：它要求目标**当前未配对**，
而平移里每张图的真对象此刻都配着别人，一道候选都找不到。

所以本轮的做法不是再找一条"单对判据"，而是**把整批交给同一套匹配算法**：
取冲突池（冲突对的两端）跑 `matchByTime`（引擎第 0.5 步的同一个实现），
得到的新配对**每一对都在容差内**，匹配不上的明确回到未配对。

## 为什么这不是 R18 拒绝过的「贪心链」

贪心链 = 一步一步挪、**中间状态没人验证**。这里是**一次性、整体地**给出最终分配，
并且逐条验证最终状态（五道闸），不存在"改到一半"的状态：
要么整批成立，要么一条建议都不给。

## 五道闸（缺一不可）

  1. 至少 2 对冲突（1 对交给 findSideMoves，它的证据更硬：尺寸体积全同）；
  2. 匹配结果至少 2 对**确实换了对象**（否则这条建议没有意义）；
  3. 每一对改动都**严格变好**（新差值 < 旧差值）—— 不许为修一串而弄坏某一对；
  4. 被腾出来的项在池子里**确实找不到容差内的对象**（否则是匹配不完整，宁可不动）；
  5. 池子外的文件一概不碰（池子 = 冲突对的两端，构建时就固定了）。

跑法：python verify_realign.py
"""

import re
from pathlib import Path

LEFT, RIGHT = "LEFT", "RIGHT"
TOL = 2000  # EXIF_TOLERANCE_MS
EXIF, CONTENT = "EXIF", "CONTENT"

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

    @property
    def display_name(self):
        """对应 Kotlin 的 ImageItem.displayName —— 面板报给用户的是它。"""
        return self.name


# ------------------------------------- Kotlin 复刻：collectConflicts（唯一的冲突判定）
# 返回 [(左 key, 右 key, 差值)]，按差值降序 → 左 key → 右 key（**全序**，确定性）。


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
        if r is None or r in (EXIF, CONTENT):
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


# ------------------------------------------- Kotlin 复刻：matchByTime
# 「全局最近未占用」：右栏按时间排序，左栏每个项在插入点向两侧交替走，
# 拿到的第一个未占用项就是全局最近的未占用项。
#
# 排序键取**全序**（takenAt, key）：连拍/同一秒的两张若只按时间排，
# 谁在前就取决于输入列表顺序 —— "同一批文件、换个扫描顺序"会算出不同配对。
# 补上 key 作次级键，结果才真正与列表顺序无关（与 collectConflicts 同一个理由）。


def match_by_time(left, right, tol=TOL, taken=()):
    timed = sorted((it for it in right if it.taken_at > 0),
                   key=lambda it: (it.taken_at, it.key))
    left_by_time = sorted((it for it in left if it.taken_at > 0),
                          key=lambda it: (it.taken_at, it.key))
    used = set(taken)
    out = []
    for l in left_by_time:
        if l.key in used:
            continue
        # 二分：第一个 takenAt >= l.takenAt 的下标
        lo, hi = 0, len(timed)
        while lo < hi:
            mid = (lo + hi) // 2
            if timed[mid].taken_at < l.taken_at:
                lo = mid + 1
            else:
                hi = mid
        down, up = lo - 1, lo
        best, best_diff = None, None
        while down >= 0 or up < len(timed):
            d_diff = l.taken_at - timed[down].taken_at if down >= 0 else 0
            u_diff = timed[up].taken_at - l.taken_at if up < len(timed) else 0
            if down < 0:
                take_down = False
            elif up >= len(timed):
                take_down = True
            else:
                take_down = d_diff <= u_diff
            diff = d_diff if take_down else u_diff
            # 本轮取的是「更近的那一侧」，所以：
            # 手上已有候选且更近 → 对面只会更远，停；
            # 还没候选但已超出误差 → 对面同样超误差，停。
            if best is not None and best_diff is not None and diff >= best_diff:
                break
            if best is None and diff > tol:
                break
            r = timed[down] if take_down else timed[up]
            down, up = (down - 1, up) if take_down else (down, up + 1)
            if r.key not in used and (best_diff is None or diff < best_diff):
                best_diff, best = diff, r
        if best is None or best_diff is None or best_diff > tol:
            continue
        used.add(l.key)
        used.add(best.key)
        out.append((l.key, best.key))
    return out


# ------------------------------------------- Kotlin 复刻：findTimeRealign


class Plan:
    __slots__ = ("pairs", "freed_left", "freed_right", "before_conflicts", "keys")

    def __init__(self, pairs, freed_left, freed_right, before_conflicts):
        self.pairs = pairs
        self.freed_left = freed_left
        self.freed_right = freed_right
        self.before_conflicts = before_conflicts
        self.keys = [k for p in pairs for k in p] + freed_left + freed_right

    def __eq__(self, other):
        return isinstance(other, Plan) and (
            self.pairs == other.pairs
            and self.freed_left == other.freed_left
            and self.freed_right == other.freed_right
            and self.before_conflicts == other.before_conflicts
        )

    def __repr__(self):
        return (f"Plan(pairs={self.pairs}, freedL={self.freed_left}, "
                f"freedR={self.freed_right}, before={self.before_conflicts})")


def find_time_realign(partner, reason, by_key, tol=TOL):
    # 闸 1：一对冲突交给 findSideMoves（判据更硬），这里只处理成批的
    conflicts = collect_conflicts(partner, reason, by_key, tol)
    if len(conflicts) < 2:
        return None

    # 池子 = 冲突对的两端。**只在这里面动**，池子外一个文件都不碰（闸 5）
    # 注意：Python 的 set.add() 返回 None（Kotlin 的 HashSet.add 返回 Boolean），
    # 所以先判 `not in` 再加 —— 直接拿 add() 当条件会永远判否（池子恒为空）。
    pool_left, pool_right, seen = [], [], set()
    for l_key, r_key, _ in conflicts:
        l, r = by_key.get(l_key), by_key.get(r_key)
        if l is None or r is None:
            continue
        if f"L{l_key}" not in seen:
            seen.add(f"L{l_key}")
            pool_left.append(l)
        if f"R{r_key}" not in seen:
            seen.add(f"R{r_key}")
            pool_right.append(r)
    if len(pool_left) < 2 or len(pool_right) < 2:
        return None

    # 池子里重新配 —— 用引擎第 0.5 步的同一套匹配（不在池外找对象）
    matched = match_by_time(pool_left, pool_right, tol)
    if len(matched) < 2:
        return None

    pairs, moved_left, moved_right, changed = [], set(), set(), 0
    for l_key, r_key in matched:
        l, r = by_key.get(l_key), by_key.get(r_key)
        if l is None or r is None:
            return None
        before = partner.get(l_key)
        if before is None:
            return None
        before_item = by_key.get(before)
        if before_item is None:
            return None
        after_diff = abs(l.taken_at - r.taken_at)
        # 闸 3：严格变好。相等就说明这条改动没有收益，不该进方案
        if after_diff >= abs(l.taken_at - before_item.taken_at):
            continue
        # 闸 2 的口径：只有真的换了对象才算数
        if before != r_key:
            changed += 1
        pairs.append((l_key, r_key))
        moved_left.add(l_key)
        moved_right.add(r_key)
    if changed < 2 or len(pairs) < 2:
        return None

    freed_left = [it.key for it in pool_left if it.key not in moved_left]
    freed_right = [it.key for it in pool_right if it.key not in moved_right]
    # 闸 4：被腾出来的必须真的没有对象 —— 池子里还有容差内的候选却说"没配上"，
    # 说明匹配没跑完（比如并发/数据不一致），这种半成品不能拿去改用户的文件
    for k in freed_left:
        it = by_key.get(k)
        if it is None:
            return None
        if any((r.key not in moved_right) and abs(r.taken_at - it.taken_at) <= tol
               for r in pool_right):
            return None
    for k in freed_right:
        it = by_key.get(k)
        if it is None:
            return None
        if any((l.key not in moved_left) and abs(l.taken_at - it.taken_at) <= tol
               for l in pool_left):
            return None

    return Plan(sorted(pairs), sorted(freed_left), sorted(freed_right), len(conflicts))


# ---------------------------------------------------------------- 场景

# 均匀平移：右栏时间整体比左栏少一格（等于右栏多了一张）。
# 引擎按序号配，于是每一对都差 100s；s 是无关的一张（离得很远，池外）。
_shift_L = [
    Item("La", "a.jpg", LEFT, taken_at=200_000),
    Item("Lb", "b.jpg", LEFT, taken_at=300_000),
    Item("Lc", "c.jpg", LEFT, taken_at=400_000),
]
_shift_R = [
    Item("Rp", "p.jpg", RIGHT, taken_at=100_000),
    Item("Rq", "q.jpg", RIGHT, taken_at=200_000),
    Item("Rr", "r.jpg", RIGHT, taken_at=300_000),
    Item("Rs", "s.jpg", RIGHT, taken_at=900_000),
]
_by_shift = {it.key: it for it in _shift_L + _shift_R}
_partner_shift = {"La": "Rp", "Rp": "La", "Lb": "Rq", "Rq": "Lb",
                  "Lc": "Rr", "Rr": "Lc"}
_reason_shift = {"La": "SEQ", "Rp": "SEQ", "Lb": "SEQ", "Rq": "SEQ",
                 "Lc": "SEQ", "Rr": "SEQ"}


def swap_can_improve(partner, by_key, tol=TOL):
    """R18 的判据：存在 2-交换让**两对同时**严格变好。"""
    conf = collect_conflicts(partner, _reason_shift, by_key, tol)
    for i in range(len(conf)):
        for j in range(i + 1, len(conf)):
            l1, r1, d1 = conf[i]
            l2, r2, d2 = conf[j]
            a = by_key[l1]
            b = by_key[r1]
            c = by_key[l2]
            d = by_key[r2]
            n1 = abs(a.taken_at - d.taken_at)  # l1 ↔ r2
            n2 = abs(c.taken_at - b.taken_at)  # l2 ↔ r1
            if n1 < d1 and n2 < d2:
                return True
    return False


def side_move_candidates(partner, by_key, tol=TOL):
    """R21 的判据：冲突里的某张能搬到一张**当前未配对**、且尺寸体积全同的候选上。"""
    conf = collect_conflicts(partner, _reason_shift, by_key, tol)
    unpaired = [it for it in by_key.values() if it.key not in partner]
    hits = 0
    for l_key, r_key, _ in conf:
        for move_key, from_key in ((l_key, r_key), (r_key, l_key)):
            m = by_key[move_key]
            for cand in unpaired:
                if cand.side == m.side:
                    continue
                if (cand.width, cand.height, cand.size) != (m.width, m.height, m.size):
                    continue
                if abs(cand.taken_at - m.taken_at) <= tol:
                    hits += 1
    return hits


print("=" * 64)
print("A. 均匀平移：逐对判据全失效，整批重配（3 对 → 修好 2 对）")
print("=" * 64)

check("A1 三对都在冲突里（每对都差 100s，> 2s 容差）",
      len(collect_conflicts(_partner_shift, _reason_shift, _by_shift)) == 3)

check("A2 互换救不了（没有 2-交换能让两对同时变好）——局部颠倒 ≠ 整体平移",
      not swap_can_improve(_partner_shift, _by_shift))

check("A3 搬移也救不了（每张图的真对象此刻都配着别人，没有空着的候选）",
      side_move_candidates(_partner_shift, _by_shift) == 0)

plan_a = find_time_realign(_partner_shift, _reason_shift, _by_shift)
check("A4 整体重配给出方案", plan_a is not None)

if plan_a is not None:
    check("A5 新配对是 a↔q、b↔r（按拍摄时间对齐）",
          plan_a.pairs == [("La", "Rq"), ("Lb", "Rr")])
    check("A6 对不上的 c 与 p 明确回到未配对",
          plan_a.freed_left == ["Lc"] and plan_a.freed_right == ["Rp"])
    check("A7 修之前有 3 对在冲突里", plan_a.before_conflicts == 3)
    check("A8 方案里每一对的时间差都 ≤ 容差",
          all(abs(_by_shift[l].taken_at - _by_shift[r].taken_at) <= TOL
              for l, r in plan_a.pairs))
    # 结果正确性：新配对与"真实对应"一致，且没有把谁的差搞更大
    check("A9 每一对都严格变好（新差值 < 旧差值）",
          all(abs(_by_shift[l].taken_at - _by_shift[r].taken_at)
              < abs(_by_shift[l].taken_at - _by_shift[_partner_shift[l]].taken_at)
              for l, r in plan_a.pairs))
else:
    for lbl in ("A5 新配对是 a↔q、b↔r（按拍摄时间对齐）",
                "A6 对不上的 c 与 p 明确回到未配对",
                "A7 修之前有 3 对在冲突里",
                "A8 方案里每一对的时间差都 ≤ 容差",
                "A9 每一对都严格变好（新差值 < 旧差值）"):
        check(lbl, False)


# ================================================================ B. 每道闸各自承重

print()
print("=" * 64)
print("B. 闸 1：只有一对冲突 → 不出方案（交给单侧搬移，它的证据更硬）")
print("=" * 64)

_one_L = [Item("La", "a.jpg", LEFT, taken_at=200_000)]
_one_R = [Item("Rp", "p.jpg", RIGHT, taken_at=100_000, size=1)]
_by_one = {it.key: it for it in _one_L + _one_R}
_partner_one = {"La": "Rp", "Rp": "La"}
_reason_one = {"La": "SEQ", "Rp": "SEQ"}

check("B1 冲突恰好一对", len(collect_conflicts(_partner_one, _reason_one, _by_one)) == 1)
check("B2 闸 1 挡住：一对不出整体方案",
      find_time_realign(_partner_one, _reason_one, _by_one) is None)


print()
print("=" * 64)
print("C. 闸 2/3：池里对不上时明说修不了（不许硬凑一对）")
print("=" * 64)

# 两对都错，但右栏两张离左栏两张都很远 —— 池里没有任何容差内的组合。
_far_L = [Item("La", "a.jpg", LEFT, taken_at=200_000),
          Item("Lb", "b.jpg", LEFT, taken_at=300_000)]
_far_R = [Item("Rp", "p.jpg", RIGHT, taken_at=900_000),
          Item("Rq", "q.jpg", RIGHT, taken_at=1_000_000)]
_by_far = {it.key: it for it in _far_L + _far_R}
_partner_far = {"La": "Rp", "Rp": "La", "Lb": "Rq", "Rq": "Lb"}
_reason_far = {"La": "SEQ", "Rp": "SEQ", "Lb": "SEQ", "Rq": "SEQ"}

check("C1 两个冲突",
      len(collect_conflicts(_partner_far, _reason_far, _by_far)) == 2)
check("C2 池里匹配不到 2 对 → null（宁可不动，也不硬凑）",
      find_time_realign(_partner_far, _reason_far, _by_far) is None)

# 结构性说明：matchByTime 只在容差内出配对，而冲突的定义是"超容差"，
# 所以任何被匹配上的对**必然换了对象** —— 闸 3（严格变好）与闸 2（真的换了）
# 在实现上等价（都被 pairs.size < 2 兜住）。这条不变量对所有场景都成立。
_invariants_hold = True
for _p, _rs, _bk in ((_partner_shift, _reason_shift, _by_shift),
                     (_partner_one, _reason_one, _by_one),
                     (_partner_far, _reason_far, _by_far)):
    _pl = find_time_realign(_p, _rs, _bk)
    if _pl is None:
        continue
    for _l, _r in _pl.pairs:
        _ii = _bk[_l]
        if _r == _p.get(_l):
            _invariants_hold = False
        if abs(_ii.taken_at - _bk[_r].taken_at) > TOL:
            _invariants_hold = False
check("C3 不变量：方案里每一对都在容差内，且都不是原来的对象", _invariants_hold)


print()
print("=" * 64)
print("D. 闸 4：被腾出的项在池内确实无对象（方案必须是「配完了」的）")
print("=" * 64)


def gate4_ok(partner, reason, by_key, tol=TOL):
    pl = find_time_realign(partner, reason, by_key, tol)
    if pl is None:
        return True
    moved_left = {l for l, _ in pl.pairs}
    moved_right = {r for _, r in pl.pairs}
    pool_l = {by_key[l] for l, _, _ in collect_conflicts(partner, reason, by_key, tol)}
    pool_r = {by_key[r] for _, r, _ in collect_conflicts(partner, reason, by_key, tol)}
    for k in pl.freed_left:
        it = by_key[k]
        if any((r.key not in moved_right) and abs(r.taken_at - it.taken_at) <= tol
               for r in pool_r):
            return False
    for k in pl.freed_right:
        it = by_key[k]
        if any((l.key not in moved_left) and abs(l.taken_at - it.taken_at) <= tol
               for l in pool_l):
            return False
    return True


check("D1 均匀平移场景：被腾出的 c / p 在池内确实找不到对象",
      gate4_ok(_partner_shift, _reason_shift, _by_shift))
check("D2 两个对不上的场景同样满足（不成立就返回 null，不会给半成品）",
      gate4_ok(_partner_far, _reason_far, _by_far))


print()
print("=" * 64)
print("E. 闸 5：池子外的文件一概不碰（池子 = 冲突对的两端）")
print("=" * 64)

check("E1 未配对、且在容差外的 s 不在方案里（它跟平移无关）",
      plan_a is not None and "Rs" not in plan_a.keys)

# 池外还放一张**已配对但没冲突**的（d↔t，时间完全相同）—— 它也不该被碰。
_L5 = _shift_L + [Item("Ld", "d.jpg", LEFT, taken_at=700_000)]
_R5 = _shift_R + [Item("Rt", "t.jpg", RIGHT, taken_at=700_000)]
_by5 = {it.key: it for it in _L5 + _R5}
_partner5 = dict(_partner_shift)
_partner5.update({"Ld": "Rt", "Rt": "Ld"})
_reason5 = dict(_reason_shift)
_reason5.update({"Ld": "SEQ", "Rt": "SEQ"})

plan_e = find_time_realign(_partner5, _reason5, _by5)
check("E2 已配好的一对（d↔t，差 0）不进池子，方案与原来一致",
      plan_e is not None and plan_e == plan_a)
check("E3 d / t 都没出现在方案的任何位置",
      plan_e is not None and "Ld" not in plan_e.keys and "Rt" not in plan_e.keys)


print()
print("=" * 64)
print("F. 确定性：与输入列表顺序无关（含 takenAt 相同的并列）")
print("=" * 64)

# 连拍：a 与 a2 时间相同、b 与 b2 时间相同；右栏也各有两张同时间的对应。
# 只按 takenAt 排序时（稳定排序），谁在前取决于输入顺序 → 会算出不同的配对。
_tie_L = [Item("La", "a.jpg", LEFT, taken_at=200_000),
          Item("La2", "a2.jpg", LEFT, taken_at=200_000),
          Item("Lb", "b.jpg", LEFT, taken_at=300_000),
          Item("Lb2", "b2.jpg", LEFT, taken_at=300_000)]
_tie_R = [Item("Rp", "p.jpg", RIGHT, taken_at=100_000),
          Item("Rq", "q.jpg", RIGHT, taken_at=100_000),
          Item("Rr", "r.jpg", RIGHT, taken_at=200_000),
          Item("Rs", "s.jpg", RIGHT, taken_at=200_000)]
_by_tie = {it.key: it for it in _tie_L + _tie_R}
_partner_tie = {"La": "Rp", "Rp": "La", "La2": "Rq", "Rq": "La2",
                "Lb": "Rr", "Rr": "Lb", "Lb2": "Rs", "Rs": "Lb2"}
_reason_tie = {k: "SEQ" for k in _partner_tie}

_tie_conf = collect_conflicts(_partner_tie, _reason_tie, _by_tie)
check("F1 并列时间下四对都在冲突里", len(_tie_conf) == 4)

_plan_tie_1 = find_time_realign(_partner_tie, _reason_tie, _by_tie)
_plan_tie_2 = find_time_realign(_partner_tie, _reason_tie, dict(reversed(list(_by_tie.items()))))
check("F2 并列时间下也能给出方案", _plan_tie_1 is not None)
check("F3 打乱字典顺序后结果完全相同（排序键是全序）",
      _plan_tie_1 == _plan_tie_2)

# 端到端：matchByTime 直接打乱列表顺序，结果必须一样
m1 = match_by_time(_tie_L, _tie_R)
m2 = match_by_time(list(reversed(_tie_L)), list(reversed(_tie_R)))
check("F4 matchByTime 本身与列表顺序无关（含并列时间）", m1 == m2)

m3 = match_by_time(_shift_L, _shift_R)
m4 = match_by_time(list(reversed(_shift_L)), list(reversed(_shift_R)))
check("F5 matchByTime 与列表顺序无关（时间各不相同时）", m3 == m4)


print()
print("=" * 64)
print("G. 撤回语义（Credential：pairs + unlinked + session 一体）")
print("=" * 64)


def undo_credential(manual_pairs, unlinked, creed, session_key):
    """复刻 MainViewModel 的撤回：只删**值仍匹配**的条目，换现场整批作废。"""
    if not creed["pairs"]:
        return manual_pairs, unlinked, creed, "noop"
    if creed["session"] != session_key:
        return manual_pairs, unlinked, {"pairs": {}, "unlinked": set(), "session": ""}, "stale"
    kept = {k: v for k, v in manual_pairs.items() if creed["pairs"].get(k) != v}
    return (kept, unlinked - creed["unlinked"],
            {"pairs": {}, "unlinked": set(), "session": ""}, "undone")


_mp = {"La": "Rq", "Rq": "La", "Lb": "Rr", "Rr": "Lb"}
_unl = {"Lc", "Rp"}
_creed = {"pairs": dict(_mp), "unlinked": {"Lc", "Rp"}, "session": "dirA"}

kept, unl2, c2, how = undo_credential(_mp, set(_unl), _creed, "dirA")
check("G1 同一现场下撤回真的动手了", how == "undone")
check("G2 撤回后 manualPairs 清空（这一批全是它写的）", kept == {})
check("G3 被腾出来的 c / p 从解除集合里拿回来", unl2 == set())
check("G4 撤回后凭据被清（不再挂着一条假的'可撤回'）", c2["pairs"] == {})

# 用户之后又手动改过其中一对：那条不能被无脑删掉
_mp2 = dict(_mp)
_mp2["La"] = "Rr"
_mp2["Rr"] = "La"
kept2, _, _, how2 = undo_credential(_mp2, set(_unl), _creed, "dirA")
check("G5 只删值仍匹配的条目（用户改过的 La↔Rr 保留）",
      how2 == "undone" and kept2 == {"La": "Rr", "Rr": "La"})

kept3, unl3, c3, how3 = undo_credential(_mp, set(_unl), _creed, "dirB")
check("G6 换现场 → 凭据作废", how3 == "stale")
check("G7 作废时不动任何配对（同名文件的 pairKey 撞车是常态）",
      kept3 == _mp and unl3 == set(_unl))
check("G8 作废后凭据被清", c3["pairs"] == {})

kept4, _, _, how4 = undo_credential(_mp, set(_unl),
                                    {"pairs": {}, "unlinked": set(), "session": ""}, "dirA")
check("G9 没有凭据时 → noop（不误删、不误清）", how4 == "noop" and kept4 == _mp)


# ================================================================ H. 接线结构

print()
print("=" * 64)
print("H. 接线结构（行为测试看不见的地方）")
print("=" * 64)

SRC = Path(__file__).resolve().parent / "app/src/main/java/com/yuanbao/pairrename"
pair_kt = (SRC / "util/Pairing.kt").read_text(encoding="utf-8")
adv_kt = (SRC / "util/Advisor.kt").read_text(encoding="utf-8")
vm_kt = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")
app_kt = (SRC / "ui/PairRenameApp.kt").read_text(encoding="utf-8")
hub_kt = (SRC / "ui/ToolsHub.kt").read_text(encoding="utf-8")
str_kt = (SRC / ".." / ".." / ".." / ".." / "res/values/strings.xml").resolve()
strings_xml = str_kt.read_text(encoding="utf-8")

check("H1 matchByTime 只有一份实现，且恰好两个消费者（第 0.5 步 + 整体重配）",
      "internal fun matchByTime(" in pair_kt and pair_kt.count("matchByTime(") == 3)

check("H2 matchByTime 的排序键是**全序**（takenAt + key）",
      pair_kt.count("sortedWith(compareBy({ it.takenAt }, { it.key }))") == 2)

check("H3 findTimeRealign / TimeRealign 都被定义",
      "fun findTimeRealign(" in pair_kt and "data class TimeRealign(" in pair_kt)

check("H4 引擎第 0.5 步直接调用共用的 matchByTime（不再自己写一遍）",
      "matchByTime(left, right, exifToleranceMs, partner.keys)" in pair_kt)

check("H5 整体重配对冲突池跑同一套匹配",
      "val matched = matchByTime(poolLeft, poolRight, toleranceMs)" in pair_kt)

check("H6 UiState 带 timeRealign / realignCredential",
      "val timeRealign: TimeRealign? = null" in vm_kt
      and "val realignCredential: Credential = Credential()" in vm_kt)

check("H7 三种凭据共用一个 Credential 类型（不再是三份字段各自抄一遍）",
      "data class Credential(" in vm_kt
      and "val swapCredential: Credential = Credential()" in vm_kt
      and "val sideMoveCredential: Credential = Credential()" in vm_kt
      and "val realignCredential: Credential = Credential()" in vm_kt)

check("H8 「撤得动吗」只有一个实现 + 三个引用（定义 1 + 调用 3）",
      vm_kt.count("credentialUsable(") == 4)

check("H9 三个 xxxUndoAvailable 都委托同一个口径（不再自己判一遍）",
      vm_kt.count("credentialUsable(s.swapCredential.present") == 1
      and vm_kt.count("credentialUsable(s.sideMoveCredential.present") == 1
      and vm_kt.count("credentialUsable(s.realignCredential.present") == 1)

check("H10 三种凭据各有 3 个**无条件**作废出口（撤回stale / 撤回normal / 一键重置），"
      "恢复存档那一处改成带闸恢复（R25）",
      vm_kt.count("swapCredential = Credential()") == 3
      and vm_kt.count("sideMoveCredential = Credential()") == 3
      and vm_kt.count("realignCredential = Credential()") == 3
      and vm_kt.count("swapCredential = creds[CredentialKind.SWAP] ?: Credential()") == 1
      and vm_kt.count("sideMoveCredential = creds[CredentialKind.SIDE_MOVE] ?: Credential()") == 1
      and vm_kt.count("realignCredential = creds[CredentialKind.REALIGN] ?: Credential()") == 1)

check("H11 签发时都带现场戳（session = s.sessionKey，三处）",
      vm_kt.count("session = s.sessionKey") == 3)

check("H12 Advisor：整体重配 495 排在搬移 490 / 互换 480 之前（面最大的先做）",
      "priority = 495" in adv_kt and "priority = 490" in adv_kt
      and adv_kt.index("priority = 495") < adv_kt.index("priority = 490")
      and adv_kt.index("priority = 490") < adv_kt.index("priority = 480"))

_prios = [int(m) for m in re.findall(r"priority = (\d+)", adv_kt)]

check("H13 Advisor 按 priority 降序出建议（追加顺序不决定展示顺序）",
      "return out.sortedByDescending { it.priority }" in adv_kt)

check("H14 撤回是退路，都排在修法之后（修法最低 470 > 退路最高 460）",
      min(p for p in _prios if p >= 470) > max(p for p in _prios if p < 470))

check("H15 三个撤回的相对次序：互换 460 > 搬移 455 > 重配 453（最后做的那批最该先撤）",
      "priority = 460" in adv_kt and "priority = 455" in adv_kt and "priority = 453" in adv_kt
      and 460 > 455 > 453)

check("H16 两个动作都有文案（枚举必须穷举），且与状态条/面板同一个词",
      'AdviceAction.REALIGN_TIME -> "整批重配"' in hub_kt
      and 'AdviceAction.UNDO_REALIGN -> "撤回整批重配"' in hub_kt)

check("H17 runAdvice：重配入口走**预览**（与另外两个入口同一条路），撤回直连",
      "AdviceAction.REALIGN_TIME -> previewRealign()" in vm_kt
      and "AdviceAction.UNDO_REALIGN -> undoTimeRealign()" in vm_kt)

check("H18 状态条露出整批重配的规模与入口（入口是预览，不是直写）",
      "val realign = ui.timeRealign?.pairs?.size ?: 0" in app_kt
      and "vm.previewRealign()" in app_kt)

check("H19 三条文案齐全（应用 / 撤回 / 作废）",
      "msg_realign_applied" in strings_xml
      and "msg_realign_undone" in strings_xml
      and "msg_realign_stale" in strings_xml)

check("H20 用户可见文案与代码里都不再出现「整体重配」这个第二套叫法",
      "整体重配" not in strings_xml
      and "整体重配" not in adv_kt
      and "整体重配" not in app_kt
      and "整体重配" not in hub_kt
      and "整体重配" not in vm_kt)


# ================================================================ I. 对比面板里的出路与去向
# R22 只把整批重配做出来了，入口在状态条与工具页 —— 但用户点「去核对」落到的是
# **对比面板**，而那里的冲突警告只写着"请核对后再决定"，一个字都没提这条出路。
# 面板不说，用户就得退出去别处找。这一组钉住：出路与**去向**都摆在他眼前。

print()
print("=" * 64)
print("I. 对比面板：把「整批重配」的出路与去向说出来")
print("=" * 64)

dlg_kt = (SRC / "ui/dialogs/CompareDialog.kt").read_text(encoding="utf-8")

# ---- 行为：反查表的语义（复刻 recomputeMatch 里建表的那几行）


def build_realign_index(plan, by_key):
    """复刻：整批重配的去向表（key → 新对方文件名）+ 会被腾出的 key。"""
    target, freed = {}, set()
    if plan is None:
        return target, freed
    for l_key, r_key in plan.pairs:
        l, r = by_key.get(l_key), by_key.get(r_key)
        if l is not None and r is not None:
            target[l_key] = r.display_name
            target[r_key] = l.display_name
    freed |= set(plan.freed_left)
    freed |= set(plan.freed_right)
    return target, freed


_idx_target, _idx_freed = build_realign_index(plan_a, _by_shift)

check("I1 去向表双向都建：a → q.jpg、q → a.jpg",
      _idx_target.get("La") == "q.jpg" and _idx_target.get("Rq") == "a.jpg")
check("I2 会被腾出的两张都在表里（c / p），且它们没有「去向」",
      _idx_freed == {"Lc", "Rp"}
      and "Lc" not in _idx_target and "Rp" not in _idx_target)
check("I3 没参与重配的文件不在去向表里（不打扰）",
      "Rs" not in _idx_target and "Rs" not in _idx_freed)

# 面板对"这一对"要说清的三件事，逐一还原成它读表的方式
_panel_key = "La"                      # 用户打开的是 a 这张卡
_partner_key = _partner_shift["La"]    # 它当前的对面是 p
check("I4 面板能说出「这一对会改成：q.jpg」",
      _idx_target.get(_panel_key) == "q.jpg")
check("I5 面板能说出对面那张「p.jpg 会回到未配对」",
      _panel_key not in _idx_freed and _partner_key in _idx_freed)
check("I6 反过来打开那对被腾出的对象时，说的是自己会回到未配对",
      "Rp" in _idx_freed and "Rp" not in _idx_target)

# 最典型的那种变化是"两件事同时发生"：这一张换到别人那儿去了（有去向）
# ＋ 对面那张空出来（被腾出）。报成二选一，用户按下按钮才发现对面那张不见了。
check("I7 最典型的变化里两件事同时成立（La 有去向、Rp 被腾出）",
      _idx_target.get("La") is not None and "Rp" in _idx_freed)

# 没有方案时不建表 —— 面板据此完全不显示这个块（不是显示一个空块）
check("I8 没有方案 → 两张表都是空的（面板不留空壳）",
      build_realign_index(None, _by_shift) == ({}, set()))

# ---- 结构：接线与次序


check("I9 UiState 带去向表与被腾出表",
      "val realignTargetName: Map<String, String> = emptyMap()" in vm_kt
      and "val realignFreedKeys: Set<String> = emptySet()" in vm_kt)

check("I10 两张表与重算同源（在同一次 update 里写入）",
      vm_kt.count("realignTargetName = realignTargetName") == 1
      and vm_kt.count("realignFreedKeys = realignFreedKeys") == 1
      and vm_kt.index("timeRealign = timeRealign") < vm_kt.index("realignTargetName = realignTargetName"))

check("I11 面板新增四个参数（能修几对 / 去向 / 谁被腾出 / 执行入口）",
      "realignPairs: Int = 0" in dlg_kt
      and "realignToName: String? = null" in dlg_kt
      and "realignFreedName: String? = null" in dlg_kt
      and "onRealign: (() -> Unit)? = null" in dlg_kt)

check("I12 出路横幅用 tertiaryContainer（「有办法」）而不是 errorContainer（「出错了」）",
      "color = MaterialTheme.colorScheme.tertiaryContainer" in dlg_kt
      and dlg_kt.index("if (realignPairs > 0)") > dlg_kt.index("if (conflictDeltaMs > 0)"))

check("I13 横幅把「这一对会改成什么」说出来（整批操作最该回答的就是这个）",
      '"这一对会改成：$to"' in dlg_kt)

check("I14 横幅把「哪张会回到未配对」单独说出来（否则用户以为配对丢了）",
      '"$freed 会回到未配对' in dlg_kt)

check("I15 两条各自独立判断，**不是** else if（两件事是同时发生的）",
      "val freed = realignFreedName" in dlg_kt
      and "} else if (freed != null) {" not in dlg_kt)

check("I16 按钮排在「照拍摄时间换过来」之前（与 Advisor 495 > 490 > 480 同一个次序）",
      dlg_kt.index("val realign = onRealign") < dlg_kt.index("val swap = onSwapTime")
      and dlg_kt.index("val swap = onSwapTime") < dlg_kt.index("val move = onSideMove"))

check("I17 调用点把四样都传进去了，且没有方案时不给入口（不留灰按钮）",
      "realignPairs = realignPairs" in app_kt
      and "realignToName = realignToName" in app_kt
      and "realignFreedName = realignFreedName" in app_kt
      and "onRealign = realignPlan?.let {" in app_kt)

check("I18 被腾出的成员**收全**（0/1/2 张）且按 displayName 报给用户",
      "listOfNotNull(" in app_kt
      and "item.takeIf { it.key in latestUi.realignFreedKeys }" in app_kt
      and "partner?.takeIf { it.key in latestUi.realignFreedKeys }" in app_kt
      and 'joinToString("、") { it.displayName }' in app_kt)

# ================================================================ J. 逐条预览
# 互换 / 搬移错了一眼看得出（最多两对），按下去直接生效没问题；整批重配一次动一串：
# 按下去之后，别处那几对的搭档也一起换了，而用户手里只有"能重配 N 对"这一个数。
# 这个项目里另外三处批量操作（改名方案 / 归档计划 / 顺序对齐）都是先摊开再确认，
# 整批重配是唯一漏掉的。这一组钉住：每一对摊开、只列会变的、三个入口同一条路、
# 且**只有一个写入点**（预览里的确认）。

print()
print("=" * 64)
print("J. 整批重配：逐条摊开再确认")
print("=" * 64)

prev_kt = (SRC / "ui/dialogs/RealignPreviewDialog.kt").read_text(encoding="utf-8")


def preview_rows(plan, by_key, partner):
    """复刻：预览对话框每一行 = (左名, 右名, 原来是谁)。数据只来自 plan.pairs。"""
    rows = []
    for l_key, r_key in plan.pairs:
        left, right = by_key.get(l_key), by_key.get(r_key)
        before = by_key.get(partner.get(l_key))
        rows.append((
            left.display_name if left else l_key,
            right.display_name if right else r_key,
            before.display_name if before else None,
        ))
    return rows


_rows = preview_rows(plan_a, _by_shift, _partner_shift)
check("J1 逐条列出这一批的每一对（2 对 → 2 行，不多不少）", len(_rows) == 2)
check("J2 每行都是「左名 ↔ 右名」的新搭档",
      ("a.jpg", "q.jpg", "p.jpg") in _rows and ("b.jpg", "r.jpg", "q.jpg") in _rows)
check("J3 每行都带「原来是哪张」，且必与新搭档不同（列出来的就是会变的）",
      all(before is not None and before != right for _, right, before in _rows))
check("J4 被腾出的两张不进配对列表（回到未配对 ≠ 换个搭档，走单独一节）",
      "c.jpg" not in [r for _, r, _ in _rows] and "p.jpg" not in [r for _, r, _ in _rows])
check("J5 预览里的每一对就是执行时会写的那些对（同一份 plan.pairs，不另算一遍）",
      [(l, r) for l, r, _ in _rows]
      == [(_by_shift[lk].display_name, _by_shift[rk].display_name) for lk, rk in plan_a.pairs])

# ---- 结构：对话框本身

check("J6 预览对话框存在，且收 plan / byKey / partner 三样",
      "fun RealignPreviewDialog(" in prev_kt
      and "plan: TimeRealign," in prev_kt
      and "byKey: Map<String, ImageItem>," in prev_kt
      and "partner: Map<String, String>," in prev_kt)

check("J7 逐条列的是 plan.pairs；超出上限只报数量（不静默少列）",
      "plan.pairs.take(PREVIEW_MAX).forEach { (lKey, rKey) ->" in prev_kt
      and "if (plan.pairs.size > PREVIEW_MAX)" in prev_kt
      and "R.string.realign_preview_more" in prev_kt)

check("J8 被腾出的左右分开列（回到的是各自那一栏的未配对区）",
      "plan.freedLeft.forEach" in prev_kt and "plan.freedRight.forEach" in prev_kt
      and "R.string.realign_preview_freed_left" in prev_kt
      and "R.string.realign_preview_freed_right" in prev_kt)

check("J9 确认按钮写明会改几对（不是笼统的「确定」）",
      "R.string.realign_preview_confirm, plan.pairs.size" in prev_kt)

check("J10 文案齐全：标题 / 说明 / 对数 / 原名 / 溢出 / 被腾出 / 确认",
      all(f'name="{n}"' in strings_xml for n in (
          "realign_preview_title", "realign_preview_desc", "realign_preview_pairs",
          "realign_preview_before", "realign_preview_more",
          "realign_preview_freed_title", "realign_preview_freed_left",
          "realign_preview_freed_right", "realign_preview_confirm")))

check("J11 说明里承诺了「确认后才生效」「改错了能撤回」（批量操作必须给退路）",
      "确认后才会生效" in strings_xml and "能撤回" in strings_xml)

# ---- 结构：接线

check("J12 vm：预览入口只转发方案（不复算），且没有方案时明说而不是静默",
      "fun previewRealign()" in vm_kt
      and "_events.tryEmit(UiEvent.PreviewRealign(plan))" in vm_kt
      and "data class PreviewRealign(val plan: TimeRealign) : UiEvent" in vm_kt)

check("J13 vm：按下重配却没有方案时**不再静默返回**（按钮没反应最像卡死）",
      'name="msg_realign_none"' in strings_xml
      and vm_kt.count("R.string.msg_realign_none") == 2)

check("J14 app：预览状态 + 事件分支 + 关掉对比面板（不叠两个弹窗）",
      "var realignPreview by remember { mutableStateOf<TimeRealign?>(null) }" in app_kt
      and "is UiEvent.PreviewRealign -> {" in app_kt
      and "realignPreview = event.plan" in app_kt)

check("J15 app：**只有一个写入点** —— app 层 applyTimeRealign 恰好出现 1 次（预览的确认）",
      app_kt.count("applyTimeRealign") == 1
      and "vm.applyTimeRealign()" in app_kt)

check("J16 三个入口全部走预览：状态条 / 对比面板 / 工具页建议",
      app_kt.count("vm.previewRealign()") == 2
      and "AdviceAction.REALIGN_TIME -> previewRealign()" in vm_kt)

check("J17 返回键能收起预览（否则按返回会直接退出应用）",
      "realignPreview != null," in app_kt and "realignPreview = null" in app_kt)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：均匀平移（每对都配着别人）能一次性整批重配；")
print("五道闸各自承重，池外一律不碰；结果与列表顺序无关（含时间并列）；")
print("撤回只删值仍匹配的条目，换现场整批作废且不动任何配对；")
print("对比面板在冲突现场就把出路与去向说清楚（会改成谁 / 哪张会回到未配对）；")
print("三个入口同一个流程：先把每一对摊开（旧→新 + 谁被腾出），确认后才写。")
