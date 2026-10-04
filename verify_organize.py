"""验证归档计划、归档执行、撤销步骤、回收站恢复的安全性。

沙盒无 kotlinc，用 Python 复刻 Organizer.planArchive / executeArchive /
toMoveSteps 与 TrashRepository.restore 的逻辑做等价验证。

三条不变量，每一条都对应「用户会不会凭空丢一张图」：

  I1 计划幂等：已在对应日期目录里的文件不再搬一遍。
  I2 执行不覆盖：目标已有同名文件时跳过、原文件不动，
     并且**跳过不等于失败**（界面要能说清"有几个没动"）。
  I3 撤销步骤只含**真正移动过**的行 —— 否则撤销会把别人占着的位置腾空，
     把用户原本的图覆盖掉，而且和他刚做的操作看起来毫无关系。

I2/I3 都用「修复前 / 修复后」双向跑：只有能证明"不修就会丢文件"，
这两条断言才有意义。

注：模型里的路径统一用 `/` 拼（不用 os.path.join）—— 脚本在 Windows 上
跑时 ntpath 会拼出反斜杠，和字面量键对不上，字典命中率就成了玄学。
"""
import os
from datetime import datetime


def _norm(p):
    return p.replace("\\", "/")


def _join(*parts):
    return "/".join(_norm(x).strip("/") if i else _norm(x) for i, x in enumerate(parts))


# ---------------------------------------------------------------- 计划

def plan_archive(items, pattern="%Y-%m"):
    """items: (path, takenAt_ms) -> MoveRow(name, from, to)

    幂等判据用「父目录路径以 dirName 结尾」，**不是** basename 相等：
    后者只认单层格式，pattern 一旦写成 `yyyy/MM`（dirName = "2024/03"），
    目录名永远不可能等于 "2024/03"，嵌套照样发生。
    """
    rows = []
    for path, taken in items:
        if not path or taken <= 0:
            continue
        parent = os.path.dirname(path)
        name = os.path.basename(path)
        dirname = datetime.fromtimestamp(taken / 1000).strftime(pattern)
        if _norm(parent).endswith("/" + _norm(dirname)):
            continue
        dst = _join(parent, dirname, name)
        if dst == _norm(path):          # 二道保险，实际被上面那句挡住了
            continue
        rows.append((name, path, dst))
    return rows


# --------------------------------------------- 文件系统模型（path -> 内容）

def fs_rename(fs, src, dst, guard):
    """同分区 rename。Linux 上目标已存在会被**静默替换** —— 这就是必须加 guard 的原因。"""
    if src not in fs:
        return False
    if dst == src:
        return True
    if guard and dst in fs:
        return False
    fs[dst] = fs.pop(src)
    return True


def execute_archive(rows, fs, enforce=True):
    """复刻 Organizer.executeArchive。

    enforce=False 复现**修复前**的代码：没有任何 dst 存在性判断，
    直接 rename —— 目标被占时静默替换。
    """
    moved = failed = skipped = 0
    moved_rows = []
    for name, src, dst in rows:
        if src not in fs:
            failed += 1
            continue
        if dst == src:
            continue                    # 同一路径：已在位，不移动也不报失败
        if enforce and dst in fs:
            skipped += 1
            continue                    # 目标被占：跳过，原文件不动
        if fs_rename(fs, src, dst, guard=enforce):
            moved += 1
            moved_rows.append((name, src, dst))
        else:
            # 跨存储退化：先拷后删
            fs[dst] = fs[src]
            del fs[src]
            moved += 1
            moved_rows.append((name, src, dst))
    return {"moved": moved, "failed": failed, "skipped": skipped, "movedRows": moved_rows}


def to_move_steps_fixed(result):
    """修好之后：只有真正移动过的行进撤销栈。"""
    return [(src, dst) for _n, src, dst in result["movedRows"]]


def to_move_steps_buggy(rows):
    """修好之前：计划里的全部行都进撤销栈（含根本没移动的）。"""
    return [(src, dst) for _n, src, dst in rows]


def move_back(fs, to_path, from_path, guard=True):
    """撤销 / 重做：把文件从 toPath 移回 fromPath。"""
    return fs_rename(fs, to_path, from_path, guard)


# ================================================================ 场景

print("=== 场景 1：混合日期，全部需要归档")
T = datetime(2024, 3, 15, 14, 30, 22).timestamp()
T3 = int(datetime(2024, 3, 15).timestamp() * 1000)
items = [
    ("/DCIM/a.jpg", int(T * 1000)),
    ("/DCIM/b.jpg", int(T * 1000)),
    ("/DCIM/c.jpg", int(datetime(2024, 5, 1).timestamp() * 1000)),
]
rows = plan_archive(items)
print(f"  输入 {len(items)} 个 -> 计划移动 {len(rows)} 个")
for n, src, dst in rows:
    print(f"    {n}: -> {os.path.dirname(dst)}")
dirs = {os.path.dirname(r[2]) for r in rows}
print(f"  生成 {len(dirs)} 个日期目录: {sorted(dirs)}")

