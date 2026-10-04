"""第十九轮验证：「靠尺寸 + 体积认出来的漏配」（findSizeMatches）。

时间冲突 / 互换（R14~R18）修的是"配**错**了"。本轮找的是另一件事：
**根本没配上、但证据很硬的**。

引擎按 `CONTENT → EXIF → NAME → SIMILAR → SEQ → ORDER` 出手。
后三步全靠名字与顺序 —— 两边名字不同、又**还没跑流水线**（没读拍摄时间、
没算内容指纹）时，剩下的就只有"按顺序猜"。而列表元数据里一直躺着一条硬证据：
**宽 × 高 × 体积**。JPEG 对像素内容极敏感，内容不同则体积几乎不可能完全一样。

本轮的主张必须被证明，而不是"看起来对"：

  主张 A（能配上）：
      尺寸相同 + 体积完全相同 + 两边各只有这一张 → 几乎可以肯定是同一张。
      证据强度接近内容指纹（引擎第 0 步），却完全不用读文件内容。

  主张 B（两道闸承重）：
      1. 必须「两边各只有这一张」—— 同尺寸同体积有多张就是重复图 / 连拍，
         它们彼此分不出谁配谁，猜一个就是制造新的错配（宁可漏）；
      2. 已算出的内容指纹说了算：两边都有指纹且不同 → 不是同一张，
         哪怕尺寸体积都一模一样（连拍后删掉中间帧再重存，就可能撞成这样）。

  主张 C（不打扰）：
      已配上的、用户解除过的、元数据是 0 的，一律不建议。
      尤其"解除过的又被建议回去" —— 用户会把这个功能整个关掉。

跑法：python verify_sizematch.py
"""

from pathlib import Path

LEFT, RIGHT = "LEFT", "RIGHT"

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


# ------------------------------------------------- Kotlin 复刻：findSizeMatches
# 返回 (left_key, right_key, width, height, size_bytes)


def find_size_matches(partner, left, right, unlinked=None, content_keys=None):
    unlinked = unlinked or set()
    content_keys = content_keys or {}

    def usable(it):
        return (it.key not in partner and it.pair_key not in unlinked
                and it.width > 0 and it.height > 0 and it.size > 0)

    unmatched_left = [it for it in left if usable(it)]
    unmatched_right = [it for it in right if usable(it)]
    if not unmatched_left or not unmatched_right:
        return []

    left_groups, right_groups = {}, {}
    for it in unmatched_left:
        left_groups.setdefault((it.width, it.height, it.size), []).append(it)
    for it in unmatched_right:
        right_groups.setdefault((it.width, it.height, it.size), []).append(it)

    out = []
    for sig, ls in left_groups.items():
        rs = right_groups.get(sig)
        if rs is None or len(ls) != 1 or len(rs) != 1:
            continue
        l, r = ls[0], rs[0]
        lk, rk = content_keys.get(l.key), content_keys.get(r.key)
        if lk is not None and rk is not None and lk != rk:
            continue
        out.append((l.key, r.key, sig[0], sig[1], sig[2]))
    out.sort()
    return out


# -------------------------------------- Kotlin 复刻：linkSizeMatches / applySizeMatch(s)
#
# v5.8.0 起写入逻辑收敛到 linkSizeMatches(matches)，批量入口（状态条 / 工具页）
# 与单张入口（卡片角标）只是喂的列表不同 —— 复刻保持同一个形状：
# apply_size_matches == link(state, 全部建议)；apply_size_match == link(state, [key 对应那条])。


def link_size_matches(state, matches):
    if not matches:
        return 0
    pk = state["pairKey"]
    by_key = state["byKey"]
    pairs = dict(state["manualPairs"])
    linked = 0
    for m in matches:
        l, r = by_key.get(m[0]), by_key.get(m[1])
        if l is None or r is None:
            continue
        if pk[m[0]] == pk[m[1]]:
            continue
        pairs[pk[m[0]]] = pk[m[1]]
        pairs[pk[m[1]]] = pk[m[0]]
        linked += 1
    if linked == 0:
        return 0
    state["manualPairs"] = pairs
    return linked


def apply_size_matches(state):
    return link_size_matches(state, state["sizeMatches"])


