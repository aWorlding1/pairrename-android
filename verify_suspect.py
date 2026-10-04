"""第九轮验证：「可疑配对复核」+ 对比导航的可见集合修复。

本轮两个主张必须被证明，而不是"看起来对"：

  主张 A（新功能）：`弱依据` = 靠 SEQ / ORDER 猜出来的配对。
      过滤、对比面板提示、统计、建议四处口径同源（isWeakReason）。
      且必须是"当前**真的**配对中"的那批 —— 手动抢配对后残留的
      孤儿 reason 条目不能混进来。

  主张 B（修缺陷）：compareNeighbor 从"同栏全量列表"改成"当前可见集合"。
      关键性质是 **默认场景零回归**：ALL + 无搜索时，
      新旧序列必须逐项相同；否则这个修复本身就是在制造新 bug。

跑法：python verify_suspect.py
"""
from dataclasses import dataclass, field

# ---------------------------------------------------------------- 模型

CONTENT, EXIF, NAME, SIMILAR, SEQ, ORDER = (
    "CONTENT", "EXIF", "NAME", "SIMILAR", "SEQ", "ORDER",
)

ALL, TODO, DONE, UNPAIRED, SUSPECT, ONLY_LEFT, ONLY_RIGHT = (
    "ALL", "TODO", "DONE", "UNPAIRED", "SUSPECT", "ONLY_LEFT", "ONLY_RIGHT",
)


@dataclass
class Item:
    key: str
    name: str
    side: str
    size: int = 1000
    pair_key: str = ""

    def __post_init__(self):
        # 与 Naming.baseOf 同义：去掉最后一个扩展名。
        # unlinked / manualPairs 存的都是 pairKey（用户看得见的主文件名），
        # 而不是文档 Uri —— 改名后 Uri 会变，pairKey 才是稳定锚点。
        if not self.pair_key:
            object.__setattr__(self, "pair_key", self.name.rsplit(".", 1)[0])


@dataclass
class Result:
    """与 util/Pairing.kt 的 PairResult 同形。"""
    partner: dict = field(default_factory=dict)
    keys: set = field(default_factory=set)
    reason: dict = field(default_factory=dict)
    by_seq: int = 0
    by_order: int = 0
    by_similar: int = 0

    # ---- 派生属性（与 Kotlin 侧逐字对应）----
    @property
    def weak_keys(self):
        return {
            k for k, r in self.reason.items()
            if k in self.partner and is_weak(r)
        }

    @property
    def weak_pairs(self):
        return len(self.weak_keys) // 2


def is_weak(reason):
    """与 util/Pairing.kt 的 isWeakReason 逐字对应。"""
    return reason in (ORDER, SEQ)


# ---------------------------------------------------------------- 过滤

def apply_filter(items, query, match_filter, synced, matched, weak_keys):
    """与 util/Filter.kt 的 applyFilter 对应（只保留与验证相关的分支）。"""
    q = query.strip()
    out = []
    for it in items:
        ok_q = (not q) or (q.lower() in it.name.lower())
        if match_filter == ALL:
            ok_f = True
        elif match_filter == DONE:
            ok_f = it.key in synced
        elif match_filter == TODO:
            ok_f = it.key not in synced
        elif match_filter == UNPAIRED:
            ok_f = it.key not in matched
        elif match_filter == SUSPECT:
            ok_f = it.key in weak_keys          # <- 本轮新增
        elif match_filter == ONLY_LEFT:
            ok_f = it.side == "L" and it.key not in matched
        else:
            ok_f = it.side == "R" and it.key not in matched
        if ok_q and ok_f:
            out.append(it)
    return out


# ---------------------------------------------------------------- 手动覆盖