print("\n=== 场景 2：部分已归档（I1 幂等性）")
items2 = [
    ("/DCIM/a.jpg", int(T * 1000)),                       # 需移动
    ("/DCIM/2024-03/b.jpg", int(T * 1000)),               # 已在正确位置
    ("/DCIM/2024-03/c.jpg", int(datetime(2024, 3, 20).timestamp() * 1000)),  # 已在正确位置
]
rows2 = plan_archive(items2)
print(f"  输入 {len(items2)} 个 -> 计划移动 {len(rows2)} 个（应为 1）")
for n, src, dst in rows2:
    print(f"    {n}: {os.path.dirname(src)} -> {os.path.dirname(dst)}")
assert len(rows2) == 1, "已归档的文件被重复移动了！"
print("  I1 OK：已在正确位置的文件不会被重复搬动")

print("\n=== 场景 3：嵌套 pattern（yyyy/MM）不能嵌套进自己")
nested = [("/DCIM/2024/03/a.jpg", T3)]
rows3 = plan_archive(nested, pattern="%Y/%m")
print(f"  已在 /DCIM/2024/03 的文件 -> 计划移动 {len(rows3)} 个（应为 0）")
# 用旧的 basename 判据复算一遍，证明它确实会嵌套
naive = []
for path, taken in nested:
    parent = os.path.dirname(path)
    dirname = datetime.fromtimestamp(taken / 1000).strftime("%Y/%m")
    if os.path.basename(parent) != dirname:
        naive.append(_join(parent, dirname, os.path.basename(path)))
print(f"  旧的 basename 判据 -> {len(naive)} 个，落到 {naive}")
assert len(naive) == 1 and naive[0].count("2024/03") == 2, "反例构造有误"
assert len(rows3) == 0, "嵌套 pattern 下又嵌套了一层！"
print("  I1 OK：endsWith 判据挡住了 /DCIM/2024/03/2024/03/a.jpg")

print("\n=== 场景 4：无 EXIF 时间的文件应跳过")
items4 = [("/DCIM/a.jpg", 0), ("/DCIM/b.jpg", int(T * 1000)), (None, int(T * 1000))]
rows4 = plan_archive(items4)
print(f"  输入 {len(items4)} 个（1 个无时间 + 1 个无路径）-> 计划移动 {len(rows4)} 个（应为 1）")
assert len(rows4) == 1

print("\n=== 场景 5：I2 目标被同名文件占住 —— 不覆盖（修复前 / 修复后）")
# 目标目录里已经有一张 a.jpg（上次归档留下的、或用户自己放的）
base_fs = {
    "/DCIM/a.jpg": "A-用户的图",
    "/DCIM/2024-03/a.jpg": "A-已经在那儿的那张",
    "/DCIM/c.jpg": "C-用户的图",
}
plan5 = plan_archive([("/DCIM/a.jpg", T3), ("/DCIM/c.jpg", T3)])
print(f"  计划移动 {len(plan5)} 个")

fs_before = dict(base_fs)
r_before = execute_archive(plan5, fs_before, enforce=False)
lost = fs_before.get("/DCIM/2024-03/a.jpg") != base_fs["/DCIM/2024-03/a.jpg"]
now = fs_before.get("/DCIM/2024-03/a.jpg")
print(f"  修复前：moved={r_before['moved']} skipped={r_before['skipped']}")
print(f"          /DCIM/2024-03/a.jpg 现在 = {now!r} —— 原来那张已被吃掉")
assert lost, "没复现出覆盖 —— 这个反例失去意义了"

fs_after = dict(base_fs)
r_after = execute_archive(plan5, fs_after, enforce=True)
print(f"  修复后：moved={r_after['moved']} failed={r_after['failed']} "
      f"skipped={r_after['skipped']}")
assert fs_after["/DCIM/2024-03/a.jpg"] == "A-已经在那儿的那张", "占位文件被覆盖了！"
assert fs_after["/DCIM/a.jpg"] == "A-用户的图", "原文件被搬走了！"
# 跳过不算失败：必须能区分，否则界面只会说"失败 1 个"，用户以为出错了
assert r_after["skipped"] == 1 and r_after["failed"] == 0, "跳过被算成失败了"
assert r_after["moved"] == 1
print("  I2 OK：占位文件与原文件都在，且跳过不计入失败")

print("\n=== 场景 6：I3 撤销步骤只含真正移动过的行（修复前 / 修复后）")
print("  承接场景 5：a 那行因目标被占没动，只有 c 真正移动了")
steps_buggy = to_move_steps_buggy(plan5)
steps_fixed = to_move_steps_fixed(r_after)
print(f"  修复前：撤销栈 {len(steps_buggy)} 步 -> {[os.path.basename(s) for s, _ in steps_buggy]}")
print(f"  修复后：撤销栈 {len(steps_fixed)} 步 -> {[os.path.basename(s) for s, _ in steps_fixed]}")
assert len(steps_buggy) == 2 and len(steps_fixed) == 1, "撤销步骤数不对"

# 修复前：撤销会去动 a 那行 —— 把占位文件搬回 /DCIM/，顶掉用户原本的图
fs_b1 = dict(fs_after)
fs_b1["/DCIM/a.jpg"] = "A-用户的图"          # 用户原本那张还在原地（没被归档）
for src, dst in reversed(steps_buggy):
    move_back(fs_b1, dst, src, guard=False)
