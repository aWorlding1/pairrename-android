"""把「撤销栈一行字」的两条纪律钉成可机械复跑的门禁。

判据 D5 原本只写在 README 的人工验收清单里（「撤销列表里的条目名要和刚才那个
操作对得上」）。人工清单的问题在于：它依赖验收的人主观判断「对得上」。本轮把它
机械化，顺带钉住判据 D1 的同义词收敛。

一、为什么 label 必须自描述
    · 撤销列表（UndoListDialog）一行只渲染 label，不渲染数量；
    · 改名历史（HistoryDialog）主文字是 label，旁边另起一列写「N 项 · 时间」。
    所以 label = "3 项" 时，撤销列表显示「3 项」（看不出干了什么），历史列表
    显示「3 项」+「3 项 · 12:30」（同一个计数说两遍）。
    → 症状是「重复」，病根是 label 没有承担「说出动作」的职责。

二、为什么 label 里不能再出现「项」
    列表已经用「N 项」表示规模。label 再带「项」就与旁边的计数重复，
    且「还原 N 项」这种写法仍然没说自己是从哪还原的（对照表？回收站？）。

三、为什么静态文案不许硬编码在 Kotlin 里
    label 是会被反复调整的用户可见文案（历史上已改过多次措辞）。硬编码会让
    「统一叫法」这件事退化成满仓库搜字符串，也让 strings.xml 不再是唯一事实源。

全部用源码扫描断言（沙盒无 kotlinc）；每条都附反例，确保证据能失败。

已知例外（刻意不纳入门禁，记在这里免得以后被当成漏网）：
  UndoListDialog / HistoryDialog 里各有一处 `label.ifBlank { "改名" }` /
  `if (label.isNotBlank()) label else "批量改名"` 的兜底字面量。它是防御分支
  （14 处调用点没有一个会传空串），且用词已经与本轮收敛后的「改名」一致，
  强行抽成资源只会为了形式统一而增加两处间接层。若将来兜底被真正触发，
  那说明 label 的产出链路断了 —— 该修的是链路，不是兜底文案。
"""
import pathlib
import re