def apply_overrides_new(partner, keys, reason, manual, unlinked, by_key):
    """与修复后的 applyManualOverrides 对应（含孤儿清理 + 计数重算）。"""
    partner = dict(partner)
    reason = dict(reason)

    unlinked_keys = [k for k, v in by_key.items() if v.pair_key in unlinked]
    for k in unlinked_keys:
        other = partner.pop(k, None)
        if other is not None:
            partner.pop(other, None)

    for a, b in manual:
        ia, ib = by_key.get(a), by_key.get(b)
        if ia is None or ib is None or ia.side == ib.side:
            continue
        other = partner.pop(ia.key, None)
        if other is not None:
            partner.pop(other, None)
        other = partner.pop(ib.key, None)
        if other is not None:
            partner.pop(other, None)
        partner[ia.key] = ib.key
        partner[ib.key] = ia.key

    for a, b in manual:
        ia, ib = by_key.get(a), by_key.get(b)
        if ia is None or ib is None or ia.side == ib.side:
            continue
        reason.pop(ia.key, None)
        reason.pop(ib.key, None)
    for k in unlinked_keys:
        reason.pop(k, None)

    # 3) 孤儿清理：reason 只允许描述真实存在的配对
    for k in [k for k in reason if k not in partner]:
        reason.pop(k)

    # 4) 三个计数从 reason 派生，与 weak_pairs 同源
    by_seq = by_order = by_similar = 0
    counted = set()
    for k, other in partner.items():
        if k in counted:
            continue
        counted.add(k)
        counted.add(other)
        r = reason.get(k)
        if r == SEQ:
            by_seq += 1
        elif r == ORDER:
            by_order += 1
        elif r == SIMILAR:
            by_similar += 1

    return Result(partner, set(partner.keys()), reason, by_seq, by_order, by_similar)


def apply_overrides_old(partner, keys, reason, manual, unlinked, by_key,
                        raw_by_seq=0, raw_by_order=0, raw_by_similar=0):
    """修复前的 applyManualOverrides。

    两点与修复后不同：
      1. 不清理孤儿 reason（被抢走配对的一方仍留着依据）；
      2. bySeq / byOrder / bySimilar 直接沿用引擎原始输出，不跟着 reason 重算。
    """
    partner = dict(partner)
    reason = dict(reason)

    unlinked_keys = [k for k, v in by_key.items() if v.pair_key in unlinked]
    for k in unlinked_keys:
        other = partner.pop(k, None)
        if other is not None:
            partner.pop(other, None)

    for a, b in manual:
        ia, ib = by_key.get(a), by_key.get(b)
        if ia is None or ib is None or ia.side == ib.side:
            continue
        other = partner.pop(ia.key, None)
        if other is not None:
            partner.pop(other, None)
        other = partner.pop(ib.key, None)
        if other is not None:
            partner.pop(other, None)
        partner[ia.key] = ib.key
        partner[ib.key] = ia.key

    for a, b in manual:
        ia, ib = by_key.get(a), by_key.get(b)
        if ia is None or ib is None or ia.side == ib.side:
            continue
        reason.pop(ia.key, None)
        reason.pop(ib.key, None)
    for k in unlinked_keys:
        reason.pop(k, None)

    return Result(
        partner, set(partner.keys()), reason,
        raw_by_seq, raw_by_order, raw_by_similar,
    )


def weak_keys_naive(reason, partner):
    """**去掉** `key in partner` 那道闸的版本。

    这不是随便写的反例 —— 它正是"如果没有那道闸会怎样"。
    用来证明 PairResult.weakKeys 里的交集是**承重**的，不是装饰。
    """
    return {k for k, r in reason.items() if is_weak(r)}


# ---------------------------------------------------------------- 对比导航

def neighbor_pool_old(all_items, matched, synced):
    """修复前：同栏**全量**列表里找候选，完全不看过滤。"""
    pending = [i for i in all_items if i.key in matched and i.key not in synced]
    pool = pending or [i for i in all_items if i.key in matched]
    return pool


def neighbor_pool_new(all_items, query, match_filter, synced, matched,
                      weak_keys, extra_empty=True):
    """修复后：先算可见集合（与界面同一个 applyFilter），再挑候选。"""
    visible = apply_filter(all_items, query, match_filter, synced, matched, weak_keys)
    if not visible:
        return []
    pending = [i for i in visible if i.key in matched and i.key not in synced]
    pool = pending or [i for i in visible if i.key in matched]
    return pool


