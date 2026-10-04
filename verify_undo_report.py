"""第十五轮验证：让撤销这道最后防线「说话」。

前面十四轮修的都是「改错文件」「丢了东西」，这一轮修的是**最后防线本身**
说不清话的问题。四条主张：

  主张 A（新功能）：撤销栈因超上限丢包，必须是**可见**的。
     丢掉的条目已经改到磁盘上了，只是从这里撤不回来 ——
      界面上必须能说出「更早的 N 步（共 M 项）已超出上限」。
      旧实现里这件事完全静默：用户数一数记录少了几条，没有任何解释。

  主张 B（真缺陷）：重做复制后，entry.created 里存的还是**上一轮**的 Uri。
     撤销是按 docUri 删的，重做会复制出一个新文档（新 Uri）。
      所以「重做 → 再撤销」这条链上删的是已经不存在的 Uri：
      删除失败 → 报"撤销失败"，而磁盘上那个新副本**永远留下来**。
      每走一轮这条路就多一个孤儿文件 —— 用户以为撤干净了。

  主张 C（文案缺陷）：重做失败弹的是「撤销失败，请手动检查」。
     用户点的是重做，报的却是撤销 —— 会让人往完全相反的方向排查。

  主张 D（半成品）：失败提示只有一句"请手动检查"，不说是哪几个文件。
     一次撤销 20 步、第 13 步失败时，等于让用户自己在那 20 个里逐个试。

跑法：python verify_undo_report.py
"""

MAX_ENTRIES = 64
MAX_TOTAL_STEPS = 20_000

problems = []


def check(label, cond):
    if cond:
        print(f"  [OK]   {label}")
    else:
        print(f"  [FAIL] {label}")
        problems.append(label)


# ---------------------------------------------------------------- 模型


class Entry:
    def __init__(self, label, steps=0, moves=0, created=None, trashed=0):
        self.label = label
        self.steps = steps
        self.moves = moves
        self.created = list(created or [])
        self.trashed = trashed

    @property
    def total(self):
        return self.steps + self.moves + len(self.created) + self.trashed

    def copy_with_created(self, created):
        return Entry(self.label, self.steps, self.moves, created, self.trashed)


class Outcome:
    def __init__(self, ok, failed=None):
        self.ok = ok
        self.failed = failed or []


class Stack:
    """逐行复刻 vm/UndoStack.kt。

    注意两条栈叫 undo_list / redo_list：`redo` 这个名字要留给方法，
    Python 里实例属性会盖掉同名方法。
    """

    def __init__(self):
        self.undo_list = []
        self.redo_list = []
        self.dropped_entries = 0
        self.dropped_steps = 0

    @property
    def undo_count(self):
        return len(self.undo_list)

    @property
    def redo_count(self):
        return len(self.redo_list)

    def push(self, entry):
        while self.undo_list and (
            len(self.undo_list) >= MAX_ENTRIES
            or sum(e.total for e in self.undo_list) + entry.total > MAX_TOTAL_STEPS
        ):
            gone = self.undo_list.pop(0)
            self.dropped_entries += 1
            self.dropped_steps += gone.total
        self.undo_list.append(entry)
        self.redo_list.clear()

    def take_undo(self):
        return self.undo_list.pop() if self.undo_list else None

    def peek_undo(self):
        return self.undo_list[-1] if self.undo_list else None

    def push_back(self, entry):
        self.undo_list.append(entry)

    def moved_to_redo(self, entry):
        self.redo_list.append(entry)

    def take_redo(self):
        return self.redo_list.pop() if self.redo_list else None

    def push_back_redo(self, entry):
        self.redo_list.append(entry)

    def moved_to_undo(self, entry):
        self.undo_list.append(entry)

    def labels(self):
        return [(i, e.label) for i, e in enumerate(self.undo_list)][::-1]

    def clear(self):
        self.undo_list.clear()
        self.redo_list.clear()
        self.dropped_entries = 0
        self.dropped_steps = 0

    # ---- 撤销 / 重做（只保留与本轮有关的 created 分支） ----

    def undo_one(self, fs, fail_at=None):
        entry = self.take_undo()
        if entry is None:
            return Outcome(False)
        ok = True
        failed = []
        if fail_at == "rename":
            ok = False
            failed.append("IMG_0042.jpg")
        for c in reversed(entry.created):
            if not fs.delete(c["doc_uri"]):
                ok = False
                failed.append(c["name"])
        if ok:
            self.moved_to_redo(entry)
        else:
            self.push_back(entry)
        return Outcome(ok, failed)

    def redo(self, fs, new_uri_capture):
        """new_uri_capture=False 复刻旧实现（丢弃 copyTo 的返回值）。"""
        entry = self.take_redo()
        if entry is None:
            return None
        ok = True
        produced = []
        for c in entry.created:
            nf = fs.copy_to(c["source_uri"], c["target_tree"])
            if nf is None:
                ok = False
            else:
                produced.append({
                    "doc_uri": nf,
                    "source_uri": c["source_uri"],
                    "target_tree": c["target_tree"],
                    "name": c["name"],
                })
        if new_uri_capture and produced and len(produced) == len(entry.created):
            entry = entry.copy_with_created(produced)
        if ok:
            self.moved_to_undo(entry)
        else:
            self.push_back_redo(entry)
        return entry

    def undo_until(self, index, fs, fail_at_round=None):
        """fail_at_round：让第 N 次（0 起）撤销调用失败，用于模拟"撤了几步后卡住"。"""
        n = 0
        failed = None
        while self.undo_count > index:
            outcome = self.undo_one(
                fs, fail_at=("rename" if n == fail_at_round else None)
            )
            if not outcome.ok:
                failed = outcome.failed
                break
            n += 1
        return n, failed


