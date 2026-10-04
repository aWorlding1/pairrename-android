"""收尾门禁：逐条检查「用户能做的事」是否满足交付不变量。

## 为什么需要它

41 个 verify_*.py 验证的是**算法**（配对、命名、撤销栈……），回答"算得对不对"。
这一份回答另一个问题：

    界面上每一个能点的地方，点完之后用户知不知道自己做了什么、能不能退回来？

这是**收尾**的判据，不是新功能的判据 —— 算法对了但某个按钮静默失败或吞掉用户的文件，
用户照样认为软件坏了。

## 分母（可穷举，所以进度可数）

界面能直接触发的 ViewModel 副作用方法（`vm.xxx(` / `viewModel.xxx(` 出现在 ui/ 下，
且方法体真的有副作用）。新加一个动作分母就 +1；分母不涨 = 没有新动作要收口。

## 判据 A：静默无反应

动作结束后用户**什么都看不到**才算不达标。三种"看得到"都认：

- **A1 直接反馈**：`emitMsg(` / `_events.emit(`；
- **A2 状态变更**：`_ui.update` —— 允许顺着一到三跳的 VM 内部调用去看
  （`refresh -> load` 这样，用户看到的是列表真的刷新了）；
- **A3 响应式重渲染**：写了一个已经被 `_ui.update` 接管的 Flow 数据源
  （`recentFolders.folders.collect { _ui.update { it.copy(recents = list) } }`）——
  清空最近目录没有"状态字段"可改，但界面确实会重组。

A 的每一处通过都会打印**是怎么通过的**（`动作 -> 一跳 -> 反馈点`），
不然"能过"本身就不可核对。

## 判据 B：改磁盘必须有退路

动作里出现真正的磁盘写（改名 / 删除 / 复制 / 移动 / 进回收站 / 清空回收站），
就必须至少有一条退路：

- **B1 VM 层**：方法体自己出现 `pushUndo(` / `Credential(`。
  **故意不顺着调用链看** —— "有人替我 pushUndo 了"这种间接覆盖太容易看错，
  宁可要求在这一层显式写出来。
- **B2 UI 层二次确认**：确认框紧挨着那个调用；也认**一跳回调**
  （`onEmpty = { vm.emptyTrash() }` 把动作交给 TrashDialog，
  真正的两步确认住在那一侧的实现里）。
- **B3 写入本身不具破坏性**：退路不是"能撤回来"，而是"根本不会覆盖" ——
  失败的代价是这一步没做成，不是丢文件。正则判不出这种保证（它藏在
  `exists()` 判断和返回值的语义里），所以用**具名白名单 + 必须绑一句锚点注释**：
  锚点写在钉它的 verify_*.py 里，删了锚点门禁立刻变红 —— 白名单不会悄悄腐烂。

判据 C（做不到时是否说明理由）与 D（同一动作是否只有一个名字 / 一条流程）
无法靠正则判定，由 verify_*.py 的结构断言与人工抽查承担，不在这里假装机器能判。

跑法：python audit_actions.py        非零退出 = 还有动作没达标
"""

import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent
SRC = BASE / "app/src/main/java/com/yuanbao/pairrename"
VM = SRC / "vm/MainViewModel.kt"

FEEDBACK = ("emitMsg(", "_events.emit(", "_events.tryEmit(")
UI_STATE = ("_ui.update",)
EXIT_VM = ("pushUndo(", "Credential(")
# 真正改写磁盘的调用（写用户看得见的文件）
DISK_WRITE = (
    "docs.rename(", "docs.delete(", "docs.copyTo(", "docs.moveTo(",
    "trash.moveToTrash(", "trash.movePathToTrash(", "trash.restore(", "trash.empty(",
)
# UI 层的二次确认标记
CONFIRM = ("confirm", "AlertDialog", "askDelete", "AskDelete", "再确认", "二次确认")
# A3：被 _ui.update 接管的响应式数据源（写它 = 界面会重组）
REACTIVE_VERBS = ("clear", "remove", "touch", "add", "save", "put", "edit",
                  "write", "delete", "set", "insert", "update")
MAX_HOPS = 3

# B3：写入不具破坏性的动作，必须由某个 verify 脚本里的一句锚点注释钉住。
# 动作 -> (钉住它的脚本, 脚本里必须出现的锚点)
NON_DESTRUCTIVE = {
    "restoreTrashed": ("verify_organize.py", "AUDIT-B:restoreTrashed"),
    # 撤销 / 重做的退路不是"能再撤回来"，而是"根本不会覆盖"：
    # nameTaken 命中就报失败，一行盘都不动（见 verify_undo_stack.py 第 9 节）。
    "undo": ("verify_undo_stack.py", "AUDIT-B:undo-redo"),
    "redo": ("verify_undo_stack.py", "AUDIT-B:undo-redo"),
    "undoUntil": ("verify_undo_stack.py", "AUDIT-B:undo-redo"),
}