def walk(pool, start_key, steps):
    """模拟连点「下一对」：返回访问到的 key 序列。"""
    if not pool:
        return []
    idx = next((i for i, x in enumerate(pool) if x.key == start_key), -1)
    seen = []
    cur = idx if idx >= 0 else 0
    for _ in range(steps):
        cur = ((cur + 1) % len(pool) + len(pool)) % len(pool)
        seen.append(pool[cur].key)
    return seen


# ================================================================ 用例

problems = []


def check(name, cond, detail=""):
    ok = "PASS" if cond else "FAIL"
    print(f"  [{ok}] {name}" + (f"  {detail}" if detail else ""))
    if not cond:
        problems.append(name)


# ---- 构造：8 张左 + 8 张右，依据从强到弱都有 ----
L = [Item(f"L{i}", n, "L") for i, n in enumerate([
    "img_0001.jpg", "img_0002.jpg", "cat.jpg", "dog.jpg",
    "sunset-副本.jpg", "tree(1).jpg", "foo_0008.jpg", "bar_0009.jpg",
], start=1)]
R = [Item(f"R{i}", n, "R") for i, n in enumerate([
    "IMG_0001.jpg", "IMG_0002.jpg", "cat.jpg", "dog2.jpg",
    "sunset.jpg", "tree.jpg", "baz_0008.jpg", "qux_0009.jpg",
], start=1)]

partner = {
    "L1": "R1", "R1": "L1",   # SEQ
    "L2": "R2", "R2": "L2",   # ORDER
    "L3": "R3", "R3": "L3",   # NAME（强）
    "L4": "R4", "R4": "L4",   # CONTENT（强）
    "L5": "R5", "R5": "L5",   # SIMILAR（强，不是猜）
    "L6": "R6", "R6": "L6",   # EXIF（强）
}
reason = {
    "L1": SEQ, "R1": SEQ,
    "L2": ORDER, "R2": ORDER,
    "L3": NAME, "R3": NAME,
    "L4": CONTENT, "R4": CONTENT,
    "L5": SIMILAR, "R5": SIMILAR,
    "L6": EXIF, "R6": EXIF,
}
merged = Result(partner, set(partner.keys()), reason, by_seq=1, by_order=1, by_similar=1)

print("=" * 64)
print("A. 弱依据判定：只有 SEQ / ORDER 算「猜」")
print("=" * 64)
weak = merged.weak_keys
print(f"  弱依据 key: {sorted(weak)}")
check("SEQ 计入", "L1" in weak and "R1" in weak)
check("ORDER 计入", "L2" in weak and "R2" in weak)
check("NAME 不计入", "L3" not in weak)
check("CONTENT 不计入", "L4" not in weak)
check("EXIF 不计入", "L6" not in weak)
check("SIMILAR 不计入（人工命名过，是强证据）", "L5" not in weak)
check("weak_pairs = 2", merged.weak_pairs == 2, f"实际 {merged.weak_pairs}")
check("weak_keys 偶数（理由左右各一条）", len(weak) % 2 == 0)

print()
print("=" * 64)
print("B. 过滤「仅弱依据」= 弱 keys 的真子集，且不含强依据/未配对")
print("=" * 64)
matched = merged.keys
vis_all = apply_filter(L + R, "", SUSPECT, set(), matched, merged.weak_keys)
vis_keys = sorted(i.key for i in vis_all)
print(f"  仅弱依据可见: {vis_keys}")
check("只出 4 张（2 对）", len(vis_all) == 4, f"实际 {len(vis_all)}")
check("不含强依据项", not ({"L3", "L4", "L5", "L6"} & set(vis_keys)))
check("全部确实在配对中", all(k in matched for k in vis_keys))

