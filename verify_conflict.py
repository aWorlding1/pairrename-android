"""第十四轮验证：「时间冲突复核」（findTimeConflicts）。

本轮的主张必须被证明，而不是"看起来对"：

  主张 A（新功能）：靠 NAME / SIMILAR / SEQ / ORDER 配上的对，
      如果两边都有拍摄时间、且相差超过容差，就是**错配**。
      引擎按 CONTENT → EXIF → NAME → SIMILAR → SEQ → ORDER 依次出手，
      后面的规则不会回头校验，所以这批对从来没被拍摄时间校过。

  主张 B（正交性）：CONFLICT 与 SUSPECT 是两件不同的事。
      · NAME 依据（强证据）+ 时间打架  → 进 CONFLICT、**不进** SUSPECT；
      · SEQ 依据（弱证据）+ 时间一致   → 进 SUSPECT、**不进** CONFLICT；
      · SEQ 依据 + 时间打架            → **两边都进**。
      少了这条，本轮就是把 SUSPECT 又抄了一遍，白干。

  主张 C（四条排除闸）：EXIF/CONTENT 依据、任一边无时间、容差内、手工配对，
      一条都不能报 —— 报了就是制造噪音，用户会把复核功能关掉。

  第十七轮补 F 组：卡片上的"差 X 小时"与对比面板的横幅共用 Naming.formatDuration，
      行为逐行验证，并用结构断言钉住"只有一份实现"。

跑法：python verify_conflict.py
"""

import pathlib
import re

CONTENT, EXIF, NAME, SIMILAR, SEQ, ORDER = (
    "CONTENT", "EXIF", "NAME", "SIMILAR", "SEQ", "ORDER",
)

ALL, TODO, DONE, UNPAIRED, SUSPECT, CONFLICT, ONLY_LEFT, ONLY_RIGHT = (
    "ALL", "TODO", "DONE", "UNPAIRED", "SUSPECT", "CONFLICT",
    "ONLY_LEFT", "ONLY_RIGHT",
)

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
    __slots__ = ("key", "name", "side", "taken_at")

    def __init__(self, key, name, side, taken_at=0):
        self.key = key
        self.name = name
        self.side = side
        self.taken_at = taken_at


def is_weak(r):
    """与 Kotlin isWeakReason 同义：只有 ORDER / SEQ 算弱。"""
    return r in (ORDER, SEQ)


def find_time_conflicts(partner, reason, by_key, tol=TOL):
    """逐字复刻 util/Pairing.kt 的 findTimeConflicts。"""
    if not partner:
        return {}
    out = {}
    counted = set()
    for a_key, b_key in partner.items():
        if a_key in counted:
            continue
        counted.add(a_key)
        counted.add(b_key)
        r = reason.get(a_key)
        if r is None:
            continue
        if r in (EXIF, CONTENT):
            continue
        a = by_key.get(a_key)
        b = by_key.get(b_key)
        if a is None or b is None:
            continue
        if a.taken_at <= 0 or b.taken_at <= 0:
            continue
        diff = a.taken_at - b.taken_at
        absd = -diff if diff < 0 else diff
        if absd <= tol:
            continue
        out[a_key] = absd
        out[b_key] = absd
    return out


def apply_filter(items, query, mf, matched, weak_keys, conflict_keys):
    """复刻 util/Filter.kt 相关分支（只看与本轮有关的四种过滤）。"""
    out = []
    q = query.strip().lower()
    for it in items:
        if q and q not in it.name.lower():
            continue
        if mf == ALL:
            pass
        elif mf == SUSPECT and it.key not in weak_keys:
            continue
        elif mf == CONFLICT and it.key not in conflict_keys:
            continue
        elif mf == ONLY_LEFT and not (it.side == "LEFT" and it.key not in matched):
            continue
        elif mf == ONLY_RIGHT and not (it.side == "RIGHT" and it.key not in matched):
            continue
        out.append(it)
    return out