def _match_paren(text: str, i: int) -> int:
    """text[i] == '(' 时返回配对的 ')' 下标，找不到返回 -1。"""
    depth = 0
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def method_bodies(text: str) -> dict:
    """取出每个方法的方法体（按大括号配平）。

    两个坑都要绕开，少一个都会让门禁出现盲区：

    1. **修饰符**。`private fun load(` / `private suspend fun readExifSuspend(`
       以前只匹配 `^    fun `，173 个方法只抓到 113 个，漏掉的恰好是
       `load` / `updatePane` / `readExifSuspend` 这些真正干活的私有方法 ——
       于是"反馈住在一跳之外"的动作全被误报成静默。
    2. **表达式体**。本工程大量用 `fun redo() = io { ... }` /
       `= once("x") { ... }`。早先的实现见到 `=` 就当成"没有方法体"跳过，
       结果 `undo` / `redo` 这两个**会改磁盘**的动作整个从分母里消失了 ——
       门禁看不见它们，自然也就报不出它们没有退路。
       判据：参数表 `)` 与第一个 `{` 之间没有换行 → 是 `= io {` 这种写法，取；
       中间有换行且以 `=` 开头 → 是 `fun f() =\n  expr` 的真表达式体，跳过。
    """
    mods = ("private", "internal", "public", "protected", "suspend",
            "override", "open", "inline", "operator", "tailrec", "final")
    fun_re = re.compile(r"^    (?:(?:" + "|".join(mods) + r")\s+)*fun\s+(\w+)\s*\(", re.M)
    out = {}
    for m in fun_re.finditer(text):
        p_open = text.index("(", m.end() - 1)
        p_close = _match_paren(text, p_open)
        if p_close < 0:
            continue
        brace = text.find("{", p_close)
        if brace < 0:
            continue
        seg = text[p_close + 1:brace]
        if "\n" in seg and seg.strip().startswith("="):
            continue                      # 真·表达式体，没有方法体
        depth, j = 0, brace
        while j < len(text):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.setdefault(m.group(1), text[brace:j + 1])
    return out


def ui_calls():
    """{动作名: {文件: 内容}} —— 界面里直接调用的 vm.xxx(。"""
    seen = {}
    for f in sorted((SRC / "ui").rglob("*.kt")):
        txt = f.read_text(encoding="utf-8")
        for m in re.finditer(r"\b(?:vm|viewModel)\.(\w+)\s*\(", txt):
            seen.setdefault(m.group(1), {})[str(f)] = txt
    return seen


def all_ui():
    return {str(f): f.read_text(encoding="utf-8")
            for f in sorted((SRC / "ui").rglob("*.kt"))}


def reactive_sources(text: str) -> set:
    """被 `_ui.update` 接管的 Flow：`X.flow.collect { ... _ui.update ... }`。

    写这些源等于改界面状态，只是没有"状态字段"可改。认得窄一点没关系，
    漏了顶多多报一处，认得宽了才会把真静默放过去 —— 所以只在 collect
    块里确实出现 `_ui.update` 时才认。
    """
    out = set()
    for m in re.finditer(r"\b(\w+)\.\w+\.collect\s*\{", text):
        head = text.find("{", m.end() - 1)
        depth, j = 0, head
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if "_ui.update" in text[head:j + 1]:
            out.add(m.group(1))
    return out


def reach(name: str, bodies: dict, keys, depth=0, seen=None) -> str:
    """顺着一到三跳 VM 内部调用找**别人身上**的 `keys`；返回命中链（'' = 没命中）。

    `undo -> undoOne` 这种就是靠它才能看见的：`undo` 自己一行磁盘写都没有，
    真正的 `docs.rename(` 住在 `undoOne` 里。不顺着调用链看，
    这两个会改磁盘的动作会从分母里整整齐齐地消失。
    """
    seen = seen or set()
    if name in seen or name not in bodies:
        return ""
    seen.add(name)
    body = bodies[name]
    if any(k in body for k in keys):
        return ""
    if depth >= MAX_HOPS:
        return ""
    for m in re.finditer(r"\b(\w+)\s*\(", body):
        callee = m.group(1)
        if callee in bodies and callee != name:
            if any(k in bodies[callee] for k in keys):
                return callee
            sub = reach(callee, bodies, keys, depth + 1, seen)
            if sub:
                return f"{callee} -> {sub}"
    return ""


