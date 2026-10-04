"""验证抽出来的 UndoStack 类行为，尤其是几条不变量。

重点验证：
1. push 会清空 redo（常规撤销语义）
2. 单条超大操作依然可撤销（不能因为超限就把自己弹掉）
3. 撤销失败要能塞回去
4. flipSides 交换两次要复原
5. 双重上限真的生效（防止内存被吃光）
"""

MAX_ENTRIES, MAX_TOTAL = 64, 20_000


class Entry:
    def __init__(self, label, total, side="L"):
        self.label, self.total, self.side = label, total, side

    def copy(self, side=None):
        return Entry(self.label, self.total, side or self.side)


class UndoStack:
    def __init__(self):
        self.undo, self.redo = [], []
        # 第十五轮新增：超上限丢包要留痕（详细验证见 verify_undo_report.py）
        self.dropped_entries, self.dropped_steps = 0, 0

    @property
    def undo_count(self):
        return len(self.undo)

    @property
    def redo_count(self):
        return len(self.redo)

    def push(self, e):
        while self.undo and (
            len(self.undo) >= MAX_ENTRIES
            or sum(x.total for x in self.undo) + e.total > MAX_TOTAL
        ):
            gone = self.undo.pop(0)
            self.dropped_entries += 1
            self.dropped_steps += gone.total
        self.undo.append(e)
        self.redo.clear()

    def clear(self):
        """换目录时调用（见 MainViewModel.resetUndoForFolderChange）。"""
        self.undo.clear()
        self.redo.clear()
        self.dropped_entries = 0
        self.dropped_steps = 0

    def take_undo(self):
        return self.undo.pop() if self.undo else None

    def push_back(self, e):
        self.undo.append(e)

    def moved_to_redo(self, e):
        self.redo.append(e)

    def take_redo(self):
        return self.redo.pop() if self.redo else None

    def push_back_redo(self, e):
        self.redo.append(e)

    def moved_to_undo(self, e):
        self.undo.append(e)

    def flip_sides(self):
        f = lambda e: Entry(e.label, e.total, "R" if e.side == "L" else "L")
        self.undo = [f(e) for e in self.undo]
        self.redo = [f(e) for e in self.redo]

    def labels(self):
        return [(i, e.label) for i, e in enumerate(self.undo)][::-1]


print("=== 1. push 会清空 redo")
st = UndoStack()
st.push(Entry("a", 1))
st.moved_to_redo(st.take_undo())
print(f"  撤销一次后 redo={st.redo_count}")
assert st.redo_count == 1
st.push(Entry("b", 1))
print(f"  新操作后 redo={st.redo_count}")
assert st.redo_count == 0, "push 必须清空 redo"
print("  OK")

print("\n=== 2. 单条超大操作依然可撤销（关键取舍）")
st = UndoStack()
big = Entry("超大批量", MAX_TOTAL + 5000)   # 单条就超过总上限
st.push(big)
print(f"  压入 {big.total} 步后，可撤销数 = {st.undo_count}")
assert st.undo_count == 1, "单条超限时被自己弹掉了 —— 超大批量就撤销不了了！"
print("  OK：宁可占内存也要保住可撤销性")

print("\n=== 3. 双重上限生效")
st = UndoStack()
for i in range(200):
    st.push(Entry(f"e{i}", 1))
print(f"  压 200 条小操作 -> 保留 {st.undo_count} 条（条目上限 {MAX_ENTRIES}）")
assert st.undo_count <= MAX_ENTRIES

st2 = UndoStack()
for i in range(30):
    st2.push(Entry(f"big{i}", 1000))
total = sum(e.total for e in st2.undo)
print(f"  压 30 条×1000 步 -> 保留 {st2.undo_count} 条，共 {total} 步（上限 {MAX_TOTAL}）")
assert total <= MAX_TOTAL
print("  OK：内存不会被吃光")

print("\n=== 4. 撤销失败能塞回去")
st = UndoStack()
st.push(Entry("x", 1))
e = st.take_undo()
assert e is not None
st.push_back(e)          # 失败时放回
print(f"  失败放回后可撤销数 = {st.undo_count}")
assert st.undo_count == 1, "记录丢了，用户会以为撤过了"
print("  OK")

print("\n=== 5. flipSides 交换两次复原")
st = UndoStack()
st.push(Entry("a", 1, "L"))
st.push(Entry("b", 1, "R"))
original = [e.side for e in st.undo]
st.flip_sides()
once = [e.side for e in st.undo]
st.flip_sides()
twice = [e.side for e in st.undo]
print(f"  原始 {original} -> 翻一次 {once} -> 翻两次 {twice}")
assert once == ["R", "L"], "翻转错误"
assert twice == original, "交换两次没复原"
print("  OK")

print("\n=== 6. labels 最新的在最前")
st = UndoStack()
for n in ["第一", "第二", "第三"]:
    st.push(Entry(n, 1))
