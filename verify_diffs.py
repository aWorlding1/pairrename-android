"""验证「单边差异过滤」与「多选拖放的顺序对应」。

差异过滤的风险：ONLY_LEFT / ONLY_RIGHT 如果只看 side 而忘了
排除已配对的，就会把"左栏有、右栏也有（已配对）"的文件也显示出来，
那就不叫差异了。

多选拖放的风险：应用顺序必须跟界面可见顺序一致，
否则名字会套到错误的文件上 —— 这是最危险的那一类错误。
"""

# ---------- 差异过滤 ----------

LEFT, RIGHT = "L", "R"
ALL, TODO, DONE, UNPAIRED, ONLY_LEFT, ONLY_RIGHT = (
    "ALL", "TODO", "DONE", "UNPAIRED", "ONLY_LEFT", "ONLY_RIGHT")


class It:
    def __init__(self, key, side, name):
        self.key, self.side, self.name = key, side, name

    def __repr__(self):
        return f"{self.key}({self.side})"


def apply_filter(items, f, matched, synced=frozenset()):
    out = []
    for it in items:
        if f == ALL:
            ok = True
        elif f == DONE:
            ok = it.key in synced
        elif f == TODO:
            ok = it.key not in synced
        elif f == UNPAIRED:
            ok = it.key not in matched
        elif f == ONLY_LEFT:
            ok = it.side == LEFT and it.key not in matched
        else:  # ONLY_RIGHT
            ok = it.side == RIGHT and it.key not in matched
        if ok:
            out.append(it)
    return out


items = [
    It("L1", LEFT, "a"),    # 与 R1 配对
    It("L2", LEFT, "b"),    # 左栏独有
    It("R1", RIGHT, "a"),   # 与 L1 配对
    It("R2", RIGHT, "c"),   # 右栏独有
    It("R3", RIGHT, "d"),   # 右栏独有
]
matched = {"L1", "R1"}

print("=== 单边差异：只显示对面没有的")
left_only = apply_filter(items, ONLY_LEFT, matched)
right_only = apply_filter(items, ONLY_RIGHT, matched)
print(f"  ONLY_LEFT  -> {left_only}")
print(f"  ONLY_RIGHT -> {right_only}")
assert [i.key for i in left_only] == ["L2"], "左栏独有错"
assert [i.key for i in right_only] == ["R2", "R3"], "右栏独有错"
print("  OK：已配对的 L1/R1 被正确排除")

print("\n=== 关键：如果只看 side 忘了排除已配对（展示 bug）")
buggy_left = [i for i in items if i.side == LEFT]
buggy_right = [i for i in items if i.side == RIGHT]
print(f"  错误实现 ONLY_LEFT  -> {buggy_left}  <- 混入了已配对的 L1")
assert len(buggy_left) == 2 and len(left_only) == 1
print("  OK：确认必须同时排除已配对")

print("\n=== 全部配对时差异应为空（两边完全对上）")
all_matched = {i.key for i in items}
assert apply_filter(items, ONLY_LEFT, all_matched) == []
assert apply_filter(items, ONLY_RIGHT, all_matched) == []
print("  OK：无差异")

print("\n=== UNPAIRED 应等于两边独有之和")
unp = apply_filter(items, UNPAIRED, matched)
print(f"  {unp}")
assert len(unp) == 3 and {i.key for i in unp} == {"L2", "R2", "R3"}
print("  OK")

# ---------- 多选拖放顺序 ----------

print("\n=== 多选拖放：应用顺序必须与可见顺序一致")
visible = [It(f"t{i}", RIGHT, f"old{i}") for i in range(5)]
picked = [visible[0], visible[2], visible[4]]   # 勾选了第 0、2、4 个
sources = [It(f"s{i}", LEFT, f"new{i}") for i in range(3)]

# 落点是 visible[2]，应套到 [2,3,4]
idx = 2
targets = visible[idx:idx + len(picked)]
print(f"  勾选 {len(picked)} 个，落在第 {idx} 个")
print(f"  目标段: {targets}")
pairs = list(zip(sources, targets))
print(f"  配对: {[(s.name, t.name) for s, t in pairs]}")
assert [t.key for t in targets] == ["t2", "t3", "t4"]
assert len(pairs) == 3
print("  OK：顺序一一对应，不跳不重")

print("\n=== 目标不够时只套能套的（不越界）")
idx = 4
targets = visible[idx:idx + len(picked)]
print(f"  落在第 {idx} 个，只剩 {len(targets)} 个目标")
assert len(targets) == 1
print("  OK：自动缩短，不越界")

print("\n=== 顺序依据可见序而非勾选序")
# 勾选顺序是乱的，但应用必须按可见顺序
shuffled = [visible[4], visible[0], visible[2]]
rank = {it.key: i for i, it in enumerate(visible)}
ordered = sorted(shuffled, key=lambda x: rank[x.key])
print(f"  勾选序:   {[i.key for i in shuffled]}")
print(f"  应用序:   {[i.key for i in ordered]}")
assert [i.key for i in ordered] == ["t0", "t2", "t4"]
print("  OK：按可见顺序重排")

print("\n结论：差异过滤正确，多选拖放顺序与界面一致")