print()
print("=" * 64)
print("B2. 回归：孤儿 reason（已解除配对却留着依据）不得混进复核")
print("=" * 64)
# 场景：手动把 L1 抢去配 R3（跨依据、跨配对），于是 R1 与 L3 变成未配对。
# 旧实现不会清 R1 的 reason（SEQ），它会以"弱依据配对"的身份留在表里。
by_key = {i.key: i for i in L + R}
old_r = apply_overrides_old(partner, set(partner.keys()), reason, [("L1", "R3")], set(), by_key,
                            raw_by_seq=1, raw_by_order=1, raw_by_similar=1)
new_r = apply_overrides_new(partner, set(partner.keys()), reason, [("L1", "R3")], set(), by_key)
print(f"  旧实现里 R1 的依据: {old_r.reason.get('R1')}；R1 还在 partner 里吗: {'R1' in old_r.partner}")
check("旧实现确实残留孤儿 reason（被抢走配对的一方没清）",
      "R1" in old_r.reason and "R1" not in old_r.partner)

naive = sorted(weak_keys_naive(old_r.reason, old_r.partner))
print(f"  若弱依据只按 reason 判（无护栏）: {naive}  <- R1 这个**未配对**的项漏进来了")
check("无护栏时孤儿会污染弱依据集合（缺陷风险已被证实）", "R1" in naive)
check("有护栏时不被污染（key in partner 那道闸是承重的）", "R1" not in old_r.weak_keys)
check("新实现顺手把孤儿条目清掉（双保险）", "R1" not in new_r.reason)

guard_vis = sorted(i.key for i in apply_filter(L + R, "", SUSPECT, set(), old_r.keys, old_r.weak_keys))
naive_vis = sorted(i.key for i in apply_filter(L + R, "", SUSPECT, set(), old_r.keys, naive))
print(f"  复核列表（有护栏）: {guard_vis}")
print(f"  复核列表（无护栏）: {naive_vis}")
check("有护栏的复核列表不含未配对项", "R1" not in guard_vis)
check("无护栏的复核列表会含未配对项", "R1" in naive_vis)
check("手动确认过的那一对不算可疑", not ({"L1", "R3"} & set(new_r.weak_keys)))

print()
print("=" * 64)
print("C. 计数同源：bySeq/byOrder/bySimilar 与 weak_pairs 不再打架")
print("=" * 64)
# 场景：手动解除一对序号配对（unlinkPartner 会同时记下两侧 pairKey）
by_key2 = {i.key: i for i in L + R}
unlink_pair = {"img_0001", "IMG_0001"}  # unlinkPartner 会同时记下左右两侧的 pairKey
old2 = apply_overrides_old(partner, set(partner.keys()), reason, [], unlink_pair, by_key2,
                           raw_by_seq=1, raw_by_order=1, raw_by_similar=1)
new2 = apply_overrides_new(partner, set(partner.keys()), reason, [], unlink_pair, by_key2)
old2_sum = old2.by_seq + old2.by_order
print(f"  解除一对序号配对之后：")
print(f"    旧实现：bySeq={old2.by_seq} byOrder={old2.by_order} 合计 {old2_sum}，"
          f"而按 reason 数出来只有 {old2.weak_pairs} 对  <- 同一屏两个数字打架")
print(f"    新实现：bySeq={new2.by_seq} byOrder={new2.by_order} 合计 "
          f"{new2.by_seq + new2.by_order}，弱依据 {new2.weak_pairs} 对  <- 一致")
check("旧实现的两个口径确实不一致（缺陷复现）", old2_sum != old2.weak_pairs)
check("新实现 bySeq+byOrder == weak_pairs", new2.by_seq + new2.by_order == new2.weak_pairs)
check("新实现解除后剩 1 对弱依据", new2.weak_pairs == 1, f"实际 {new2.weak_pairs}")
check("解除掉的是 SEQ，所以 bySeq 归零、byOrder 保留",
      new2.by_seq == 0 and new2.by_order == 1)
check("被解除的 L1/R1 不再出现在弱依据里", not ({"L1", "R1"} & set(new2.weak_keys)))