class Fs:
    """假文件系统：只关心「哪些文档 Uri 存在」。"""

    def __init__(self):
        self.docs = set()
        self.n = 0

    def copy_to(self, src, tree):
        self.n += 1
        uri = f"doc{self.n}"
        self.docs.add(uri)
        return uri

    def delete(self, uri):
        if uri in self.docs:
            self.docs.remove(uri)
            return True
        return False


def failure_msg(plain, count_fmt, failed):
    """复刻 MainViewModel.failureMsg（v5.15.0 起带原因）。

    failed 的元素是 (名字, 原因) 元组；裸字符串仍被接受（视为 FAILED），
    这样 C 组的旧断言继续测「无原因时维持原样」这条兼容路径。
    """
    items = [(f, "failed") if isinstance(f, str) else f for f in failed]
    if not items:
        return plain
    head = "、".join(n for n, _ in items[:3])
    ellipsis = " 等" if len(items) > 3 else ""
    return count_fmt.format(len(items), head + ellipsis) + reason_notes(items)


# 复刻 strings.xml 的两条原因文案
fmt_why_occupied = "其中 {0} 个名字已被占用（未改动，把同名文件移走后可再试一次撤销）：{1}"
fmt_why_missing = "其中 {0} 个文件已不存在（可能被移走或删除）：{1}"


def _head_of(names):
    head = "、".join(names[:3])
    return head + (" 等" if len(names) > 3 else "")


def reason_notes(items):
    """复刻 MainViewModel.reasonNotes：按原因分组各附一句，可能为空串。"""
    occupied = [n for n, w in items if w == "occupied"]
    missing = [n for n, w in items if w == "missing"]
    if not occupied and not missing:
        return ""
    out = []
    if occupied:
        out.append(fmt_why_occupied.format(len(occupied), _head_of(occupied)))
    if missing:
        out.append(fmt_why_missing.format(len(missing), _head_of(missing)))
    return "。" + "；".join(out)


def base_name_of(p):
    """复刻 baseNameOf：两种分隔符都要认。"""
    return p.split("/")[-1].split("\\")[-1]


# ---------------------------------------------------------------- A. 丢包可见

print("=" * 64)
print("A. 撤销栈超上限丢包，必须留下痕迹")
print("=" * 64)

s = Stack()
for i in range(65):
    s.push(Entry(f"操作{i}", steps=1))
check("A1 条目数上限仍然生效（栈里 64 条）", s.undo_count == MAX_ENTRIES)
check("A2 丢了 1 条", s.dropped_entries == 1)
check("A3 丢掉的是最旧那条的步数（共 1 项）", s.dropped_steps == 1)
s.push(Entry("再来一条", steps=1))
check("A4 丢包计数是累计的（继上一次的 1 条，现在 2 条）", s.dropped_entries == 2)

s2 = Stack()
s2.push(Entry("小操作", steps=5))
s2.push(Entry("小操作", steps=5))
check("A5 没超限时计数为 0（不能虚报）", s2.dropped_entries == 0 and s2.dropped_steps == 0)

# 单条就超总上限：必须丢掉全部旧的、把自己放进去（超大操作仍可撤销）
s3 = Stack()
for i in range(4):
    s3.push(Entry(f"旧的{i}", steps=5_000))
before = s3.undo_count
s3.push(Entry("巨无霸", steps=25_000))
check("A6 单条超过总上限时，旧的被清掉、自己留下（超大操作仍可撤销）",
      s3.undo_count == 1 and s3.undo_list[0].label == "巨无霸")
check(f"A7 被清掉的 {before} 条全部记进了丢包统计",
      s3.dropped_entries == before and s3.dropped_steps == before * 5_000)
check("A7b 丢包统计的步数等于那些条目的实际总量（不是条目数）",
      s3.dropped_steps == 20_000)

# 步数上限（非条目上限）
s4 = Stack()
s4.push(Entry("A", steps=15_000))
s4.push(Entry("B", steps=15_000))
check("A8 总步数超限时丢最旧的（保留 1 条）",
      s4.undo_count == 1 and s4.undo_list[0].label == "B")
