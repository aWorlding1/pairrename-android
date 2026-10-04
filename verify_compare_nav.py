"""验证对比面板的「上一对 / 下一对」导航。

风险点：
1. 应用完当前对之后，名单还没更新（改名是异步的），
   此时「当前项 +1」必须仍然给出正确的下一对 —— 不能跳过也不能原地不动
2. 走到末尾要能绕回（否则"点最后一对就没反应"）
3. 全处理完后要退化成在已配对的里循环，方便回看
4. 当前项已不在候选里（比如刚被排除）时，按方向就近取
5. **只在当前可见集合内导航**（v4.7.0 修复）：
   候选池必须来自与界面同一个 applyFilter，否则「仅待处理」过滤下
   点「下一对」会跳到界面上根本看不见的项。

第 5 条是本脚本 v4.7.0 的重要升级 —— 在它之前，这个脚本里的
`neighbor()` 只有 (items, current, delta, matched, synced) 五个参数，
压根没有"过滤"这个概念可以传进来，于是它永远不可能发现那个缺陷。
**检查器只认它被写时的调用形状**：函数签名一变，检查器就跟着过期了。
"""

ALL, TODO, DONE, UNPAIRED, SUSPECT = "ALL", "TODO", "DONE", "UNPAIRED", "SUSPECT"


class It:
    def __init__(self, key, name, matched=True, synced=False, side="L"):
        self.key, self.name = key, name
        self.matched, self.synced = matched, synced
        self.side = side


def apply_filter(items, query, match_filter, synced, matched, weak_keys):
    """与 util/Filter.kt 的 applyFilter 对应。"""
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
            ok_f = it.key in weak_keys
        else:
            ok_f = it.key not in matched
        if ok_q and ok_f:
            out.append(it)
    return out


def neighbor(items, current_key, delta, matched, synced,
             query="", match_filter=ALL, weak_keys=None):
    """与 vm/MainViewModel.kt 的 compareNeighbor 对应（v4.7.0 起含可见集合）。"""
    if not items:
        return None
    visible = apply_filter(items, query, match_filter, synced, matched, weak_keys or set())
    if not visible:
        return None
    pending = [x for x in visible if x.key in matched and x.key not in synced]
    # 兜底也必须用 visible，不能退回 items —— 否则过滤等于失效
    pool = pending if pending else [x for x in visible if x.key in matched]
    if not pool:
        return None
    idx = next((i for i, x in enumerate(pool) if x.key == current_key), -1)
    if idx < 0:
        nxt = 0 if delta > 0 else len(pool) - 1
    else:
        raw = idx + delta
        nxt = ((raw % len(pool)) + len(pool)) % len(pool)
    return pool[nxt] if 0 <= nxt < len(pool) else None


items = [It(f"L{i}", f"a{i}.jpg") for i in range(5)]
matched = {x.key for x in items}
synced = set()

print("=== 基本前进（默认：无过滤，等价于全量）")
cur = items[0]
seq = [cur.name]
for _ in range(5):
    cur = neighbor(items, cur.key, +1, matched, synced)
    seq.append(cur.name)
print(f"  {seq}")
assert seq == ["a0.jpg", "a1.jpg", "a2.jpg", "a3.jpg", "a4.jpg", "a0.jpg"]
print("  OK：到末尾绕回开头")

print("\n=== 后退")
cur = items[0]
seq = [cur.name]
for _ in range(2):
    cur = neighbor(items, cur.key, -1, matched, synced)
    seq.append(cur.name)
print(f"  {seq}")
assert seq == ["a0.jpg", "a4.jpg", "a3.jpg"]
print("  OK：到开头绕回末尾")

print("\n=== 关键：应用后名单未更新时，+1 仍给出正确的下一对")
# 模拟：a0 刚被应用，但 synced 还没更新（异步）
cur = items[0]
nxt = neighbor(items, cur.key, +1, matched, synced)
print(f"  当前 a0 -> 下一个 {nxt.name}")
assert nxt.name == "a1.jpg", "跳错了！"
print("  OK：异步场景下行为正确（当前项仍在名单里，+1 就是下一个）")

print("\n=== 已统一的会被跳过")
synced = {"L0", "L2"}
cur = items[0]   # 已 synced，不在候选池里
nxt = neighbor(items, cur.key, +1, matched, synced)
print(f"  L0 已统一，从它出发 +1 -> {nxt.name}")
# L0 不在 pool（pool = L1,L3,L4），idx=-1 -> delta>0 取 0 即 L1
assert nxt.name == "a1.jpg"
print("  OK：已统一的自动跳过")

print("\n=== 全部统一后退化为在已配对里循环")
synced = {x.key for x in items}
nxt = neighbor(items, items[0].key, +1, matched, synced)
print(f"  全部处理完 -> {nxt.name}")
assert nxt is not None, "全部处理完应该还能回看，不能返回 None"
print("  OK：仍可回看")

print("\n=== 没有配对时返回 None（不崩）")
assert neighbor(items, items[0].key, +1, set(), set()) is None
assert neighbor([], "x", +1, matched, synced) is None
print("  OK")

print("\n=== 单个元素时不会死循环/越界")
one = [items[0]]
nxt = neighbor(one, items[0].key, +1, {"L0"}, set())
print(f"  只有一个 -> {nxt.name}")
assert nxt.name == "a0.jpg"
print("  OK：绕回自己")

print("\n=== v4.7.0 新增：导航严格收敛于可见集合")
# 1) 搜索词生效
nxt = neighbor(items, "L0", +1, matched, set(), query="a3")
print(f"  搜索 'a3' 后从 a0 出发 +1 -> {nxt.name}")
assert nxt.name == "a3.jpg", "搜索词没被尊重"
print("  OK：搜索之外的文件不会被跳到")

# 2) 「仅未配对」过滤下没有可导航的配对 -> 返回 None（按钮应当不可用）
allm = set()   # 全部未配对
nxt = neighbor(items, "L0", +1, allm, set(), match_filter=UNPAIRED)
print(f"  「仅未配对」下 +1 -> {nxt}")
assert nxt is None, "不该跳到界面上看不见的配对"
print("  OK：没有可导航的配对时返回 None")

# 3) 「仅弱依据」过滤：只在弱依据里循环，绝不越界
weak = {"L1", "L3"}
seq = []
cur = "L1"
for _ in range(4):
    cur = neighbor(items, cur, +1, matched, set(), match_filter=SUSPECT, weak_keys=weak).key
    seq.append(cur)
print(f"  「仅弱依据」(L1,L3) 连点 4 次 -> {seq}")
assert all(k in weak for k in seq), "跳出了弱依据范围"
assert set(seq) == weak, "只应在弱依据内循环"
print("  OK：复核流水线只在自己这批里走")

# 4) 过滤把候选池清空时不能抛异常
nxt = neighbor(items, "L0", +1, matched, set(), query="不存在的东西")
print(f"  过滤后一个都不剩 -> {nxt}")
assert nxt is None
print("  OK")

print("\n结论：对比导航行为正确，且在过滤 / 搜索下严格收敛于可见集合")