print()
print("=" * 64)
print("D. 对比导航：默认场景必须零回归")
print("=" * 64)
synced = set()
matched = merged.keys
pool_old = neighbor_pool_old(L, matched, synced)
pool_new = neighbor_pool_new(L, "", ALL, synced, matched, merged.weak_keys)
old_seq = [i.key for i in pool_old]
new_seq = [i.key for i in pool_new]
print(f"  旧池: {old_seq}")
print(f"  新池: {new_seq}")
check("ALL + 无搜索时新旧候选池逐项相同", old_seq == new_seq)
s_old = walk(pool_old, "L1", 6)
s_new = walk(pool_new, "L1", 6)
print(f"  连点 6 次「下一对」：旧 {s_old}")
print(f"                      新 {s_new}")
check("连点序列逐项相同（零回归）", s_old == s_new)

print()
print("=" * 64)
print("D2. 缺陷复现 + 修复：过滤生效后「下一对」不再跳到看不见的地方")
print("=" * 64)
synced = set()
# 「仅弱依据」过滤下：界面只剩 L1/L2，旧实现却会跳到 L3..L6（界面上根本看不见）
pool_old2 = neighbor_pool_old(L, matched, synced)
pool_new2 = neighbor_pool_new(L, "", SUSPECT, synced, matched, merged.weak_keys)
s_old2 = walk(pool_old2, "L1", 4)
s_new2 = walk(pool_new2, "L1", 4)
print(f"  界面可见（仅弱依据）: {[i.key for i in pool_new2]}")
print(f"  旧实现跳转轨迹: {s_old2}   <- 跳到了 L3/L4/L5/L6，用户看不见")
print(f"  新实现跳转轨迹: {s_new2}")
check("旧实现确实会跳出可见范围（缺陷复现）", any(k not in {"L1", "L2"} for k in s_old2))
check("新实现只在弱依据里循环", all(k in {"L1", "L2"} for k in s_new2))
check("新实现复核池恰好 2 项", len(pool_new2) == 2)

# 「仅待处理」下同理
synced = set()
pool_new3 = neighbor_pool_new(L, "", TODO, synced, matched, merged.weak_keys)
check("TODO 过滤下候选池 = 可见的已配对项", [i.key for i in pool_new3] == ["L1", "L2", "L3", "L4", "L5", "L6"])
# 「仅未配对」下没有可导航的配对 -> 空池，按钮应当不可用
pool_new4 = neighbor_pool_new(L, "", UNPAIRED, synced, matched, merged.weak_keys)
check("UNPAIRED 过滤下候选池为空（不该跳到看不见的配对）", pool_new4 == [], f"实际 {[i.key for i in pool_new4]}")
# 搜索词也要被尊重
pool_new5 = neighbor_pool_new(L, "cat", ALL, synced, matched, merged.weak_keys)
check("搜索 'cat' 后候选池只剩 cat", [i.key for i in pool_new5] == ["L3"])

print()
print("=" * 64)
print("E. 边界")
print("=" * 64)
empty = Result()
check("空配对结果 weak_pairs=0", empty.weak_pairs == 0)
check("空配对结果 weak_keys 为空", empty.weak_keys == set())
check("无可配对时 SUSPECT 过滤返回空", apply_filter(L + R, "", SUSPECT, set(), set(), set()) == [])
# 只有弱依据时
only_weak = Result({"L1": "R1", "R1": "L1"}, {"L1", "R1"}, {"L1": ORDER, "R1": ORDER})
check("纯弱依据 weak_pairs=1", only_weak.weak_pairs == 1)
# 手动配对（reason 被抹掉）不计入
manual_only = apply_overrides_new(
    {}, set(), {}, [("L1", "R1")], set(), {i.key: i for i in L + R})
check("手动配对的 weak_pairs=0（用户确认过的不打扰）", manual_only.weak_pairs == 0)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：弱依据口径唯一，孤儿 reason 不再混入；")
print("对比导航在默认场景零回归、在过滤/搜索下严格收敛于可见集合。")