check("A9 丢的是 15000 步", s4.dropped_steps == 15_000)

# 边界：恰好等于上限不丢
s5 = Stack()
s5.push(Entry("A", steps=10_000))
s5.push(Entry("B", steps=10_000))
check("A10 恰好等于总上限（20000）不丢任何东西",
      s5.undo_count == 2 and s5.dropped_entries == 0)

# clear 重置计数
s5.clear()
check("A11 clear() 把丢包计数一并归零（栈都空了，再提旧事只会更困惑）",
      s5.dropped_entries == 0 and s5.dropped_steps == 0)

# redo / undo 往返不产生丢包
s6 = Stack()
s6.push(Entry("往返", created=[{"doc_uri": "x", "source_uri": "s",
                                "target_tree": "t", "name": "copy.jpg"}]))
fs6 = Fs()
fs6.docs.add("x")
s6.undo_one(fs6)
s6.redo(fs6, new_uri_capture=True)
check("A12 撤销 / 重做往返不产生丢包统计（只有 push 才记账）",
      s6.dropped_entries == 0 and s6.dropped_steps == 0)

print()
print("=" * 64)
print("B. 重做复制后的 Uri 必须被写回（孤儿文件）")
print("=" * 64)

# --- 旧实现：丢弃 copyTo 的返回值 ---
old = Stack()
old.push(Entry("复制 3 张", created=[
    {"doc_uri": "old1", "source_uri": "s1", "target_tree": "t", "name": "a.jpg"},
    {"doc_uri": "old2", "source_uri": "s2", "target_tree": "t", "name": "b.jpg"},
    {"doc_uri": "old3", "source_uri": "s3", "target_tree": "t", "name": "c.jpg"},
]))
fs_old = Fs()
fs_old.docs.update({"old1", "old2", "old3"})
old.undo_one(fs_old)
check("B1 旧：撤销后副本被删干净", fs_old.docs == set())
old.redo(fs_old, new_uri_capture=False)
after_redo_old = set(fs_old.docs)
check("B2 旧：重做后磁盘上有 3 个新副本", len(after_redo_old) == 3)
old.undo_one(fs_old)
check("B3 旧：**再撤销失败** —— entry 里还是上一轮的 old1/2/3，删不掉",
      fs_old.docs == after_redo_old and len(fs_old.docs) == 3)
check("B4 旧：于是留下 3 个孤儿文件（每走一轮就多 3 个）", len(fs_old.docs) == 3)

# --- 新实现：写回新 Uri ---
new = Stack()
new.push(Entry("复制 3 张", created=[
    {"doc_uri": "old1", "source_uri": "s1", "target_tree": "t", "name": "a.jpg"},
    {"doc_uri": "old2", "source_uri": "s2", "target_tree": "t", "name": "b.jpg"},
    {"doc_uri": "old3", "source_uri": "s3", "target_tree": "t", "name": "c.jpg"},
]))
fs_new = Fs()
fs_new.docs.update({"old1", "old2", "old3"})
new.undo_one(fs_new)
new.redo(fs_new, new_uri_capture=True)
check("B5 新：重做后同样是 3 个新副本", len(fs_new.docs) == 3)
out = new.undo_one(fs_new)
check("B6 新：再撤销**成功**（删的是重做产生的新 Uri）", out.ok)
check("B7 新：磁盘干净，零孤儿", fs_new.docs == set())

# 同一对 entry 反复往返：旧实现第一轮就留下孤儿，而且此后**撤销永久失败**
# （删不掉 -> 那条被 pushBack 回 undo 栈 -> redo 栈空 -> 链卡死）。
# 这比"每轮多一个孤儿"更糟：用户每次点撤销都被告知失败，但文件其实还在。
old2 = Stack()
old2.push(Entry("往返", created=[
    {"doc_uri": "o1", "source_uri": "s1", "target_tree": "t", "name": "a.jpg"}]))
fs_o = Fs()
fs_o.docs.add("o1")
ok_history = []
for _ in range(4):
    ok_history.append(old2.undo_one(fs_o).ok)
    old2.redo(fs_o, new_uri_capture=False)
final = old2.undo_one(fs_o)
check("B8 旧：首轮往返留下 1 个孤儿", len(fs_o.docs) == 1)
check("B8b 旧：此后撤销**永久失败**（链路卡死，比孤儿更糟，且会一直骗用户）",
      ok_history[0] is True and not any(ok_history[1:]) and not final.ok)

new2 = Stack()
new2.push(Entry("往返", created=[
    {"doc_uri": "o1", "source_uri": "s1", "target_tree": "t", "name": "a.jpg"}]))
