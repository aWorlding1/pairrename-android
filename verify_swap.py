"""验证「交换左右两栏」的正确性，重点是撤销栈。

关键风险：撤销栈里每一步都记着 side。交换后 side 含义反了，
不翻转的话，撤销会把名字改到**另一栏**去 ——
又是那种「提示成功、实际改错」的错误。

沙盒无 kotlinc，用 Python 复刻状态变换做等价验证。
"""

LEFT, RIGHT = "L", "R"


class Step:
    def __init__(self, name, old, new, side):
        self.name, self.old, self.new, self.side = name, old, new, side

    def __repr__(self):
        return f"Step({self.old}->{self.new}@{self.side})"


def flip(steps):
    """复刻 flipUndoStackSides"""
    return [Step(s.name, s.old, s.new, RIGHT if s.side == LEFT else LEFT) for s in steps]


class Entry:
    """撤销栈里存的是 entry，entry.steps 才是步骤列表"""

    def __init__(self, steps):
        self.steps = steps

    def with_steps(self, steps):
        return Entry(steps)


def swap_panes(left, right, undo, redo):
    """复刻 swapPanes：交换两栏 + 翻转撤销栈里每个 entry 的 side"""
    new_undo = [e.with_steps(flip(e.steps)) for e in undo]
    new_redo = [e.with_steps(flip(e.steps)) for e in redo]
    return right, left, new_undo, new_redo


def undo_apply(steps, panes):
    """复刻撤销：把文件名按 step 改回去，只动 step.side 指定的那一栏"""
    for st in reversed(steps):
        panes[st.side] = [st.old if n == st.new else n for n in panes[st.side]]
    return panes


print("=== 场景 1：不翻转 side 会怎样（展示 bug）")
panes = {LEFT: ["L_renamed.jpg"], RIGHT: ["R1.jpg"]}
steps = [Step("x", "L_orig.jpg", "L_renamed.jpg", LEFT)]
# 用户在左栏改了名，然后交换两栏，再点撤销
swapped = {LEFT: panes[RIGHT], RIGHT: panes[LEFT]}
bad = undo_apply(steps, dict(swapped))
print(f"  交换后: 左={swapped[LEFT]} 右={swapped[RIGHT]}")
print(f"  撤销后: 左={bad[LEFT]} 右={bad[RIGHT]}")
print(f"  ^ 文件现在在右栏，却去改左栏 —— 撤销没生效（静默错误）")

print("\n=== 场景 2：翻转 side（修复后）")
good = undo_apply(flip(steps), dict(swapped))
print(f"  撤销后: 左={good[LEFT]} 右={good[RIGHT]}")
assert good[RIGHT] == ["L_orig.jpg"], "撤销没改到正确的栏！"
assert good[LEFT] == ["R1.jpg"], "不该动左栏！"
print("  OK：撤销正确作用于文件所在的那一栏")

print("\n=== 场景 3：交换两次应回到原样（对合性）")
undo0 = [Entry([Step("a", "a1", "a2", LEFT), Step("b", "b1", "b2", RIGHT)])]
redo0 = [Entry([Step("c", "c1", "c2", LEFT)])]
l, r = ["L1"], ["R1"]
l2, r2, u2, rd2 = swap_panes(l, r, undo0, redo0)
l3, r3, u3, rd3 = swap_panes(l2, r2, u2, rd2)
print(f"  交换两次后 左={l3} 右={r3}")
assert (l3, r3) == (l, r)
sides_ok = all(
    a.side == c.side
    for a, c in zip(undo0[0].steps, u3[0].steps)
) and all(a.side == c.side for a, c in zip(redo0[0].steps, rd3[0].steps))
print(f"  撤销栈 side 也回到原样: {sides_ok}")
assert sides_ok, "翻转两次后 side 不一致！"
print("  OK：交换两次完全复原")

print("\n=== 场景 4：混左右的多步操作")
steps = [Step("1", "x1", "x2", LEFT), Step("2", "y1", "y2", RIGHT)]
f = flip(steps)
print(f"  翻转前: {[s.side for s in steps]}")
print(f"  翻转后: {[s.side for s in f]}")
assert [s.side for s in f] == [RIGHT, LEFT]
# 名字不应被改动，只有 side 变
assert [s.old for s in f] == ["x1", "y1"] and [s.new for s in f] == ["x2", "y2"]
print("  OK：只翻 side，文件名原样保留")

print("\n结论：交换两栏后撤销仍作用于正确的栏")