# 与 MainViewModel.cycleMatchFilter 的环同序
CYCLE = [ALL, TODO, DONE, UNPAIRED, SUSPECT, CONFLICT, ONLY_LEFT, ONLY_RIGHT]


def cycle_next(f):
    return CYCLE[(CYCLE.index(f) + 1) % len(CYCLE)]


# ---------------------------------------------------------------- A. 真冲突必报

print("=" * 64)
print("A. 真冲突必报：NAME 依据 + 两边有时间 + 差得远")
print("=" * 64)

# 左边 IMG_0001 拍于 10:00，右边 旅行.jpg 拍于 12:00（相隔 2 小时）。
# 引擎按 NAME 配上了（名字相同？—— 这里模拟"名字骨架对上"），
# 但拍摄时间差 7200000ms，远超容差。
t0 = 1_700_000_000_000
L = [Item("L1", "IMG_0001.jpg", "LEFT", t0)]
R = [Item("R1", "IMG_0001.jpg", "RIGHT", t0 + 7_200_000)]
by_key = {i.key: i for i in L + R}
partner = {"L1": "R1", "R1": "L1"}
reason = {"L1": NAME, "R1": NAME}

cf = find_time_conflicts(partner, reason, by_key)
check("差 2 小时的对被报为冲突", cf != {})
check("冲突差值为 7200000ms", cf.get("L1") == 7_200_000)
check("左右两侧都记（过滤能直接用）", set(cf.keys()) == {"L1", "R1"})
check("两侧差值相同", cf.get("R1") == cf.get("L1"))
check("conflictPairs = keys/2 = 1", len(cf) // 2 == 1)

# 负方向也必须报（右早于左）
R2 = [Item("R1", "IMG_0001.jpg", "RIGHT", t0 - 5_000_000)]
by_key2 = {i.key: i for i in L + R2}
cf2 = find_time_conflicts(partner, reason, by_key2)
check("右比左早 5000s 一样报（取绝对值）", cf2.get("L1") == 5_000_000)

print()
print("=" * 64)
print("B. 四条排除闸（报了就制造噪音）")
print("=" * 64)

# B1. EXIF 依据：EXIF 步本身就要求差 ≤ 容差，不可能冲突
cf_exif = find_time_conflicts(partner, {"L1": EXIF, "R1": EXIF}, by_key)
check("B1 EXIF 依据即使时间差得远也不报（不可能的状态）", cf_exif == {})

# B2. CONTENT 依据：内容相同 = 同一份字节，时间必然相同
cf_content = find_time_conflicts(partner, {"L1": CONTENT, "R1": CONTENT}, by_key)
check("B2 CONTENT 依据不报（真报了是读取环节的另一个 bug）", cf_content == {})

# B3. 任一边没有拍摄时间 → 构不成矛盾
R_notime = [Item("R1", "IMG_0001.jpg", "RIGHT", 0)]
by_key3 = {i.key: i for i in L + R_notime}
check(
    "B3a 右边无拍摄时间不报",
    find_time_conflicts(partner, reason, by_key3) == {},
)
L_notime = [Item("L1", "IMG_0001.jpg", "LEFT", 0)]
by_key4 = {i.key: i for i in L_notime + R}
check(
    "B3b 左边无拍摄时间不报",
    find_time_conflicts(partner, reason, by_key4) == {},
)
check(
    "B3c 两边都无时间不报",
    find_time_conflicts(
        partner, reason, {i.key: i for i in L_notime + R_notime}
    ) == {},
)

# B4. 容差内 → 反而是支持的证据，绝不能报
for delta in (0, 500, 1999, TOL):
    R_tol = [Item("R1", "IMG_0001.jpg", "RIGHT", t0 + delta)]
    got = find_time_conflicts(partner, reason, {i.key: i for i in L + R_tol})
    check(f"B4 容差内（Δ={delta}ms）不报", got == {})

# 刚越过容差 → 必须报（边界另一侧）
R_over = [Item("R1", "IMG_0001.jpg", "RIGHT", t0 + TOL + 1)]
got_over = find_time_conflicts(partner, reason, {i.key: i for i in L + R_over})
check(f"B4 刚越过容差（Δ={TOL + 1}ms）必须报", got_over.get("L1") == TOL + 1)

# B5. 手工配对：applyManualOverrides 会把 reason 抹掉 → 天然排除
manual_reason = {}  # 手工对的 reason 是 null（这里用缺失表达）
check(
    "B5 手工配对不报（用户确认过的，不打扰）",
    find_time_conflicts(partner, manual_reason, by_key) == {},
)

# B6. 只抹掉一边的 reason，另一边的 reason 仍在 —— 会不会漏判？
# 引擎在 applyManualOverrides 里是**两边**都抹，这里验证"只要任一侧还有依据就报"
half = {"L1": NAME}
check(
    "B6 单侧仍有依据时报（更保守，宁可多报）",
    find_time_conflicts(partner, half, by_key) != {},
)

print()
print("=" * 64)
print("C. 与 SUSPECT 正交（本轮的全部价值所在）")
print("=" * 64)

partner_all = {"L1": "R1", "R1": "L1", "L2": "R2", "R2": "L2", "L3": "R3", "R3": "L3"}
reason_all = {"L1": NAME, "R1": NAME, "L2": SEQ, "R2": SEQ, "L3": SEQ, "R3": SEQ}

items = [
    # L1/R1: 强证据 NAME，但时间打架  → CONFLICT 专属
    Item("L1", "a.jpg", "LEFT", t0),
    Item("R1", "a.jpg", "RIGHT", t0 + 3_600_000),
    # L2/R2: 弱证据 SEQ，时间一致     → SUSPECT 专属
    Item("L2", "b.jpg", "LEFT", t0),
    Item("R2", "b_1.jpg", "RIGHT", t0 + 200),
    # L3/R3: 弱证据 SEQ，且时间打架   → 两边都进
    Item("L3", "c.jpg", "LEFT", t0),
    Item("R3", "c_1.jpg", "RIGHT", t0 + 900_000),
]
by_key_all = {i.key: i for i in items}

conf = find_time_conflicts(partner_all, reason_all, by_key_all)
weak = {
    k for k, r in reason_all.items() if k in partner_all and is_weak(r)
}

print(f"  conflictKeys = {sorted(conf)}")
print(f"  weakKeys     = {sorted(weak)}")

check("C1 NAME+打架 进 CONFLICT", "L1" in conf and "R1" in conf)
check("C1 NAME+打架 **不进** SUSPECT（依据本身是强的）", "L1" not in weak and "R1" not in weak)
check("C2 SEQ+一致 进 SUSPECT", "L2" in weak and "R2" in weak)
check("C2 SEQ+一致 **不进** CONFLICT（时间反而是支持的证据）", "L2" not in conf and "R2" not in conf)
check("C3 SEQ+打架 两边都进（两个问题同时存在）", "L3" in conf and "R3" in conf and "L3" in weak and "R3" in weak)

check(
    "C4 两个集合**不相等** —— 如果相等，本轮就是给 SUSPECT 换了个名字",
    conf.keys() != weak,
)
check("C4 CONFLICT 独占 L1/R1，SUSPECT 占不到", ("L1" in conf) and ("L1" not in weak))
check("C4 SUSPECT 独占 L2/R2，CONFLICT 占不到", ("L2" in weak) and ("L2" not in conf))
check("C5 交集正是「又弱又打架」的 L3/R3", (conf.keys() & weak) == {"L3", "R3"})

print()
print("=" * 64)
print("D. 过滤与循环（UI 消费点）")
print("=" * 64)

v_conf = apply_filter(items, "", CONFLICT, set(), weak, conf)
check("D1 CONFLICT 过滤只留冲突 key（4 个：L1/R1/L3/R3）", sorted(i.key for i in v_conf) == ["L1", "L3", "R1", "R3"])
v_susp = apply_filter(items, "", SUSPECT, set(), weak, conf)
check("D2 SUSPECT 过滤只留弱依据（4 个：L2/R2/L3/R3）", sorted(i.key for i in v_susp) == ["L2", "L3", "R2", "R3"])
check("D3 两个过滤结果不同（正交的直接体现）", sorted(i.key for i in v_conf) != sorted(i.key for i in v_susp))
v_all = apply_filter(items, "", ALL, set(), weak, conf)
check("D4 ALL 过滤不受影响（6 个全在）", len(v_all) == 6)
check("D5 CONFLICT 过滤为空集时返回空", apply_filter(items, "", CONFLICT, set(), weak, set()) == [])
check(
    "D6 搜索词与 CONFLICT 叠加（搜 a.jpg 只剩 L1/R1）",
    sorted(i.key for i in apply_filter(items, "a.jpg", CONFLICT, set(), weak, conf)) == ["L1", "R1"],
)

check("D7 cycle: SUSPECT → CONFLICT", cycle_next(SUSPECT) == CONFLICT)
check("D8 cycle: CONFLICT → ONLY_LEFT", cycle_next(CONFLICT) == ONLY_LEFT)
check("D9 cycle: 环完整闭合回 ALL", cycle_next(ONLY_RIGHT) == ALL)
check("D10 cycle: 8 个值不重不漏", sorted(CYCLE) == sorted([ALL, TODO, DONE, UNPAIRED, SUSPECT, CONFLICT, ONLY_LEFT, ONLY_RIGHT]))

print()
print("=" * 64)
print("E. 边界")
print("=" * 64)

check("E1 空 partner → 空", find_time_conflicts({}, {}, {}) == {})
check("E2 partner 指向不存在的 key → 跳过（不崩）", find_time_conflicts({"X": "Y", "Y": "X"}, {"X": NAME}, {}) == {})
# 单向存储（只有 L1→R1，缺 R1→L1）：仍应报，且两侧都记
one_way = find_time_conflicts({"L1": "R1"}, {"L1": NAME}, by_key)
check("E3 单向存储也能报，且补全两侧", set(one_way) == {"L1", "R1"})
# 差恰好为 0 但一边是 0 值（未读）→ 必须靠 taken_at<=0 挡住，不能靠差值
check("E4 一边 takenAt=0 且另一边也是 0 → 不报", find_time_conflicts(partner, reason, {i.key: i for i in [Item("L1", "a", "LEFT", 0), Item("R1", "b", "RIGHT", 0)]}) == {})
# 大量对：只处理一次，不重复
many_partner = {}
many_reason = {}
many_items = []
for n in range(50):
    lk, rk = f"L{n}", f"R{n}"
    many_partner[lk] = rk
    many_partner[rk] = lk
    many_reason[lk] = NAME
    many_reason[rk] = NAME
    many_items.append(Item(lk, f"x{n}.jpg", "LEFT", t0))
    many_items.append(Item(rk, f"x{n}.jpg", "RIGHT", t0 + 10_000))
many_conf = find_time_conflicts(many_partner, many_reason, {i.key: i for i in many_items})
check("E5 50 对全部报出，且正好 100 个 key", len(many_conf) == 100)

# ---------------------------------------------------------------- F. formatDuration
# 第十七轮把 CompareDialog 的私有 fmtDuration 合并进 util/Naming.kt，
# 让「对比面板的时间冲突横幅」和「卡片上的 ·差 X 小时」说同一句话。
#
# 为什么值得单独立组：两处文案不一致是最难查的一类 bug ——
# 面板说「2 小时」、卡片说「2 时 0 分」，用户会以为在看两个不同的数，
# 然后开始怀疑哪个才是真的。所以要同时钉住**行为**和**只有一份实现**。

print()
print("=" * 64)
print("F. formatDuration（本轮新增：两处消费者共用一个说法）")
print("=" * 64)


def format_duration(ms):
    """与 Kotlin Naming.formatDuration 逐行同义。"""
    sec = ms // 1000
    if sec <= 0:
        return "不到 1 秒"
    if sec < 60:
        return f"{sec} 秒"
    minute = sec // 60
    if minute < 60:
        return f"{minute} 分 {sec % 60} 秒"
    hour = minute // 60
    if hour < 24:
        return f"{hour} 小时 {minute % 60} 分"
    day = hour // 24
    return f"{day} 天 {hour % 24} 小时"


fd = format_duration
check("F1 0 ms → 不到 1 秒", fd(0) == "不到 1 秒")
check("F2 负数 → 不到 1 秒（绝不出现 '-1 秒'）", fd(-5000) == "不到 1 秒")
check("F3 999 ms → 不到 1 秒（不足 1 秒就不说 1 秒）", fd(999) == "不到 1 秒")
check("F4 恰好 1000 ms → 1 秒", fd(1000) == "1 秒")
check("F5 59 秒 → 59 秒", fd(59_000) == "59 秒")
check("F6 60 秒 → 1 分 0 秒（进位但秒位不省）", fd(60_000) == "1 分 0 秒")
check("F7 125 秒 → 2 分 5 秒（取模而非整除）", fd(125_000) == "2 分 5 秒")
check("F8 3599 秒 → 59 分 59 秒", fd(3_599_000) == "59 分 59 秒")
check("F9 3600 秒 → 1 小时 0 分", fd(3_600_000) == "1 小时 0 分")
check("F10 1.5 小时 → 1 小时 30 分", fd(5_400_000) == "1 小时 30 分")
check("F11 23 小时 59 分 → 23 小时 59 分（不提前进位到天）", fd(86_340_000) == "23 小时 59 分")
check("F12 整 1 天 → 1 天 0 小时", fd(86_400_000) == "1 天 0 小时")
check("F13 5 天 3 小时", fd(5 * 86_400_000 + 3 * 3_600_000) == "5 天 3 小时")


def parse_back(txt):
    """把文案反解成秒，用来查单调性。"""
    nums = [int(x) for x in re.findall(r"\d+", txt)]
    if "天" in txt:
        return nums[0] * 86400 + (nums[1] if len(nums) > 1 else 0) * 3600
    if "小时" in txt:
        return nums[0] * 3600 + (nums[1] if len(nums) > 1 else 0) * 60
    if "分" in txt:
        return nums[0] * 60 + (nums[1] if len(nums) > 1 else 0)
    if "秒" in txt:
        return nums[0]
    return 0


_samples = [0, 1_000, 59_000, 60_000, 3_600_000, 86_400_000, 30 * 86_400_000]
_vals = [parse_back(fd(ms)) for ms in _samples]
check("F14 反解出的秒数单调不减（差得越多人话也越大）",
      all(_vals[i] <= _vals[i + 1] for i in range(len(_vals) - 1)))
check("F15 同一毫秒永远同一句话（没有隐藏状态）",
      len({fd(5_400_000) for _ in range(50)}) == 1)

# 结构断言：两处消费者必须**共用**同一份实现。
# 只在 CompareDialog 里放一份私有副本，正是本轮要消灭的形态。
_SRC = pathlib.Path(__file__).resolve().parent / "app/src/main/java/com/yuanbao/pairrename"
_naming_src = (_SRC / "util/Naming.kt").read_text(encoding="utf-8")
_card_src = (_SRC / "ui/ImageCard.kt").read_text(encoding="utf-8")
_cmp_src = (_SRC / "ui/dialogs/CompareDialog.kt").read_text(encoding="utf-8")
check("F16 Naming.kt 里定义了 fun formatDuration", "fun formatDuration(" in _naming_src)
check("F17 ImageCard（卡片）调用的是 Naming.formatDuration", "Naming.formatDuration(" in _card_src)
check("F18 CompareDialog（面板）调用的是 Naming.formatDuration", "Naming.formatDuration(" in _cmp_src)
check("F19 CompareDialog 里不再有私有 fmtDuration（重复已消灭）", "fun fmtDuration(" not in _cmp_src)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：时间冲突只报「配上了却和更硬证据打架」的对；")
print("四条排除闸承重；CONFLICT 与 SUSPECT 正交，环完整、过滤一致。")