fs_n = Fs()
fs_n.docs.add("o1")
ok_history_n = []
for _ in range(4):
    ok_history_n.append(new2.undo_one(fs_n).ok)
    new2.redo(fs_n, new_uri_capture=True)
last_ok = new2.undo_one(fs_n).ok
check("B9 新：4 轮往返里每一步撤销都成功（链不会卡死）", all(ok_history_n) and last_ok)
check("B9b 新：最终磁盘干净，零孤儿", fs_n.docs == set())


# 部分成功时不覆盖（长度对不上，硬塞会让没重做的那条指向不存在的文件）
class HalfFs(Fs):
    def __init__(self):
        super().__init__()
        self.calls = 0

    def copy_to(self, src, tree):
        self.calls += 1
        if self.calls == 2:
            return None
        return super().copy_to(src, tree)


partial = Stack()
partial.push(Entry("复制 2 张", created=[
    {"doc_uri": "p1", "source_uri": "s1", "target_tree": "t", "name": "a.jpg"},
    {"doc_uri": "p2", "source_uri": "s2", "target_tree": "t", "name": "b.jpg"},
]))
half = HalfFs()
half.docs.update({"p1", "p2"})
partial.undo_one(half)
kept = partial.redo(half, new_uri_capture=True)
check("B10 部分成功时不覆盖 created（否则长度对不上）",
      kept is not None and [c["doc_uri"] for c in kept.created] == ["p1", "p2"])
check("B11 失败的 entry 回到重做栈（还能再试一次）", partial.redo_count == 1)

print()
print("=" * 64)
print("C. 失败文案：说清是哪几个文件")
print("=" * 64)

plain_undo = "撤销失败，请手动检查"
f_undo = "撤销失败：{0} 个文件没撤回来（{1}）"

check("C1 清单为空时退回通用文案（不弹「0 个文件」）",
      failure_msg(plain_undo, f_undo, []) == plain_undo)
check("C2 1 个失败：带上名字",
      failure_msg(plain_undo, f_undo, ["IMG_0042.jpg"])
      == "撤销失败：1 个文件没撤回来（IMG_0042.jpg）")
check("C3 3 个失败：全部列出，不加「等」",
      failure_msg(plain_undo, f_undo, ["a.jpg", "b.jpg", "c.jpg"])
      == "撤销失败：3 个文件没撤回来（a.jpg、b.jpg、c.jpg）")
check("C4 5 个失败：只列前 3 个 + 「等」，但数量报全",
      failure_msg(plain_undo, f_undo, ["a", "b", "c", "d", "e"])
      == "撤销失败：5 个文件没撤回来（a、b、c 等）")

# 重做失败必须用自己的文案（旧实现借用「撤销失败」）
f_redo = "重做失败：{0} 个文件没处理成功（{1}）"
check("C5 重做失败文案与撤销失败**不同**（点重做却报撤销会误导排查方向）",
      failure_msg("重做失败，请手动检查", f_redo, ["x.jpg"])
      != failure_msg(plain_undo, f_undo, ["x.jpg"]))
check("C6 重做失败文案自身正确",
      failure_msg("重做失败，请手动检查", f_redo, ["x.jpg"])
      == "重做失败：1 个文件没处理成功（x.jpg）")

# ---- C 扩展（v5.15.0）：失败原因必须能区分 ----
#
# 「名字被占」的用户有明确动作（把同名文件移走，再点一次撤销）；
# 「文件不见了」的没有。两种混在一句"没撤回来"里，用户不知道该处理哪个。
# Organzier.moveFile / moveBack 因此从 Int(1/0) 换成 MoveOutcome 四值枚举，
# UndoOutcome.failed 从 List<String> 换成 (name, why)。

print()
print("  C 扩展（v5.15.0）：失败原因必须能区分")

# 反例先行：旧版（无原因）根本装不下原因 —— 同一个文件「被占」与「不见了」
# 两种现实只能映射到同一句话，用户无从分辨该做哪个动作
old_style = lambda names: f_undo.format(len(names), "、".join(names[:3]))
assert old_style(["a.jpg"]) == "撤销失败：1 个文件没撤回来（a.jpg）"
check("C7 反例：旧版输出装不下原因（被占 / 不见了都只有一句话）",
      "占用" not in old_style(["a.jpg"]) and "不存在" not in old_style(["a.jpg"]))
check("C7b 新版：同名文件、不同原因 → 文案不同（信息缺口补上了）",
      failure_msg(plain_undo, f_undo, [("a.jpg", "occupied")])
      != failure_msg(plain_undo, f_undo, [("a.jpg", "missing")]))

check("C8 被占的失败带原因与**用户动作**（移走后可再试）",
      "名字已被占用" in failure_msg(plain_undo, f_undo, [("a.jpg", "occupied")])
      and "可再试一次撤销" in failure_msg(plain_undo, f_undo, [("a.jpg", "occupied")])
      and "a.jpg" in failure_msg(plain_undo, f_undo, [("a.jpg", "occupied")]))