def apply_size_match(state, key):
    m = next((m for m in state["sizeMatches"] if m[0] == key or m[1] == key), None)
    if m is None:
        return 0
    return link_size_matches(state, [m])


# --------------------------------------------- Kotlin 复刻：sizeMatchPartner（卡片反查表）


def size_match_partner(matches, by_key):
    out = {}
    for m in matches:
        l, r = by_key.get(m[0]), by_key.get(m[1])
        if l is None or r is None:
            continue
        out[m[0]] = r.name
        out[m[1]] = l.name
    return out


# ================================================================ A. 能配上

print("=" * 64)
print("A. 尺寸 + 体积 + 两边各一张 → 配上")
print("=" * 64)

# 两个目录各有一些"名字对不上"的原图：配对引擎帮不上（没数据），但尺寸 + 体积在
def spec(side, *rows):
    return [Item(k, f"{k}.jpg", side, w, h, s) for (k, w, h, s) in rows]


left_a = spec(LEFT,
    ("L1", 4000, 3000, 2_300_000),
    ("L2", 4000, 3000, 2_410_500),
    ("L3", 1080, 1920, 980_000),
)
right_a = spec(RIGHT,
    ("R1", 4000, 3000, 2_300_000),   # ↔ L1
    ("R2", 4000, 3000, 2_410_500),   # ↔ L2
)

partner_a, unlinked_a, content_a = {}, set(), {}
matches_a = find_size_matches(partner_a, left_a, right_a, unlinked_a, content_a)

# L3（1080×1920）在右栏没有对应 → 不建议；L1/L2 各有**唯一**候选 → 建议
check("A1 找出两对", len(matches_a) == 2)
check("A2 L1 ↔ R1、L2 ↔ R2",
      [(m[0], m[1]) for m in matches_a] == [("L1", "R1"), ("L2", "R2")])
check("A3 顺带带上尺寸和体积供界面展示",
      matches_a[0][2:] == (4000, 3000, 2_300_000))


# ================================================================ B. 两道闸

print()
print("=" * 64)
print("B. 两道闸各自承重")
print("=" * 64)

# 闸 1：同尺寸同体积出现多张 → 谁也不建议
# （第一版把"右栏多张"直接混进了 A 组的期望里，结果 A 组期望"两对"是错的 ——
#   那正是闸 1 要拦的情形。左栏 / 右栏各测一条，拆开来才看得清。）
left_b1 = spec(LEFT, ("L1", 4000, 3000, 2_300_000), ("L2", 4000, 3000, 2_300_000))
right_b1 = spec(RIGHT, ("R1", 4000, 3000, 2_300_000))
check("B1 左栏两张同尺寸同体积 → 不建议（闸 1 承重）",
      find_size_matches({}, left_b1, right_b1) == [])

right_b1c = spec(RIGHT, ("R1", 4000, 3000, 2_300_000), ("R9", 4000, 3000, 2_300_000))
check("B1c 右栏两张同尺寸同体积 → 不建议（闸 1 承重）",
      find_size_matches({}, left_b1[:1], right_b1c) == [])

# 闸 1 的另一面：只要各一张，即使左右栏还有别的文件，也不影响
left_b1b = spec(LEFT, ("L1", 4000, 3000, 2_300_000), ("L9", 1080, 1920, 500_000))
right_b1b = spec(RIGHT, ("R1", 4000, 3000, 2_300_000), ("R9", 720, 1280, 400_000))
check("B1b 各一张且别处还有别的文件 → 照常建议",
      find_size_matches({}, left_b1b, right_b1b) == [("L1", "R1", 4000, 3000, 2_300_000)])

# 闸 2：内容指纹不同 → 排除（连拍后删中间帧再重存，可能撞成同尺寸同体积）
left_b2 = spec(LEFT, ("L1", 4000, 3000, 2_300_000))
right_b2 = spec(RIGHT, ("R1", 4000, 3000, 2_300_000))
check("B2 两边指纹不同 → 不是同一张，不建议（闸 2 承重）",
      find_size_matches({}, left_b2, right_b2,
                        content_keys={"L1": "hash_a", "R1": "hash_b"}) == [])