def _guarded_by_confirm(lines, idx: int) -> bool:
    """调用点**所处的作用域**里，有没有一个确认条件在管着它。

    判据必须窄，否则门禁会替真缺口打掩护：

    早先用的是窗口式 —— "调用点附近 45 行里出现过 confirm 就算有确认"。
    回收站对话框里同时有"恢复"和"清空"两个按钮，窗口式让"恢复"蹭到了
    "清空"的 `if (confirmEmpty)`，于是 `restoreTrashed` 被判成"有退路"，
    真缺口再也报不出来。重做（redo）也是这么被误判成"UI 确认"的。

    现在的判据：往上找**缩进更浅且到调用行还没闭合**的最近 `if`，
    条件里必须出现 confirm 关键字。
    """
    line = lines[idx]
    if any(k in line for k in CONFIRM) and re.search(r"\bif\s*\(([^)]*)\)", line):
        return True                      # `if (confirmX) { onY() }` 单行写法
    indent = len(line) - len(line.lstrip())
    for j in range(idx - 1, -1, -1):
        prev = lines[j]
        if not prev.strip():
            continue
        if (len(prev) - len(prev.lstrip())) < indent:
            m = re.search(r"\bif\s*\(([^)]*)\)", prev)
            if m and any(k in m.group(1) for k in CONFIRM):
                # 还必须是"开着"的块：从 if 行到调用行净开 1，中途不闭合
                d, still_open = 0, True
                for k in range(j, idx):
                    d += lines[k].count("{") - lines[k].count("}")
                    if d <= 0 and k > j:
                        still_open = False
                        break
                if still_open and d > 0:
                    return True
        if (len(prev) - len(prev.lstrip())) == 0:
            break
    return False