check("C9 不见了的失败带原因（可能被移走或删除）",
      "已不存在" in failure_msg(plain_undo, f_undo, [("b.jpg", "missing")])
      and "b.jpg" in failure_msg(plain_undo, f_undo, [("b.jpg", "missing")]))
msg_mix = failure_msg(plain_undo, f_undo, [("a.jpg", "occupied"), ("b.jpg", "missing")])
check("C10 混合原因：两句都在，用「；」分开",
      "名字已被占用" in msg_mix and "已不存在" in msg_mix and msg_mix.count("；") >= 1)
check("C10b 各段带各的名字（被占段含 a、不见段含 b）",
      "a.jpg" in msg_mix.split("；")[0] and "b.jpg" in msg_mix.split("；")[1])
check("C11 5 个被占：数量报全、只列前 3 + 「等」（与主清单同一条纪律）",
      (m := failure_msg(plain_undo, f_undo, [(f"o{i}.jpg", "occupied") for i in range(5)]))
      and "5 个名字已被占用" in m and "o0.jpg、o1.jpg、o2.jpg 等" in m)
check("C12 FAILED 为主时**不**追加原因句（不硬造解释）",
      failure_msg(plain_undo, f_undo, ["x.jpg"])
      == "撤销失败：1 个文件没撤回来（x.jpg）")
check("C13 FAILED 混少量被占：仍只对被占的说原因",
      (m13 := failure_msg(plain_undo, f_undo, ["x.jpg", ("a.jpg", "occupied")]))
      and "名字已被占用" in m13 and "已不存在" not in m13)
check("C14 undoUntil 的中途失败也带原因（同一 reasonNotes 拼装）",
      "名字已被占用" in (
          "已撤销 3 项，之后 2 个文件没撤回来（a.jpg、b.jpg）" + reason_notes(
              [("a.jpg", "occupied"), ("b.jpg", "occupied")])))

print()
print("=" * 64)
print("D. 撤销到某一步：中途失败要说清撤了几步")
print("=" * 64)

st = Stack()
for i in range(5):
    st.push(Entry(f"操作{i}", steps=1))
fsd = Fs()
n, failed = st.undo_until(0, fsd)
check("D1 全部成功：撤了 5 步、没有失败", n == 5 and failed is None)
check("D2 栈清空到 index=0", st.undo_count == 0)

st2 = Stack()
for i in range(5):
    st2.push(Entry(f"操作{i}", steps=1))
st2.undo_one(Fs())  # 先撤掉一条，让栈变成 4 条（操作0..操作3）
n2, failed2 = st2.undo_until(0, Fs(), fail_at_round=1)
check("D3 中途失败：报告**已成功撤掉**的步数（1 步）", n2 == 1)
check("D4 中途失败：带出失败的文件名", failed2 == ["IMG_0042.jpg"])
check("D5 中途失败后停下（不会硬跑完剩下的）：4 - 1 成功 = 3 条留在栈里",
      st2.undo_count == 3)
check("D6 失败的那一条被放回栈（可以重试），且就是它卡住的",
      st2.undo_list[-1].label == "操作2")

# labels 语义：最新在前，index 是栈内下标
st3 = Stack()
for name in ["A", "B", "C"]:
    st3.push(Entry(name, steps=1))
check("D7 labels 最新在前、带栈内下标", st3.labels() == [(2, "C"), (1, "B"), (0, "A")])
st3.undo_until(1, Fs())
# 对话框 KDoc 写的是「点一条会一次性回退到那一步**之前**」。
# index=1 是 B，回到 B 之前 => B 和 C 都被撤掉，只剩 A。
# 所以实现里的 `while (undoCount > index)` 与文案是一致的 ——
# 如果哪天有人"顺手"改成 `index + 1`，就会变成"保留 B、只撤 C"，与文案不符。
check("D8 「回到这里(index=1)」= 撤销到 B **之前**，只剩 A（与界面文案一致）",
      [e.label for e in st3.undo_list] == ["A"])

st4 = Stack()
for name in ["A", "B", "C"]:
    st4.push(Entry(name, steps=1))
st4.undo_until(2, Fs())
check("D9 点最新那条(index=2)= 只撤最后一步，剩 A、B",
      [e.label for e in st4.undo_list] == ["A", "B"])

print()
print("=" * 64)
print("E. 文件名提取（失败清单要能拿去搜索）")
print("=" * 64)

check("E1 Windows 反斜杠路径", base_name_of(r"C:\Users\x\DCIM\IMG_0001.jpg") == "IMG_0001.jpg")
check("E2 SAF 正斜杠路径", base_name_of("/storage/emulated/0/Pictures/IMG_0002.jpg") == "IMG_0002.jpg")
check("E3 纯文件名", base_name_of("IMG_0003.jpg") == "IMG_0003.jpg")
check("E4 混合分隔符", base_name_of("a/b\\c/d.jpg") == "d.jpg")

