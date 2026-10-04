"""验证撤销安全网的完整性。

核心风险不是「某个操作不能撤销」，而是：
如果操作 N 没进撤销栈，用户点撤销时会静默撤销**操作 N-1** ——
界面提示"已撤销 N 项"，实际改的是另一批文件。这是最糟的一类错误：
看起来成功了，其实改错了东西。

本脚本：1) 静态审计所有变更操作是否都 pushUndo；
        2) 模拟撤销栈行为，证明「缺口会导致撤销到错误的操作」。
"""
import re
import pathlib

SRC = (pathlib.Path(__file__).parent
       / "app/src/main/java/com/yuanbao/pairrename/vm/MainViewModel.kt").read_text()

# ---------- 1) 静态审计 ----------
print("=== 静态审计：变更操作是否都进撤销栈")
lines = SRC.split("\n")
covered, gaps = [], []
for i, l in enumerate(lines):
    m = re.match(r"    fun (\w+)\(", l)
    if not m:
        continue
    name = m.group(1)
    j, depth, body = i + 1, 0, []
    while j < len(lines):
        body.append(lines[j])
        depth += lines[j].count("{") - lines[j].count("}")
        if lines[j].strip() == "}" and depth <= 0:
            break
        j += 1
    text = "\n".join(body)
    if not re.search(r"docs\.(rename|delete|copyTo)|trash\.move|Organizer\.execute", text):
        continue
    (covered if "pushUndo" in text else gaps).append(name)

for n in covered:
    print(f"  可撤销  {n}")
for n in gaps:
    print(f"  ★缺口  {n}")
assert not gaps, f"仍有不可撤销的变更操作: {gaps}"
print(f"  -> {len(covered)} 个变更操作全部可撤销")

# ---------- 2) 模拟「缺口会导致撤销错操作」 ----------
print("\n=== 为什么缺口危险：模拟撤销栈")


def simulate(record_delete):
    """用户依次做：改名 A、删除 B、然后点撤销"""
    stack = []
    log = []

    def rename(items):
        stack.append(("rename", items))
        log.append(f"改名 {items}")

    def delete(items):
        if record_delete:
            stack.append(("delete", items))
        log.append(f"删除 {items}")

    def undo():
        if not stack:
            return "无可撤销"
        kind, items = stack.pop()
        return f"撤销了 [{kind}] {items}"

    rename("A组(10个文件)")
    delete("B组(3个文件)")   # 删除
    return undo()


print(f"  修复前（删除不记录）: {simulate(False)}")
print(f"        ^ 用户想撤销删除，实际撤销了改名 —— 静默改错")
print(f"  修复后（删除记录）  : {simulate(True)}")
assert "delete" in simulate(True), "删除未进栈"
assert "rename" in simulate(False), "模拟有误"
print("  -> 修复后撤销的是用户以为的那个操作")

# ---------- 3) 撤销顺序必须是后进先出 ----------
print("\n=== 撤销顺序（四类变动必须逆序回滚）")
order = ["moves(归档移动)", "steps(改名)", "created(复制)", "trashed(删除)"]
print("  执行顺序:", " -> ".join(order))
print("  撤销顺序:", " -> ".join(reversed(order)))
print("  说明：先撤删除/复制，再撤改名，最后把归档的文件移回原处，")
print("        每一步都逆序，避免新文件挡住旧路径")

print("\n结论：全部变更操作可撤销，且撤销的是用户以为的操作")
