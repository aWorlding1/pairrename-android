"""验证顺序对齐中的「置换」场景。

缺陷：计划生成时，A 想改成 B，而 B 也是本次要改名的文件之一 ——
此时 B 还在「已占用名字」集合里，A 会被判为冲突而改成 `B (1)`。
但 B 马上也要改走，名字本该空出来。

现实中触发场景：两边顺序相反（一边正序、一边倒序），
或偏移量让对应关系首尾互换 —— 这时整批都是置换，会全部改错。
"""

from collections import OrderedDict


def numbering(base, ext, used, style="paren"):
    i = 1
    while True:
        cand = f"{base} ({i})" if style == "paren" else f"{base}_{i}"
        if cand.lower() not in {u.lower() for u in used}:
            return cand + ("." + ext if ext else "")
        i += 1


def build_align_old(from_names, to_names, existing, ext="jpg"):
    """修复前：used 里没剔除「本次也要改名」的旧名"""
    used = set(existing)
    rows = []
    for src, dst in zip(from_names, to_names):
        old = dst
        candidate = f"{src}.{ext}"
        used.discard(old)
        taken = candidate.lower() in {u.lower() for u in used}
        final = numbering(src, ext, used) if taken else candidate
        used.add(final)
        rows.append((old, final, taken))
    return rows


def build_align_new(from_names, to_names, existing, ext="jpg"):
    """修复后：先算出所有 desired，再统一裁决冲突"""
    # 本次要改名的旧名 —— 它们会被腾出来，不算占用
    renaming = set(to_names)
    hard = {e.lower() for e in existing if e.lower() not in {r.lower() for r in renaming}}

    # 第一轮：先各自算 desired
    desired = [f"{s}.{ext}" for s in from_names]

    # 第二轮：多 row 争同一个名字时，后面的让位
    taken = set(hard)
    finals = []
    for d in desired:
        if d.lower() in taken:
            finals.append(numbering(d.rsplit(".", 1)[0], ext, taken))
        else:
            finals.append(d)
        taken.add(finals[-1].lower())
    return list(zip(to_names, finals))


print("=== 场景 1：两边顺序完全相反（全是置换）")
# 注意：必须用真实文件名（带扩展名），否则冲突检测根本不会触发，
# 测试会给出虚假的"通过" —— 这一点我自己第一版就写错了。
src = ["c", "b", "a"]
dst = ["a.jpg", "b.jpg", "c.jpg"]
print(f"  来源顺序: {src}")
print(f"  目标顺序: {dst}")
print("  （第 1 个：a 应改成 c；第 3 个：c 应改成 a）")

old = build_align_old(src, dst, set(dst))
new = build_align_new(src, dst, set(dst))

print("\n  修复前:")
for o, f, conflict in old:
    mark = " <- 被误判冲突" if conflict else ""
    print(f"    {o} -> {f}{mark}")

print("\n  修复后:")
for o, f in new:
    print(f"    {o} -> {f}")

expected = {"a.jpg": "c.jpg", "b.jpg": "b.jpg", "c.jpg": "a.jpg"}
got = dict(new)
ok = all(got[k] == v for k, v in expected.items())
print(f"\n  期望: {expected}")
print(f"  实际: {got}")
assert ok, "置换仍未正确处理"
bad = sum(1 for _, _, c in old if c)
print(f"  修复前有 {bad} 个被误判冲突 -> 修复后 0 个")
print("  OK")

print("\n=== 场景 2：真正的冲突仍需避让（不能矫枉过正）")
# 目标栏有个不参与改名的 d.jpg，而来源里也要改成 d
src = ["d", "x"]
dst = ["a.jpg", "b.jpg"]
existing = {"a.jpg", "b.jpg", "d.jpg"}   # d.jpg 不参与改名
new = build_align_new(src, dst, existing)
print(f"  existing = {sorted(existing)}（d 不参与改名）")
for o, f in new:
    print(f"    {o} -> {f}")
assert dict(new)["a.jpg"] == "d (1).jpg", "真冲突没避让！"
print("  OK：不参与改名的同名文件仍被正确避让")

print("\n=== 场景 3：两个来源争同一个名字")
src = ["same", "same"]
dst = ["a.jpg", "b.jpg"]
new = build_align_new(src, dst, {"a.jpg", "b.jpg"})
print(f"  {dict(new)}")
d = dict(new)
assert d["a.jpg"] == "same.jpg"
assert d["b.jpg"] != "same.jpg", "两个文件重名了！"
print("  OK：重名被自动编号避让")

print("\n结论：置换正确完成，真冲突仍避让，不重名")