print()
print("=" * 64)
print("F. 换目录必须让撤销栈失效（否则改到别的目录去）")
print("=" * 64)


class Docs:
    """假 SAF：rename 按 Uri 定位，找不到时才回退到按名字找。"""

    def __init__(self, trees):
        self.trees = trees          # {tree: {uri: name}}
        self.dead = set()           # 授权已失效的 Uri

    def rename(self, uri, new_name):
        if uri in self.dead:
            return False
        for m in self.trees.values():
            if uri in m:
                m[uri] = new_name
                return True
        return False

    def find_by_name(self, tree, name):
        for uri, n in self.trees.get(tree, {}).items():
            if n == name:
                return uri
        return None


class Vm:
    """只复刻与本组有关的：load() 里的换目录判定 + 撤销。"""

    def __init__(self):
        self.stack = Stack()
        self.tree = {"LEFT": None, "RIGHT": None}
        self.msgs = []

    def load(self, side, uri):
        previous = self.tree[side]
        if previous is not None and previous != uri:
            self.reset_undo_for_folder_change()
        self.tree[side] = uri

    def reset_undo_for_folder_change(self):
        n = self.stack.undo_count
        had_redo = self.stack.redo_count > 0
        if n == 0 and not had_redo:
            return
        self.stack.clear()
        if n > 0:
            self.msgs.append(f"换了目录，之前 {n} 步操作不能在新目录里撤销")
        else:
            self.msgs.append("换了目录，之前撤销的操作不能在这里重做了")

    def undo_step(self, docs, uri, prev_name, new_name, tree):
        """复刻 undoOne 里单步改名的分支（含按名字回退）。"""
        done = docs.rename(uri, prev_name)
        if done is None or done is False:
            found = docs.find_by_name(tree, new_name)
            if found:
                return docs.rename(found, prev_name)
        return done


# --- F1~F3 判定条件 ---
vm = Vm()
vm.stack.push(Entry("在 A 目录改的名", steps=1))
vm.load("LEFT", "treeA")
vm.load("LEFT", "treeA")            # 同目录刷新
check("F1 刷新**同一个**目录不清空（Uri 没变，撤销依然有效）", vm.stack.undo_count == 1)
check("F1b 也没弹提示", vm.msgs == [])

vm.load("LEFT", "treeB")            # 换目录
check("F2 换目录清空撤销栈", vm.stack.undo_count == 0)
check("F2b 而且明确告诉用户清了几步（静默清空 = 用户不知道为什么按钮空了）",
      vm.msgs == ["换了目录，之前 1 步操作不能在新目录里撤销"])

vm2 = Vm()
vm2.load("LEFT", "treeA")           # 首次加载（null -> treeA）
check("F3 首次加载（previous 为 null）不清空 —— 启动恢复不该把栈拍掉",
      vm2.stack.undo_count == 0 and vm2.msgs == [])

# --- F4 只有重做栈时换目录 ---
vm3 = Vm()
vm3.load("LEFT", "treeA")           # 先有"上一个目录"，否则 previous 是 null 不构成"换"
vm3.stack.push(Entry("x", steps=1))
vm3.stack.undo_one(Fs())
vm3.stack.undo_one(Fs())            # 撤到没东西可撤
check("F4 前置：撤销栈空、重做栈有 1 条",
      vm3.stack.undo_count == 0 and vm3.stack.redo_count == 1)
vm3.load("LEFT", "treeB")
check("F4b 只有重做栈时换目录也清（重做同样按 Uri 定位）", vm3.stack.redo_count == 0)
check("F4c 用的是重做版文案（不说「0 步操作」）",
      vm3.msgs == ["换了目录，之前撤销的操作不能在这里重做了"])

# --- F5 清空时丢包计数一起归零 ---
vm4 = Vm()
for i in range(MAX_ENTRIES + 2):
    vm4.stack.push(Entry(f"op{i}", steps=1))
check("F5 前置：已经产生丢包统计", vm4.stack.dropped_entries == 2)
vm4.load("LEFT", "treeA")
vm4.load("LEFT", "treeB")
check("F5b 换目录后丢包统计也归零（新目录 = 新的一本账）",
      vm4.stack.dropped_entries == 0 and vm4.stack.dropped_steps == 0)

# --- F6 任一边换目录都要清 ---
vm5 = Vm()
vm5.load("LEFT", "treeA")
vm5.load("RIGHT", "treeC")
vm5.stack.push(Entry("a", steps=1))
vm5.load("RIGHT", "treeD")
check("F6 右栏换目录同样清空（栈是左右共用的）", vm5.stack.undo_count == 0)

