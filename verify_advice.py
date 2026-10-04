"""验证智能建议引擎。

核心风险：建议的顺序错了，用户按错的流程走，后面全白费。
比如"配对还不准"时必须排在"统一名字"前面 —— 照着错的配对改名字
就是改错文件。

另一个风险：拿不准时不该乱建议。这里验证明确的客观问题
（脏名字、数量不等）一定会被提到，而不会因为无关条件被吞掉。
"""

PIPELINE, READ_EXIF, SYNC, FIX, ALIGN, VERIFY, PICK, NONE = (
    "PIPELINE", "READ_EXIF", "SYNC", "FIX", "ALIGN", "VERIFY", "PICK", "NONE")


def dirty_name(n):
    import re
    if n != n.strip():
        return True
    if re.search(r"\s{2,}", n):
        return True
    if any(c in '\\/:*?"<>' for c in n):
        return True
    dot = n.rfind(".")
    if dot > 0 and n[dot+1:] and n[dot+1:] != n[dot+1:].lower():
        return True
    return False


def seq_of(name):
    import re
    base = name.rsplit(".", 1)[0] if "." in name else name
    m = re.search(r"(\d{1,6})\s*$", base)
    if not m:
        return None
    raw = m.group(1)
    v = int(raw)
    if len(raw) == 4 and 1900 <= v <= 2099:
        return None
    return v


def advise(left, right, matched, synced, content_keys, left_uri=True, right_uri=True):
    out = []
    if not left_uri or not right_uri:
        return [("先选两个文件夹", 1000, PICK)]
    if not left and not right:
        return [("没有图片", 1000, NONE)]

    allf = left + right
    paired = len(matched) // 2
    total = min(len(left), len(right))

    if len(left) != len(right):
        out.append(("数量不等", 900, NONE))

    has_exif = sum(1 for x in allf if x.get("exif"))
    rate = paired / total if total else 0

    if rate < 0.5 and has_exif == 0 and not content_keys:
        out.append(("配对率低", 800, PIPELINE))
    elif rate < 0.8 and has_exif == 0:
        out.append(("读拍摄时间", 700, READ_EXIF))

    if not content_keys and paired > 0 and rate < 0.95:
        out.append(("内容校验", 500, VERIFY))

    dirty = sum(1 for x in allf if dirty_name(x["name"]))
    if dirty > 0:
        out.append(("清理名字", 600, FIX))

    if paired < total and total >= 3 and abs(len(left) - len(right)) <= 2:
        sl = sum(1 for x in left if seq_of(x["name"]) is not None)
        sr = sum(1 for x in right if seq_of(x["name"]) is not None)
        if sl > total * 0.6 and sr > total * 0.6:
            out.append(("序号错位", 550, ALIGN))

    pending = paired - len(synced) // 2
    if pending > 0 and rate >= 0.5:
        out.append(("统一名字", 400, SYNC))

    if not out or (pending <= 0 and dirty == 0 and paired > 0):
        out.append(("处理完了", 10, NONE))

    return sorted(out, key=lambda x: -x[1])


def mk(names, exif=False):
    return [{"name": n, "exif": exif} for n in names]


print("=== 关键：配对不准时，『统一名字』绝不能排在前面")
left = mk(["a1.jpg", "a2.jpg", "a3.jpg"])
right = mk(["b1.jpg", "b2.jpg", "b3.jpg"])
r = advise(left, right, set(), set(), {})
print("  场景：两边名字完全对不上、没 EXIF")
for t, p, a in r:
    print(f"    {p:>4}  {t}  -> {a}")
# 配对率 0，不会建议 SYNC
assert not any(a == SYNC for _, _, a in r), "配对率为 0 时不该建议统一！"
print("  OK：没有误导性的『统一名字』建议")

print("\n=== 配对良好时，『统一名字』应该出现")
matched = {"L0", "R0", "L1", "R1", "L2", "R2"}
left = mk(["a1.jpg", "a2.jpg", "a3.jpg"])
right = mk(["b1.jpg", "b2.jpg", "b3.jpg"])
r = advise(left, right, matched, set(), {})
for t, p, a in r:
    print(f"    {p:>4}  {t}  -> {a}")
assert any(a == SYNC for _, _, a in r), "配对良好时应建议统一"
print("  OK")

print("\n=== 脏名字一定会被提到（客观问题）")
left = mk(["  a.jpg", "b  c.jpg"])
right = mk(["x.JPG"])
r = advise(left, right, set(), set(), {})
assert any(a == FIX for _, _, a in r), "脏名字必须被建议"
print("  OK：清理建议存在")

print("\n=== 没选目录时只给一条建议")
r = advise([], [], set(), set(), {}, left_uri=False)
print(f"  {r}")
assert len(r) == 1 and r[0][2] == PICK
print("  OK：不啰嗦")

print("\n=== 顺序严格递减")
left = mk(["a1.jpg", "a2.jpg", "a3.jpg", "  dirty.jpg"])
right = mk(["b1.jpg", "b2.jpg"])
r = advise(left, right, set(), set(), {})
prios = [p for _, p, _ in r]
print(f"  优先级: {prios}")
assert prios == sorted(prios, reverse=True), "优先级未排序"
print("  OK")

print("\n=== 年份不被误判为序号")
assert seq_of("IMG_2024.jpg") is None, "2024 被当成序号了"
assert seq_of("IMG_0042.jpg") == 42
assert seq_of("photo.jpg") is None
print("  OK：IMG_2024 不算序号，IMG_0042 算")

print("\n结论：建议优先级正确，不产生误导")