BASE = pathlib.Path(__file__).parent
SRC = BASE / "app/src/main/java/com/yuanbao/pairrename"
STRINGS = (BASE / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
VM = (SRC / "vm/MainViewModel.kt").read_text(encoding="utf-8")

# 会被当作 UndoEntry.label 用的资源名 -> 资源文本
STRING_VALUES = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', STRINGS, re.S))

n_fail = 0


def check(label, cond):
    global n_fail
    print(f"  [{'OK' if cond else 'FAIL'}]   {label}")
    if not cond:
        n_fail += 1


# ---------------------------------------------------------------- 源码切片工具

def balanced(text, open_idx, o="(", c=")"):
    """从 text[open_idx] 处的 ( 开始，返回配平的首个括号组内容与结束位置。

    需要字符串感知：label 里可能出现 "(" ")" 或逗号。
    """
    assert text[open_idx] == o, text[open_idx]
    depth, i, in_str = 0, open_idx, False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == o:
            depth += 1
        elif ch == c:
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i
        i += 1
    raise AssertionError("括号不配平")


def top_args(s):
    """按顶层逗号切分实参（跳过字符串与嵌套括号）。"""
    out, cur, depth, in_str, i = [], "", 0, False, 0
    while i < len(s):
        ch = s[i]
        if in_str:
            cur += ch
            if ch == "\\":
                cur += s[i + 1]
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        out.append(cur)
    return [a.strip() for a in out]


LABEL_RE = re.compile(r"^label\s*=\s*(.+)$", re.S)


def call_label(call_args):
    """从一个 UndoEntry(...) 的实参串里取出 label 表达式。

    UndoEntry(steps, label)        位置式（label 是第 2 个）
    UndoEntry(moves = m, label = x) 具名式
    """
    named = [a for a in top_args(call_args) if LABEL_RE.match(a)]
    if named:
        return LABEL_RE.match(named[0]).group(1).strip()
    args = top_args(call_args)
    assert len(args) >= 2, f"位置式 UndoEntry 至少有 steps 与 label 两个实参：{call_args!r}"
    return args[1].strip()


def undo_entry_labels():
    out = []
    for m in re.finditer(r"\bUndoEntry\s*\(", VM):
        body, _ = balanced(VM, m.end() - 1)
        out.append(call_label(body))
    return out


# ---------------------------------------------------------------- 规则本体
# 三条规则写成纯函数，好让「反例」段落复用同一份代码去证明它真的会失败。

COUNT_NOUNS = ("项", "个", "个文件", "个名字", "个独有的文件", "次")


def rule_self_describing(text):
    """去掉数量与插值后，label 还剩不剩「说出动作」的字。"""
    stripped = re.sub(r"\$\{[^}]*\}", "", text)       # 去掉 ${...}
    stripped = re.sub(r"%\d+\$[sd]", "", stripped)     # 去掉 %1$d / %2$s
    stripped = re.sub(r"[\d\s%.,、：:·—\-]", "", stripped)
    return len(stripped) >= 2 and stripped not in COUNT_NOUNS


def rule_no_item_noun(text):
    """「项」是列表自己渲染的规模单位，label 里再出现就是重复。"""
    return "项" not in text


def rule_no_raw_literal(expr):
    """label 不许是 Kotlin 字符串字面量（静态文案必须进 strings.xml）。"""
    return not expr.lstrip().startswith('"')


print("=" * 64)
print("D5 撤销/历史一行字：label 必须自己说清「干了什么」")
print("=" * 64)

labels = undo_entry_labels()
print(f"  扫到 {len(labels)} 处 UndoEntry(label) 调用点：")
for e in labels:
    print(f"    - {e}")

lit = [e for e in labels if not rule_no_raw_literal(e)]
check("没有硬编码字符串 label（静态文案走 strings.xml）", not lit)
if lit:
    for e in lit:
        print(f"      ✗ 硬编码：{e}")

resolved = {}          # 资源名 -> 文本；按**资源名**去重，不按调用点
for expr in labels:
    m = re.search(r"getString\(R\.string\.(\w+)", expr, re.S)
    if m:
        name = m.group(1)
        assert name in STRING_VALUES, f"strings.xml 里没有 {name}（label 会退化成空串）"
        resolved[name] = STRING_VALUES[name]

check("引用到的每个 R.string.label_* 都真的存在（不留空串）",
      all(re.search(r"getString\(R\.string\.(\w+)", e, re.S).group(1) in STRING_VALUES
          for e in labels if "R.string." in e))

for name, text in sorted(resolved.items()):
    check(f"{name} =「{text}」自描述且不带「项」",
          rule_self_describing(text) and rule_no_item_noun(text))

# 同一个动作在多个入口复用同一资源是对的（label_copy 两处、label_delete 两处）。
# 要防的是**两个不同资源写出同一句话** —— 那才是「叫法没区分开动作」。
texts = list(resolved.values())
dup = [t for t in set(texts) if texts.count(t) > 1]
check(f"一个叫法只指一个动作（{len(resolved)} 个资源文本互不重复）", not dup)
for t in dup:
    print(f"      ✗ 两个资源写同一句话：{t}")

print()
print("=" * 64)
print("反例：把本轮修掉的两个旧 label 喂给同一份规则，它们必须失败")
print("=" * 64)

# 这两条是修复前的原文（executePlan / restoreFromCsv 的 pushUndo）。
# 用它们证明上面那三条规则不是恒真的装饰。
old_cases = [
    ('"${steps.size} 项"', "executePlan：批量改完只写「N 项」"),
    ('"还原 ${steps.size} 项"', "restoreFromCsv：只说了还原、没说从哪还原，且带「项」"),
]
for expr, why in old_cases:
    raw_ok = rule_no_raw_literal(expr)
    inner = expr.strip('"')
    sd = rule_self_describing(inner)
    ni = rule_no_item_noun(inner)
    print(f"  · {expr}")
    print(f"      硬编码检查 → {'通过(漏)' if raw_ok else '拦下'}"
          f" / 自描述 → {'通过(漏)' if sd else '拦下'}"
          f" / 无「项」→ {'通过(漏)' if ni else '拦下'}")
    check(f"拦得住旧写法（{why}）", (not raw_ok) or (not sd) or (not ni))

print("""
  旧写法在界面上长什么样：
    撤销列表  : 「3 项」                      ← 只有数量，看不出干了什么
    改名历史  : 「3 项」  右侧「3 项 · 14:22」  ← 同一个计数说了两遍
  新写法（label_batch_rename / label_restore_csv）：
    撤销列表  : 「批量改名 3 个文件」
    改名历史  : 「按对照表还原 3 个文件名」  右侧「3 项 · 14:22」""")

print()
print("=" * 64)
print("D1 同义收敛：全仓只留一种叫法（改名），不再有「重命名」")
print("=" * 64)

hits = []
for p in sorted(SRC.rglob("*.kt")):
    t = p.read_text(encoding="utf-8")
    for i, line in enumerate(t.splitlines(), 1):
        if "重命名" in line:
            hits.append(f"{p.relative_to(BASE)}:{i}: {line.strip()}")
check("app/src/main 下「重命名」出现 0 次", not hits)
for h in hits:
    print(f"      ✗ {h}")
check("strings.xml 下「重命名」出现 0 次", "重命名" not in STRINGS)
# 反向确认：收敛方向没有走反（改名确实在用，而不是两边都被删光了）
check("「改名」在 strings.xml 里仍在用（不是两边都删空）", "改名" in STRINGS)
check("「改名」在源码里仍在用", "改名" in VM)

print()
print("=" * 64)
if n_fail:
    print(f"结论：{n_fail} 条不达标")
    raise SystemExit(1)
print("结论：撤销/历史文案自描述 + 同义收敛成立（label 自带动作，全仓只有「改名」）")