print(f"  {st.labels()}")
assert st.labels()[0][1] == "第三", "最新的应排最前"
print("  OK")

print("\n=== 7. 超上限丢包要留痕（第十五轮新增）")
st = UndoStack()
for i in range(MAX_ENTRIES + 3):
    st.push(Entry(f"op{i}", 1))
assert st.undo_count == MAX_ENTRIES
assert st.dropped_entries == 3 and st.dropped_steps == 3, "丢包必须记账"
print(f"  丢 {st.dropped_entries} 条 / {st.dropped_steps} 项（栈里 {st.undo_count} 条）")
print("  OK")

print("\n=== 8. clear() 连丢包计数一起归零（换目录 = 新的一本账）")
st.clear()
assert st.undo_count == 0 and st.redo_count == 0
assert st.dropped_entries == 0 and st.dropped_steps == 0
print("  OK")

print("\n=== 9. 撤销 / 重做的改名不覆盖同名文件")
# AUDIT-B:undo-redo —— 本节就是 audit_actions.py 里 NON_DESTRUCTIVE 白名单
# 指着的那句锚点。undo / redo / undoUntil 这三条改磁盘的路径，退路不是
# "能再撤回来"，而是**根本不会覆盖**：nameTaken 命中就报失败、一行盘都不动。
# 删掉本节，门禁会立刻把它们报成"改磁盘没退路"。
#
# 为什么必须拦：撤销 / 重做也是一次改名，而"想改回去的那个名字"可能已经
# 被别人占了（用户在文件管理器里新建了同名文件、或另一个 App 写了进来）。
# DocumentsContract.renameDocument 底下是 provider 的 File.renameTo，
# Linux 上目标存在会被静默替换 —— 点一次撤销，丢的是另一个文件，
# 提示还写着"已撤销 N 项"。


def undo_rename(fs, cur, want, guard):
    """把当前叫 cur 的文件改回 want。返回 (是否成功, 原因)。

    guard=True 复刻 MainViewModel.nameTaken + 拒绝路径；
    guard=False 复刻修复前：直接 rename，目标被占就替换。
    """
    if cur not in fs:
        return False, "missing"
    if want == cur:
        return True, "noop"          # 已经在那个名字上，空操作
    if guard and want in fs:
        return False, "occupied"     # nameTaken 命中：不覆盖
    fs[want] = fs.pop(cur)           # File.renameTo 的语义
    return True, "ok"


base = {"/DCIM/B.jpg": "用户刚改过名的那张", "/DCIM/A.jpg": "别人放进来的 A"}

fs_before = dict(base)
ok, why = undo_rename(fs_before, "/DCIM/B.jpg", "/DCIM/A.jpg", guard=False)
print(f"  修复前：ok={ok} -> /DCIM/A.jpg = {fs_before.get('/DCIM/A.jpg')!r}"
      f"（原来那个 {'已被吃掉' if fs_before.get('/DCIM/A.jpg') != base['/DCIM/A.jpg'] else '还在'}）")
assert ok and fs_before["/DCIM/A.jpg"] != base["/DCIM/A.jpg"], "没复现出覆盖"

fs_after = dict(base)
ok2, why2 = undo_rename(fs_after, "/DCIM/B.jpg", "/DCIM/A.jpg", guard=True)
print(f"  修复后：ok={ok2} 原因={why2} -> "
      f"A={fs_after.get('/DCIM/A.jpg')!r} B={fs_after.get('/DCIM/B.jpg')!r}")
assert (not ok2) and why2 == "occupied", "占位时应当拒绝"
assert fs_after["/DCIM/A.jpg"] == "别人放进来的 A", "别人的文件被覆盖了！"
assert fs_after["/DCIM/B.jpg"] == "用户刚改过名的那张", "源文件不该消失"

# 重做是同一个判据的另一半：目标名 = newName，同样不能占别人的名字
fs_redo = {"/DCIM/A.jpg": "别人放进来的 A", "/DCIM/B.jpg": "用户刚改过名的那张"}
okr, whyr = undo_rename(fs_redo, "/DCIM/B.jpg", "/DCIM/A.jpg", guard=True)
assert (not okr) and whyr == "occupied" and fs_redo["/DCIM/A.jpg"] == "别人放进来的 A"
print(f"  重做同判据：ok={okr} 原因={whyr}")

# 名字没被占时照常生效，且重复执行是空操作（幂等，撤销连点不会出错）
fs_ok = {"/DCIM/B.jpg": "用户刚改过名的那张"}
assert undo_rename(fs_ok, "/DCIM/B.jpg", "/DCIM/A.jpg", guard=True) == (True, "ok")
assert undo_rename(fs_ok, "/DCIM/A.jpg", "/DCIM/A.jpg", guard=True) == (True, "noop")
print(f"  名字空着时正常改回，重复执行 = 空操作 -> {fs_ok}")
print("  OK：撤销 / 重做宁可报失败，也不顶掉同名文件")

print("\n结论：UndoStack 不变量全部成立")