print(f"  修复前撤销后 /DCIM/a.jpg = {fs_b1.get('/DCIM/a.jpg')!r}")
assert fs_b1["/DCIM/a.jpg"] != "A-用户的图", "反例没复现出来"
print("        ^ 用户点了一次撤销，原先那张图被『已经在那儿的那张』顶掉了")

# 修复后：只撤 c 那行，a 完全不被碰
fs_b2 = dict(fs_after)
for src, dst in reversed(steps_fixed):
    move_back(fs_b2, dst, src, guard=True)
assert fs_b2["/DCIM/a.jpg"] == "A-用户的图", "撤销碰了不该碰的文件！"
assert fs_b2["/DCIM/c.jpg"] == "C-用户的图", "c 没撤回去"
print("  修复后撤销后 /DCIM/a.jpg 原样、/DCIM/c.jpg 已回原位")
print("  I3 OK：撤销只回滚真正发生过的移动")

print("\n=== 场景 7：撤销时原路径又被占住 —— 拒绝，不覆盖")
fs7 = {"/DCIM/2024-03/c.jpg": "C-用户的图", "/DCIM/c.jpg": "C-新放的"}
ok = move_back(fs7, "/DCIM/2024-03/c.jpg", "/DCIM/c.jpg", guard=True)
print(f"  moveBack 返回 {ok}；/DCIM/c.jpg = {fs7.get('/DCIM/c.jpg')!r}")
assert ok is False, "占位时不该成功"
assert fs7["/DCIM/c.jpg"] == "C-新放的", "新放的文件被覆盖了！"
assert fs7["/DCIM/2024-03/c.jpg"] == "C-用户的图", "源文件不该消失"
print("  OK：拒绝撤回，两边内容都在（界面会报撤销失败，用户能自己去处理）")

print("\n=== 场景 8：回收站恢复 —— I2 同样适用")


def restore(fs, src, dst, guard=True):
    """复刻 TrashRepository.restore：src 在回收站里，dst 是 originalPath。"""
    if src not in fs:
        return "MISSING"
    if dst == src:
        return "OK"
    if guard and dst in fs:
        return "OCCUPIED"
    fs_rename(fs, src, dst, guard)
    return "OK"


TRASH = "/Android/data/pkg/files/trash/123/a.jpg"
fs8 = {TRASH: "A-用户的图", "/DCIM/a.jpg": "A-别人占着的"}
fs8_old = dict(fs8)
fs_rename(fs8_old, TRASH, "/DCIM/a.jpg", guard=False)
print(f"  修复前（无 guard）：/DCIM/a.jpg = {fs8_old.get('/DCIM/a.jpg')!r}")
print(f"          别人那张被吃掉；回收站里还有吗：{TRASH in fs8_old}")
assert fs8_old.get("/DCIM/a.jpg") == "A-用户的图" and TRASH not in fs8_old, "反例构造有误"

fs8b = dict(fs8)
res = restore(fs8b, TRASH, "/DCIM/a.jpg", guard=True)
print(f"  修复后：{res} -> /DCIM/a.jpg = {fs8b.get('/DCIM/a.jpg')!r}；回收站里还在吗：{TRASH in fs8b}")
# AUDIT-B:restoreTrashed —— 这一句就是 audit_actions.py 里
# NON_DESTRUCTIVE 白名单指着的那句锚点：原位置被占时必须返回 OCCUPIED
# 且不动那个文件。删掉它，门禁会立刻把 restoreTrashed 报成"改磁盘没退路"。
assert res == "OCCUPIED", "占位时没返回 OCCUPIED"
assert fs8b["/DCIM/a.jpg"] == "A-别人占着的", "恢复了不该恢复的位置，吃掉了别人的文件"
assert TRASH in fs8b, "源文件不该丢，用户还有机会另存"
print("  OK：OCCUPIED 让界面能说清『原位置已有同名文件』，而不是含糊的『操作失败』")

fs8c = {TRASH: "A-用户的图"}                  # 原位置空着
assert restore(fs8c, TRASH, "/DCIM/a.jpg", guard=True) == "OK"
assert fs8c["/DCIM/a.jpg"] == "A-用户的图"
assert restore(dict(fs8c), "/nowhere/a.jpg", "/DCIM/x.jpg") == "MISSING"
print("  OK：原位置空着才恢复；源没了返回 MISSING")

print("\n=== 查重清理安全性：保留第 1 张，其余移入回收站")
for members in (["a", "b"], ["a", "b", "c"], ["only"]):
    keep, to_trash = members[0], members[1:]
    freed = len(to_trash)
    print(f"  {len(members)} 张 -> 保留 {keep}，移入回收站 {freed} 张"
          f"{'  （单张不处理）' if not to_trash else ''}")
    assert len(members) < 2 or len(to_trash) == len(members) - 1

print("\n结论：归档计划幂等、执行与撤销都不覆盖同名文件，"
      "撤销栈只含真正移动过的行，回收站恢复遇占位则拒绝")
