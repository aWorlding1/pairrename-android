"""验证「搜索/过滤时勾选不会被隐藏文件污染」。

缺陷：过滤逻辑原先只存在于界面，ViewModel 完全不知道有过滤这回事。
于是——搜索后点全选，会把**被隐藏的文件**也选上，
批量改名就改到了用户看不见的文件。这类错误没有任何提示，极难发现。

修复：过滤算法抽到 util/Filter.kt，界面与 VM 共用同一份；
      setQuery / cycleMatchFilter / pruneChecked 都会收敛勾选。
"""
from dataclasses import dataclass, field

ALL, TODO, DONE = "ALL", "TODO", "DONE"


@dataclass
class Item:
    key: str
    name: str
    side: str


def apply_filter(items, query, match_filter, synced):
    """与 util/Filter.kt 完全一致的算法"""
    q = query.strip()
    out = []
    for it in items:
        ok_q = (not q) or (q.lower() in it.name.lower())
        if match_filter == ALL:
            ok_f = True
        elif match_filter == DONE:
            ok_f = it.key in synced
        else:
            ok_f = it.key not in synced
        if ok_q and ok_f:
            out.append(it)
    return out


def prune_checked(checked, all_items, query, match_filter, synced):
    """修复后：只保留**可见**的"""
    alive = {i.key for i in apply_filter(all_items, query, match_filter, synced)}
    return {k for k in checked if k in alive}


def prune_checked_old(checked, all_items):
    """修复前：只要文件还在就保留，不管看不看得见"""
    alive = {i.key for i in all_items}
    return {k for k in checked if k in alive}


items = [
    Item("L1", "beach.jpg", "L"),
    Item("L2", "cat.jpg", "L"),
    Item("L3", "beach (1).jpg", "L"),
]

print("=== 场景：搜索 'beach' 后全选，再清空搜索词执行批量")
# 1) 搜索 beach，可见 L1 L3
visible = apply_filter(items, "beach", ALL, set())
print(f"  搜索 'beach' 可见: {[i.key for i in visible]}")
checked = {i.key for i in visible}
print(f"  点全选 -> {sorted(checked)}")

# 2) 清空搜索词，全部可见
q2 = ""
old = prune_checked_old(checked, items)
new = prune_checked(checked, items, q2, ALL, set())
print(f"\n  修复前（清空搜索后仍生效于）: {sorted(old)}")
print(f"  修复后（当前搜索 '{q2}'）      : {sorted(new)}")
# 这个场景下清空搜索后都可见，两者相同 —— 关键在下面反向场景

print("\n=== 反向场景（真正暴露缺陷的）")
# 1) 无搜索，全选三张
checked = {i.key for i in items}
print(f"  无搜索全选 -> {sorted(checked)}")
# 2) 搜索 'cat'，只剩 L2，L1/L3 被隐藏
q = "cat"
old = prune_checked_old(checked, items)
new = prune_checked(checked, items, q, ALL, set())
print(f"  搜索 'cat' 后可见: {[i.key for i in apply_filter(items, q, ALL, set())]}")
print(f"  修复前仍作用于  : {sorted(old)}  <- 含被隐藏的 L1、L3")
print(f"  修复后只作用于  : {sorted(new)}")
assert old == {"L1", "L2", "L3"}, "修复前应该是全部"
assert new == {"L2"}, "修复后应只剩可见的 L2"
print("  OK：隐藏文件不再被误操作")

print("\n=== 过滤（仅待处理）同样收敛")
synced = {"L1"}
checked = {"L1", "L2", "L3"}
vis = apply_filter(items, "", TODO, synced)
new = prune_checked(checked, items, "", TODO, synced)
print(f"  L1 已统一，切到「仅待处理」可见: {[i.key for i in vis]}")
print(f"  勾选收敛为: {sorted(new)}")
assert new == {"L2", "L3"}
print("  OK：已统一的被排除")

print("\n=== 边界：勾选为空 / 全部被过滤掉")
assert prune_checked(set(), items, "cat", ALL, set()) == set()
assert prune_checked({"L1"}, items, "zzz", ALL, set()) == set()
print("  OK：空集合与全不匹配均安全")

print("\n结论：勾选始终收敛在可见集合内，不会误改隐藏文件")