def confirm_near(txt: str, name: str) -> bool:
    """UI 文件里直接调用该方法的位置，是不是被一个确认条件管着。"""
    lines = txt.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\b(?:vm|viewModel)\." + re.escape(name) + r"\s*\(", line):
            if _guarded_by_confirm(lines, i):
                return True
    return False


def confirm_via_callback(name: str, files, ui) -> str:
    """动作被当成回调传进别的 composable，确认住在那一侧的实现里。

    回收站清空就是这样：`onEmpty = { vm.emptyTrash() }` 只是把动作交出去，
    真正的两步确认在 TrashDialog 里，`onEmpty()` 只在 confirmEmpty 之后才调。
    门禁只看调用点就会把已经做对的地方报成"没退路"。
    """
    pat = re.compile(r"(\w+)\s*=\s*\{\s*(?:vm|viewModel)\." + re.escape(name) + r"\s*\(")
    props = set()
    for txt in files.values():
        props |= {m.group(1) for m in pat.finditer(txt)}
    if not props:
        return ""
    for path, txt in ui.items():
        lines = txt.splitlines()
        for prop in props:
            if not re.search(r"\b" + re.escape(prop) + r"\s*:", txt):
                continue
            for i, line in enumerate(lines):
                if re.search(r"\b" + re.escape(prop) + r"\s*\(", line):
                    if _guarded_by_confirm(lines, i):
                        return f"UI 确认（{Path(path).name}·{prop}）"
    return ""


def pinned_anchor(name: str) -> str:
    """B3：白名单动作在对应 verify 脚本里必须真有那句锚点。"""
    if name not in NON_DESTRUCTIVE:
        return ""
    script, anchor = NON_DESTRUCTIVE[name]
    p = BASE / script
    if not p.exists():
        return f"[锚点丢失：{script} 不存在]"
    return "不覆盖（锚点 OK）" if anchor in p.read_text(encoding="utf-8") \
        else f"[锚点丢失：{script} 里没有 {anchor}]"


def is_mutating(body: str) -> bool:
    return ("_ui.update" in body or "viewModelScope" in body or "io {" in body
            or any(k in body for k in DISK_WRITE)
            or any(k in body for k in FEEDBACK)
            or "refreshBoth()" in body)


def exit_on_disk_chain(name: str, bodies: dict, chain: str) -> bool:
    """退路在不在**写盘的那条链**上。

    退路允许住在链上：`onCardTap -> applyRename` 里写盘和 `pushUndo` 都在
    applyRename 身上，说它"没退路"是冤枉它。

    但必须和写盘对齐到同一个方法：`onDrop -> applyNamesInOrder` 是真覆盖，
    而"顺手调了个会 pushUndo 的无关方法"不是 —— 所以只认写盘链上的那几个方法。
    """
    if not chain:
        return any(k in bodies.get(name, "") for k in EXIT_VM)
    for c in (x.strip() for x in chain.split("->")):
        if any(k in bodies.get(c, "") for k in EXIT_VM):
            return True
    return False


def main() -> int:
    text = VM.read_text(encoding="utf-8")
    bodies = method_bodies(text)
    calls = ui_calls()
    ui = all_ui()
    sources = reactive_sources(text)
    rx = re.compile(r"\b(" + "|".join(map(re.escape, sorted(sources))) + r")\.\w*("
                    + "|".join(REACTIVE_VERBS) + r")") if sources else None
    rules = [("A1 反馈", FEEDBACK), ("A2 状态", UI_STATE)]

    rows = []
    for name in sorted(calls):
        body = bodies.get(name)
        if body is None:
            continue
        own_disk = any(k in body for k in DISK_WRITE)
        disk_via = "" if own_disk else reach(name, bodies, DISK_WRITE)
        # 分母 = 界面能点 + 有副作用。副作用里"改磁盘"必须顺着链看，
        # 否则 undo / redo 这种"自己一行写盘都没有"的动作会整整齐齐地消失，
        # 而它们恰恰是最需要退路的两个。
        if not is_mutating(body) and not disk_via:
            continue
        # A1/A2 自己身上就有？
        own = next((r for r, k in (("A1 反馈", FEEDBACK), ("A2 状态", UI_STATE))
                    if any(x in body for x in k)), "")
        if own:
            feedback = own
        else:
            via = (reach(name, bodies, FEEDBACK) or reach(name, bodies, UI_STATE))
            feedback = f"一跳之外（{name} -> {via}）" if via else ""
            if not feedback and rx and rx.search(body):
                feedback = "A3 响应式重渲染"
        rows.append({
            "name": name,
            "body": body,
            "feedback": feedback,
            "disk_chain": disk_via,
            "disk": ("本方法" if own_disk
                     else f"链上（{name} -> {disk_via}）" if disk_via else ""),
            "exit_vm": any(k in body for k in EXIT_VM),
            "files": calls[name],
        })

    total = len(rows)
    silent = [r for r in rows if not r["feedback"]]
    disk = [r for r in rows if r["disk"]]
    disk_bad = []
    for r in disk:
        if r["exit_vm"] or exit_on_disk_chain(r["name"], bodies, r["disk_chain"]):
            r["exit_where"] = "VM"
        elif any(confirm_near(txt, r["name"]) for txt in r["files"].values()):
            r["exit_where"] = "UI 确认"
        elif (cb := confirm_via_callback(r["name"], r["files"], ui)):
            r["exit_where"] = cb
        elif (pa := pinned_anchor(r["name"])):
            r["exit_where"] = pa
        else:
            r["exit_where"] = "—"
        if "丢失" in r["exit_where"] or r["exit_where"] == "—":
            disk_bad.append(r)

    print("=" * 74)
    print(f"分母：{total} 个动作（界面可触发 + 有副作用）")
    print(f"      其中改写磁盘的 {len(disk)} 个 —— 这 {len(disk)} 个必须有退路")
    print(f"响应式数据源（写它 = 界面重组）：{', '.join(sorted(sources)) or '无'}")
    print("=" * 74)

    print(f"\nA 静默无反应：{total - len(silent)}/{total} 通过")
    for r in silent:
        print(f"  [缺] {r['name']:24s} 既不提示也不改界面状态"
              f"  <- {', '.join(Path(p).name for p in r['files'])}")

    print(f"\nB 改磁盘有退路：{len(disk) - len(disk_bad)}/{len(disk)} 通过")
    for r in sorted(disk, key=lambda x: x["name"]):
        ok = "OK" if r not in disk_bad else "缺"
        print(f"  [{ok}] {r['name']:24s} 退路：{r['exit_where']:26s} 写盘：{r['disk']}")

    # A 的"通过"必须能核对：一跳之外的反馈把整条链打出来
    print("\nA 说明（反馈住在一跳之外的，链上写了是谁在改界面）")
    for r in rows:
        if r["feedback"].startswith("一跳") or r["feedback"].startswith("A3"):
            print(f"  {r['name']:24s} {r['feedback']}")

    bad = len(silent) + len(disk_bad)
    print()
    if bad:
        print(f"结论：还有 {bad} 处不达标（A 缺 {len(silent)} / B 缺 {len(disk_bad)}）。")
        return 1
    print("结论：动作清单全部达标（A 都有反馈、B 改磁盘都有退路）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