check("B2b 指纹相同 → 反而更确定，照常建议",
      find_size_matches({}, left_b2, right_b2,
                        content_keys={"L1": "hash_a", "R1": "hash_a"})
      == [("L1", "R1", 4000, 3000, 2_300_000)])
check("B2c 只有一边有指纹 → 信息不足，仍按尺寸体积判断",
      find_size_matches({}, left_b2, right_b2,
                        content_keys={"L1": "hash_a"})
      == [("L1", "R1", 4000, 3000, 2_300_000)])


# ================================================================ C. 不打扰

print()
print("=" * 64)
print("C. 已配上 / 已解除 / 元数据是 0 → 一律不建议")
print("=" * 64)

check("C1 已配对的不建议",
      find_size_matches({"L1": "R1", "R1": "L1"}, left_a, right_a)
      == [("L2", "R2", 4000, 3000, 2_410_500)])

check("C2 用户解除过的，绝不建议回去",
      find_size_matches({}, left_a, right_a, unlinked={"l1"})
      == [("L2", "R2", 4000, 3000, 2_410_500)])

# 尺寸不同（微信压图）：体积可能碰巧一样，但尺寸不同 → 不建议
left_c3 = spec(LEFT, ("L1", 4000, 3000, 2_300_000))
right_c3 = spec(RIGHT, ("R1", 2000, 1500, 2_300_000))
check("C3 尺寸不同（哪怕体积一样）→ 不建议",
      find_size_matches({}, left_c3, right_c3) == [])

# 体积不同（重压缩）：尺寸一样也不行
right_c4 = spec(RIGHT, ("R1", 4000, 3000, 2_300_001))
check("C4 体积差 1 字节 → 不建议",
      find_size_matches({}, left_c3, right_c4) == [])

# 元数据为 0：列表时就没读到，拿 0 去比会把所有"读不到"凑成一堆假候选
left_c5 = spec(LEFT, ("L1", 0, 0, 2_300_000))
right_c5 = spec(RIGHT, ("R1", 0, 0, 2_300_000))
check("C5 宽 / 高是 0（没读到）→ 不建议",
      find_size_matches({}, left_c5, right_c5) == [])
right_c6 = spec(RIGHT, ("R1", 4000, 3000, 0))
check("C6 体积是 0 → 不建议",
      find_size_matches({}, left_a[:1], right_c6) == [])


# ================================================================ D. 确定性

print()
print("=" * 64)
print("D. 结果与列表顺序无关")
print("=" * 64)

check("D1 左右列表倒过来，建议逐字相同",
      find_size_matches(partner_a, list(reversed(left_a)), list(reversed(right_a)))
      == matches_a)
check("D2 重复调用结果稳定",
      len({str(find_size_matches(partner_a, left_a, right_a)) for _ in range(20)}) == 1)


# ================================================================ E. 执行语义

print()
print("=" * 64)
print("E. 一键配上（写 manualPairs，跳过撞车的）")
print("=" * 64)

all_items = {it.key: it for it in left_a + right_a}
state = {
    "pairKey": {k: it.pair_key for k, it in all_items.items()},
    "byKey": all_items,
    "manualPairs": {},
    "sizeMatches": matches_a,
}
n = apply_size_matches(state)
check("E1 配上两对", n == 2)
check("E2 L1 ↔ R1 双向都写了",
      state["manualPairs"].get("l1") == "r1" and state["manualPairs"].get("r1") == "l1")
check("E3 L2 ↔ R2 双向都写了",
      state["manualPairs"].get("l2") == "r2" and state["manualPairs"].get("r2") == "l2")
# 配上后重算：partner 用**文档 key**（applyManualOverrides 会把 pairKey 映射回文档 key），
# 于是这两对都"已配上"，建议应当清空 —— 不能配完又把同一对塞回来。
partner_after = {"L1": "R1", "R1": "L1", "L2": "R2", "R2": "L2"}
check("E4 配上后重算，建议清空（不再重复建议）",
      find_size_matches(partner_after, left_a, right_a) == [])
state2 = dict(state)
state2["manualPairs"] = {}
check("E5 空建议时 apply 返回 0、不动状态",
      apply_size_matches({**state2, "sizeMatches": []}) == 0)