# --- F7 承重证明：没有这道闸，撤销会改到**另一个目录**的同名文件 ---
# 场景：在 A 目录把 IMG_0001.jpg 改名为 旅行.jpg；随后切到 B 目录。
# B 目录里恰好也有一个叫 旅行.jpg 的文件（无关）。
# 此时点撤销：
#   · 旧 Uri 已失效（换目录后授权被回收）→ 代码回退到"在 B 里按名字找 旅行.jpg"
#   · 找到 B 里那个无关文件 → 把它改名成 IMG_0001.jpg
# 界面显示"已撤销"，实际改错了一个毫无关系的文件。
trees = {
    "treeA": {"uA": "旅行.jpg"},
    "treeB": {"uB": "旅行.jpg"},
}
docs = Docs(trees)
docs.dead.add("uA")

# 无护栏：直接撤（栈没清）
guard_off = Vm()
guard_off.tree["LEFT"] = "treeB"
guard_off.undo_step(docs, "uA", "IMG_0001.jpg", "旅行.jpg", "treeB")
check("F7 无护栏时：B 目录里那个无关文件**被改错**（这正是要防的）",
      trees["treeB"]["uB"] == "IMG_0001.jpg")

# 有护栏：换目录时栈已清，所以根本没得撤
trees2 = {
    "treeA": {"uA": "旅行.jpg"},
    "treeB": {"uB": "旅行.jpg"},
}
docs2 = Docs(trees2)
docs2.dead.add("uA")
guard_on = Vm()
guard_on.load("LEFT", "treeA")
guard_on.stack.push(Entry("A 目录改名", steps=1))
guard_on.load("LEFT", "treeB")          # 换目录 -> 清栈
check("F7b 有护栏时：栈已清空，没有可撤的记录",
      guard_on.stack.undo_count == 0 and guard_on.stack.take_undo() is None)
check("F7c 有护栏时：B 目录的文件**完好无损**", trees2["treeB"]["uB"] == "旅行.jpg")

print()
print("=" * 64)
print("G. 纵深防御：记录自己钉住目录（守卫失效也改不错）")
print("=" * 64)


class Step:
    """复刻 model/ImageItem.kt 的 RenameStep（含新增的 tree_uri）。"""

    def __init__(self, doc_uri, previous_name, new_name, side, tree_uri=None):
        self.doc_uri = doc_uri
        self.previous_name = previous_name
        self.new_name = new_name
        self.side = side
        self.tree_uri = tree_uri


EMPTY = "__uri_empty__"


def tree_for(step, pane_uri):
    """复刻 MainViewModel.treeFor：先看记录，再回退当前目录。"""
    return step.tree_uri if step.tree_uri is not None else pane_uri(step.side)


def stamp(steps, pane_uri):
    """复刻 pushUndo 里把"出身"钉进记录的这一步。"""
    out = []
    for st in steps:
        if st.tree_uri is not None:
            out.append(st)
        else:
            t = pane_uri(st.side)
            out.append(Step(st.doc_uri, st.previous_name, st.new_name, st.side,
                            None if t == EMPTY else t))
    return out


def undo_rename_step(docs, step, tree):
    """复刻 undoOne 里单步改名的分支（含按名字回退）。"""
    done = docs.rename(step.doc_uri, step.previous_name)
    if not done:
        found = docs.find_by_name(tree, step.new_name)
        if found:
            return docs.rename(found, step.previous_name)
    return done


# --- G1~G4 改名定位：旧 Uri 失效时，tree_uri 决定改谁 ---
def scenario(with_tree_uri):
    trees = {
        "treeA": {"uA": "旅行.jpg"},   # A 目录：这一步改出来的名字
        "treeB": {"uB": "旅行.jpg"},   # B 目录：无关的同名文件
    }
    docs = Docs(trees)
    docs.dead.add("uA")                # 换目录后旧授权被回收
    step = Step("uA", "IMG_0001.jpg", "旅行.jpg", "LEFT",
                "treeA" if with_tree_uri else None)
    pane = lambda side: "treeB"        # 界面此刻停在 B 目录（守卫失效，栈没清）
    tree = tree_for(step, pane)
    ok = undo_rename_step(docs, step, tree)
    return trees, ok


t_bad, ok_bad = scenario(with_tree_uri=False)
check("G1 没有 tree_uri（老记录）：回退到当前目录 → **改错** B 里那个无关文件",
      t_bad["treeB"]["uB"] == "IMG_0001.jpg" and ok_bad is True)
check("G2 这就是上一轮 F7 的缺陷复现（只是这次靠 tree_uri 而非清空来兜）", ok_bad is True)

t_good, ok_good = scenario(with_tree_uri=True)
check("G3 有 tree_uri：去 A 目录找 → 找到的是那个失效 Uri → **撤不动**（安全失败）",
      ok_good is False)
check("G4 关键：B 目录的文件**原封不动**（宁可撤不动，绝不改错）",
      t_good["treeB"]["uB"] == "旅行.jpg")