# pairKey 撞车：两边主文件名相同时会被静默跳过 —— 但那本来就已经"同名"
left_e = [Item("Lx", "same.jpg", LEFT, 4000, 3000, 2_300_000)]
right_e = [Item("Rx", "same.png", RIGHT, 4000, 3000, 2_300_000)]
m_e = find_size_matches({}, left_e, right_e)
check("E6 名字骨架相同（只差扩展名）也会被找出来", len(m_e) == 1)
all_e = {it.key: it for it in left_e + right_e}
st_e = {
    "pairKey": {k: it.pair_key for k, it in all_e.items()},
    "byKey": all_e,
    "manualPairs": {},
    "sizeMatches": m_e,
}
check("E7 但 pairKey 撞车（主文件名相同）时跳过，不写",
      apply_size_matches(st_e) == 0 and st_e["manualPairs"] == {})


# ================================================================ F. 单张配上与卡片露出（v5.8.0）

print()
print("=" * 64)
print("F. 卡片角标：配上这一对（单张入口）+ 反查表 + 接线结构断言")
print("=" * 64)

# 单张入口：只配 key 对应的那一条，其它建议原样保留
state_f = {
    "pairKey": {k: it.pair_key for k, it in all_items.items()},
    "byKey": all_items,
    "manualPairs": {},
    "sizeMatches": matches_a,
}
check("F1 applySizeMatch(L2) 只配一对", apply_size_match(state_f, "L2") == 1)
check("F2 只有 L2 ↔ R2 写了，L1 没被动",
      state_f["manualPairs"] == {"l2": "r2", "r2": "l2"})
check("F3 右栏 key 同样能找到同一条建议", apply_size_match(state_f, "R1") == 1
      and state_f["manualPairs"].get("l1") == "r1")
check("F4 不在建议里的 key → 静默返回、不动状态",
      apply_size_match(state_f, "L3") == 0)

# 反查表：双向都有，值是对方的**文件名**（卡片上直接显示，不用再去另一栏找）
partner_f = size_match_partner(matches_a, all_items)
check("F5 反查表双向各一条",
      partner_f.get("L1") == "R1.jpg" and partner_f.get("R1") == "L1.jpg")
check("F6 没有候选的 key 不在表里（L3 右栏没有对应）", "L3" not in partner_f)

# 结构断言：行为测试看不见"角标忘了只在没配对时出现"这种接线错误 —— 直接扫源码
SRC = Path(__file__).resolve().parent / "app/src/main/java/com/yuanbao/pairrename"
vm_kt = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")
app_kt = (SRC / "ui/PairRenameApp.kt").read_text(encoding="utf-8")
pane_kt = (SRC / "ui/PaneColumn.kt").read_text(encoding="utf-8")
card_kt = (SRC / "ui/ImageCard.kt").read_text(encoding="utf-8")

check("F7 UiState 带 sizeMatchPartner 字段且重算时一起更新",
      "sizeMatchPartner: Map<String, String>" in vm_kt
      and "sizeMatchPartner = sizeMatchPartner" in vm_kt)
check("F8 单张入口存在且只喂一条",
      "fun applySizeMatch(key: String)" in vm_kt and "linkSizeMatches(listOf(m))" in vm_kt)
check("F9 批量与单张共用同一条写入路径（linkSizeMatches 恰好两个调用点）",
      vm_kt.count("linkSizeMatches(") == 3)  # 定义 1 次 + 两个入口各 1 次
check("F10 三处 PaneColumn 调用点都传了 sizeMatchPartner",
      app_kt.count("sizeMatchPartner = ui.sizeMatchPartner") == 3)
check("F11 PaneColumn 把反查结果和回调交给卡片",
      "sizeMatchName = sizeMatchPartner[item.key]" in pane_kt
      and "onSizeLink = { vm.applySizeMatch(item.key) }" in pane_kt)
check("F12 卡片角标只在**没配对**时出现（有配对的卡不会被引去重配）",
      "!hasPartner && !sizeMatchName.isNullOrBlank()" in card_kt)
check("F13 文字行与配对行互斥（else if），已配对的卡永远显示对面名字",
      '} else if (!sizeMatchName.isNullOrBlank()) {' in card_kt)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：尺寸 + 体积 + 两边各一张能配上；")
print("多候选与指纹不同都被拦住；已配对 / 已解除 / 元数据为 0 的一律不打扰。")