check("G4b A 目录也没被误改（仍是 undo 前的状态）", t_good["treeA"]["uA"] == "旅行.jpg")

# --- G5 旧授权还在时，两条路都改对（tree_uri 不影响这条） ---
t5 = {"treeA": {"uA": "旅行.jpg"}, "treeB": {"uB": "旅行.jpg"}}
d5 = Docs(t5)                          # 不把 uA 标为失效
s5 = Step("uA", "IMG_0001.jpg", "旅行.jpg", "LEFT", "treeA")
check("G5 旧授权仍在：按 Uri 直接改对（tree_uri 只在回退时才起作用）",
      undo_rename_step(d5, s5, tree_for(s5, lambda side: "treeB")) is True
      and t5["treeA"]["uA"] == "IMG_0001.jpg"
      and t5["treeB"]["uB"] == "旅行.jpg")


# --- G6~G9 缓存迁移：树 + 名字才是键 ---
class Meta:
    """复刻 MetaCache 的 key(tree, name) + migrate。"""

    def __init__(self):
        self.exif = {}

    def key(self, tree, name):
        return f"{tree}::{name}"

    def migrate(self, steps, pane_uri):
        for st in steps:
            tree = st.tree_uri if st.tree_uri is not None else pane_uri(st.side)
            if tree is None:
                continue
            old_key, new_key = self.key(tree, st.previous_name), self.key(tree, st.new_name)
            if old_key == new_key:
                continue
            if old_key in self.exif:
                self.exif[new_key] = self.exif.pop(old_key)


mk = Meta()
mk.exif[mk.key("treeA", "IMG_0001.jpg")] = 1700000000000   # 拍摄时间，挂在 A 目录下
step_ok = Step("uA", "IMG_0001.jpg", "旅行.jpg", "LEFT", "treeA")
mk.migrate([step_ok], lambda side: "treeB")
check("G6 有 tree_uri：缓存从「A::IMG_0001」迁到「A::旅行」",
      mk.key("treeA", "旅行.jpg") in mk.exif and mk.key("treeA", "IMG_0001.jpg") not in mk.exif)

# 老记录：没有 tree_uri → 用当前目录 B 算键 → 查不到 → **静默什么都不做**
mk2 = Meta()
mk2.exif[mk2.key("treeA", "IMG_0001.jpg")] = 1700000000000
step_old = Step("uA", "IMG_0001.jpg", "旅行.jpg", "LEFT", None)
mk2.migrate([step_old], lambda side: "treeB")
check("G7 没有 tree_uri：oldKey 落在 B 的命名空间里，查不到 → 什么都不迁移",
      mk2.exif == {mk2.key("treeA", "IMG_0001.jpg"): 1700000000000})
check("G8 G7 是**静默**的：没有任何异常，缓存只是悄悄没跟上（撤销后配对会退回推算）",
      mk2.key("treeA", "旅行.jpg") not in mk2.exif)
# 对照：B 目录里恰好也有同名文件、且缓存里有它时，会**把它的缓存当作自己的搬走**
mk3 = Meta()
mk3.exif[mk3.key("treeB", "IMG_0001.jpg")] = 999   # 这是 B 目录那个无关文件的缓存
mk3.migrate([step_old], lambda side: "treeB")
check("G9 对照：不是「漏」，而是「张冠李戴」—— 把 B 里同名文件的缓存搬到 B::旅行 名下",
      mk3.key("treeB", "旅行.jpg") in mk3.exif
      and mk3.key("treeB", "IMG_0001.jpg") not in mk3.exif)

# --- G10~G12 pushUndo 的回填规则 ---
paneAB = lambda side: "treeA" if side == "LEFT" else "treeB"
stamped = stamp([Step("u1", "a", "b", "LEFT"), Step("u2", "c", "d", "RIGHT")], paneAB)
check("G10 无 tree_uri 时按 side 回填当前目录",
      stamped[0].tree_uri == "treeA" and stamped[1].tree_uri == "treeB")

already = Step("u1", "a", "b", "LEFT", "treeOld")
stamped2 = stamp([already], paneAB)
check("G11 已有 tree_uri 的原样保留（幂等：重复 push 不会把出身改成现在）",
      stamped2[0].tree_uri == "treeOld")

stamped3 = stamp([Step("u1", "a", "b", "LEFT")], lambda side: EMPTY)
check("G12 那一栏还没选目录（Uri.EMPTY）→ 保持 null，不假装知道",
      stamped3[0].tree_uri is None)

print()
print("=" * 64)
if problems:
    print(f"发现问题 {len(problems)} 处：")
    for p in problems:
        print(f"  - {p}")
    raise SystemExit(1)
print("全部通过：丢包可见、重做链不再留孤儿、失败能说清是哪几个文件、")
print("换目录后撤销栈及时失效；且记录自己钉住了目录（守卫失效也只会撤不动，绝不改错）。")
